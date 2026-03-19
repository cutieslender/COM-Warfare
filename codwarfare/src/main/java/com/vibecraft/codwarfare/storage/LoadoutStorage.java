package com.vibecraft.codwarfare.storage;

import java.util.UUID;

/**
 * Abstraction de la persistance des choix joueur (classe, perk, arme primaire/secondaire).
 */
public interface LoadoutStorage {
    String getClass(UUID uuid, String fallback);

    String getPerk(UUID uuid, String fallback);

    String getPrimary(UUID uuid, String fallback);

    String getSecondary(UUID uuid, String fallback);

    void save(UUID uuid, String clazz, String perk, String primary, String secondary);

    default void shutdown() {
        // no-op by default
    }
}

