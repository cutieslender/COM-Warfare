package com.vibecraft.codwarfare.config;

import com.vibecraft.codwarfare.CodWarfarePlugin;
import com.vibecraft.codwarfare.game.CodArena;
import com.vibecraft.codwarfare.integration.MultiverseWorldResolver;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class ArenaConfig {

    private ArenaConfig() {
    }

    public static CodArena loadArena(CodWarfarePlugin plugin) {
        String arenaId = plugin.getConfig().getString("arena", "default");
        List<CodArena> all = loadArenas(plugin);
        for (CodArena arena : all) {
            if (arena.id().equalsIgnoreCase(arenaId)) return arena;
        }
        return all.isEmpty() ? null : all.get(0);
    }

    public static List<CodArena> loadArenas(CodWarfarePlugin plugin) {
        File file = new File(plugin.getDataFolder(), "arenas.yml");
        if (!file.exists()) plugin.saveResource("arenas.yml", false);

        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection arenasSec = cfg.getConfigurationSection("arenas");
        List<CodArena> arenas = new ArrayList<>();
        if (arenasSec == null) return arenas;

        for (String arenaId : arenasSec.getKeys(false)) {
            ConfigurationSection root = arenasSec.getConfigurationSection(arenaId);
            if (root == null) continue;

            String worldName = root.getString("world");
            if (worldName == null || worldName.isEmpty()) continue;
            World world = MultiverseWorldResolver.resolve(plugin, worldName);
            if (world == null) {
                if (plugin.getConfig().getBoolean("integrations.multiverse.warn-missing-world", true)) {
                    plugin.getLogger().warning("[COD] Arena '" + arenaId + "': monde '" + worldName
                            + "' introuvable (charge le monde, verifie Multiverse-Core ou arenas.yml).");
                }
                continue;
            }

            Location lobby = readLocation(root.getConfigurationSection("lobby-spawn"), world);
            Location red = readLocation(root.getConfigurationSection("red.spawn"), world);
            Location blue = readLocation(root.getConfigurationSection("blue.spawn"), world);
            if (lobby == null || red == null || blue == null) continue;

            Location redFlag = readLocation(root.getConfigurationSection("red.flag"), world);
            Location blueFlag = readLocation(root.getConfigurationSection("blue.flag"), world);
            double flagRadius = root.getDouble("ctf.capture-radius", 2.5D);

            arenas.add(new CodArena(arenaId, worldName, lobby, red, blue, redFlag, blueFlag, flagRadius));
        }
        return arenas;
    }

    public static void savePoint(CodWarfarePlugin plugin, String arenaId, String path, Location loc) {
        File file = new File(plugin.getDataFolder(), "arenas.yml");
        if (!file.exists()) plugin.saveResource("arenas.yml", false);

        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        String base = "arenas." + arenaId + "." + path;
        cfg.set("arenas." + arenaId + ".world", loc.getWorld().getName());
        cfg.set(base + ".x", loc.getX());
        cfg.set(base + ".y", loc.getY());
        cfg.set(base + ".z", loc.getZ());
        cfg.set(base + ".yaw", loc.getYaw());
        cfg.set(base + ".pitch", loc.getPitch());
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("[COD] Failed to save arenas.yml: " + e.getMessage());
        }
    }

    private static Location readLocation(ConfigurationSection sec, World world) {
        if (sec == null) return null;
        return new Location(
                world,
                sec.getDouble("x"),
                sec.getDouble("y"),
                sec.getDouble("z"),
                (float) sec.getDouble("yaw", 0.0),
                (float) sec.getDouble("pitch", 0.0)
        );
    }
}
