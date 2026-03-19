package com.vibecraft.codwarfare;

import com.vibecraft.codwarfare.config.ArenaConfig;
import com.vibecraft.codwarfare.config.MessageConfig;
import com.vibecraft.codwarfare.game.CodArena;
import com.vibecraft.codwarfare.game.CodGameManager;
import com.vibecraft.codwarfare.listeners.PlayerListener;
import com.vibecraft.codwarfare.storage.LoadoutStore;
import com.vibecraft.codwarfare.storage.LoadoutStorage;
import com.vibecraft.codwarfare.storage.MySqlLoadoutStore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class CodWarfarePlugin extends JavaPlugin {

    private MessageConfig messages;
    private CodGameManager gameManager;
    private LoadoutStorage loadoutStore;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("arenas.yml", false);

        // If the server already has an older config.yml, saveDefaultConfig() won't merge new keys.
        // We still inject missing keys so runtime messages/logic don't break.
        ensureIntegrationDefaults();
        ensureMessageDefaults();
        ensureLoadoutStorageDefaults();
        ensureNewGameplayDefaults();

        this.messages = new MessageConfig(this);

        LoadoutStore local = new LoadoutStore(this);
        String type = getConfig().getString("loadout-storage.type", "mysql").toLowerCase();
        if ("mysql".equals(type) || "db".equals(type)) {
            this.loadoutStore = new MySqlLoadoutStore(this, local);
        } else {
            this.loadoutStore = local;
        }
        this.gameManager = new CodGameManager(this, messages, loadoutStore);
        getServer().getPluginManager().registerEvents(new PlayerListener(gameManager), this);
        logIntegrationStatus();
        getLogger().info("COD Warfare enabled (loadout-storage.type=" + type + ").");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) gameManager.shutdown();
        getLogger().info("COD Warfare disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("cod")) return false;
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(messages.get("usage"));
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "join" -> gameManager.join(player);
            case "leave" -> gameManager.leave(player);
            case "class" -> {
                if (args.length == 1) {
                    gameManager.openArmoryMenu(player);
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(messages.get("usage-class"));
                    return true;
                }
                String classKey = args[1].toLowerCase();
                boolean ok = gameManager.setClass(player, classKey);
                if (!ok) {
                    player.sendMessage(messages.get("unknown-class"));
                } else {
                    player.sendMessage(messages.get("class-selected", java.util.Map.of("class", classKey)));
                }
            }
            case "armory" -> gameManager.openArmoryMenu(player);
            case "perk" -> {
                if (args.length < 2) {
                    player.sendMessage(messages.get("usage-perk"));
                    return true;
                }
                String perkKey = args[1].toLowerCase();
                boolean ok = gameManager.setPerk(player, perkKey);
                if (!ok) {
                    player.sendMessage(messages.get("unknown-perk"));
                } else {
                    player.sendMessage(messages.get("perk-selected", java.util.Map.of("perk", perkKey)));
                }
            }
            case "votemap" -> {
                if (args.length < 2) {
                    player.sendMessage(messages.get("usage-votemap"));
                    return true;
                }
                String arena = args[1];
                boolean ok = gameManager.voteMap(player, arena);
                if (!ok) {
                    player.sendMessage(messages.get("unknown-map"));
                } else {
                    player.sendMessage(messages.get("map-voted", java.util.Map.of("map", arena)));
                }
            }
            case "setspawn" -> {
                if (!player.hasPermission("codwarfare.admin")) {
                    player.sendMessage(messages.get("no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(messages.get("usage-setspawn"));
                    return true;
                }
                String arenaId = getConfig().getString("arena", "default");
                String team = args[1].toLowerCase();
                Location loc = player.getLocation();
                if (team.equals("red") || team.equals("rouge")) {
                    ArenaConfig.savePoint(this, arenaId, "red.spawn", loc);
                    player.sendMessage(messages.get("setspawn-ok"));
                } else if (team.equals("blue") || team.equals("bleu")) {
                    ArenaConfig.savePoint(this, arenaId, "blue.spawn", loc);
                    player.sendMessage(messages.get("setspawn-ok"));
                } else {
                    player.sendMessage(messages.get("unknown-team"));
                }
            }
            case "setflag" -> {
                if (!player.hasPermission("codwarfare.admin")) {
                    player.sendMessage(messages.get("no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(messages.get("usage-setflag"));
                    return true;
                }
                String arenaId = getConfig().getString("arena", "default");
                String team = args[1].toLowerCase();
                Location loc = player.getLocation();
                if (team.equals("red") || team.equals("rouge")) {
                    ArenaConfig.savePoint(this, arenaId, "red.flag", loc);
                    player.sendMessage(messages.get("setflag-ok"));
                } else if (team.equals("blue") || team.equals("bleu")) {
                    ArenaConfig.savePoint(this, arenaId, "blue.flag", loc);
                    player.sendMessage(messages.get("setflag-ok"));
                } else {
                    player.sendMessage(messages.get("unknown-team"));
                }
            }
            case "setlobby" -> {
                if (!player.hasPermission("codwarfare.admin")) {
                    player.sendMessage(messages.get("no-permission"));
                    return true;
                }
                String arenaId = getConfig().getString("arena", "default");
                ArenaConfig.savePoint(this, arenaId, "lobby-spawn", player.getLocation());
                player.sendMessage(messages.get("setlobby-ok"));
            }
            case "reload" -> {
                if (!player.hasPermission("codwarfare.admin")) {
                    player.sendMessage(messages.get("no-permission"));
                    return true;
                }
                reloadConfig();
                gameManager.reloadArena();
                player.sendMessage(messages.get("reload-ok"));
            }
            case "admin" -> {
                if (!player.hasPermission("codwarfare.admin")) {
                    player.sendMessage(messages.get("no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(messages.get("usage"));
                    return true;
                }
                handleAdminCommand(player, args);
            }
            case "debug" -> {
                if (!player.hasPermission("codwarfare.admin")) {
                    player.sendMessage(messages.get("no-permission"));
                    return true;
                }
                player.sendMessage(messages.get("debug-prefix") + gameManager.debugStatus());
            }
            default -> player.sendMessage(messages.get("usage"));
        }
        return true;
    }

    private void handleAdminCommand(Player player, String[] args) {
        String adminSub = args[1].toLowerCase();
        if (!"arena".equals(adminSub)) {
            player.sendMessage(messages.get("usage"));
            return;
        }
        handleAdminArena(player, args);
    }

    private void handleAdminArena(Player player, String[] args) {
        // /cod admin arena <list|select|setspawn|setflag|setlobby|tp> ...
        if (args.length < 3) {
            player.sendMessage(messages.get("usage-votemap"));
            return;
        }

        String action = args[2].toLowerCase();
        switch (action) {
            case "list" -> {
                List<String> ids = gameManager.getArenaIds();
                if (ids.isEmpty()) {
                    player.sendMessage(messages.get("no-arena"));
                    return;
                }
                player.sendMessage("§aArenas: §f" + String.join(", ", ids));
            }
            case "select" -> {
                if (args.length < 4) {
                    player.sendMessage(messages.get("usage-votemap"));
                    return;
                }
                String arenaId = args[3];
                getConfig().set("arena", arenaId);
                saveConfig();
                gameManager.reloadArena();
                player.sendMessage("§aArène active: §f" + arenaId);
            }
            case "setspawn" -> {
                if (args.length < 5) {
                    player.sendMessage(messages.get("usage-setspawn"));
                    return;
                }
                String arenaId = args[3];
                String team = args[4].toLowerCase();
                Location loc = player.getLocation();
                if (team.equals("red") || team.equals("rouge")) {
                    ArenaConfig.savePoint(this, arenaId, "red.spawn", loc);
                    player.sendMessage(messages.get("setspawn-ok"));
                } else if (team.equals("blue") || team.equals("bleu")) {
                    ArenaConfig.savePoint(this, arenaId, "blue.spawn", loc);
                    player.sendMessage(messages.get("setspawn-ok"));
                } else {
                    player.sendMessage(messages.get("unknown-team"));
                }
                gameManager.reloadArena();
            }
            case "setflag" -> {
                if (args.length < 5) {
                    player.sendMessage(messages.get("usage-setflag"));
                    return;
                }
                String arenaId = args[3];
                String team = args[4].toLowerCase();
                Location loc = player.getLocation();
                if (team.equals("red") || team.equals("rouge")) {
                    ArenaConfig.savePoint(this, arenaId, "red.flag", loc);
                    player.sendMessage(messages.get("setflag-ok"));
                } else if (team.equals("blue") || team.equals("bleu")) {
                    ArenaConfig.savePoint(this, arenaId, "blue.flag", loc);
                    player.sendMessage(messages.get("setflag-ok"));
                } else {
                    player.sendMessage(messages.get("unknown-team"));
                }
                gameManager.reloadArena();
            }
            case "setlobby" -> {
                if (args.length < 4) {
                    player.sendMessage(messages.get("usage-votemap"));
                    return;
                }
                String arenaId = args[3];
                ArenaConfig.savePoint(this, arenaId, "lobby-spawn", player.getLocation());
                player.sendMessage(messages.get("setlobby-ok"));
                gameManager.reloadArena();
            }
            case "tp" -> {
                if (args.length < 5) {
                    player.sendMessage(messages.get("usage-votemap"));
                    return;
                }
                String arenaId = args[3];
                String place = args[4].toLowerCase();
                CodArena arena = findArena(arenaId);
                if (arena == null) {
                    player.sendMessage(messages.get("unknown-map"));
                    return;
                }
                switch (place) {
                    case "lobby" -> player.teleport(arena.lobbySpawn());
                    case "red", "rouge" -> player.teleport(arena.redSpawn());
                    case "blue", "bleu" -> player.teleport(arena.blueSpawn());
                    case "red-flag" -> {
                        if (arena.redFlag() != null) player.teleport(arena.redFlag());
                    }
                    case "blue-flag" -> {
                        if (arena.blueFlag() != null) player.teleport(arena.blueFlag());
                    }
                    default -> player.sendMessage(messages.get("usage-votemap"));
                }
            }
            default -> player.sendMessage(messages.get("usage"));
        }
    }

    private CodArena findArena(String arenaId) {
        List<CodArena> arenas = ArenaConfig.loadArenas(this);
        for (CodArena a : arenas) {
            if (a.id().equalsIgnoreCase(arenaId)) return a;
        }
        return null;
    }

    private void logIntegrationStatus() {
        if (Bukkit.getPluginManager().getPlugin("Multiverse-Core") != null) {
            getLogger().info("[COD] Multiverse-Core present : les mondes d'arene peuvent etre resolus par alias MV (integrations.multiverse).");
        }
        getLogger().info("[COD] Stats reseau : HubCore-BridgeSpigot (BridgeSyncAPI), sync kills/deaths = "
                + getConfig().getBoolean("integrations.bridge.sync-stats", true));
    }

    private void ensureIntegrationDefaults() {
        if (!getConfig().contains("integrations.multiverse.resolve-worlds")) {
            getConfig().set("integrations.multiverse.resolve-worlds", true);
        }
        if (!getConfig().contains("integrations.multiverse.warn-missing-world")) {
            getConfig().set("integrations.multiverse.warn-missing-world", true);
        }
        if (!getConfig().contains("integrations.bridge.sync-stats")) {
            getConfig().set("integrations.bridge.sync-stats", true);
        }
        saveConfig();
    }

    private void ensureLoadoutStorageDefaults() {
        // Important: si le serveur a déjà un config.yml, saveDefaultConfig() ne fusionne pas.
        // On injecte donc seulement les clés manquantes pour activer facilement la persistance réseau.
        if (!getConfig().contains("loadout-storage.type")) {
            getConfig().set("loadout-storage.type", "mysql");
        }

        if (!getConfig().contains("loadout-storage.mysql.enabled")) {
            getConfig().set("loadout-storage.mysql.enabled", true);
        }

        if (!getConfig().contains("loadout-storage.mysql.host")) {
            getConfig().set("loadout-storage.mysql.host", "localhost");
        }
        if (!getConfig().contains("loadout-storage.mysql.port")) {
            getConfig().set("loadout-storage.mysql.port", 3306);
        }
        if (!getConfig().contains("loadout-storage.mysql.database")) {
            getConfig().set("loadout-storage.mysql.database", "minecraft");
        }
        if (!getConfig().contains("loadout-storage.mysql.username")) {
            getConfig().set("loadout-storage.mysql.username", "invit");
        }
        if (!getConfig().contains("loadout-storage.mysql.password")) {
            getConfig().set("loadout-storage.mysql.password", "");
        }
        if (!getConfig().contains("loadout-storage.mysql.pool-size")) {
            getConfig().set("loadout-storage.mysql.pool-size", 5);
        }

        saveConfig();
    }

    private void ensureMessageDefaults() {
        // Minimal set required by the GUI weapon/class/perk selection.
        if (!getConfig().contains("messages.weapon-selected")) {
            getConfig().set("messages.weapon-selected", "&aArme selectionnee: &e%weapon%");
        }
        if (!getConfig().contains("messages.class-selected")) {
            getConfig().set("messages.class-selected", "&aClasse selectionnee: &e%class%");
        }
        if (!getConfig().contains("messages.perk-selected")) {
            getConfig().set("messages.perk-selected", "&aPerk selectionne: &e%perk%");
        }
        if (!getConfig().contains("messages.usage")) {
            getConfig().set("messages.usage", "&cUsage: /cod <join|leave|armory|class|perk|votemap|admin|reload|debug>");
        }
        saveConfig();
    }

    private void ensureNewGameplayDefaults() {
        // Added after recent weapon/headshot + killstreak improvements.
        if (!getConfig().contains("game.headshot-distance")) {
            getConfig().set("game.headshot-distance", 0.9);
        }
        if (!getConfig().contains("killstreaks.uav-duration-ticks")) {
            getConfig().set("killstreaks.uav-duration-ticks", 200);
        }
        if (!getConfig().contains("killstreaks.uav-interval-ticks")) {
            getConfig().set("killstreaks.uav-interval-ticks", 40);
        }
        if (!getConfig().contains("killstreaks.airstrike-radius")) {
            getConfig().set("killstreaks.airstrike-radius", 6.0);
        }
        if (!getConfig().contains("killstreaks.airstrike-delay-ticks")) {
            getConfig().set("killstreaks.airstrike-delay-ticks", 20);
        }
        if (!getConfig().contains("killstreaks.airstrike-target-range")) {
            getConfig().set("killstreaks.airstrike-target-range", 30);
        }
        if (!getConfig().contains("killstreaks.airstrike-y-offset")) {
            getConfig().set("killstreaks.airstrike-y-offset", 0.0);
        }
        saveConfig();
    }
}
