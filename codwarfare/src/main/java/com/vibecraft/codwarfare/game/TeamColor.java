package com.vibecraft.codwarfare.game;

public enum TeamColor {
    RED("Rouge"),
    BLUE("Bleu");

    private final String display;

    TeamColor(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }
}
