package com.vibecraft.codwarfare.game;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public final class CodArena {

    private final String id;
    private final String worldName;
    private final Location lobbySpawn;
    private final Location redSpawn;
    private final Location blueSpawn;
    private final Location redFlag;
    private final Location blueFlag;
    private final double flagCaptureRadius;

    public CodArena(String id, String worldName, Location lobbySpawn, Location redSpawn, Location blueSpawn,
                    Location redFlag, Location blueFlag, double flagCaptureRadius) {
        this.id = id;
        this.worldName = worldName;
        this.lobbySpawn = lobbySpawn;
        this.redSpawn = redSpawn;
        this.blueSpawn = blueSpawn;
        this.redFlag = redFlag;
        this.blueFlag = blueFlag;
        this.flagCaptureRadius = flagCaptureRadius;
    }

    public String id() {
        return id;
    }

    public World world() {
        return Bukkit.getWorld(worldName);
    }

    public Location lobbySpawn() {
        return lobbySpawn.clone();
    }

    public Location redSpawn() {
        return redSpawn.clone();
    }

    public Location blueSpawn() {
        return blueSpawn.clone();
    }

    public Location redFlag() {
        return redFlag == null ? null : redFlag.clone();
    }

    public Location blueFlag() {
        return blueFlag == null ? null : blueFlag.clone();
    }

    public double flagCaptureRadius() {
        return flagCaptureRadius;
    }
}
