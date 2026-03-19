package com.vibecraft.codwarfare.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import com.vibecraft.codwarfare.CodWarfarePlugin;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistance MySQL de la table dédiée `cod_loadouts`.
 * Fallback sur `loadouts.yml` si DB indisponible.
 */
public final class MySqlLoadoutStore implements LoadoutStorage {

    private record Loadout(String clazz, String perk, String primary, String secondary) {}

    private final CodWarfarePlugin plugin;
    private final LoadoutStore localFallback;
    private volatile HikariDataSource ds;
    private final Map<UUID, Loadout> cache = new ConcurrentHashMap<>();

    public MySqlLoadoutStore(CodWarfarePlugin plugin, LoadoutStore localFallback) {
        this.plugin = plugin;
        this.localFallback = localFallback;
        init();
    }

    private void init() {
        String mysqlHost = plugin.getConfig().getString("loadout-storage.mysql.host", "localhost");
        int mysqlPort = plugin.getConfig().getInt("loadout-storage.mysql.port", 3306);
        String mysqlDb = plugin.getConfig().getString("loadout-storage.mysql.database", "minecraft");
        String mysqlUser = plugin.getConfig().getString("loadout-storage.mysql.username", "root");
        String mysqlPass = plugin.getConfig().getString("loadout-storage.mysql.password", "");
        boolean enabled = plugin.getConfig().getBoolean("loadout-storage.mysql.enabled", true);

        if (!enabled) {
            plugin.getLogger().warning("[COD] loadout-storage.mysql.enabled=false => fallback local.");
            ds = null;
            return;
        }

        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("CODWarfare-MySQLLoadouts");
        cfg.setJdbcUrl("jdbc:mysql://" + mysqlHost + ":" + mysqlPort + "/" + mysqlDb
                + "?useUnicode=true&characterEncoding=utf8"
                + "&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC");
        cfg.setUsername(mysqlUser);
        cfg.setPassword(mysqlPass);
        cfg.setMaximumPoolSize(plugin.getConfig().getInt("loadout-storage.mysql.pool-size", 5));
        cfg.setMinimumIdle(Math.min(1, cfg.getMaximumPoolSize()));
        cfg.setConnectionTimeout(10_000L);
        cfg.setValidationTimeout(5_000L);

        try {
            HikariDataSource newDs = new HikariDataSource(cfg);
            // Ensure schema exists (best-effort).
            try (Connection c = newDs.getConnection();
                 PreparedStatement ps = c.prepareStatement("""
                         CREATE TABLE IF NOT EXISTS cod_loadouts (
                           uuid VARCHAR(36) PRIMARY KEY,
                           cod_class VARCHAR(32) DEFAULT '',
                           cod_perk VARCHAR(32) DEFAULT 'none',
                           cod_primary VARCHAR(64) DEFAULT '',
                           cod_secondary VARCHAR(64) DEFAULT ''
                         );
                         """)) {
                ps.executeUpdate();
            }
            this.ds = newDs;
            plugin.getLogger().info("[COD] MySQL loadout persistence enabled (cod_loadouts).");
        } catch (SQLException e) {
            plugin.getLogger().warning("[COD] MySQL loadout persistence disabled: " + e.getMessage() + " (fallback local).");
            this.ds = null;
        }
    }

    private boolean isDbEnabled() {
        return ds != null;
    }

    private Loadout ensureLoaded(UUID uuid) {
        Loadout cached = cache.get(uuid);
        if (cached != null) return cached;

        if (!isDbEnabled()) return loadFromLocal(uuid);

        Loadout loaded = loadFromDb(uuid);
        if (loaded != null) {
            cache.put(uuid, loaded);
            return loaded;
        }

        // No row yet: cache "empty" values so we don't hammer DB.
        Loadout empty = new Loadout("", "none", "", "");
        cache.put(uuid, empty);
        return empty;
    }

    private Loadout loadFromLocal(UUID uuid) {
        String clazz = localFallback.getClass(uuid, "");
        String perk = localFallback.getPerk(uuid, "none");
        String primary = localFallback.getPrimary(uuid, "");
        String secondary = localFallback.getSecondary(uuid, "");
        Loadout l = new Loadout(clazz, perk, primary, secondary);
        cache.put(uuid, l);
        return l;
    }

    private Loadout loadFromDb(UUID uuid) {
        String sql = "SELECT cod_class, cod_perk, cod_primary, cod_secondary FROM cod_loadouts WHERE uuid = ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String clazz = rs.getString(1);
                String perk = rs.getString(2);
                String primary = rs.getString(3);
                String secondary = rs.getString(4);
                return new Loadout(clazz == null ? "" : clazz, perk == null ? "none" : perk,
                        primary == null ? "" : primary, secondary == null ? "" : secondary);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[COD] Failed to load cod_loadouts for " + uuid + ": " + e.getMessage() + " (fallback local).");
            ds = null; // disable for future calls (until restart)
            return loadFromLocal(uuid);
        }
    }

    private String chooseNonBlank(String value, String fallback) {
        if (value == null) return fallback;
        String v = value.trim();
        return v.isEmpty() ? fallback : v;
    }

    @Override
    public String getClass(UUID uuid, String fallback) {
        Loadout l = ensureLoaded(uuid);
        return chooseNonBlank(l.clazz(), fallback);
    }

    @Override
    public String getPerk(UUID uuid, String fallback) {
        Loadout l = ensureLoaded(uuid);
        String chosen = chooseNonBlank(l.perk(), fallback);
        if (chosen == null) return fallback;
        return chosen;
    }

    @Override
    public String getPrimary(UUID uuid, String fallback) {
        Loadout l = ensureLoaded(uuid);
        return chooseNonBlank(l.primary(), fallback);
    }

    @Override
    public String getSecondary(UUID uuid, String fallback) {
        Loadout l = ensureLoaded(uuid);
        return chooseNonBlank(l.secondary(), fallback);
    }

    @Override
    public void save(UUID uuid, String clazz, String perk, String primary, String secondary) {
        // Update cache immediately.
        cache.put(uuid, new Loadout(clazz, perk, primary, secondary));

        if (!isDbEnabled()) {
            localFallback.save(uuid, clazz, perk, primary, secondary);
            return;
        }

        // Async DB write.
        CompletableFuture.runAsync(() -> {
            String sql = """
                    INSERT INTO cod_loadouts (uuid, cod_class, cod_perk, cod_primary, cod_secondary)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                      cod_class = VALUES(cod_class),
                      cod_perk = VALUES(cod_perk),
                      cod_primary = VALUES(cod_primary),
                      cod_secondary = VALUES(cod_secondary)
                    """;
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, clazz);
                ps.setString(3, perk);
                ps.setString(4, primary);
                ps.setString(5, secondary);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("[COD] Failed to save cod_loadouts for " + uuid + ": " + e.getMessage() + " (fallback local next).");
                ds = null;
                localFallback.save(uuid, clazz, perk, primary, secondary);
            }
        }, runnable -> Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable));
    }

    @Override
    public void shutdown() {
        if (ds != null) {
            try {
                ds.close();
            } catch (Exception ignored) {}
            ds = null;
        }
    }
}

