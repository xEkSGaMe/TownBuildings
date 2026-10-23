package ru.example.cloudcore;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import ru.example.cloudcore.cache.CacheWarmup;
import ru.example.cloudcore.cache.TownCache;
import ru.example.cloudcore.command.AdminCommand;
import ru.example.cloudcore.command.TCommand;
import ru.example.cloudcore.command.TbCommand;
import ru.example.cloudcore.config.*;
import ru.example.cloudcore.database.DatabaseManager;
import ru.example.cloudcore.database.MigrationRunner;
import ru.example.cloudcore.database.dao.*;
import ru.example.cloudcore.gui.MenuManager;
import ru.example.cloudcore.gui.holder.InventoryMenuHolder;
import ru.example.cloudcore.gui.holder.StockMenuHolder;
import ru.example.cloudcore.gui.menu.CityInventoryMenu;
import ru.example.cloudcore.gui.menu.CityStockMenu;
import ru.example.cloudcore.gui.session.PageSessionRegistry;
import ru.example.cloudcore.institutes.command.CommandInterceptor;
import ru.example.cloudcore.institutes.command.InstitutesCommand;
import ru.example.cloudcore.institutes.config.InstitutesConfigManager;
import ru.example.cloudcore.institutes.config.InstitutesMessages;
import ru.example.cloudcore.institutes.database.InstitutesDao;
import ru.example.cloudcore.institutes.database.InstitutesPlaytimeDao;
import ru.example.cloudcore.institutes.gui.InstituteMenuListener;
import ru.example.cloudcore.institutes.listener.InstitutesPlayerListener;
import ru.example.cloudcore.institutes.listener.ReligionBonusListener;
import ru.example.cloudcore.institutes.service.*;
import ru.example.cloudcore.institutes.util.VaultEconomyHolder;
import ru.example.cloudcore.listener.*;
import ru.example.cloudcore.service.*;
import ru.example.cloudcore.service.impl.DbInventoryService;
import ru.example.cloudcore.task.TownSyncTask;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CloudCorePlugin extends JavaPlugin {

    private static CloudCorePlugin instance;

    private ConfigManager configManager;
    private MessagesConfig messagesConfig;
    private InstitutesConfigManager institutesConfig;
    private InstitutesMessages institutesMessages;

    private DatabaseManager databaseManager;
    private MigrationRunner migrationRunner;
    private TownDao townDao;
    private BuildingDao buildingDao;
    private LogDao logDao;
    private OnlineTimeDao onlineTimeDao;
    private InventoryDao inventoryDao;
    private StockDao stockDao;
    private WonderDao wonderDao;
    private InstitutesDao institutesDao;
    private InstitutesPlaytimeDao institutesPlaytimeDao;

    private TownyIntegrationService townyIntegration;
    private TownCache townCache;
    private TownService townService;
    private EconomyService economyService;
    private SoundService soundService;
    private PermissionService permissionService;
    private TownyWarService townyWarService;
    private VaultEconomyHolder vaultEconomy;

    private BuildingDefinitionRegistry buildingRegistry;
    private WonderRegistry wonderRegistry;
    private BuildingService buildingService;
    private WonderService wonderService;
    private InventoryService inventoryService;
    private StockService stockService;
    private LogService logService;
    private RewardService rewardService;
    private EffectService effectService;
    private BonusService bonusService;
    private OnlineTimeService onlineTimeService;
    private OnlineBatchWriter onlineBatchWriter;
    private UniversityService universityService;
    private MenuManager menuManager;
    private PageSessionRegistry inventorySessions;
    private PageSessionRegistry stockSessions;
    private CityInventoryMenu cityInventoryMenu;
    private CityStockMenu cityStockMenu;
    private BlockPlaceTracker blockPlaceTracker;
    private TownSyncTask townSyncTask;
    private BonusListener bonusListener;
    private MayorChangeListener mayorChangeListener;

    private InstitutesService institutesService;
    private InstitutesPlaytimeService institutesPlaytime;
    private InstituteSelectionService instituteSelection;
    private InstituteBonusService instituteBonus;

    private CompletableFuture<Void> cacheWarmupFuture = CompletableFuture.completedFuture(null);
    private final AtomicBoolean commandsRegistered = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        instance = this;
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, this::onCommandsLifecycle);

        saveDefaultConfig();
        saveIfAbsent("messages.yml");
        saveIfAbsent("buildings.yml");
        saveIfAbsent("wonders.yml");
        saveIfAbsent("institutes.yml");

        ConfigMigrator migrator = new ConfigMigrator(this);
        migrator.migrate("config.yml");
        migrator.migrate("messages.yml");
        migrator.migrate("institutes.yml");

        this.configManager = new ConfigManager(this); this.configManager.load();
        this.messagesConfig = new MessagesConfig(this); this.messagesConfig.load();
        this.institutesConfig = new InstitutesConfigManager(this); this.institutesConfig.load();
        this.institutesMessages = new InstitutesMessages(this);

        this.buildingRegistry = new BuildingDefinitionRegistry(this); this.buildingRegistry.load();
        this.wonderRegistry = new WonderRegistry(this); this.wonderRegistry.load();
        this.blockPlaceTracker = new BlockPlaceTracker(this);

        getServer().getPluginManager().registerEvents(new InstituteMenuListener(this), this);
        getServer().getPluginManager().registerEvents(new CommandInterceptor(this), this);
        getServer().getPluginManager().registerEvents(new InstitutesPlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new ReligionBonusListener(this), this);

        bootstrapDatabaseAsync();
        getLogger().info("CloudCore v" + getPluginMeta().getVersion() + " включён.");
    }

    @Override
    public void onDisable() {
        if (townSyncTask != null) townSyncTask.stop();
        if (inventorySessions != null) { inventorySessions.stopSweep(); inventorySessions.flushAllSync(); inventorySessions.dropAllSessions(); }
        if (stockSessions != null) { stockSessions.stopSweep(); stockSessions.flushAllSync(); stockSessions.dropAllSessions(); }
        if (institutesPlaytime != null) { institutesPlaytime.stop(); institutesPlaytime.flushSync(); }
        if (onlineBatchWriter != null) { onlineBatchWriter.stop(); onlineBatchWriter.flushSync(); }
        if (effectService != null) effectService.onDisable();
        if (onlineTimeService != null) onlineTimeService.onDisable();
        if (instituteBonus != null) instituteBonus.stop();
        if (menuManager != null) menuManager.closeAll();
        if (townCache != null) townCache.invalidateAll();
        if (blockPlaceTracker != null) blockPlaceTracker.clear();
        if (permissionService != null) permissionService.resetApiCache();
        if (databaseManager != null) databaseManager.disconnect();
        instance = null;
    }

    private void saveIfAbsent(String name) {
        if (!new java.io.File(getDataFolder(), name).exists()) saveResource(name, false);
    }

    private void bootstrapDatabaseAsync() {
        Thread t = new Thread(() -> {
            try {
                DatabaseManager dbm = new DatabaseManager(this);
                dbm.connect();
                MigrationRunner runner = new MigrationRunner(this, dbm);
                runner.migrate();

                this.databaseManager = dbm; this.migrationRunner = runner;
                this.townDao = new TownDao(dbm); this.buildingDao = new BuildingDao(dbm);
                this.logDao = new LogDao(dbm); this.onlineTimeDao = new OnlineTimeDao(dbm);
                this.inventoryDao = new InventoryDao(dbm); this.stockDao = new StockDao(dbm);
                this.wonderDao = new WonderDao(dbm); this.institutesDao = new InstitutesDao(dbm);
                this.institutesPlaytimeDao = new InstitutesPlaytimeDao(dbm);

                Bukkit.getScheduler().runTask(this, this::afterDatabaseReady);
            } catch (Throwable ex) {
                getLogger().severe("[DB] Критическая ошибка миграции: " + ex.getMessage());
            }
        }, "CloudCore-Bootstrap");
        t.setDaemon(true); t.start();
    }

    private void afterDatabaseReady() {
        if (!isEnabled()) return;

        this.townyIntegration = new TownyIntegrationService(this);
        this.townCache = new TownCache(configManager.cache().townTtlSeconds());
        this.townService = new TownService(this, townDao, buildingDao, townCache);

        if (configManager.cache().warmupOnStart()) this.cacheWarmupFuture = new CacheWarmup(this, townService, townDao).run();
        this.townSyncTask = new TownSyncTask(this, townService); this.townSyncTask.start();

        this.economyService = new EconomyService(this);
        this.inventoryService = new DbInventoryService(inventoryDao);
        this.stockService = new StockService(stockDao);
        this.logService = new LogService(logDao);
        this.soundService = new SoundService(this);

        this.townyWarService = new TownyWarService(this); this.townyWarService.onEnable();

        this.buildingService = new BuildingService(this, buildingRegistry, townService, townyIntegration, economyService, inventoryService, logService);
        this.rewardService = new RewardService(this);

        this.onlineBatchWriter = new OnlineBatchWriter(this, databaseManager); this.onlineBatchWriter.start();
        this.onlineTimeService = new OnlineTimeService(this, onlineTimeDao, onlineBatchWriter); this.onlineTimeService.start();

        this.effectService = new EffectService(this);
        this.bonusService = new BonusService(this);
        this.permissionService = new PermissionService(this);
        this.mayorChangeListener = new MayorChangeListener(this, permissionService);
        this.effectService.start();

        this.menuManager = new MenuManager(this);
        this.universityService = new UniversityService(this);
        cacheWarmupFuture.whenComplete((v, ex) -> Bukkit.getScheduler().runTask(this, () -> universityService.syncAll()));

        this.inventorySessions = new PageSessionRegistry(this, inventoryDao::loadPage, inventoryDao::savePageDiff,
                (uuid, page, totalPages, name) -> new InventoryMenuHolder(uuid, page, totalPages, name),
                (name, page, totalPages) -> getMessagesConfig().get("inventory.menu-title", Map.of("page", Integer.toString(page), "pages", Integer.toString(totalPages), "town", name)));
        this.stockSessions = new PageSessionRegistry(this, stockDao::loadPage, stockDao::savePageDiff,
                (uuid, page, totalPages, name) -> new StockMenuHolder(uuid, page, totalPages, name),
                (name, page, totalPages) -> getMessagesConfig().get("stock.menu-title", Map.of("page", Integer.toString(page), "pages", Integer.toString(totalPages), "town", name)));
        this.cityInventoryMenu = new CityInventoryMenu(this, inventorySessions);
        this.cityStockMenu = new CityStockMenu(this, stockSessions);

        this.wonderService = new WonderService(this, wonderRegistry, wonderDao, townService, inventoryService, economyService, logService);
        this.wonderService.warmup();

        this.institutesService = new InstitutesService(this, institutesDao, townService);
        this.institutesPlaytime = new InstitutesPlaytimeService(this, institutesPlaytimeDao);
        this.vaultEconomy = VaultEconomyHolder.tryCreate(this);

        institutesService.warmup().thenCompose(v -> institutesPlaytime.warmup()).thenRun(() -> Bukkit.getScheduler().runTask(this, () -> {
            if (!isEnabled()) return;
            this.institutesPlaytime.start();
            this.instituteSelection = new InstituteSelectionService(this, institutesService, townyIntegration);
            this.instituteBonus = new InstituteBonusService(this, townyIntegration, institutesService, institutesPlaytime);
            this.instituteBonus.start();
            this.instituteSelection.reconcileAllTowns();
        }));

        registerTownBuildingsListeners();
        bootstrapCommandsLate();
    }

    private void registerTownBuildingsListeners() {
        getServer().getPluginManager().registerEvents(new TownListener(this, townService), this);
        getServer().getPluginManager().registerEvents(new InventoryListener(this, cityInventoryMenu, inventorySessions), this);
        getServer().getPluginManager().registerEvents(new StockListener(this, cityStockMenu, stockSessions), this);
        getServer().getPluginManager().registerEvents(new MenuListener(this, menuManager), this);
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        this.bonusListener = new BonusListener(this);
        getServer().getPluginManager().registerEvents(bonusListener, this);
        getServer().getPluginManager().registerEvents(new LibraryListener(this), this);
        getServer().getPluginManager().registerEvents(new TrainingCampListener(this), this);
        getServer().getPluginManager().registerEvents(new TaxOfficeListener(this), this);
        getServer().getPluginManager().registerEvents(new TopographicInstituteListener(this), this);
        getServer().getPluginManager().registerEvents(new HeadquartersStackListener(this), this);
        getServer().getPluginManager().registerEvents(new UniversityListener(this), this);
        getServer().getPluginManager().registerEvents(new EffectReapplyListener(this), this);
        getServer().getPluginManager().registerEvents(mayorChangeListener, this);
        getServer().getPluginManager().registerEvents(new WonderClaimListener(this), this);
        getServer().getPluginManager().registerEvents(new WonderColossusListener(this), this);
        if (townyWarService != null && townyWarService.available()) {
            getServer().getPluginManager().registerEvents(new TownyWarKillListener(this), this);
        }
    }

    public void reloadAll() {
        configManager.load(); messagesConfig.load(); institutesConfig.load(); buildingRegistry.load();
        try { bonusService.invalidateCaches(); } catch (Throwable ignored) {}
        try { effectService.invalidateCaches(); } catch (Throwable ignored) {}
        try { onlineBatchWriter.reloadSchedule(configManager.online().batchFlushSeconds()); } catch (Throwable ignored) {}
        try { inventorySessions.reloadSettings(); } catch (Throwable ignored) {}
        try { stockSessions.reloadSettings(); } catch (Throwable ignored) {}
        try { effectService.reloadSettings(); } catch (Throwable ignored) {}
        try { blockPlaceTracker.reloadSettings(); } catch (Throwable ignored) {}
        try { rewardService.reloadSettings(); } catch (Throwable ignored) {}
        try { townyWarService.retryFetch(); } catch (Throwable ignored) {}
        onInstitutesReload();
    }

    public void onInstitutesReload() {
        if (institutesService == null) return;
        institutesService.invalidateAll();
        try { institutesPlaytime.flushSync(); } catch (Throwable ignored) {}
        institutesPlaytime.clearCache();
        institutesService.warmup().thenCompose(v -> institutesPlaytime.warmup()).thenRun(() -> Bukkit.getScheduler().runTask(this, () -> {
            if (!isEnabled()) return;
            if (instituteSelection != null) instituteSelection.reconcileAllTowns();
            this.vaultEconomy = VaultEconomyHolder.tryCreate(this);
            getLogger().info("[Institutes] Reload завершён.");
        }));
    }

    private void onCommandsLifecycle(ReloadableRegistrarEvent<Commands> event) {
        registerCommandsCore((node, desc, aliases) -> event.registrar().register(node, desc, aliases));
    }

    private void bootstrapCommandsLate() {
        if (!isEnabled() || databaseManager == null || !Bukkit.isPrimaryThread()) return;
        try {
            Class<?> clazz = Class.forName("io.papermc.paper.command.brigadier.PaperCommands");
            Commands dispatcher = (Commands) clazz.getField("INSTANCE").get(null);
            registerCommandsCore((node, desc, aliases) -> dispatcher.register(getPluginMeta(), node, desc, aliases));
        } catch (Throwable ex) {
            getLogger().severe("[Commands] Ошибка поздней регистрации: " + ex);
        }
    }

    @FunctionalInterface
    private interface CommandRegisterFn {
        void register(com.mojang.brigadier.tree.LiteralCommandNode<io.papermc.paper.command.brigadier.CommandSourceStack> node, String description, java.util.Collection<String> aliases);
    }

    private void registerCommandsCore(CommandRegisterFn reg) {
        if (!commandsRegistered.compareAndSet(false, true)) return;
        reg.register(new TbCommand(this, buildingRegistry, buildingService).build(), "CloudCore: debug-команды", List.of());
        reg.register(new TCommand(this).buildUnder("tbuildings"), "CloudCore: меню города", List.of());
        if (getConfigManager().features().adminCommands()) {
            reg.register(new AdminCommand(this).build(), "CloudCore: админ-команды", List.of("tbadmin"));
        }
        InstitutesCommand ic = new InstitutesCommand(this);
        reg.register(Commands.literal("institutes").requires(src -> src.getSender().hasPermission(institutesConfig.permissionBypass()))
                .executes(ctx -> { ic.handle(ctx.getSource().getSender(), new String[0]); return 1; })
                .then(Commands.literal("reload").executes(ctx -> { ic.handle(ctx.getSource().getSender(), new String[]{"reload"}); return 1; }))
                .build(), "CloudCore: Institutes", List.of("inst"));
    }

    public static @NotNull CloudCorePlugin getInstance() {
        CloudCorePlugin inst = instance;
        if (inst == null) throw new IllegalStateException("Плагин не инициализирован.");
        return inst;
    }

    public @NotNull ConfigManager configManager() { return configManager; }
    public @NotNull ConfigManager getConfigManager() { return configManager; }
    public @NotNull MessagesConfig getMessagesConfig() { return messagesConfig; }
    public @NotNull InstitutesConfigManager institutesConfig() { return institutesConfig; }
    public @NotNull InstitutesMessages institutesMessages() { return institutesMessages; }
    public @NotNull BuildingDefinitionRegistry buildingRegistry() { return buildingRegistry; }
    public @NotNull WonderRegistry wonderRegistry() { return wonderRegistry; }
    public @NotNull DatabaseManager databaseManager() { return databaseManager; }
    public @NotNull TownDao townDao() { return townDao; }
    public @NotNull BuildingDao buildingDao() { return buildingDao; }
    public @NotNull LogDao logDao() { return logDao; }
    public @NotNull OnlineTimeDao onlineTimeDao() { return onlineTimeDao; }
    public @NotNull InventoryDao inventoryDao() { return inventoryDao; }
    public @NotNull StockDao stockDao() { return stockDao; }
    public @NotNull WonderDao wonderDao() { return wonderDao; }
    public @NotNull InstitutesDao institutesDao() { return institutesDao; }
    public @NotNull InstitutesPlaytimeDao institutesPlaytimeDao() { return institutesPlaytimeDao; }
    public @NotNull TownyIntegrationService towny() { return townyIntegration; }
    public @NotNull TownCache cache() { return townCache; }
    public @NotNull TownService townService() { return townService; }
    public @NotNull EconomyService economyService() { return economyService; }
    public @NotNull SoundService soundService() { return soundService; }
    public @NotNull PermissionService permissionService() { return permissionService; }
    public @NotNull TownyWarService townyWarService() { return townyWarService; }
    public VaultEconomyHolder vaultEconomy() { return vaultEconomy; }
    public @NotNull BuildingService buildingService() { return buildingService; }
    public @NotNull WonderService wonderService() { return wonderService; }
    public @NotNull InventoryService inventoryService() { return inventoryService; }
    public @NotNull StockService stockService() { return stockService; }
    public @NotNull LogService logService() { return logService; }
    public @NotNull RewardService rewardService() { return rewardService; }
    public @NotNull EffectService effectService() { return effectService; }
    public @NotNull BonusService bonusService() { return bonusService; }
    public @NotNull OnlineTimeService onlineTimeService() { return onlineTimeService; }
    public @NotNull OnlineBatchWriter onlineBatchWriter() { return onlineBatchWriter; }
    public @NotNull UniversityService universityService() { return universityService; }
    public @NotNull MenuManager menuManager() { return menuManager; }
    public @NotNull PageSessionRegistry inventorySessions() { return inventorySessions; }
    public @NotNull PageSessionRegistry stockSessions() { return stockSessions; }
    public @NotNull CityInventoryMenu cityInventoryMenu() { return cityInventoryMenu; }
    public @NotNull CityStockMenu cityStockMenu() { return cityStockMenu; }
    public @NotNull BlockPlaceTracker blockPlaceTracker() { return blockPlaceTracker; }
    public @NotNull BonusListener bonusListener() { return bonusListener; }
    public @NotNull MayorChangeListener mayorChangeListener() { return mayorChangeListener; }
    public InstitutesService institutesService() { return institutesService; }
    public InstitutesPlaytimeService institutesPlaytime() { return institutesPlaytime; }
    public InstituteSelectionService instituteSelection() { return instituteSelection; }
    public InstituteBonusService instituteBonus() { return instituteBonus; }
}