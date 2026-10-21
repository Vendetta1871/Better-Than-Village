package net.betterthanvillage.law;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.economy.Chests;
import net.betterthanvillage.economy.Credit;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.List;

/**
 * Peace treaties (docs_v2/05-law.md §Peace treaties), bought through the vanilla trade screen of an elder of the
 * warring country. The price follows the balance of strength; a country that just killed the player refuses; a weary
 * country pays the player instead. The written book is only a receipt: the treaty is stored on the server.
 */
public final class Treaties {
    /** A country refuses peace this long after its warriors killed the player, while it is ahead. */
    static final long REFUSE_AFTER_KILL = 6000;

    private Treaties() {
    }

    /** Emeralds the player pays: the base price times the country's strength over the player's (a quarter to four times). */
    public static int price(ServerLevel level, PlayerWar war, Player player) {
        double ratio = (double) Wars.countryStrength(level, war.country) / Math.max(1, Wars.playerStrength(player, war));
        ratio = Math.max(0.25, Math.min(4, ratio));
        return Math.max(1, Math.min(64, (int) Math.round(ColonyConfig.get().law.treaty_base_price * ratio)));
    }

    /** The country is winning: it killed the player recently and lost less than it took. */
    public static boolean refuses(ServerLevel level, PlayerWar war) {
        long now = level.getGameTime();
        return !war.weary && war.lastPlayerDeath >= 0 && now - war.lastPlayerDeath >= 0 && now - war.lastPlayerDeath < REFUSE_AFTER_KILL
                && war.playerDeaths > war.losses;
    }

    /** Emeralds a weary country pays for peace: two per loss and some, as far as the village's treasury goes. */
    public static int reparation(ServerLevel level, Colony colony, PlayerWar war) {
        int wanted = Math.min(64, 2 + 2 * war.losses);
        int cash = Chests.count(ColonyStorage.treasury(level, colony), s -> s.is(Items.EMERALD));
        return Math.max(0, Math.min(wanted, cash));
    }

    /** What the elder answers a surrendering player. */
    static Component terms(ServerLevel level, Colony colony, PlayerWar war, Player player) {
        if (war.weary) return Law.message("ceasefire.weary", reparation(level, colony, war));
        if (refuses(level, war)) return Law.message("ceasefire.refused");
        return Law.message("ceasefire", price(level, war, player));
    }

    /** The treaty book: parties, date, price and truce. price < 0: the country paid. */
    public static ItemStack book(ServerLevel level, Colony colony, PlayerWar war, int price) {
        long day = Credit.day(level);
        long truceDay = day + ColonyConfig.get().law.truce_days;
        Component paid = price >= 0 ? Law.message("treaty.paid_by_player", price) : Law.message("treaty.paid_by_country", -price);
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        Component page = Law.message("treaty.page", Law.village(colony), war.playerName, day, paid, truceDay);
        String title = Component.translatable("message.better_than_village.law.treaty.title").getString();
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(title.length() > 32 ? title.substring(0, 32) : title),
                Law.village(colony).getString(), 0, List.of(Filterable.passThrough(page)), true));
        return book;
    }
}
