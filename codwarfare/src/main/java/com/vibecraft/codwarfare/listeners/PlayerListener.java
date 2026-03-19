package com.vibecraft.codwarfare.listeners;

import com.vibecraft.codwarfare.game.CodGameManager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.entity.ProjectileHitEvent;

public final class PlayerListener implements Listener {

    private final CodGameManager gameManager;

    public PlayerListener(CodGameManager gameManager) {
        this.gameManager = gameManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        gameManager.loadSelections(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        gameManager.handleQuit(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        gameManager.handleMove(event.getPlayer(), event.getTo());
    }

    @EventHandler(ignoreCancelled = true)
    public void onHeldSlot(PlayerItemHeldEvent event) {
        if (!gameManager.isInGame(event.getPlayer())) return;
        gameManager.updateCurrentWeaponFromSlot(event.getPlayer(), event.getNewSlot());
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHandsReload(PlayerSwapHandItemsEvent event) {
        if (!gameManager.isInGame(event.getPlayer())) return;
        event.setCancelled(true);
        gameManager.reloadCurrentWeapon(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!gameManager.isInGame(event.getEntity())) return;
        event.getDrops().clear();
        event.setShouldDropExperience(false);
        event.deathMessage(null);
        gameManager.handleDeath(event.getEntity(), event.getEntity().getKiller());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onShoot(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player shooter = event.getPlayer();
        if (!gameManager.isInGame(shooter)) return;

        // Equipements: medkit custom (avant le système d'armes).
        if (gameManager.tryUseMedkit(shooter)) {
            event.setCancelled(true);
            return;
        }

        if (gameManager.tryUseStim(shooter)) {
            event.setCancelled(true);
            return;
        }

        if (gameManager.tryUseGrenade(shooter)) {
            event.setCancelled(true);
            return;
        }

        if (gameManager.tryUseAirstrike(shooter)) {
            event.setCancelled(true);
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta() || item.getItemMeta() == null) return;
        String name = item.getItemMeta().getDisplayName();
        if (name == null) return;

        String weaponKey = gameManager.extractWeaponKey(item);
        if (weaponKey == null) {
            weaponKey = weaponKeyFromName(name);
        }
        if (weaponKey == null) return;

        Projectile projectile = shooter.launchProjectile(org.bukkit.entity.Snowball.class);
        boolean fired = gameManager.fireWeapon(shooter, weaponKey, projectile);
        if (!fired) {
            projectile.remove();
            return;
        }
        shooter.playSound(shooter.getLocation(), org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.35f, 1.8f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClassMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();
        if (!"COD Armory".equals(title) && !"COD Classes".equals(title)
                && !"COD Primary Weapons".equals(title) && !"COD Secondary Weapons".equals(title)
                && !"COD Perks".equals(title)) {
            return;
        }
        event.setCancelled(true);
        gameManager.handleArmoryClick(player, title, event.getCurrentItem());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onProjectileDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Projectile projectile)) return;
        if (!(event.getEntity() instanceof Player)) return;

        // Grenade projectile: explode and ignore weapon damage model.
        if (gameManager.isActiveGrenadeProjectile(projectile)) {
            event.setCancelled(true);
            gameManager.handleGrenadeProjectileImpact(projectile);
            return;
        }

        event.setCancelled(true); // use custom damage model
        gameManager.handleProjectileHit(event.getEntity(), projectile);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onGrenadeHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Projectile projectile)) return;
        if (!gameManager.isActiveGrenadeProjectile(projectile)) return;
        gameManager.handleGrenadeProjectileImpact(projectile);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onKnifeDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!gameManager.isInGame(attacker) || !gameManager.isInGame(victim)) return;

        if (!gameManager.isKnifeInMainHand(attacker)) return;

        // Use custom knife damage + cooldown.
        event.setCancelled(true);
        gameManager.tryUseKnife(attacker, victim);
    }

    private String weaponKeyFromName(String displayName) {
        String plain = org.bukkit.ChatColor.stripColor(displayName);
        if (plain == null) return null;
        String n = plain.toLowerCase();
        if (n.contains("assault")) return "assault-rifle";
        if (n.contains("sniper")) return "sniper";
        if (n.contains("smg")) return "smg";
        if (n.contains("secondary")) return "secondary";
        return null;
    }
}
