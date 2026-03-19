package com.vibecraft.codwarfare.game;

import com.vibecraft.bridge.spigot.BridgeSyncAPI;
import com.vibecraft.codwarfare.CodWarfarePlugin;
import com.vibecraft.codwarfare.config.ArenaConfig;
import com.vibecraft.codwarfare.config.MessageConfig;
import com.vibecraft.codwarfare.storage.LoadoutStorage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CodGameManager {

    private final CodWarfarePlugin plugin;
    private final MessageConfig messages;
    private final LoadoutStorage store;
    private final Deque<UUID> queue = new ArrayDeque<>();
    private final List<CodArena> arenas = new ArrayList<>();
    private int arenaIndex = 0;
    private CodMode mode = CodMode.TDM;
    private final Map<UUID, String> mapVotes = new ConcurrentHashMap<>();

    private final Map<UUID, String> selectedClass = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectedPerk = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectedPrimary = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectedSecondary = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastWeaponShotMs = new ConcurrentHashMap<>();
    private final Map<UUID, WeaponShot> activeShots = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMedkitUseMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastKnifeUseMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastStimUseMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastGrenadeUseMs = new ConcurrentHashMap<>();
    private final Map<UUID, GrenadeShot> activeGrenades = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> grenadeFuseTasks = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> activeUavTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> personalKills = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> personalDeaths = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> killStreak = new ConcurrentHashMap<>();
    private final Map<UUID, String> inHandWeapon = new ConcurrentHashMap<>();
    private final Map<String, Integer> magAmmo = new ConcurrentHashMap<>();
    private final Map<String, Integer> reserveAmmo = new ConcurrentHashMap<>();
    private final Set<UUID> reloading = ConcurrentHashMap.newKeySet();
    // CTF: which player carries each flag (key = flag team color).
    private final Map<TeamColor, UUID> flagCarrier = new ConcurrentHashMap<>();
    // CTF: current location of each flag when dropped (otherwise last known drop).
    private final Map<TeamColor, Location> ctfFlagPosition = new ConcurrentHashMap<>();
    private final Map<TeamColor, BukkitTask> ctfFlagRespawnTasks = new ConcurrentHashMap<>();

    private CodGame game;
    private BukkitTask startTask;
    private BukkitTask scoreboardTask;

    public CodGameManager(CodWarfarePlugin plugin, MessageConfig messages, LoadoutStorage store) {
        this.plugin = plugin;
        this.messages = messages;
        this.store = store;
        reloadArena();
    }

    public void reloadArena() {
        arenas.clear();
        arenas.addAll(ArenaConfig.loadArenas(plugin));
        mode = CodMode.fromConfig(plugin.getConfig().getString("game.mode", "tdm"));

        String preferred = plugin.getConfig().getString("arena", "default");
        arenaIndex = 0;
        for (int i = 0; i < arenas.size(); i++) {
            if (arenas.get(i).id().equalsIgnoreCase(preferred)) {
                arenaIndex = i;
                break;
            }
        }
        game = arenas.isEmpty() ? null : new CodGame(arenas.get(arenaIndex));
    }

    public void join(Player player) {
        loadSelections(player);
        if (game == null) {
            player.sendMessage(messages.get("no-arena"));
            return;
        }
        if (game.isInGame(player)) {
            player.sendMessage(messages.get("already-in-game"));
            return;
        }
        if (queue.contains(player.getUniqueId())) {
            player.sendMessage(messages.get("already-in-queue"));
            return;
        }
        queue.addLast(player.getUniqueId());
        selectedClass.putIfAbsent(player.getUniqueId(), plugin.getConfig().getString("classes.default", "assault"));
        selectedPerk.putIfAbsent(player.getUniqueId(), plugin.getConfig().getString("perks.default", "none"));
        player.sendMessage(messages.get("queue-joined", Map.of("position", String.valueOf(queue.size()))));
        tryStart();
    }

    public void loadSelections(Player player) {
        UUID id = player.getUniqueId();
        String defaultClass = plugin.getConfig().getString("classes.default", "assault");
        String clazz = store.getClass(id, defaultClass);
        if (!plugin.getConfig().isConfigurationSection("classes." + clazz)) clazz = defaultClass;
        selectedClass.put(id, clazz);

        String defaultPerk = plugin.getConfig().getString("perks.default", "none");
        String perk = store.getPerk(id, defaultPerk);
        if (!"none".equalsIgnoreCase(perk) && !plugin.getConfig().isConfigurationSection("perks.list." + perk)) {
            perk = defaultPerk;
        }
        selectedPerk.put(id, perk);

        String classBase = "classes." + clazz;
        String primaryDefault = plugin.getConfig().getString(classBase + ".primary", "assault-rifle");
        String secondaryDefault = plugin.getConfig().getString(classBase + ".secondary", "secondary");
        String primary = store.getPrimary(id, primaryDefault);
        String secondary = store.getSecondary(id, secondaryDefault);
        if (!plugin.getConfig().isConfigurationSection("weapons." + primary)) primary = primaryDefault;
        if (!plugin.getConfig().isConfigurationSection("weapons." + secondary)) secondary = secondaryDefault;
        selectedPrimary.put(id, primary);
        selectedSecondary.put(id, secondary);
    }

    private void persistSelections(UUID id) {
        String clazz = selectedClass.getOrDefault(id, plugin.getConfig().getString("classes.default", "assault"));
        String perk = selectedPerk.getOrDefault(id, plugin.getConfig().getString("perks.default", "none"));
        String classBase = "classes." + clazz;
        String primary = selectedPrimary.getOrDefault(id, plugin.getConfig().getString(classBase + ".primary", "assault-rifle"));
        String secondary = selectedSecondary.getOrDefault(id, plugin.getConfig().getString(classBase + ".secondary", "secondary"));
        store.save(id, clazz, perk, primary, secondary);
    }

    public void leave(Player player) {
        if (queue.remove(player.getUniqueId())) {
            mapVotes.remove(player.getUniqueId());
            player.sendMessage(messages.get("queue-left"));
            return;
        }
        if (game != null && game.isInGame(player)) {
            game.removePlayer(player);
            player.teleport(game.arena().lobbySpawn());
            player.sendMessage(messages.get("queue-left"));
            return;
        }
        player.sendMessage(messages.get("not-in-queue-or-game"));
    }

    private void tryStart() {
        if (game == null || game.state() != GameState.WAITING) return;
        int needed = mode == CodMode.FFA
                ? Math.max(2, plugin.getConfig().getInt("game.ffa.min-players", 4))
                : Math.max(2, plugin.getConfig().getInt("game.players-per-team", 4) * Math.max(2, plugin.getConfig().getInt("game.max-teams", 2)));
        if (queue.size() < needed) return;

        // If players already voted while waiting, apply it now for this upcoming match.
        // Votes will still be used after the match for the "next" map (via rotateArena()).
        applyMapVoteForNextStart();

        Set<Player> starters = new HashSet<>();
        while (starters.size() < needed && !queue.isEmpty()) {
            UUID id = queue.pollFirst();
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) starters.add(p);
        }
        if (starters.size() < needed) {
            for (Player p : starters) queue.addFirst(p.getUniqueId());
            return;
        }
        int seconds = Math.max(0, plugin.getConfig().getInt("game.start-countdown-seconds", 10));
        if (seconds > 0) {
            for (Player p : starters) {
                p.sendMessage(messages.get("game-starting", Map.of("seconds", String.valueOf(seconds))));
            }
            if (startTask != null) startTask.cancel();
            startTask = Bukkit.getScheduler().runTaskLater(plugin, () -> start(starters), seconds * 20L);
        } else {
            start(starters);
        }
    }

    private void applyMapVoteForNextStart() {
        if (mapVotes.isEmpty() || arenas.isEmpty()) return;

        String voted = null;
        Map<String, Integer> counts = new HashMap<>();
        for (String id : mapVotes.values()) {
            if (id == null) continue;
            String key = id.toLowerCase(Locale.ROOT);
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }

        int top = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > top) {
                top = e.getValue();
                voted = e.getKey();
            }
        }

        if (voted != null) {
            for (int i = 0; i < arenas.size(); i++) {
                if (arenas.get(i).id().equalsIgnoreCase(voted)) {
                    arenaIndex = i;
                    break;
                }
            }
        }

        // Re-create the waiting game so the upcoming match uses the chosen arena.
        game = new CodGame(arenas.get(arenaIndex));

        // Clear votes so the match doesn't consume future "next map" votes.
        mapVotes.clear();
    }

    private void start(Set<Player> starters) {
        if (game == null) return;
        game.resetScores();
        game.setState(GameState.INGAME);
        personalKills.clear();
        personalDeaths.clear();
        killStreak.clear();
        magAmmo.clear();
        reserveAmmo.clear();
        reloading.clear();
        flagCarrier.clear();
        resetCtfFlags();

        List<Player> starterList = new ArrayList<>(starters);
        for (Player p : starterList) {
            game.addPlayer(p);
            personalKills.put(p.getUniqueId(), 0);
            personalDeaths.put(p.getUniqueId(), 0);
            killStreak.put(p.getUniqueId(), 0);
        }

        if (mode == CodMode.INFECT && !starterList.isEmpty()) {
            // In Infect, exactly one player starts infected (RED), others are survivors (BLUE).
            for (Player p : starterList) {
                game.setTeam(p, TeamColor.BLUE);
            }
            Player firstInfected = starterList.get((int) (Math.random() * starterList.size()));
            game.setTeam(firstInfected, TeamColor.RED);
            firstInfected.sendMessage(messages.get("infect-first"));
        }

        for (Player p : starterList) {
            equipLoadout(p);
            teleportSpawnByMode(p);
            applyPerkOnSpawn(p);
            p.sendMessage(messages.get("game-started"));
        }
        startScoreboardLoop();
    }

    public void handleDeath(Player victim, Player killer) {
        if (game == null || !game.isInGame(victim) || game.state() != GameState.INGAME) return;
        cancelUav(victim.getUniqueId());
        personalDeaths.put(victim.getUniqueId(), personalDeaths.getOrDefault(victim.getUniqueId(), 0) + 1);
        killStreak.put(victim.getUniqueId(), 0);
        reloading.remove(victim.getUniqueId());
        dropCarriedFlag(victim, victim.getLocation());

        if (killer != null && game.isInGame(killer)) {
            TeamColor team = game.teamOf(killer);
            game.addKill(team);
            personalKills.put(killer.getUniqueId(), personalKills.getOrDefault(killer.getUniqueId(), 0) + 1);
            int streak = killStreak.getOrDefault(killer.getUniqueId(), 0) + 1;
            killStreak.put(killer.getUniqueId(), streak);
            if (plugin.getConfig().getBoolean("integrations.bridge.sync-stats", true)) {
                BridgeSyncAPI.sendKill(killer, victim);
            }

            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!game.isInGame(online)) continue;
                online.sendMessage(messages.get("kill-feed", Map.of(
                        "killer", killer.getName(),
                        "victim", victim.getName(),
                        "red_kills", String.valueOf(game.redKills()),
                        "blue_kills", String.valueOf(game.blueKills())
                )));
            }
            triggerKillstreakRewards(killer, streak);
            applyScavengerPerk(killer);

            if (mode == CodMode.INFECT) {
                TeamColor killerTeam = game.teamOf(killer);
                TeamColor victimTeam = game.teamOf(victim);
                if (killerTeam == TeamColor.RED && victimTeam == TeamColor.BLUE) {
                    // Infection conversion.
                    game.setTeam(victim, TeamColor.RED);
                    victim.sendMessage(messages.get("infect-you"));
                    for (UUID id : game.allPlayers()) {
                        Player p = Bukkit.getPlayer(id);
                        if (p != null && p.isOnline()) {
                            p.sendMessage(messages.get("infect-convert", Map.of("player", victim.getName())));
                        }
                    }
                }
            }

            checkWin();
        } else {
            if (plugin.getConfig().getBoolean("integrations.bridge.sync-stats", true)) {
                BridgeSyncAPI.sendDeath(victim);
            }
        }

        victim.getInventory().clear();
        long delay = Math.max(0L, plugin.getConfig().getLong("game.respawn-delay-ticks", 40L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!victim.isOnline() || game == null || !game.isInGame(victim)) return;
            teleportSpawnByMode(victim);
            equipLoadout(victim);
            applyPerkOnSpawn(victim);
            victim.sendMessage(messages.get("respawned"));
        }, delay);
    }

    public void handleMove(Player player, Location to) {
        if (game == null || to == null || game.state() != GameState.INGAME || !game.isInGame(player)) return;
        if (to.getY() <= plugin.getConfig().getDouble("game.void-y", -64.0D)) {
            dropCarriedFlag(player, player.getLocation());
            teleportSpawnByMode(player);
            player.sendMessage(messages.get("void-respawn"));
            return;
        }

        if (mode == CodMode.CTF) {
            handleCtfMovement(player, to);
        }
    }

    public void handleQuit(Player player) {
        persistSelections(player.getUniqueId());
        cancelUav(player.getUniqueId());
        queue.remove(player.getUniqueId());
        mapVotes.remove(player.getUniqueId());
        selectedClass.remove(player.getUniqueId());
        selectedPerk.remove(player.getUniqueId());
        selectedPrimary.remove(player.getUniqueId());
        selectedSecondary.remove(player.getUniqueId());
        lastWeaponShotMs.remove(player.getUniqueId());
        inHandWeapon.remove(player.getUniqueId());
        killStreak.remove(player.getUniqueId());
        reloading.remove(player.getUniqueId());
        personalKills.remove(player.getUniqueId());
        personalDeaths.remove(player.getUniqueId());
        lastMedkitUseMs.remove(player.getUniqueId());
        lastStimUseMs.remove(player.getUniqueId());
        lastGrenadeUseMs.remove(player.getUniqueId());
        lastKnifeUseMs.remove(player.getUniqueId());
        if (game != null && game.isInGame(player)) {
            game.removePlayer(player);
        }
    }

    public boolean isInGame(Player player) {
        return game != null && game.isInGame(player);
    }

    private void checkWin() {
        if (game == null) return;
        int target = Math.max(1, plugin.getConfig().getInt("game.kills-to-win", 50));
        String winnerLabel;

        if (mode == CodMode.FFA) {
            UUID topId = null;
            int topKills = -1;
            for (UUID id : game.allPlayers()) {
                int k = personalKills.getOrDefault(id, 0);
                if (k > topKills) {
                    topKills = k;
                    topId = id;
                }
            }
            if (topKills < target) return;
            Player top = topId != null ? Bukkit.getPlayer(topId) : null;
            winnerLabel = top != null ? top.getName() : "N/A";
        } else if (mode == CodMode.CTF) {
            int capturesToWin = Math.max(1, plugin.getConfig().getInt("game.ctf.captures-to-win", 3));
            TeamColor winner = null;
            if (game.redObjective() >= capturesToWin) winner = TeamColor.RED;
            if (game.blueObjective() >= capturesToWin) winner = TeamColor.BLUE;
            if (winner == null) return;
            winnerLabel = winner.display();
        } else if (mode == CodMode.INFECT) {
            int survivors = game.teamSize(TeamColor.BLUE);
            int infected = game.teamSize(TeamColor.RED);
            if (survivors <= 0 && infected > 0) {
                winnerLabel = "Infectés";
            } else {
                int survivorTarget = Math.max(1, plugin.getConfig().getInt("game.infect.survivor-kills-to-win", 30));
                if (game.blueKills() < survivorTarget) return;
                winnerLabel = "Survivants";
            }
        } else {
            TeamColor winner = null;
            if (game.redKills() >= target) winner = TeamColor.RED;
            if (game.blueKills() >= target) winner = TeamColor.BLUE;
            if (winner == null) return;
            winnerLabel = winner.display();
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!game.isInGame(p)) continue;
            p.sendMessage(messages.get("game-ended", Map.of("winner", winnerLabel)));
            p.teleport(game.arena().lobbySpawn());
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            p.setWalkSpeed(0.2f);
        }
        if (scoreboardTask != null) scoreboardTask.cancel();
        cancelAllUavs();
        rotateArena();
        queue.clear();
        mapVotes.clear();
    }

    private void equipLoadout(Player player) {
        player.getInventory().clear();
        String clazz = selectedClass.getOrDefault(player.getUniqueId(), plugin.getConfig().getString("classes.default", "assault"));
        String base = "classes." + clazz;
        String primary = selectedPrimary.getOrDefault(player.getUniqueId(), plugin.getConfig().getString(base + ".primary", "assault-rifle"));
        String secondary = selectedSecondary.getOrDefault(player.getUniqueId(), plugin.getConfig().getString(base + ".secondary", "secondary"));

        // Equip class armor (optional).
        equipClassArmor(player, clazz);
        // Equip extra equipment items (optional).
        equipClassEquipment(player, clazz);

        player.getInventory().setItem(0, loadoutItem(primary));
        player.getInventory().setItem(1, loadoutItem(secondary));
        player.getInventory().setItem(8, loadoutItem("ammo"));
        inHandWeapon.put(player.getUniqueId(), primary);

        magAmmo.put(ammoKey(player.getUniqueId(), primary), weaponMagSize(primary));
        reserveAmmo.put(ammoKey(player.getUniqueId(), primary), weaponReserveAmmo(primary));
        magAmmo.put(ammoKey(player.getUniqueId(), secondary), weaponMagSize(secondary));
        reserveAmmo.put(ammoKey(player.getUniqueId(), secondary), weaponReserveAmmo(secondary));
        showAmmoActionbar(player, primary);
    }

    private void equipClassArmor(Player player, String clazz) {
        if (player == null) return;
        String base = "classes." + clazz + ".armor.";

        // If any key is missing, we simply keep the current armor (or clear it to be safe).
        // Here we clear armor first, then apply configured pieces.
        if (player.getEquipment() != null) {
            player.getEquipment().setHelmet(null);
            player.getEquipment().setChestplate(null);
            player.getEquipment().setLeggings(null);
            player.getEquipment().setBoots(null);
        }

        setArmorPiece(player, base + "helmet", true, true);
        setArmorPiece(player, base + "chest", true, false);
        setArmorPiece(player, base + "leggings", true, false);
        setArmorPiece(player, base + "boots", true, false);
    }

    private void setArmorPiece(Player player, String path, boolean clearIfMissing, boolean isHelmet) {
        String raw = plugin.getConfig().getString(path, "AIR");
        if (raw == null) return;
        Material material;
        try {
            material = Material.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return;
        }
        if (material == Material.AIR) return;
        ItemStack item = new ItemStack(material, 1);
        if (player.getEquipment() == null) return;
        if (isHelmet) player.getEquipment().setHelmet(item);
        else if (path.endsWith("chest")) player.getEquipment().setChestplate(item);
        else if (path.endsWith("leggings")) player.getEquipment().setLeggings(item);
        else if (path.endsWith("boots")) player.getEquipment().setBoots(item);
    }

    private void equipClassEquipment(Player player, String clazz) {
        String base = "classes." + clazz + ".equipment";
        if (!plugin.getConfig().isConfigurationSection(base + ".items")) return;
        // Expected shape: classes.<clazz>.equipment.items.<index>.{slot, material, amount, name, lore, unbreakable}
        for (String key : plugin.getConfig().getConfigurationSection(base + ".items").getKeys(false)) {
            String itemBase = base + ".items." + key;
            if (!plugin.getConfig().isInt(itemBase + ".slot")) continue;
            int slot = plugin.getConfig().getInt(itemBase + ".slot", -1);
            if (slot < 0 || slot > 40) continue;
            // Protect main weapon/ammo slots.
            if (slot == 0 || slot == 1 || slot == 8) continue;

            String materialRaw = plugin.getConfig().getString(itemBase + ".material", null);
            if (materialRaw == null || materialRaw.isBlank()) continue;
            Material material;
            try {
                material = Material.valueOf(materialRaw.toUpperCase(Locale.ROOT));
            } catch (Exception e) {
                continue;
            }

            int amount = Math.max(1, plugin.getConfig().getInt(itemBase + ".amount", 1));
            String name = plugin.getConfig().getString(itemBase + ".name", null);
            java.util.List<String> lore = plugin.getConfig().getStringList(itemBase + ".lore");
            boolean unbreakable = plugin.getConfig().getBoolean(itemBase + ".unbreakable", false);

            ItemStack item = new ItemStack(material, amount);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                if (name != null && !name.isBlank()) {
                    meta.setDisplayName(name.replace('&', '§'));
                }
                if (lore != null && !lore.isEmpty()) {
                    java.util.List<String> translated = new java.util.ArrayList<>();
                    for (String line : lore) translated.add(line == null ? "" : line.replace('&', '§'));
                    meta.setLore(translated);
                }
                if (unbreakable) meta.setUnbreakable(true);
                item.setItemMeta(meta);
            }

            player.getInventory().setItem(slot, item);
        }
    }

    public ItemStack loadoutItem(String key) {
        String base = "loadout." + key;
        Material mat;
        try {
            mat = Material.valueOf(plugin.getConfig().getString(base + ".material", "STONE"));
        } catch (Exception e) {
            mat = Material.STONE;
        }
        int amount = Math.max(1, plugin.getConfig().getInt(base + ".amount", 1));
        String name = plugin.getConfig().getString(base + ".name", key);
        ItemStack item = new ItemStack(mat, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name == null ? key : name.replace('&', '§'));
            // Generic weapon id marker so listeners can support any configured weapon.
            meta.setLore(java.util.List.of("weapon:" + key));
            item.setItemMeta(meta);
        }
        return item;
    }

    public String extractWeaponKey(ItemStack item) {
        if (item == null || !item.hasItemMeta() || item.getItemMeta() == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta.getLore() != null) {
            for (String line : meta.getLore()) {
                if (line == null) continue;
                String plain = org.bukkit.ChatColor.stripColor(line).toLowerCase(Locale.ROOT);
                if (plain.startsWith("weapon:")) {
                    String key = plain.substring("weapon:".length()).trim();
                    if (!key.isEmpty()) return key;
                }
            }
        }
        return null;
    }

    public void updateCurrentWeaponFromSlot(Player player, int slot) {
        if (!isInGame(player)) return;
        String clazz = selectedClass.getOrDefault(player.getUniqueId(), plugin.getConfig().getString("classes.default", "assault"));
        String base = "classes." + clazz;
        String primary = selectedPrimary.getOrDefault(player.getUniqueId(), plugin.getConfig().getString(base + ".primary", "assault-rifle"));
        String secondary = selectedSecondary.getOrDefault(player.getUniqueId(), plugin.getConfig().getString(base + ".secondary", "secondary"));
        String current = slot == 1 ? secondary : primary;
        inHandWeapon.put(player.getUniqueId(), current);
        showAmmoActionbar(player, current);
    }

    public boolean tryUseMedkit(Player player) {
        if (!isInGame(player) || game == null || game.state() != GameState.INGAME) return false;

        long now = System.currentTimeMillis();
        long cooldownMs = Math.max(0L, plugin.getConfig().getLong("medkit.cooldown-ms", 15_000L));
        long last = lastMedkitUseMs.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < cooldownMs) return false;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() != Material.GOLDEN_APPLE || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.getDisplayName() == null) return false;

        String display = org.bukkit.ChatColor.stripColor(meta.getDisplayName()).trim();
        if (!"Medkit".equalsIgnoreCase(display)) return false;

        double heal = Math.max(0.0D, plugin.getConfig().getDouble("medkit.heal", 8.0D));
        int regenTicks = Math.max(0, plugin.getConfig().getInt("medkit.regen-ticks", 100));
        int regenAmplifier = Math.max(0, plugin.getConfig().getInt("medkit.regen-amplifier", 1));

        // Cancel vanilla consumption by handling it here.
        lastMedkitUseMs.put(player.getUniqueId(), now);

        double maxHealth = player.getMaxHealth();
        double newHealth = Math.min(maxHealth, player.getHealth() + heal);
        player.setHealth(newHealth);
        if (regenTicks > 0) {
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.REGENERATION,
                    regenTicks,
                    regenAmplifier - 1,
                    false,
                    true
            ));
        }

        int amount = item.getAmount();
        if (amount <= 1) player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        else item.setAmount(amount - 1);

        String msg = plugin.getConfig().getString("messages.medkit-used", "&aMedkit utilisé!");
        player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', msg));
        return true;
    }

    public boolean isKnifeInMainHand(Player player) {
        if (player == null || !isInGame(player)) return false;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.getDisplayName() == null) return false;
        String display = org.bukkit.ChatColor.stripColor(meta.getDisplayName()).trim();
        // In config we use "&fKnife" for the custom name.
        return display.equalsIgnoreCase("Knife") || display.toLowerCase(Locale.ROOT).contains("knife");
    }

    public boolean tryUseKnife(Player attacker, Player victim) {
        if (!isInGame(attacker) || !isInGame(victim) || game == null || game.state() != GameState.INGAME) return false;
        if (attacker == victim) return false;

        if (!isKnifeInMainHand(attacker)) return false;

        long now = System.currentTimeMillis();
        long cooldownMs = Math.max(0L, plugin.getConfig().getLong("knife.cooldown-ms", 1500L));
        long last = lastKnifeUseMs.getOrDefault(attacker.getUniqueId(), 0L);
        if (now - last < cooldownMs) return false;
        lastKnifeUseMs.put(attacker.getUniqueId(), now);

        // Team protection (TDM/CTF/INFECT). FFA is exempt.
        TeamColor attackerTeam = game.teamOf(attacker);
        TeamColor victimTeam = game.teamOf(victim);
        if (mode != CodMode.FFA && attackerTeam != null && attackerTeam == victimTeam) {
            return false;
        }

        double damage = Math.max(0.0D, plugin.getConfig().getDouble("knife.damage", 6.0D));
        if ("stopping_power".equalsIgnoreCase(selectedPerk.getOrDefault(attacker.getUniqueId(), "none"))) {
            double perkMul = plugin.getConfig().getDouble("perks.list.stopping_power.damage-multiplier", 1.15D);
            damage *= Math.max(1.0D, perkMul);
        }

        // Cancelled event + custom damage.
        victim.damage(damage, attacker);
        attacker.sendMessage(messages.get("knife-used"));

        return true;
    }

    public boolean isStimInMainHand(Player player) {
        if (player == null || !isInGame(player)) return false;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.getDisplayName() == null) return false;
        String display = org.bukkit.ChatColor.stripColor(meta.getDisplayName()).trim();
        return display.equalsIgnoreCase("Stim") || display.toLowerCase(Locale.ROOT).contains("stim");
    }

    public boolean tryUseStim(Player player) {
        if (!isInGame(player) || game == null || game.state() != GameState.INGAME) return false;

        long now = System.currentTimeMillis();
        long cooldownMs = Math.max(0L, plugin.getConfig().getLong("stim.cooldown-ms", 20_000L));
        long last = lastStimUseMs.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < cooldownMs) return false;

        if (!isStimInMainHand(player)) return false;

        lastStimUseMs.put(player.getUniqueId(), now);

        int regenTicks = Math.max(0, plugin.getConfig().getInt("stim.regen-ticks", 60));
        int regenAmp = Math.max(0, plugin.getConfig().getInt("stim.regen-amplifier", 1));
        int speedTicks = Math.max(0, plugin.getConfig().getInt("stim.speed-ticks", 120));
        int speedAmp = Math.max(0, plugin.getConfig().getInt("stim.speed-amplifier", 1));

        if (regenTicks > 0) {
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.REGENERATION,
                    regenTicks,
                    regenAmp - 1,
                    false,
                    true
            ));
        }
        if (speedTicks > 0) {
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.SPEED,
                    speedTicks,
                    speedAmp - 1,
                    false,
                    true
            ));
        }

        int amount = player.getInventory().getItemInMainHand().getAmount();
        if (amount <= 1) player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        else player.getInventory().getItemInMainHand().setAmount(amount - 1);

        String msg = plugin.getConfig().getString("messages.stim-used", "&aStim utilise!");
        player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', msg));
        return true;
    }

    public boolean isGrenadeInMainHand(Player player) {
        if (player == null || !isInGame(player)) return false;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.getDisplayName() == null) return false;
        String display = org.bukkit.ChatColor.stripColor(meta.getDisplayName()).trim();
        return display.equalsIgnoreCase("Grenade") || display.toLowerCase(Locale.ROOT).contains("grenade");
    }

    public boolean tryUseGrenade(Player player) {
        if (!isInGame(player) || game == null || game.state() != GameState.INGAME) return false;

        long now = System.currentTimeMillis();
        long cooldownMs = Math.max(0L, plugin.getConfig().getLong("grenade.cooldown-ms", 30_000L));
        long last = lastGrenadeUseMs.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < cooldownMs) return false;

        if (!isGrenadeInMainHand(player)) return false;

        lastGrenadeUseMs.put(player.getUniqueId(), now);

        double velocity = Math.max(0.2D, plugin.getConfig().getDouble("grenade.velocity", 1.6D));
        int fuseTicks = (int) Math.max(0, plugin.getConfig().getLong("grenade.fuse-ticks", 10L));

        // Throw: use Snowball as projectile wrapper.
        Vector dir = player.getEyeLocation().getDirection().normalize();
        Snowball grenade = player.launchProjectile(Snowball.class);
        grenade.setVelocity(dir.multiply(velocity));
        grenade.setShooter(player);

        TeamColor throwTeam = game.teamOf(player);
        UUID projectileId = grenade.getUniqueId();
        activeGrenades.put(projectileId, new GrenadeShot(player.getUniqueId(), throwTeam));

        // Fuse-based explosion.
        cancelGrenadeFuse(projectileId);
        if (fuseTicks <= 0) {
            explodeGrenade(projectileId, grenade.getLocation());
        } else {
            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                GrenadeShot shot = activeGrenades.remove(projectileId);
                if (shot == null) return;
                grenadeFuseTasks.remove(projectileId);
                explodeGrenadeEffects(shot.throwerId(), grenade.getLocation());
            }, fuseTicks);
            grenadeFuseTasks.put(projectileId, task);
        }

        int amount = player.getInventory().getItemInMainHand().getAmount();
        if (amount <= 1) player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        else player.getInventory().getItemInMainHand().setAmount(amount - 1);

        String msg = plugin.getConfig().getString("messages.grenade-used", "&cGrenade !");
        player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', msg));
        return true;
    }

    public boolean isActiveGrenadeProjectile(Projectile projectile) {
        if (projectile == null) return false;
        return activeGrenades.containsKey(projectile.getUniqueId());
    }

    public void handleGrenadeProjectileImpact(Projectile projectile) {
        if (projectile == null) return;
        UUID id = projectile.getUniqueId();
        GrenadeShot shot = activeGrenades.remove(id);
        if (shot == null) return;
        cancelGrenadeFuse(id);
        explodeGrenadeEffects(shot.throwerId(), projectile.getLocation());
    }

    private void explodeGrenade(UUID projectileId, Location location) {
        GrenadeShot shot = activeGrenades.remove(projectileId);
        if (shot == null) return;
        cancelGrenadeFuse(projectileId);
        explodeGrenadeEffects(shot.throwerId(), location);
    }

    private void cancelGrenadeFuse(UUID projectileId) {
        BukkitTask task = grenadeFuseTasks.remove(projectileId);
        if (task != null) task.cancel();
    }

    private record GrenadeShot(UUID throwerId, TeamColor throwerTeam) {}

    private void explodeGrenadeEffects(UUID throwerId, Location location) {
        if (throwerId == null || location == null) return;
        if (game == null || game.state() != GameState.INGAME) return;

        Player thrower = Bukkit.getPlayer(throwerId);
        if (thrower == null || !thrower.isOnline()) return;

        double radius = Math.max(0.5D, plugin.getConfig().getDouble("grenade.radius", 5.0D));
        double damage = Math.max(0.0D, plugin.getConfig().getDouble("grenade.damage", 4.0D));
        int blindTicks = Math.max(0, plugin.getConfig().getInt("grenade.blindness-ticks", 40));
        int slowTicks = Math.max(0, plugin.getConfig().getInt("grenade.slow-ticks", 60));
        int slowAmp = Math.max(0, plugin.getConfig().getInt("grenade.slow-amplifier", 1));

        TeamColor attackerTeam = game.teamOf(thrower);
        for (UUID id : game.allPlayers()) {
            Player victim = Bukkit.getPlayer(id);
            if (victim == null || !victim.isOnline()) continue;
            if (victim.getUniqueId().equals(throwerId)) continue;
            if (victim.getWorld() != location.getWorld()) continue;

            if (mode != CodMode.FFA && attackerTeam != null) {
                TeamColor victimTeam = game.teamOf(victim);
                if (victimTeam != null && victimTeam == attackerTeam) continue;
            }

            double distSq = location.distanceSquared(victim.getLocation());
            if (distSq > radius * radius) continue;

            if (damage > 0.0D) victim.damage(damage, thrower);
            if (blindTicks > 0) {
                victim.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.BLINDNESS,
                        blindTicks,
                        0,
                        false,
                        true
                ));
            }
            if (slowTicks > 0) {
                victim.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.SLOWNESS,
                        slowTicks,
                        Math.max(0, slowAmp - 1),
                        false,
                        true
                ));
            }
        }
    }

    public boolean setClass(Player player, String classKey) {
        if (!plugin.getConfig().isConfigurationSection("classes." + classKey)) return false;
        selectedClass.put(player.getUniqueId(), classKey);
        String base = "classes." + classKey;
        selectedPrimary.put(player.getUniqueId(), plugin.getConfig().getString(base + ".primary", "assault-rifle"));
        selectedSecondary.put(player.getUniqueId(), plugin.getConfig().getString(base + ".secondary", "secondary"));
        persistSelections(player.getUniqueId());
        if (isInGame(player)) equipLoadout(player);
        return true;
    }

    public boolean setPerk(Player player, String perkKey) {
        if (!plugin.getConfig().isConfigurationSection("perks.list." + perkKey) && !"none".equalsIgnoreCase(perkKey)) return false;
        selectedPerk.put(player.getUniqueId(), perkKey);
        persistSelections(player.getUniqueId());
        if (isInGame(player)) applyPerkOnSpawn(player);
        return true;
    }

    public boolean setPrimaryWeapon(Player player, String weaponKey) {
        if (!plugin.getConfig().isConfigurationSection("weapons." + weaponKey)) return false;
        selectedPrimary.put(player.getUniqueId(), weaponKey);
        persistSelections(player.getUniqueId());
        if (isInGame(player)) equipLoadout(player);
        return true;
    }

    public boolean setSecondaryWeapon(Player player, String weaponKey) {
        if (!plugin.getConfig().isConfigurationSection("weapons." + weaponKey)) return false;
        selectedSecondary.put(player.getUniqueId(), weaponKey);
        persistSelections(player.getUniqueId());
        if (isInGame(player)) equipLoadout(player);
        return true;
    }

    public boolean fireWeapon(Player shooter, String weaponKey, Projectile projectile) {
        if (!isInGame(shooter) || game == null || game.state() != GameState.INGAME) return false;
        String w = "weapons." + weaponKey;
        if (!plugin.getConfig().isConfigurationSection(w)) return false;
        if (reloading.contains(shooter.getUniqueId())) return false;

        long cooldown = Math.max(0L, plugin.getConfig().getLong(w + ".cooldown-ms", 250L));
        long now = System.currentTimeMillis();
        long last = lastWeaponShotMs.getOrDefault(shooter.getUniqueId(), 0L);
        if (now - last < cooldown) return false;

        String ammoId = ammoKey(shooter.getUniqueId(), weaponKey);
        int currentMag = magAmmo.getOrDefault(ammoId, weaponMagSize(weaponKey));
        if (currentMag <= 0) {
            shooter.sendMessage(messages.get("no-ammo"));
            return false;
        }

        lastWeaponShotMs.put(shooter.getUniqueId(), now);
        magAmmo.put(ammoId, currentMag - 1);
        inHandWeapon.put(shooter.getUniqueId(), weaponKey);

        double speed = Math.max(0.2D, plugin.getConfig().getDouble(w + ".projectile-speed", 2.5D));
        projectile.setVelocity(shooter.getLocation().getDirection().normalize().multiply(speed));
        activeShots.put(projectile.getUniqueId(), new WeaponShot(shooter.getUniqueId(), weaponKey, now));
        showAmmoActionbar(shooter, weaponKey);
        return true;
    }

    public void reloadCurrentWeapon(Player player) {
        if (!isInGame(player) || game == null || game.state() != GameState.INGAME) return;
        UUID id = player.getUniqueId();
        if (reloading.contains(id)) return;
        String weapon = inHandWeapon.getOrDefault(id, plugin.getConfig().getString("classes.assault.primary", "assault-rifle"));
        String ammoId = ammoKey(id, weapon);

        int mag = magAmmo.getOrDefault(ammoId, weaponMagSize(weapon));
        int magSize = weaponMagSize(weapon);
        int reserve = reserveAmmo.getOrDefault(ammoId, weaponReserveAmmo(weapon));
        if (mag >= magSize || reserve <= 0) return;

        long reloadTicks = Math.max(1L, plugin.getConfig().getLong("weapons." + weapon + ".reload-ticks", 30L));
        reloading.add(id);
        player.sendMessage(messages.get("reloading"));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            reloading.remove(id);
            if (!player.isOnline()) return;
            int currentMag = magAmmo.getOrDefault(ammoId, mag);
            int currentReserve = reserveAmmo.getOrDefault(ammoId, reserve);
            int needed = Math.max(0, magSize - currentMag);
            int toLoad = Math.min(needed, currentReserve);
            if (toLoad <= 0) return;
            magAmmo.put(ammoId, currentMag + toLoad);
            reserveAmmo.put(ammoId, currentReserve - toLoad);
            player.sendMessage(messages.get("reloaded"));
            showAmmoActionbar(player, weapon);
        }, reloadTicks);
    }

    public boolean tryUseAirstrike(Player player) {
        if (!isInGame(player) || game == null || game.state() != GameState.INGAME) return false;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() != Material.BLAZE_ROD || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.getDisplayName() == null || !meta.getDisplayName().contains("Airstrike")) return false;

        double damage = Math.max(0.0D, plugin.getConfig().getDouble("killstreaks.airstrike-damage", 8.0D));
        double radius = Math.max(0.5D, plugin.getConfig().getDouble("killstreaks.airstrike-radius", 6.0D));
        long delayTicks = Math.max(0L, plugin.getConfig().getLong("killstreaks.airstrike-delay-ticks", 20L));
        int targetRange = Math.max(5, plugin.getConfig().getInt("killstreaks.airstrike-target-range", 30));
        double yOffset = plugin.getConfig().getDouble("killstreaks.airstrike-y-offset", 0.0D);

        // Optional perk multiplier.
        String perk = selectedPerk.getOrDefault(player.getUniqueId(), "none");
        if ("stopping_power".equalsIgnoreCase(perk)) {
            double perkMul = plugin.getConfig().getDouble("perks.list.stopping_power.damage-multiplier", 1.15D);
            damage *= Math.max(1.0D, perkMul);
        }

        // Aim: raytrace in the direction the player is looking.
        Location target = player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(targetRange));
        try {
            org.bukkit.util.RayTraceResult hit = player.getWorld().rayTraceBlocks(
                    player.getEyeLocation(),
                    player.getEyeLocation().getDirection(),
                    targetRange,
                    org.bukkit.FluidCollisionMode.NEVER,
                    true
            );
            if (hit != null && hit.getHitPosition() != null) {
                target = hit.getHitPosition().toLocation(player.getWorld());
            }
        } catch (Throwable ignored) {
            // Fallback to "look direction" if ray tracing differs per server version.
        }
        target = target.clone().add(0.0D, yOffset, 0.0D);

        final Location targetFinal = target;
        final double damageFinal = damage;
        final double radiusFinal = radius;

        player.sendMessage(messages.get("airstrike-called"));

        if (delayTicks <= 0L) {
            explodeAirstrike(player, targetFinal, damageFinal, radiusFinal);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> explodeAirstrike(player, targetFinal, damageFinal, radiusFinal),
                    delayTicks
            );
        }

        int amount = item.getAmount();
        if (amount <= 1) player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        else item.setAmount(amount - 1);
        return true;
    }

    private void explodeAirstrike(Player caller, Location target, double damage, double radius) {
        if (caller == null || target == null) return;
        if (game == null || game.state() != GameState.INGAME) return;
        if (target.getWorld() == null || caller.getWorld() == null) return;
        if (!target.getWorld().equals(caller.getWorld())) return;

        TeamColor team = game.teamOf(caller);
        for (UUID id : game.allPlayers()) {
            Player enemy = Bukkit.getPlayer(id);
            if (enemy == null || !enemy.isOnline()) continue;
            if (enemy.getUniqueId().equals(caller.getUniqueId())) continue;
            if (!enemy.getWorld().equals(target.getWorld())) continue;

            if (mode != CodMode.FFA) {
                TeamColor enemyTeam = game.teamOf(enemy);
                if (enemyTeam != null && team != null && enemyTeam == team) continue;
            }

            if (enemy.getLocation().distanceSquared(target) > radius * radius) continue;

            if (damage > 0.0D) enemy.damage(damage, caller);
            enemy.playSound(enemy.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 1.0f);
        }
    }

    public void handleProjectileHit(Entity hitEntity, Projectile projectile) {
        WeaponShot shot = activeShots.remove(projectile.getUniqueId());
        if (shot == null || game == null || game.state() != GameState.INGAME) return;
        Player shooter = Bukkit.getPlayer(shot.shooterId());
        if (shooter == null || !isInGame(shooter)) return;
        if (!(hitEntity instanceof Player victim)) return;
        if (!isInGame(victim)) return;
        if (victim.getUniqueId().equals(shooter.getUniqueId())) return;

        TeamColor shooterTeam = game.teamOf(shooter);
        TeamColor victimTeam = game.teamOf(victim);
        if (mode != CodMode.FFA && shooterTeam != null && shooterTeam == victimTeam) return;

        String w = "weapons." + shot.weaponKey();
        double baseDamage = plugin.getConfig().getDouble(w + ".damage", 4.0D);
        double rangeMax = plugin.getConfig().getDouble(w + ".range-max", 40.0D);
        double falloffStart = plugin.getConfig().getDouble(w + ".falloff-start", 15.0D);
        double dist = shooter.getLocation().distance(victim.getLocation());
        if (dist > rangeMax) return;

        double damage = baseDamage;
        if (dist > falloffStart && rangeMax > falloffStart) {
            double t = (dist - falloffStart) / (rangeMax - falloffStart);
            damage = Math.max(1.0D, baseDamage * (1.0D - 0.55D * t));
        }
        double headshotMultiplier = Math.max(1.0D, plugin.getConfig().getDouble("game.headshot-multiplier", 1.6D));
        // More robust headshot detection: distance to victim's eye position.
        double headshotDistance = Math.max(0.1D, plugin.getConfig().getDouble("game.headshot-distance", 0.9D));
        boolean headshot = projectile.getLocation().distanceSquared(victim.getEyeLocation()) <= headshotDistance * headshotDistance;
        // Legacy fallback: Y-threshold if configs/positions behave oddly.
        if (!headshot) {
            headshot = projectile.getLocation().getY() >= (victim.getEyeLocation().getY() - 0.2D);
        }
        if (headshot) {
            damage *= headshotMultiplier;
            shooter.sendMessage(messages.get("headshot"));
            shooter.playSound(shooter.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.6f);
        }
        if ("stopping_power".equalsIgnoreCase(selectedPerk.getOrDefault(shooter.getUniqueId(), "none"))) {
            double perkMul = plugin.getConfig().getDouble("perks.list.stopping_power.damage-multiplier", 1.15D);
            damage *= Math.max(1.0D, perkMul);
        }
        victim.damage(damage, shooter);
    }

    private void handleCtfMovement(Player player, Location to) {
        if (game == null) return;
        TeamColor team = game.teamOf(player);
        if (team == null) return;
        TeamColor enemyFlag = team == TeamColor.RED ? TeamColor.BLUE : TeamColor.RED;
        Location enemyFlagLoc = getCtfFlagLocation(enemyFlag);
        Location ownFlagLoc = team == TeamColor.RED ? game.arena().redFlag() : game.arena().blueFlag();
        double r = game.arena().flagCaptureRadius();
        if (enemyFlagLoc == null || ownFlagLoc == null || r <= 0.1D) return;

        // Pick up enemy flag.
        if (!flagCarrier.containsKey(enemyFlag) && to.getWorld() != null
                && to.getWorld().equals(enemyFlagLoc.getWorld())
                && to.distanceSquared(enemyFlagLoc) <= r * r) {
            flagCarrier.put(enemyFlag, player.getUniqueId());
            player.sendMessage(messages.get("ctf-flag-picked"));
        }

        // Capture enemy flag at own base.
        if (player.getUniqueId().equals(flagCarrier.get(enemyFlag))
                && to.getWorld() != null
                && to.getWorld().equals(ownFlagLoc.getWorld())
                && to.distanceSquared(ownFlagLoc) <= r * r) {
            flagCarrier.remove(enemyFlag);
            // After capture, respawn flag at its base.
            ctfFlagPosition.put(enemyFlag, baseFlagLocation(enemyFlag));
            cancelCtfRespawn(enemyFlag);
            game.addObjectivePoint(team);
            for (UUID id : game.allPlayers()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOnline()) {
                    p.sendMessage(messages.get("ctf-capture", Map.of(
                            "player", player.getName(),
                            "red", String.valueOf(game.redObjective()),
                            "blue", String.valueOf(game.blueObjective())
                    )));
                }
            }
            checkWin();
        }
    }

    private void dropCarriedFlag(Player player, Location dropLocation) {
        if (player == null) return;

        UUID id = player.getUniqueId();
        if (id.equals(flagCarrier.get(TeamColor.RED))) {
            flagCarrier.remove(TeamColor.RED);
            onFlagDropped(TeamColor.RED, dropLocation);
        }
        if (id.equals(flagCarrier.get(TeamColor.BLUE))) {
            flagCarrier.remove(TeamColor.BLUE);
            onFlagDropped(TeamColor.BLUE, dropLocation);
        }
    }

    private void onFlagDropped(TeamColor flagTeam, Location dropLocation) {
        if (dropLocation == null || dropLocation.getWorld() == null) return;
        ctfFlagPosition.put(flagTeam, dropLocation.clone());
        scheduleCtfRespawn(flagTeam);
    }

    private Location baseFlagLocation(TeamColor flagTeam) {
        if (game == null) return null;
        return flagTeam == TeamColor.RED ? game.arena().redFlag() : game.arena().blueFlag();
    }

    private Location getCtfFlagLocation(TeamColor flagTeam) {
        Location current = ctfFlagPosition.get(flagTeam);
        if (current != null) return current;
        return baseFlagLocation(flagTeam);
    }

    private void resetCtfFlags() {
        cancelCtfRespawn(TeamColor.RED);
        cancelCtfRespawn(TeamColor.BLUE);
        ctfFlagPosition.put(TeamColor.RED, baseFlagLocation(TeamColor.RED));
        ctfFlagPosition.put(TeamColor.BLUE, baseFlagLocation(TeamColor.BLUE));
    }

    private void cancelCtfRespawn(TeamColor flagTeam) {
        BukkitTask task = ctfFlagRespawnTasks.remove(flagTeam);
        if (task != null) task.cancel();
    }

    private void scheduleCtfRespawn(TeamColor flagTeam) {
        cancelCtfRespawn(flagTeam);
        long delayTicks = Math.max(0L, plugin.getConfig().getLong("game.ctf.flag-respawn-delay-ticks", 600L));
        if (delayTicks <= 0) {
            ctfFlagPosition.put(flagTeam, baseFlagLocation(flagTeam));
            return;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // If someone picked it up during the delay, keep it carried.
            if (flagCarrier.containsKey(flagTeam)) {
                ctfFlagRespawnTasks.remove(flagTeam);
                return;
            }
            ctfFlagPosition.put(flagTeam, baseFlagLocation(flagTeam));
            ctfFlagRespawnTasks.remove(flagTeam);
        }, delayTicks);
        ctfFlagRespawnTasks.put(flagTeam, task);
    }

    public String debugStatus() {
        String state = game == null ? "NO_GAME" : game.state().name();
        String arena = game == null ? "none" : game.arena().id();
        return "Etat=" + state + ", mode=" + mode.name() + ", arena=" + arena + ", queue=" + queue.size();
    }

    public void shutdown() {
        try {
            store.shutdown();
        } catch (Exception ignored) {
        }
        if (startTask != null) startTask.cancel();
        if (scoreboardTask != null) scoreboardTask.cancel();
        activeShots.clear();
        lastWeaponShotMs.clear();
        inHandWeapon.clear();
        killStreak.clear();
        magAmmo.clear();
        reserveAmmo.clear();
        reloading.clear();
        personalKills.clear();
        personalDeaths.clear();
        lastMedkitUseMs.clear();
        lastStimUseMs.clear();
        lastGrenadeUseMs.clear();
        lastKnifeUseMs.clear();
        selectedClass.clear();
        selectedPerk.clear();
        mapVotes.clear();
        queue.clear();
        game = null;

        // CTF cleanup.
        cancelCtfRespawn(TeamColor.RED);
        cancelCtfRespawn(TeamColor.BLUE);
        ctfFlagPosition.clear();

        // UAV cleanup.
        for (BukkitTask t : activeUavTasks.values()) {
            try { t.cancel(); } catch (Exception ignored) {}
        }
        activeUavTasks.clear();
    }

    private void startScoreboardLoop() {
        if (scoreboardTask != null) scoreboardTask.cancel();
        scoreboardTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (game == null || game.state() != GameState.INGAME) return;
            for (UUID id : game.allPlayers()) {
                Player p = Bukkit.getPlayer(id);
                if (p == null || !p.isOnline()) continue;
                updateScoreboard(p);
            }
        }, 20L, 20L);
    }

    private void updateScoreboard(Player player) {
        if (game == null) return;
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = sb.registerNewObjective("cod", "dummy");
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        obj.setDisplayName("§cCOD Warfare");
        int k = personalKills.getOrDefault(player.getUniqueId(), 0);
        int d = personalDeaths.getOrDefault(player.getUniqueId(), 0);
        int streak = killStreak.getOrDefault(player.getUniqueId(), 0);
        obj.getScore("§7 ").setScore(9);
        obj.getScore("§fMode: §c" + mode.name()).setScore(8);
        if (mode == CodMode.CTF) {
            obj.getScore("§fRed Flags: §c" + game.redObjective()).setScore(7);
            obj.getScore("§fBlue Flags: §9" + game.blueObjective()).setScore(6);
        } else if (mode == CodMode.INFECT) {
            obj.getScore("§fInfectés: §c" + game.teamSize(TeamColor.RED)).setScore(7);
            obj.getScore("§fSurvivants: §9" + game.teamSize(TeamColor.BLUE)).setScore(6);
        } else {
            obj.getScore("§fRed: §c" + game.redKills()).setScore(7);
            obj.getScore("§fBlue: §9" + game.blueKills()).setScore(6);
        }
        obj.getScore("§8 ").setScore(5);
        obj.getScore("§fKills: §a" + k).setScore(4);
        obj.getScore("§fDeaths: §c" + d).setScore(3);
        obj.getScore("§fStreak: §6" + streak).setScore(2);
        int winValue = plugin.getConfig().getInt("game.kills-to-win", 50);
        if (mode == CodMode.CTF) winValue = plugin.getConfig().getInt("game.ctf.captures-to-win", 3);
        if (mode == CodMode.INFECT) winValue = plugin.getConfig().getInt("game.infect.survivor-kills-to-win", 30);
        obj.getScore("§fWin: §6" + winValue).setScore(1);
        obj.getScore("§7vibecraft").setScore(0);
        player.setScoreboard(sb);
    }

    private void triggerKillstreakRewards(Player killer, int streak) {
        int uavAt = Math.max(1, plugin.getConfig().getInt("killstreaks.uav", 3));
        int airstrikeAt = Math.max(1, plugin.getConfig().getInt("killstreaks.airstrike", 5));
        if (streak == uavAt) startUav(killer);
        if (streak == airstrikeAt) {
            giveAirstrikeItem(killer);
            killer.sendMessage(messages.get("airstrike-ready"));
        }
    }

    private void startUav(Player owner) {
        if (owner == null || game == null) return;
        cancelUav(owner.getUniqueId());

        long durationTicks = Math.max(1L, plugin.getConfig().getLong("killstreaks.uav-duration-ticks", 200L));
        long intervalTicks = Math.max(1L, plugin.getConfig().getLong("killstreaks.uav-interval-ticks", 40L));

        long endAt = System.currentTimeMillis() + durationTicks * 50L;
        activeUavTasks.put(owner.getUniqueId(), Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (game == null || game.state() != GameState.INGAME) return;
            if (!owner.isOnline()) {
                cancelUav(owner.getUniqueId());
                return;
            }
            if (System.currentTimeMillis() >= endAt) {
                cancelUav(owner.getUniqueId());
                return;
            }
            sendUavPing(owner);
        }, 1L, intervalTicks));
    }

    private void cancelUav(UUID playerId) {
        BukkitTask task = activeUavTasks.remove(playerId);
        if (task != null) task.cancel();
    }

    /** Arrête tous les UAV actifs (fin de round / rotation d'arène). */
    private void cancelAllUavs() {
        for (BukkitTask task : activeUavTasks.values()) {
            if (task != null) task.cancel();
        }
        activeUavTasks.clear();
    }

    private void sendUavPing(Player owner) {
        if (game == null) return;
        TeamColor team = game.teamOf(owner);
        List<String> enemies = new ArrayList<>();
        for (UUID id : game.allPlayers()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) continue;
            TeamColor t = game.teamOf(p);
            if (mode == CodMode.FFA) {
                if (!p.getUniqueId().equals(owner.getUniqueId())) enemies.add(p.getName());
            } else if (t != null && t != team) {
                enemies.add(p.getName());
            }
        }
        String names = enemies.isEmpty() ? "-" : String.join(", ", enemies);
        owner.sendMessage(messages.get("uav-online", Map.of("enemies", names)));
    }

    private void giveAirstrikeItem(Player player) {
        ItemStack rod = new ItemStack(Material.BLAZE_ROD, 1);
        ItemMeta meta = rod.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§cAirstrike");
            rod.setItemMeta(meta);
        }
        player.getInventory().addItem(rod);
    }

    private String ammoKey(UUID playerId, String weapon) {
        return playerId + "|" + weapon.toLowerCase(Locale.ROOT);
    }

    private int weaponMagSize(String weapon) {
        return Math.max(1, plugin.getConfig().getInt("weapons." + weapon + ".magazine-size", 30));
    }

    private int weaponReserveAmmo(String weapon) {
        return Math.max(0, plugin.getConfig().getInt("weapons." + weapon + ".reserve-ammo", 120));
    }

    private void showAmmoActionbar(Player player, String weapon) {
        String ammoId = ammoKey(player.getUniqueId(), weapon);
        int current = magAmmo.getOrDefault(ammoId, weaponMagSize(weapon));
        int reserve = reserveAmmo.getOrDefault(ammoId, weaponReserveAmmo(weapon));
        String msg = messages.get("ammo", Map.of(
                "current", String.valueOf(current),
                "mag", String.valueOf(weaponMagSize(weapon)),
                "reserve", String.valueOf(reserve)
        ));
        player.sendActionBar(msg);
    }

    private void teleportSpawnByMode(Player player) {
        if (game == null) return;
        if (mode == CodMode.FFA) {
            if (Math.random() < 0.5D) player.teleport(game.arena().redSpawn());
            else player.teleport(game.arena().blueSpawn());
            return;
        }
        TeamColor team = game.teamOf(player);
        if (team == null) return;
        player.teleport(team == TeamColor.RED ? game.arena().redSpawn() : game.arena().blueSpawn());
    }

    private void applyPerkOnSpawn(Player player) {
        String perk = selectedPerk.getOrDefault(player.getUniqueId(), "none");
        if ("lightweight".equalsIgnoreCase(perk)) {
            float speed = (float) plugin.getConfig().getDouble("perks.list.lightweight.walk-speed", 0.24D);
            player.setWalkSpeed(Math.max(0.1f, Math.min(1.0f, speed)));
        } else {
            player.setWalkSpeed(0.2f);
        }
    }

    private void applyScavengerPerk(Player killer) {
        if (!"scavenger".equalsIgnoreCase(selectedPerk.getOrDefault(killer.getUniqueId(), "none"))) return;
        String weapon = inHandWeapon.getOrDefault(killer.getUniqueId(), plugin.getConfig().getString("classes.assault.primary", "assault-rifle"));
        String key = ammoKey(killer.getUniqueId(), weapon);
        int add = plugin.getConfig().getInt("perks.list.scavenger.reserve-add-on-kill", 8);
        reserveAmmo.put(key, reserveAmmo.getOrDefault(key, weaponReserveAmmo(weapon)) + Math.max(0, add));
        showAmmoActionbar(killer, weapon);
    }

    private void rotateArena() {
        if (arenas.isEmpty()) {
            game = null;
            return;
        }
        String voted = null;
        Map<String, Integer> counts = new HashMap<>();
        for (String id : mapVotes.values()) {
            counts.put(id.toLowerCase(Locale.ROOT), counts.getOrDefault(id.toLowerCase(Locale.ROOT), 0) + 1);
        }
        int top = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > top) {
                top = e.getValue();
                voted = e.getKey();
            }
        }
        if (voted != null) {
            for (int i = 0; i < arenas.size(); i++) {
                if (arenas.get(i).id().equalsIgnoreCase(voted)) {
                    arenaIndex = i;
                    break;
                }
            }
        } else {
            arenaIndex = (arenaIndex + 1) % arenas.size();
        }
        game = new CodGame(arenas.get(arenaIndex));
    }

    public boolean voteMap(Player player, String arenaId) {
        for (CodArena arena : arenas) {
            if (arena.id().equalsIgnoreCase(arenaId)) {
                mapVotes.put(player.getUniqueId(), arena.id());
                return true;
            }
        }
        return false;
    }

    public List<String> getArenaIds() {
        List<String> ids = new ArrayList<>();
        for (CodArena arena : arenas) ids.add(arena.id());
        return ids;
    }

    public void openArmoryMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, "COD Armory");
        inv.setItem(10, namedItem(Material.IRON_CHESTPLATE, "§cClasses"));
        inv.setItem(12, namedItem(Material.CROSSBOW, "§ePrimary Weapons"));
        inv.setItem(14, namedItem(Material.CROSSBOW, "§9Secondary Weapons"));
        inv.setItem(16, namedItem(Material.BLAZE_POWDER, "§bPerks"));
        player.openInventory(inv);
    }

    public void openClassMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, "COD Classes");
        int slot = 10;
        if (plugin.getConfig().isConfigurationSection("classes")) {
            for (String key : plugin.getConfig().getConfigurationSection("classes").getKeys(false)) {
                if ("default".equalsIgnoreCase(key)) continue;
                inv.setItem(slot, namedItem(Material.IRON_CHESTPLATE, "§c" + key));
                slot++;
                if (slot == 17) slot = 19;
                if (slot > 34) break;
            }
        }
        inv.setItem(49, namedItem(Material.ARROW, "§7Back"));
        player.openInventory(inv);
    }

    public void openPrimaryWeaponsMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, "COD Primary Weapons");
        int slot = 10;
        if (plugin.getConfig().isConfigurationSection("weapons")) {
            for (String key : plugin.getConfig().getConfigurationSection("weapons").getKeys(false)) {
                String slotType = plugin.getConfig().getString("weapons." + key + ".slot", "primary");
                if (!"primary".equalsIgnoreCase(slotType)) continue;
                Material mat = Material.IRON_HOE;
                String configured = plugin.getConfig().getString("loadout." + key + ".material", "IRON_HOE");
                try { mat = Material.valueOf(configured); } catch (Exception ignored) {}
                inv.setItem(slot, namedItem(mat, "§e" + key));
                slot++;
                if (slot == 17) slot = 19;
                if (slot > 34) break;
            }
        }
        inv.setItem(49, namedItem(Material.ARROW, "§7Back"));
        player.openInventory(inv);
    }

    public void openSecondaryWeaponsMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, "COD Secondary Weapons");
        int slot = 10;
        if (plugin.getConfig().isConfigurationSection("weapons")) {
            for (String key : plugin.getConfig().getConfigurationSection("weapons").getKeys(false)) {
                String slotType = plugin.getConfig().getString("weapons." + key + ".slot", "primary");
                if (!"secondary".equalsIgnoreCase(slotType)) continue;
                Material mat = Material.IRON_HOE;
                String configured = plugin.getConfig().getString("loadout." + key + ".material", "IRON_HOE");
                try { mat = Material.valueOf(configured); } catch (Exception ignored) {}
                inv.setItem(slot, namedItem(mat, "§9" + key));
                slot++;
                if (slot == 17) slot = 19;
                if (slot > 34) break;
            }
        }
        inv.setItem(49, namedItem(Material.ARROW, "§7Back"));
        player.openInventory(inv);
    }

    public void openPerksMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, "COD Perks");
        int slot = 10;
        if (plugin.getConfig().isConfigurationSection("perks.list")) {
            for (String key : plugin.getConfig().getConfigurationSection("perks.list").getKeys(false)) {
                inv.setItem(slot, namedItem(Material.BLAZE_POWDER, "§b" + key));
                slot++;
            }
        }
        inv.setItem(16, namedItem(Material.BARRIER, "§7none"));
        inv.setItem(22, namedItem(Material.ARROW, "§7Back"));
        player.openInventory(inv);
    }

    public void handleArmoryClick(Player player, String title, ItemStack clicked) {
        if (clicked == null || !clicked.hasItemMeta() || clicked.getItemMeta() == null) return;
        String name = clicked.getItemMeta().getDisplayName();
        if (name == null) return;
        String stripped = org.bukkit.ChatColor.stripColor(name).toLowerCase(Locale.ROOT);

        if ("COD Armory".equals(title)) {
            if (stripped.contains("classes")) openClassMenu(player);
            else if (stripped.contains("primary")) openPrimaryWeaponsMenu(player);
            else if (stripped.contains("secondary")) openSecondaryWeaponsMenu(player);
            else if (stripped.contains("perks")) openPerksMenu(player);
            return;
        }

        if ("COD Classes".equals(title)) {
            if (stripped.equals("back")) {
                openArmoryMenu(player);
                return;
            }
            if (setClass(player, stripped)) {
                player.sendMessage(messages.get("class-selected", Map.of("class", stripped)));
            }
            return;
        }

        if ("COD Primary Weapons".equals(title)) {
            if (stripped.equals("back")) {
                openArmoryMenu(player);
                return;
            }
            if (setPrimaryWeapon(player, stripped)) {
                player.sendMessage(messages.get("weapon-selected", Map.of("weapon", stripped)));
            }
            return;
        }

        if ("COD Secondary Weapons".equals(title)) {
            if (stripped.equals("back")) {
                openArmoryMenu(player);
                return;
            }
            if (setSecondaryWeapon(player, stripped)) {
                player.sendMessage(messages.get("weapon-selected", Map.of("weapon", stripped)));
            }
            return;
        }

        if ("COD Perks".equals(title)) {
            if (stripped.equals("back")) {
                openArmoryMenu(player);
                return;
            }
            if (setPerk(player, stripped)) {
                player.sendMessage(messages.get("perk-selected", Map.of("perk", stripped)));
            }
        }
    }

    private ItemStack namedItem(Material material, String name) {
        ItemStack it = new ItemStack(material);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            it.setItemMeta(meta);
        }
        return it;
    }
}
