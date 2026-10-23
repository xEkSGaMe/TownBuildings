<div align="center">

# 🏛️ TownBuildings

**Городские постройки, склады и хранилища для [Towny](https://github.com/TownyAdvanced/Towny)**

[![Paper](https://img.shields.io/badge/Paper-1.21-blue?logo=hangouts&color=e67e22)](https://docs.papermc.io/)
[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://adoptium.net/)
[![Maven](https://img.shields.io/badge/build-Maven-red?logo=apachemaven)](https://maven.apache.org/)
[![MySQL](https://img.shields.io/badge/DB-MySQL-4479A1?logo=mysql&logoColor=white)](https://www.mysql.com/)
[![Version](https://img.shields.io/badge/version-1.0.0--SNAPSHOT-brightgreen)](#-установка)

*Прокачивайте здания города — получайте бонусы, награды и преимущества.*

</div>

---

## ✨ Возможности

- 🏗️ **29 зданий** в **5 ветках развития**: экономика, военное дело, технологии, управление и производство
- ⬆️ **Многоуровневые улучшения** (до 10 уровней) — стоимость предметами и монетами из казны
- 🎁 **Пассивные награды**: монеты в казну и горожанам онлайн, ресурсы в хранилище, +чанки, снижение налогов, бонусы опыта и сбора урожая
- 📦 **Склад города** (`inventory`) и **хранилище ресурсов** (`stock`) с удобными GUI-меню
- 💾 **MySQL-хранилище** с автоматическими миграциями схемы и пулом соединений HikariCP
- ⚡ **Оптимизация под 200+ онлайна**: кэш городских данных, асинхронный JDBC, батч-запись, ленивые сессии GUI
- 🔐 **Гибкие права** через LuckPerms (ноды `townbuildings.*` с контекстом источника)
- 💰 **Экономика** через Vault API (совместимо с VaultUnlocked)
- 🎯 **Интеграция с Towny** (владелец, звания, казна) и поддержка PlaceholderAPI как опциональной зависимости
- 🔊 Звуковые эффекты, журнал действий (`logs`), effects-сервис (например, взрыв тренировочного лагеря)

### 🌳 Ветки развития

| Ветка | Примеры зданий |
|---|---|
| 💰 **ECONOMY** — Экономика | Монетный двор, Торговый дом, Налоговое управление, Городской банк, Казначейство, Золотой прииск, Биржа труда |
| ⚔️ **MILITARY** — Военное дело | Бастион, Цитадель, Крепость, Форт, Ров, Пороховой завод, Сторожевая башня, Тренировочный лагерь, Штаб |
| 🔬 **TECH** — Технологии | Школа, Университет, Библиотека, Исследовательский центр, Техническое училище, Топографический институт |
| 🏢 **MANAGEMENT** — Управление | Склад, Хранилище |
| 🏭 **PRODUCTION** — Производство | Шахта, Мануфактура, Лесопилка, Ферма, Теплица |

Все здания настраиваются в `buildings.yml` — добавляйте свои, меняйте стоимости и награды без перекомпиляции.

---

## 📋 Требования

| Компонент | Версия |
|---|---|
| Сервер | Paper **1.21.x** |
| Java | **21** или новее |
| MySQL / MariaDB | 8.x |
| [Towny](https://github.com/TownyAdvanced/Towny) | **0.103+** *(обязательно)* |
| [Vault](https://github.com/MilkBowl/Vault) (+ экономический плагин) | 1.7+ *(обязательно)* |
| [LuckPerms](https://luckperms.net/) | 5.4+ *(обязательно)* |
| [PlaceholderAPI](https://placeholderapi.com/) | 2.11+ *(опционально)* |

---

## 🚀 Установка

1. Скачайте сборку `TownBuildings-<version>.jar` (или соберите из исходников, см. ниже).
2. Положите jar в папку `plugins/` вашего Paper-сервера.
3. Укажите доступную MySQL-базу в `plugins/TownBuildings/config.yml`:

   ```yaml
   database:
     host: "127.0.0.1"
     port: 3306
     name: "townbuildings"
     user: "root"
     password: "changeme"
   ```

4. Перезапустите сервер. Таблицы (`tb_towns`, `tb_buildings`, `tb_inventories`, `tb_stocks`, `tb_logs` и др.) создадутся автоматически.

---

## 🎮 Команды

### Игроки

| Команда | Описание | Право |
|---|---|---|
| `/tbuildings builds` | Меню построек города | `townbuildings.builds` |
| `/tbuildings inventory` | Склад города (GUI) | `townbuildings.inventory` |
| `/tbuildings stock` | Хранилище ресурсов (GUI) | `townbuildings.stock` |

> ⚠️ Корень `/t` принадлежит Towny — плагин регистрирует только `/tbuildings`,
> чтобы не затирать подкоманды Towny (`new`, `spawn`, `claim`, `deposit`, ...).

### Администраторы (`/tbadmin`, алиас `tadmin`)

| Команда | Описание | Право |
|---|---|---|
| `/tbadmin info` | Информация о зданиях города | `townbuildings.admin` |
| `/tbadmin give <...>` | Выдать предметы/ресурсы | `townbuildings.admin.give` |
| `/tbadmin remove <...>` | Удалить постройку | `townbuildings.admin.remove` |
| `/tbadmin setlevel <...>` | Задать уровень здания | `townbuildings.admin.setlevel` |
| `/tbadmin logs` | Журнал действий | `townbuildings.admin.logs` |
| `/tbadmin reload` | Перезагрузить конфиги | `townbuildings.admin.reload` |

### Дебаг (`/tb`)

| Команда | Описание |
|---|---|
| `/tb list` | Список всех зданий по веткам |
| `/tb upgrade <buildingId>` | Прокачать здание в городе игрока |

---

## ⚙️ Конфигурация

| Файл | Назначение |
|---|---|
| `config.yml` | База данных, пул соединений, кэш, сессии GUI, трекинг, эффекты |
| `buildings.yml` | Описания зданий: ветка, иконка, макс. уровень, стоимости и награды по уровням |
| `messages.yml` | Все текстовые сообщения (MiniMessage, язык — `ru`) |

Пример здания в `buildings.yml`:

```yaml
mint:
  display-name: "Монетный двор"
  branch: ECONOMY
  icon: GOLD_INGOT
  max-level: 10
  levels:
    1:
      bonus-lore: ["<gray>10 монет/час в казну"]
      cost-items: { OAK_LOG: 32, COBBLESTONE: 32, ANVIL: 1 }
      rewards: { town-coins-per-hour: 10 }
```

Доступные награды: `town-coins-per-hour`, `citizen-coins-per-hour`, `stock-items-per-hour`,
`town-chunks-bonus`, `tax-reduction-percent`, `exp-bonus-percent`, `harvest-bonus-percent`.

---

## 🧱 Архитектура

```
ru.example.cloudcore
├── TownBuildingsPlugin.java   # точка входа, регистрация команд и сервисов
├── cache/                     # кэш городских данных (TTL, прогрев при старте)
├── command/                   # Brigadier-команды: /tbuildings, /tbadmin, /tb
├── config/                    # загрузчики config/buildings/messages, реестр зданий
├── database/                  # DatabaseManager (HikariCP), MigrationRunner, DAO, модели
├── gui/                       # MenuManager, меню, holders, сессии страниц, ItemBuilder
├── listener/                  # игровые слушатели (размещение блоков и др.)
├── service/                   # бизнес-логика: здания, склад, экономика, эффекты, права…
├── task/                      # периодические задачи (награды, сохранение, чистка)
└── util/                      # AsyncUtils, ItemSerializer, RomanNumbers
```

**Ключевые решения:**
- Все JDBC-операции — асинхронные (`AsyncUtils` + выделанные IO-потоки), без нагрузки на main thread.
- Пул HikariCP и MySQL-драйвер reloc-нуты в `ru.example.cloudcore.libs.*` — конфликтов с другими плагинами нет.
- GUI-сессии живут пока на страницу смотрят, и сохраняются с debounce (не спамят в БД).
- Миграции схемы версионируются в таблице `tb_schema_version`.

---

## 🔨 Сборка из исходников

```bash
git clone <repo-url> && cd TownBuildings
mvn clean package
# результат: target/TownBuildings-1.0.0-SNAPSHOT.jar
```

Требуется JDK 21 и Maven 3.9+. Зависимости (Paper, Towny, Vault…) подтягиваются
из PaperMC, Towny и jitpack-репозиториев.

---

## 📜 Лицензия

Проект распространяется под **[MIT License](LICENSE)**.

---

<div align="center">

**Автор:** EksGame · **Сайт:** [example.ru](https://example.ru)

Сделано с ❤️ для Towny-сообщества

</div>
