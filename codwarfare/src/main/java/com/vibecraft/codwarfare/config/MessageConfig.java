package com.vibecraft.codwarfare.config;

import com.vibecraft.codwarfare.CodWarfarePlugin;
import org.bukkit.ChatColor;

import java.util.Map;

public final class MessageConfig {

    private final CodWarfarePlugin plugin;

    public MessageConfig(CodWarfarePlugin plugin) {
        this.plugin = plugin;
    }

    public String get(String key) {
        String raw = plugin.getConfig().getString("messages." + key, "&cMissing: " + key);
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    public String get(String key, Map<String, String> placeholders) {
        String s = get(key);
        if (placeholders == null) return s;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            s = s.replace("%" + e.getKey() + "%", e.getValue() == null ? "" : e.getValue());
        }
        return s;
    }
}
