package ru.example.townbuildings;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import ru.example.townbuildings.cache.CacheWarmup;
import ru.example.townbuildings.cache.TownCache;
import ru.example.townbuildings.command.AdminCommand;
import ru.example.townbuildings.command.TCommand;
import ru.example.townbuildings.command.TbCommand;
import ru.example.townbuildings.config.BuildingDefinitionRegistry;
import ru.example.townbuildings.config.ConfigManager;
import ru.example.townbuildings.config.ConfigMigrator;
import ru.example.townbuildings.config.MessagesConfig;
import ru.example.townbuildings.database.DatabaseManager;
import ru.example.townbuildings.database.MigrationRunner;
import ru.example.townbuildings.database.dao.*;
import ru.example.townbuildings.gui.MenuManager;
import ru.example.townbuildings.gui.holder.InventoryMenuHolder;
import ru.example.townbuildings.gui.holder.StockMenuHolder;
import ru.example.townbuildings.gui.menu.CityInventoryMenu;
import ru.example.townbuildings.gui.menu.CityStockMenu;
import ru.example.townbuildings.gui.session.PageSessionRegistry;
import ru.example.townbuildings.listener.BonusListener;
import ru.example.townbuildings.listener.EffectReapplyListener;
import ru.example.townbuildings.listener.HeadquartersStackListener;
import ru.example.townbuildings.listener.InventoryListener;
import ru.example.townbuildings.listener.LibraryListener;
import ru.example.townbuildings.listener.MayorChangeListener;
import ru.example.townbuildings.listener.MenuListener;
import ru.example.townbuildings.listener.PlayerConnectionListener;
import ru.example.townbuildings.listener.StockListener;
import ru.example.townbuildings.listener.TaxOfficeListener;
import ru.example.townbuildings.listener.TopographicInstituteListener;
import ru.example.townbuildings.listener.TownListener;
import ru.example.townbuildings.listener.TrainingCampListener;
import ru.example.townbuildings.listener.UniversityListener;
import ru.example.townbuildings.service.*;
import ru.example.townbuildings.service.impl.DbInventoryService;
import ru.example.townbuildings.task.TownSyncTask;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class TownBuildingsPlugin extends JavaPlugin {

    private static TownBuildingsPlugin instance;

    private ConfigManager configManager;
    private MessagesConfig messagesConfig;
    private BuildingDefinitionRegistry buildingRegistry;

    private DatabaseManager databaseManager;
    private MigrationRunner migrationRunner;
    private TownDao townDao;
    private BuildingDao buildingDao;
    private LogDao logDao;
    private OnlineTimeDao onlineTimeDao;
    private InventoryDao inventoryDao;
    private StockDao stockDao;

    private TownyIntegrationService townyIntegration;
    private TownCache townCache;
    private TownService townService;

    private EconomyService economyService;
    private InventoryService inventoryService;
    private StockService stockService;
    private LogService logService;
    private BuildingService buildingService;
    private OnlineTimeService onlineTimeService;
    private RewardService rewardService;
    private EffectService effectService;
    private BonusService bonusService;
    private PermissionService permissionService;
    private SoundService soundService;

    private UniversityService universityService;

    private OnlineBatchWriter onlineBatchWriter;

    private MayorChangeListener mayorChangeListener;

    private MenuManager menuManager;

    private PageSessionRegistry inventorySessions;
    private PageSessionRegistry stockSessions;
    private CityInventoryMenu cityInventoryMenu;
    private CityStockMenu cityStockMenu;

    private BlockPlaceTracker blockPlaceTracker;

    private TownSyncTask townSyncTask;

    /** 18.7 — future завершения CacheWarmup, нужен для syncAll после прогрева. */
    private CompletableFuture<Void> cacheWarmupFuture = CompletableFuture.completedFuture(null);

    /** Защита от двойной регистрации команд (lifecycle-событие + afterDatabaseReady). */
    private final java.util.concurrent.atomic.AtomicBoolean commandsRegistered =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    public void onEnable() {
        instance = this;

        // P14 — регистрацию handler'а lifecycle нужно делать в onEnable (фаза
        // ENABLED ещё открыта). Тело handler'а выполняется сразу при старте
        // сервера: если к моменту COMMANDS БД уже готова, команды регистрируются
        // штатным путём; если миграция ещё идёт — их зарегистрирует
        // afterDatabaseReady() через поздний путь (PluginMeta overload).
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, this::onCommandsLifecycle);

        saveDefaultConfig();

        java.io.File msgFile = new java.io.File(getDataFolder(), "messages.yml");
        if (!msgFile.exists()) saveResource("messages.yml", false);

        java.io.File bldFile = new java.io.File(getDataFolder(), "buildings.yml");
        if (!bldFile.exists()) saveResource("buildings.yml", false);

        ConfigMigrator migrator = new ConfigMigrator(this);
        migrator.migrate("config.yml");
        migrator.migrate("messages.yml");

        this.configManager = new ConfigManager(this);
        this.configManager.load();
        this.messagesConfig = new MessagesConfig(this);
        this.messagesConfig.load();

        this.buildingRegistry = new BuildingDefinitionRegistry(this);
        this.buildingRegistry.load();

        this.blockPlaceTracker = new BlockPlaceTracker(this);

        if (configManager.features().buildings()
                || configManager.features().inventory()
                || configManager.features().stock()) {
            // P14 — миграция БД ушла из main-потока на отдельный стартовый
            // поток; остальной bootstrap продолжится через afterDatabaseReady().
            bootstrapDatabaseAsync();
        }

        getLogger().info("TownBuildings v" + getPluginMeta().getVersion() + " запущен.");
    }

    @Override
    public void onDisable() {
        // P21 — сначала отменяем все повторяющиеся задачи (sweep/flush/tick),
        // чтобы они не планировали новую работу на выключенном плагине,
        // затем синхронно дописываем накопленное в БД.
        if (townSyncTask != null) townSyncTask.stop();
        if (inventorySessions != null) inventorySessions.stopSweep();
        if (stockSessions != null)     stockSessions.stopSweep();

        if (inventorySessions != null) inventorySessions.flushAllSync();
        if (stockSessions != null)     stockSessions.flushAllSync();

        if (onlineBatchWriter != null) onlineBatchWriter.stop();
        if (onlineBatchWriter != null) onlineBatchWriter.flushSync();
        if (effectService != null) effectService.onDisable();
        if (onlineTimeService != null) onlineTimeService.onDisable();
        if (menuManager != null) menuManager.closeAll();
        if (townCache != null) townCache.invalidateAll();

        if (blockPlaceTracker != null) blockPlaceTracker.clear();

        if (databaseManager != null) databaseManager.disconnect();
        getLogger().info("TownBuildings выключен.");
        instance = null;
    }

    /**
     * 3.2 — миграция НЕ должна выполняться в onEnable: DDL на первой
     *       миграции может занимать секунды, а .get(30s) в main вешал
     *       watchdog/сервер на весь срок. Теперь connect+migrate идут
     *       на отдельном стартовом потоке; после успеха плагин добирает
     *       bootstrapTowny/Services/Listeners/Commands через runTask (main).
     *       Логи и /tadmin reload при этом уже работают (config к этому
     *       моменту загружен), но команды/GUI появятся только когда БД
     *       готова — это осознанная и корректная деградация вместо фриза.
     */
    private void bootstrapDatabaseAsync() {
        Thread t = new Thread(() -> {
            try {
                DatabaseManager dbm = new DatabaseManager(TownBuildingsPlugin.this);
                dbm.connect();

                MigrationRunner runner = new MigrationRunner(TownBuildingsPlugin.this, dbm);
                runner.migrate();   // внутри есть свой таймаут 30 сек (3.2)

                this.databaseManager   = dbm;
                this.migrationRunner   = runner;
                this.townDao           = new TownDao(dbm);
                this.buildingDao       = new BuildingDao(dbm);
                this.logDao            = new LogDao(dbm);
                this.onlineTimeDao     = new OnlineTimeDao(dbm);
                this.inventoryDao      = new InventoryDao(dbm);
                this.stockDao          = new StockDao(dbm);

                getLogger().info("[DB] Миграция завершена, продолжаем загрузку сервисов.");
                Bukkit.getScheduler().runTask(this, this::afterDatabaseReady);
            } catch (Throwable ex) {
                getLogger().severe("[DB] Критическая ошибка миграции: " + ex.getMessage());
                getLogger().severe("[DB] Плагин остаётся с выключенными зданиями/складом/" +
                        "хранилищем. Проверьте MySQL и выполните /reload или перезапустите сервер.");
            }
        }, "TownBuildings-Bootstrap");
        t.setDaemon(true);
        t.start();
    }

    /** Продолжение onEnable после успешной миграции (main-поток). */
    private void afterDatabaseReady() {
        if (!isEnabled()) return; // сервер мог выключиться во время миграции
        bootstrapTowny();
        bootstrapServices();
        bootstrapListeners();
        bootstrapCommands();
    }

    private void bootstrapDatabase() {
        this.databaseManager = new DatabaseManager(this);
        this.databaseManager.connect();

        this.migrationRunner = new MigrationRunner(this, databaseManager);
        this.migrationRunner.migrate();

        this.townDao = new TownDao(databaseManager);
        this.buildingDao = new BuildingDao(databaseManager);
        this.logDao = new LogDao(databaseManager);
        this.onlineTimeDao = new OnlineTimeDao(databaseManager);
        this.inventoryDao = new InventoryDao(databaseManager);
        this.stockDao = new StockDao(databaseManager);
    }

    private void bootstrapTowny() {
        this.townyIntegration = new TownyIntegrationService(this);
        this.townCache = new TownCache(configManager.cache().townTtlSeconds());
        this.townService = new TownService(this, townDao, buildingDao, townCache);

        if (configManager.cache().warmupOnStart()) {
            this.cacheWarmupFuture = new CacheWarmup(this, townService, townDao).run();
        }

        this.townSyncTask = new TownSyncTask(this, townService);
        this.townSyncTask.start();
    }

    private void bootstrapServices() {
        this.economyService = new EconomyService(this);
        this.inventoryService = new DbInventoryService(inventoryDao);
        this.stockService = new StockService(stockDao);
        this.logService = new LogService(logDao);
        this.soundService = new SoundService(this);
        this.buildingService = new BuildingService(
                this, buildingRegistry, townService,
                townyIntegration, economyService, inventoryService, logService
        );

        this.rewardService = new RewardService(this);

        this.onlineBatchWriter = new OnlineBatchWriter(this, databaseManager);
        this.onlineBatchWriter.start();
        this.onlineTimeService = new OnlineTimeService(this, onlineTimeDao, onlineBatchWriter);
        this.onlineTimeService.start();

        this.effectService = new EffectService(this);
        this.bonusService = new BonusService(this);
        this.permissionService = new PermissionService(this);
        this.mayorChangeListener = new MayorChangeListener(this, permissionService);
        this.effectService.start();

        this.menuManager = new MenuManager(this);

        // 18.7 — UniversityService + syncAll после CacheWarmup.
        this.universityService = new UniversityService(this);
        cacheWarmupFuture.whenComplete((v, ex) ->
                Bukkit.getScheduler().runTask(this, () -> universityService.syncAll()));

        this.inventorySessions = new PageSessionRegistry(
                this,
                inventoryDao::loadPage,
                inventoryDao::savePageDiff,
                (uuid, page, totalPages, name) -> new InventoryMenuHolder(uuid, page, totalPages, name),
                (name, page, totalPages) -> getMessagesConfig().get("inventory.menu-title",
                        Map.of("page", Integer.toString(page),
                                "pages", Integer.toString(totalPages),
                                "town", name))
        );

        this.stockSessions = new PageSessionRegistry(
                this,
                stockDao::loadPage,
                stockDao::savePageDiff,
                (uuid, page, totalPages, name) -> new StockMenuHolder(uuid, page, totalPages, name),
                (name, page, totalPages) -> getMessagesConfig().get("stock.menu-title",
                        Map.of("page", Integer.toString(page),
                                "pages", Integer.toString(totalPages),
                                "town", name))
        );

        this.cityInventoryMenu = new CityInventoryMenu(this, inventorySessions);
        this.cityStockMenu     = new CityStockMenu(this, stockSessions);
    }

    private void bootstrapListeners() {
        getServer().getPluginManager().registerEvents(new TownListener(this, townService), this);
        getServer().getPluginManager().registerEvents(
                new InventoryListener(this, cityInventoryMenu, inventorySessions), this);
        getServer().getPluginManager().registerEvents(
                new StockListener(this, cityStockMenu, stockSessions), this);
        getServer().getPluginManager().registerEvents(new MenuListener(this, menuManager), this);
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new BonusListener(this), this);
        getServer().getPluginManager().registerEvents(new LibraryListener(this), this);
        getServer().getPluginManager().registerEvents(new TrainingCampListener(this), this);
        getServer().getPluginManager().registerEvents(new TaxOfficeListener(this), this);
        getServer().getPluginManager().registerEvents(new TopographicInstituteListener(this), this);
        getServer().getPluginManager().registerEvents(new HeadquartersStackListener(this), this);
        getServer().getPluginManager().registerEvents(new UniversityListener(this), this);

        // ★ Возврат PERMANENT-эффектов после телепорта / меню / команды / respawn.
        getServer().getPluginManager().registerEvents(new EffectReapplyListener(this), this);

        getServer().getPluginManager().registerEvents(mayorChangeListener, this);
    }

    /**
     * Регистрация Brigadier-команд (/tb, /tbuildings, /tadmin).
     *
     * P14 — миграция БД выполняется на отдельном потоке, и afterDatabaseReady()
     * может сработать уже ПОСЛЕ завершения фазы ENABLED жизненного цикла Paper.
     * getLifecycleManager().registerEventHandler(...) в этот момент бросает
     * IllegalStateException("Cannot register lifecycle event handlers").
     * Поэтому handler LifecycleEvents.COMMANDS регистрируется заранее — в
     * onEnable(), где фаза ещё открыта (см. onCommandsLifecycle()).
     *
     * ВАЖНО про "No lifecycle owner context is set" (Paper 1.21.11):
     * registrar команд (io.papermc.paper.command.brigadier.PaperCommands в
     * paper-server) — это ОДИН синглтон на весь сервер. Безымянные
     * register(node, desc, aliases) берут владельца из внутреннего поля
     * currentContext:
     *   requireNonNull(this.currentContext, "No lifecycle owner context is set")
     * currentContext выставляется только НА ВРЕМЯ прогона lifecycle-события
     * COMMANDS и сбрасывается сразу после него. Значит:
     *   - безымянные register(...) безопасны ТОЛЬКО внутри обработчика события;
     *   - вне окна события (поздняя регистрация после долгой миграции) нужно
     *     вызывать overload с явным PluginMeta:
     *       register(PluginMeta, node, description, aliases)
     *     getPluginMeta() живёт всё время жизни плагина, контекст не нужен.
     */
    private void onCommandsLifecycle(ReloadableRegistrarEvent<Commands> event) {
        // При обычном старте (БД готова до конца ENABLED) регистрируем сразу.
        if (this.databaseManager != null) {
            registerCommandsVia(event.registrar());
        }
        // Иначе команды зарегистрирует afterDatabaseReady() (поздний путь).
    }

    /** Вызывается из afterDatabaseReady(): выбор пути регистрации. */
    private void bootstrapCommands() {
        if (isEnabled() && databaseManager != null && Bukkit.isPrimaryThread()) {
            // Мы НЕ внутри события COMMANDS — безымянные register(...) там уже
            // недоступны ("No lifecycle owner context is set"), идём через
            // overload с явным PluginMeta. Если же событие COMMANDS ещё не
            // прошло, поздняя регистрация просто выполнится раньше и защита от
            // двойной регистрации (commandsRegistered) сделает ранний путь
            // no-op.
            bootstrapCommandsLate();
        }
        // Если БД ещё не готова — вернёмся сюда из afterDatabaseReady().
    }

    /**
     * Поздняя регистрация (миграция завершилась после фазы ENABLED):
     * PaperCommands.INSTANCE.register(PluginMeta, node, description, aliases) —
     * единственный публичный API Paper 1.21.x для добавления команд вне окна
     * lifecycle-события. INSTANCE существует только в runtime (paper-server),
     * поэтому берём его через рефлексию — при компиляции против paper-api
     * прямой ссылки на класс нет.
     */
    private void bootstrapCommandsLate() {
        try {
            Class<?> clazz = Class.forName("io.papermc.paper.command.brigadier.PaperCommands");
            Commands dispatcher = (Commands) clazz.getField("INSTANCE").get(null);
            registerCommandsVia(dispatcher, getPluginMeta());
        } catch (ClassNotFoundException ex) {
            getLogger().severe("[Commands] Не найден PaperCommands.INSTANCE — "
                    + "регистрация вне lifecycle невозможна на этом ядре.");
        } catch (Throwable ex) {
            getLogger().severe("[Commands] Ошибка поздней регистрации команд: " + ex +
                    " — /tb, /tbuildings, /tadmin могут быть недоступны до перезапуска.");
        }
    }

    private void registerCommandsVia(Commands commands) {
        try {
            registerCommandsCore((node, desc, aliases) -> commands.register(node, desc, aliases));
        } catch (Throwable ex) {
            getLogger().severe("[Commands] Ошибка регистрации команд: " + ex.getMessage());
        }
    }

    private void registerCommandsVia(Commands commands, PluginMeta meta) {
        try {
            registerCommandsCore((node, desc, aliases) ->
                    commands.register(meta, node, desc, aliases));
        } catch (Throwable ex) {
            getLogger().severe("[Commands] Ошибка регистрации команд: " + ex.getMessage());
        }
    }

    @FunctionalInterface
    private interface CommandRegisterFn {
        void register(LiteralCommandNode<CommandSourceStack> node,
                      String description,
                      java.util.Collection<String> aliases);
    }

    private void registerCommandsCore(CommandRegisterFn reg) {
        // защита от двойной регистрации (lifecycle-событие + afterDatabaseReady)
        if (!commandsRegistered.compareAndSet(false, true)) return;

        reg.register(new TbCommand(this, buildingRegistry, buildingService).build(),
                "TownBuildings: debug-команды", List.of());

        // ВНИМАНИЕ: /t принадлежит Towny. Регистрация своего литерала "t"
        // через Brigadier затирает дерево подкоманд Towny (new, spawn, claim, ...).
        // Поэтому используем только /tbuildings.
        TCommand tcmd = new TCommand(this);
        reg.register(tcmd.buildUnder("tbuildings"),
                "TownBuildings: меню города (builds/inventory/stock)", List.of());

        reg.register(new AdminCommand(this).build(),
                "TownBuildings: админ-команды",
                List.of("tbadmin"));

        getLogger().info("[Commands] Зарегистрированы /tb, /tbuildings, /tadmin (+алиас /tbadmin).");
    }

    // -------- геттеры --------

    public static @NotNull TownBuildingsPlugin getInstance() {
        TownBuildingsPlugin inst = instance;
        if (inst == null) throw new IllegalStateException("Плагин не инициализирован.");
        return inst;
    }

    public @NotNull ConfigManager configManager()                 { return configManager; }
    public @NotNull MessagesConfig getMessagesConfig()            { return messagesConfig; }
    public @NotNull BuildingDefinitionRegistry buildingRegistry() { return buildingRegistry; }

    public @NotNull DatabaseManager databaseManager()             { return databaseManager; }
    public @NotNull TownDao townDao()                             { return townDao; }
    public @NotNull BuildingDao buildingDao()                     { return buildingDao; }
    public @NotNull LogDao logDao()                               { return logDao; }
    public @NotNull OnlineTimeDao onlineTimeDao()                 { return onlineTimeDao; }
    public @NotNull InventoryDao inventoryDao()                   { return inventoryDao; }
    public @NotNull StockDao stockDao()                           { return stockDao; }

    public @NotNull TownyIntegrationService towny()               { return townyIntegration; }
    public @NotNull TownCache cache()                             { return townCache; }
    public @NotNull TownService townService()                     { return townService; }

    public @NotNull EconomyService economyService()               { return economyService; }
    public @NotNull InventoryService inventoryService()           { return inventoryService; }
    public @NotNull StockService stockService()                   { return stockService; }
    public @NotNull LogService logService()                       { return logService; }
    public @NotNull BuildingService buildingService()             { return buildingService; }

    public @NotNull OnlineTimeService onlineTimeService()         { return onlineTimeService; }
    public @NotNull RewardService rewardService()                 { return rewardService; }

    public @NotNull EffectService effectService()                 { return effectService; }
    public @NotNull BonusService bonusService()                   { return bonusService; }
    public @NotNull PermissionService permissionService()         { return permissionService; }
    public @NotNull SoundService soundService()                   { return soundService; }

    public @NotNull UniversityService universityService()         { return universityService; }

    public @NotNull MayorChangeListener mayorChangeListener()     { return mayorChangeListener; }

    public @NotNull OnlineBatchWriter onlineBatchWriter()         { return onlineBatchWriter; }

    public @NotNull MenuManager menuManager()                     { return menuManager; }

    public @NotNull ConfigManager getConfigManager()              { return configManager; }

    public @NotNull PageSessionRegistry inventorySessions()       { return inventorySessions; }
    public @NotNull PageSessionRegistry stockSessions()           { return stockSessions; }
    public @NotNull CityInventoryMenu cityInventoryMenu()         { return cityInventoryMenu; }
    public @NotNull CityStockMenu cityStockMenu()                 { return cityStockMenu; }

    public @NotNull BlockPlaceTracker blockPlaceTracker()         { return blockPlaceTracker; }
}