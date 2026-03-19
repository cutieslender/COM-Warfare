# HubCore — Professional Hub/Lobby Plugin
**Version:** 1.0.0 | **API:** Paper 1.21.1 | **Java:** 21 | **Build:** Maven

> A fully modular, 100% YAML-configurable Hub/Lobby core for VibeCraft Network.
> Comparable to UltimateLobby / DeluxeHub — fully custom and self-hosted.

---

## 📁 Project Structure

```
HubCore/
├── pom.xml
└── src/main/
    ├── java/com/vibecraft/hub/
    │   ├── HubCore.java              ← Main plugin class (entry point)
    │   ├── commands/
    │   │   ├── HubCommand.java       ← /hub /lobby /spawn
    │   │   ├── SetSpawnCommand.java  ← /setspawn
    │   │   └── HubCoreCommand.java   ← /hubcore (admin) with tab-complete
    │   ├── listeners/
    │   │   ├── PlayerJoinListener.java     ← Join teleport, items, title, scoreboard
    │   │   ├── PlayerQuitListener.java     ← Quit cleanup + message
    │   │   ├── PlayerProtectListener.java  ← All lobby protections
    │   │   ├── InventoryClickListener.java ← GUI menu click handler
    │   │   ├── ItemInteractListener.java   ← Hotbar item → open menu
    │   │   ├── DoubleJumpListener.java     ← Double-jump + particles + sound
    │   │   └── ChatListener.java           ← Custom chat format + anti-spam
    │   ├── scoreboard/
    │   │   └── ScoreboardManager.java  ← Per-player sidebar with animation
    │   ├── tablist/
    │   │   └── TablistManager.java     ← Animated header/footer + team sorting
    │   ├── holograms/
    │   │   └── HologramManager.java    ← ArmorStand-based floating text
    │   ├── menus/
    │   │   └── MenuManager.java        ← Dynamic GUI builder + action dispatcher
    │   ├── items/
    │   │   └── ItemManager.java        ← Hotbar lobby item builder
    │   ├── players/
    │   │   ├── PlayerDataManager.java  ← In-memory player cache
    │   │   └── HubPlayerData.java      ← Per-player data POJO
    │   ├── ranks/
    │   │   └── RankManager.java        ← LuckPerms prefix/suffix/group
    │   ├── config/
    │   │   └── ConfigManager.java      ← Loads all YAML files, supports reload
    │   ├── bonus/
    │   │   ├── LaunchpadListener.java  ← Block-based launchpads
    │   │   ├── LobbyMusicManager.java  ← Repeating sound tracks
    │   │   └── NPCManager.java         ← Villager NPCs with menu actions
    │   └── utils/
    │       ├── ColorUtils.java         ← & codes, MiniMessage, PAPI
    │       ├── HubCoreUtil.java        ← Spawn, messaging, title helpers
    │       └── Logger.java             ← Prefixed console logger
    └── resources/
        ├── plugin.yml
        ├── config.yml        ← Main settings, protections, ranks, bonus
        ├── messages.yml      ← All player-facing messages
        ├── scoreboard.yml    ← Sidebar lines + animated title
        ├── tablist.yml       ← Header/footer + name format
        ├── menus.yml         ← All GUI definitions (server-selector, cosmetics…)
        ├── items.yml         ← Hotbar lobby items
        ├── holograms.yml     ← Floating hologram locations + lines
        └── spawn.yml         ← Hub spawn point (auto-updated by /setspawn)
```

---

## ⚙️ Requirements

| Dependency      | Version   | Required |
|-----------------|-----------|----------|
| Paper           | 1.21.1+   | ✅ Yes   |
| Java            | 21        | ✅ Yes   |
| LuckPerms       | 5.x       | ⭐ Soft  |
| PlaceholderAPI  | 2.11+     | ⭐ Soft  |
| Vault           | 1.7+      | ⭐ Soft  |

---

## 🔨 Compilation

### Prerequisites
- JDK 21 (`java -version` must show 21)
- Maven 3.8+ (`mvn -version`)

### Build Steps

```bash
# 1. Clone or extract the project
cd HubCore

# 2. Compile and package
mvn clean package -DskipTests

# 3. Find your JAR
ls target/HubCore-1.0.0.jar
```

### Output
The built JAR will be at: `target/HubCore-1.0.0.jar`

---

## 🚀 Installation

1. Copy `HubCore-1.0.0.jar` into your server's `plugins/` folder.
2. Start the server once — config files are generated automatically.
3. Stop the server.
4. Edit `plugins/HubCore/config.yml`, `messages.yml`, `scoreboard.yml`, etc.
5. Start the server again.
6. In-game: run `/setspawn` to set the hub spawn point.

---

## 🎮 Commands

| Command                       | Permission          | Description                       |
|-------------------------------|---------------------|-----------------------------------|
| `/hub`                        | `hubcore.hub`       | Teleport to hub spawn             |
| `/lobby`                      | `hubcore.hub`       | Alias for /hub                    |
| `/spawn`                      | `hubcore.hub`       | Alias for /hub                    |
| `/setspawn`                   | `hubcore.setspawn`  | Set hub spawn to your location    |
| `/hubcore reload`             | `hubcore.admin`     | Reload all configs                |
| `/hubcore info`               | `hubcore.admin`     | Plugin status + dependency info   |
| `/hubcore hologram list`      | `hubcore.admin`     | List all active holograms         |
| `/hubcore hologram reload`    | `hubcore.admin`     | Reload holograms from config      |
| `/hubcore menu <id>`          | `hubcore.admin`     | Preview any GUI menu              |
| `/hubcore items`              | `hubcore.admin`     | Re-give lobby items to yourself   |
| `/hubcore debug`              | `hubcore.admin`     | Toggle debug mode                 |

