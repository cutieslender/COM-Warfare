package com.vibecraft.codwarfare.game;

import java.util.UUID;

public record WeaponShot(UUID shooterId, String weaponKey, long shotAtMillis) {
}
