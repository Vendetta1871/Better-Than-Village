package net.betterthanvillage.law;

import net.betterthanvillage.colony.NbtHelper;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** A country's war on a player (docs_v2/05-law.md §War against the player). Stored in ColonySavedData. */
public final class PlayerWar {
    public UUID country;
    public UUID player;
    public String playerName = "";
    /** The village whose law the player broke (its elder declared the war). */
    public UUID colony;
    public Offence cause;
    public long declared;
    /** Villagers and golems of the country the player killed during this war. */
    public int losses;
    /** Times the country's warriors or golems killed the player. */
    public int playerDeaths;
    public long lastPlayerDeath = -1;
    /** Surrender is not accepted before this tick (perfidy). */
    public long perfidyUntil;
    /** The country asked for peace (war weariness): its elders pay the player for a treaty. */
    public boolean weary;
    /** Ceasefire while the player shows a white flag to the country (runtime). */
    public transient long ceasefireUntil;

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        NbtHelper.putUuid(tag, "country", country);
        NbtHelper.putUuid(tag, "player", player);
        tag.putString("player_name", playerName);
        NbtHelper.putUuid(tag, "colony", colony);
        tag.putString("cause", cause.key());
        tag.putLong("declared", declared);
        tag.putInt("losses", losses);
        tag.putInt("player_deaths", playerDeaths);
        tag.putLong("last_player_death", lastPlayerDeath);
        tag.putLong("perfidy_until", perfidyUntil);
        tag.putBoolean("weary", weary);
        return tag;
    }

    public static PlayerWar load(CompoundTag tag) {
        PlayerWar w = new PlayerWar();
        w.country = NbtHelper.uuid(tag, "country");
        w.player = NbtHelper.uuid(tag, "player");
        if (w.country == null || w.player == null) return null;
        w.playerName = tag.getString("player_name");
        w.colony = NbtHelper.uuid(tag, "colony");
        Offence cause = Offence.byKey(tag.getString("cause"));
        w.cause = cause == null ? Offence.ATTACK : cause;
        w.declared = tag.getLong("declared");
        w.losses = Math.max(0, tag.getInt("losses"));
        w.playerDeaths = Math.max(0, tag.getInt("player_deaths"));
        w.lastPlayerDeath = tag.contains("last_player_death") ? tag.getLong("last_player_death") : -1;
        w.perfidyUntil = tag.getLong("perfidy_until");
        w.weary = tag.getBoolean("weary");
        return w;
    }

    /** A signed peace: the truce the country keeps with the player (docs_v2/05-law.md §Peace treaties). */
    public static final class Treaty {
        public UUID country;
        public UUID player;
        public long signed;
        public long truceUntil;
        /** Emeralds the player paid; negative when the country paid the player. */
        public int price;

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            NbtHelper.putUuid(tag, "country", country);
            NbtHelper.putUuid(tag, "player", player);
            tag.putLong("signed", signed);
            tag.putLong("truce_until", truceUntil);
            tag.putInt("price", price);
            return tag;
        }

        public static Treaty load(CompoundTag tag) {
            Treaty t = new Treaty();
            t.country = NbtHelper.uuid(tag, "country");
            t.player = NbtHelper.uuid(tag, "player");
            if (t.country == null || t.player == null) return null;
            t.signed = tag.getLong("signed");
            t.truceUntil = tag.getLong("truce_until");
            t.price = tag.getInt("price");
            return t;
        }
    }
}