---

## 🔑 Permissions

| Permission                    | Default | Description                      |
|-------------------------------|---------|----------------------------------|
| `hubcore.hub`                 | all     | Use /hub command                 |
| `hubcore.setspawn`            | op      | Use /setspawn command            |
| `hubcore.admin`               | op      | All admin commands               |
| `hubcore.bypass.protect`      | op      | Bypass all lobby protections     |
| `hubcore.bypass.doublejump`   | all     | Use double jump feature          |
| `hubcore.bypass.fly`          | op      | Fly freely in the hub            |

---

## 📦 Features Overview

### ✅ Core Features
- **Spawn System** — `/hub`, `/setspawn`, auto-teleport on join, configurable spawn.yml
- **Lobby Protection** — Block break/place, PvP, hunger, item drop, mob spawn, weather — all toggleable
- **Adventure Mode** — Forced on all non-op players (configurable)
- **Hotbar Items** — 5 configurable items → open GUI menus on right-click
- **GUI Menus** — Fully YAML-driven: Server Selector, Cosmetics, Profile, Settings

### ✅ Scoreboard
- Per-player sidebar scoreboard
- Animated title (frame list, cycles every second)
- Configurable lines with: `%player%`, `%online%`, `%rank%`, `%coins%`, PAPI placeholders

### ✅ Tablist
- Animated header & footer (per-line `{anim:A|B|C}` syntax)
- LuckPerms prefix/suffix in player name
- Scoreboard team-based rank sorting (weight-based)

### ✅ Holograms
- ArmorStand-based floating text — no external lib required
- Defined in `holograms.yml` with world + XYZ coordinates
- Multiple lines, & color codes supported
- Persistent across reloads via PersistentDataContainer

### ✅ Ranks
- LuckPerms group detection
- Custom `display`, `color`, `weight` per group in config.yml
- Used in scoreboard, tablist, chat format

### ✅ Chat
- Custom format with `%prefix%`, `%player%`, `%suffix%`, `%message%`
- Anti-spam cooldown (configurable ms)

### ✅ Bonus Features
- **Double Jump** — configurable power, forward boost, particles, sound
- **Launchpads** — step on SLIME_BLOCK / MAGMA_BLOCK to launch
- **Lobby Music** — repeating Bukkit sounds (no resource pack)
- **NPC System** — Villager-based NPCs with menu/connect actions
- **BungeeCord** — server selector connects via plugin messaging channel

---

## 🗂️ Configuration Reference

### config.yml — Key Sections
```yaml
protection:          # Toggle each lobby protection individually
double-jump:         # Power, sounds, particles
ranks:               # Per-group display, color, sort weight
anti-spam:           # Command and chat cooldown in ms
launchpads:          # Block types and launch velocities
lobby-music:         # Sound tracks, interval, volume
npcs:                # Villager NPC list with locations + actions
```

### menus.yml — Action Types
```yaml
action: "connect:survival"        # BungeeCord server connect
action: "menu:server-selector"    # Open another menu
action: "command:say hello"       # Run command as player
action: "close"                   # Close the inventory
action: "hub"                     # Teleport to spawn
```

### holograms.yml — Format
```yaml
holograms:
  my-hologram:
    location:
      world: world
      x: 0.5
      y: 65.0
      z: 0.5
    lines:
      - "&6&lWelcome!"
      - "&7play.vibecraft.net"
```

---

## 🔌 PlaceholderAPI Integration

When PlaceholderAPI is installed, you can use any `%placeholder%` in:
- Scoreboard lines
- Tablist header/footer
- Join/quit messages
- Menu item names and lore
- Chat format

---

## 🔒 Security Notes
- All configs use `try/catch` for safe loading — a malformed YAML won't crash the server
- `/hubcore reload` is safe to run live — updates all players in-place
- Anti-spam protection on `/hub` command (configurable cooldown)
- Chat cooldown prevents message flooding
- No async operations that touch Bukkit API (Paper scheduler used correctly)

---

## 📈 Performance Notes
- Scoreboard: updated every 20 ticks per online player (O(n) per tick)
- Tablist: updated every 40 ticks
- Player data: fully in-memory cache, no disk I/O during gameplay
- Holograms: spawned once, never re-created unless reloaded
- No reflection used anywhere
- Compatible with 1000+ concurrent players

---

## 🔧 Extending HubCore

### Adding a new GUI menu
1. Add an entry to `menus.yml` under `menus:`
2. Add an item in `items.yml` with `action: your-menu-id`
3. Run `/hubcore reload`

### Adding a new hologram
1. Add an entry to `holograms.yml` under `holograms:`
2. Run `/hubcore hologram reload`

### Adding a new rank
1. Add the group to `config.yml` under `ranks:`
2. LuckPerms group name must match exactly (lowercase)
3. Run `/hubcore reload`

---

*HubCore — built for VibeCraft Network | Java 21 + Paper 1.21.1*
#   C O M - W a r f a r e  
 