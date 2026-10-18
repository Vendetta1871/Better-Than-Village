package net.betterthanvillage.defense;

import net.betterthanvillage.colony.Colony;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

import java.util.List;

/** Colony guard gear stock (02 §3.7, 06 §1.5) (stub). */
public final class GuardGear {
    private GuardGear() {
    }

    /** One equipment entry: item, base sell price, material, material count, logs count. */
    public record Entry(Item item, int price, Item material, int materialCount, int logs) {
    }

    /** The equipment table in offer order. */
    public static List<Entry> table() {
        return List.of();
    }

    /** Tops each equipment item up to 2 copies in storage when materials allow (before guard offers are built). */
    public static void ensureStock(ServerLevel level, Colony colony) {
    }
}
