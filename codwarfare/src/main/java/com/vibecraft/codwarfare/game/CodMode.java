package com.vibecraft.codwarfare.game;

public enum CodMode {
    TDM,
    FFA,
    CTF,
    INFECT;

    public static CodMode fromConfig(String raw) {
        if (raw == null) return TDM;
        try {
            return CodMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return TDM;
        }
    }
}
