package net.betterthanvillage.warfare;

import java.util.Locale;

/** Warrior classes (docs_v2/07-warfare.md §Classes). */
public enum WarriorClass {
    /** Bow or crossbow: walls and towers in defence, covering fire in a siege. */
    RANGED,
    /** Sword (and shield) or axe: gates and breaches, assaults, outpost guard. */
    INFANTRY,
    /** Sword on a horse: field battles, pursuit, intercepting raids. */
    CAVALRY;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static WarriorClass byKey(String key) {
        for (WarriorClass c : values()) {
            if (c.key().equals(key)) return c;
        }
        return INFANTRY;
    }
}
