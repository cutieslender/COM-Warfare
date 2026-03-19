package com.vibecraft.codwarfare.storage;

import com.vibecraft.codwarfare.CodWarfarePlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public final class LoadoutStore implements LoadoutStorage {

    private final CodWarfarePlugin plugin;
    private final File file;
    private FileConfiguration cfg;

    public LoadoutStore(CodWarfarePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "loadouts.yml");
        reload();
    }

    public void reload() {
        if (!file.exists()) {
            try {
                if (file.getParentFile() != null) file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("[COD] Failed to create loadouts.yml: " + e.getMessage());
            }
        }
        cfg = YamlConfiguration.loadConfiguration(file);
    }

    public String getClass(UUID uuid, String fallback) {
        return cfg.getString("players." + uuid + ".class", fallback);
    }

    public String getPerk(UUID uuid, String fallback) {
        return cfg.getString("players." + uuid + ".perk", fallback);
    }

    public String getPrimary(UUID uuid, String fallback) {
        return cfg.getString("players." + uuid + ".primary", fallback);
    }

    public String getSecondary(UUID uuid, String fallback) {
        return cfg.getString("players." + uuid + ".secondary", fallback);
    }

    public void save(UUID uuid, String clazz, String perk, String primary, String secondary) {
        String base = "players." + uuid;
        cfg.set(base + ".class", clazz);
        cfg.set(base + ".perk", perk);
        cfg.set(base + ".primary", primary);
        cfg.set(base + ".secondary", secondary);
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("[COD] Failed to save loadout: " + e.getMessage());
        }
    }
}
