package com.vibecraft.codwarfare.integration;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Résolution optionnelle des mondes d'arène quand le nom dans {@code arenas.yml}
 * correspond à un monde Multiverse (alias) plutôt qu'au nom Bukkit direct.
 * <p>
 * Aucune dépendance compile sur Multiverse-Core : reflection + {@code softdepend}.
 */
public final class MultiverseWorldResolver {

    private MultiverseWorldResolver() {}

    /**
     * @param plugin plugin COD (pour lire la config)
     * @param worldName nom du monde dans arenas.yml
     * @return le monde chargé, ou null
     */
    public static World resolve(JavaPlugin plugin, String worldName) {
        if (worldName == null || worldName.isBlank()) return null;

        World direct = Bukkit.getWorld(worldName);
        if (direct != null) return direct;

        if (plugin == null || !plugin.getConfig().getBoolean("integrations.multiverse.resolve-worlds", true)) {
            return null;
        }

        return resolveViaMultiverse(worldName);
    }

    private static World resolveViaMultiverse(String worldName) {
        Plugin mv = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
        if (mv == null || !mv.isEnabled()) return null;

        try {
            Object worldManager = mv.getClass().getMethod("getMVWorldManager").invoke(mv);
            if (worldManager == null) return null;

            Object mvWorld = worldManager.getClass().getMethod("getMVWorld", String.class).invoke(worldManager, worldName);
            if (mvWorld == null) return null;

            for (String method : new String[] {"getCBWorld", "getBukkitWorld", "getWorld"}) {
                try {
                    Object bukkit = mvWorld.getClass().getMethod(method).invoke(mvWorld);
                    if (bukkit instanceof World w) return w;
                } catch (NoSuchMethodException ignored) {
                    // autre API MV
                }
            }
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            // MV absent ou API différente
        }
        return null;
    }
}
