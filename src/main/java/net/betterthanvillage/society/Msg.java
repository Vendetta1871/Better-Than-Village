package net.betterthanvillage.society;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;

/** Translatable player-facing texts of the society subsystem (keys in lang/en_us.json and ru_ru.json). */
final class Msg {
    static final String ITEM = "item.better_than_village.";
    static final String MESSAGE = "message.better_than_village.";
    static final String COMMAND = "commands.better_than_village.";

    private Msg() {
    }

    static MutableComponent item(String key, Object... args) {
        return Component.translatable(ITEM + key, args);
    }

    static MutableComponent message(String key, Object... args) {
        return Component.translatable(MESSAGE + key, args);
    }

    static MutableComponent command(String key, Object... args) {
        return Component.translatable(COMMAND + key, args);
    }

    static void actionBar(Player player, Component text) {
        player.displayClientMessage(text, true);
    }

    static void chat(Player player, Component text) {
        player.displayClientMessage(text, false);
    }

    static String xyz(net.minecraft.core.BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
