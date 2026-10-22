package net.betterthanvillage.law;

import java.util.Locale;

/** Offences of docs_v2/05-law.md §Offences. Continuous ones get a grace period, one-shot ones a single warning. */
public enum Offence {
    WEAPON(true),
    BUILDING(true),
    ANIMAL(false),
    ATTACK(false),
    THEFT(false),
    /** An overdue debt note: warned by the creditor's village, war a day later unless paid (docs_v2/03-economy.md). */
    DEBT(true),
    /** Not an offence against this country: it joined another country's war on a strong player (docs_v2/07-warfare.md). */
    COALITION(false);

    public final boolean continuous;

    Offence(boolean continuous) {
        this.continuous = continuous;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    static Offence byKey(String key) {
        for (Offence o : values()) {
            if (o.key().equals(key)) return o;
        }
        return null;
    }
}
