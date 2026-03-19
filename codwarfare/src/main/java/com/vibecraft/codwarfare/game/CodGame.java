package com.vibecraft.codwarfare.game;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class CodGame {

    private final CodArena arena;
    private final Set<UUID> red = new HashSet<>();
    private final Set<UUID> blue = new HashSet<>();
    private int redKills;
    private int blueKills;
    private int redObjective;
    private int blueObjective;
    private GameState state = GameState.WAITING;

    public CodGame(CodArena arena) {
        this.arena = arena;
    }

    public CodArena arena() {
        return arena;
    }

    public GameState state() {
        return state;
    }

    public void setState(GameState state) {
        this.state = state;
    }

    public void addPlayer(Player player) {
        if (red.size() <= blue.size()) red.add(player.getUniqueId());
        else blue.add(player.getUniqueId());
    }

    public boolean isInGame(Player player) {
        UUID id = player.getUniqueId();
        return red.contains(id) || blue.contains(id);
    }

    public void removePlayer(Player player) {
        red.remove(player.getUniqueId());
        blue.remove(player.getUniqueId());
    }

    public TeamColor teamOf(Player player) {
        UUID id = player.getUniqueId();
        if (red.contains(id)) return TeamColor.RED;
        if (blue.contains(id)) return TeamColor.BLUE;
        return null;
    }

    public void setTeam(Player player, TeamColor team) {
        removePlayer(player);
        if (team == TeamColor.RED) {
            red.add(player.getUniqueId());
        } else if (team == TeamColor.BLUE) {
            blue.add(player.getUniqueId());
        }
    }

    public void addKill(TeamColor team) {
        if (team == TeamColor.RED) redKills++;
        if (team == TeamColor.BLUE) blueKills++;
    }

    public int redKills() {
        return redKills;
    }

    public int blueKills() {
        return blueKills;
    }

    public int redObjective() {
        return redObjective;
    }

    public int blueObjective() {
        return blueObjective;
    }

    public void addObjectivePoint(TeamColor team) {
        if (team == TeamColor.RED) redObjective++;
        if (team == TeamColor.BLUE) blueObjective++;
    }

    public void resetScores() {
        redKills = 0;
        blueKills = 0;
        redObjective = 0;
        blueObjective = 0;
    }

    public int teamSize(TeamColor team) {
        return team == TeamColor.RED ? red.size() : blue.size();
    }

    public Collection<UUID> allPlayers() {
        Set<UUID> all = new HashSet<>(red);
        all.addAll(blue);
        return all;
    }
}
