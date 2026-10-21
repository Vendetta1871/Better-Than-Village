package net.betterthanvillage.society;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.economy.Chests;
import net.betterthanvillage.law.PlayerWar;
import net.betterthanvillage.law.Treaties;
import net.betterthanvillage.law.Wars;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.List;
import java.util.Map;

/**
 * The peace treaty on the elder's trade screen (docs_v2/05-law.md §Peace treaties): emeralds for the treaty book, or,
 * when the country is weary of the war, a white paper for the emeralds it pays (the book comes with them).
 */
final class TreatyTrade {
    private TreatyTrade() {
    }

    static void offers(ServerLevel level, Colony colony, PlayerWar war, Player player, MerchantOffers offers, Map<MerchantOffer, Trading.Desc> descs) {
        if (war.weary) {
            int pay = Treaties.reparation(level, colony, war);
            ItemStack result = pay > 0 ? new ItemStack(Items.EMERALD, pay) : Treaties.book(level, colony, war, 0);
            MerchantOffer offer = new MerchantOffer(new ItemCost(Items.PAPER, 1), result, 1, 0, Trading.COLONY_MULTIPLIER);
            offers.add(offer);
            descs.put(offer, new Trading.Desc(colony.id(), null, Trading.Kind.TREATY_PAID, Items.EMERALD, pay, pay, war.player));
            return;
        }
        int price = Treaties.price(level, war, player);
        MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD, price), Treaties.book(level, colony, war, price), 1, 0, Trading.COLONY_MULTIPLIER);
        if (Treaties.refuses(level, war)) {
            // The country is winning: the treaty shows, but it is not for sale now.
            offer.setToOutOfStock();
        }
        offers.add(offer);
        descs.put(offer, new Trading.Desc(colony.id(), null, Trading.Kind.TREATY, Items.WRITTEN_BOOK, 1, price, war.player));
    }

    static boolean validate(ServerLevel level, Colony colony, Trading.Desc d, List<ColonyStorage.Slot> slots) {
        PlayerWar war = Wars.war(level, colony.country, d.debt());
        if (war == null) return false;
        return switch (d.kind()) {
            case TREATY -> !war.weary && !Treaties.refuses(level, war) && Chests.room(slots, new ItemStack(Items.EMERALD)) >= d.price();
            case TREATY_PAID -> war.weary && Chests.count(slots, s -> s.is(Items.EMERALD)) >= d.price();
            default -> false;
        };
    }

    /** Treasury side of a signed treaty, then peace. */
    static void settle(ServerLevel level, Colony colony, Villager v, Trading.Desc d, List<ColonyStorage.Slot> slots) {
        PlayerWar war = Wars.war(level, colony.country, d.debt());
        if (war == null) return;
        Player player = v.getTradingPlayer();
        int price;
        if (d.kind() == Trading.Kind.TREATY) {
            Chests.put(slots, List.of(new ItemStack(Items.EMERALD, d.price())));
            price = d.price();
        } else {
            if (d.price() > 0) {
                List<ItemStack> money = Chests.take(slots, s -> s.is(Items.EMERALD), d.price());
                if (Chests.total(money) < d.price()) Chests.put(slots, money);
                // The emeralds were the trade's output: the book comes on top.
                if (player != null) {
                    ItemStack book = Treaties.book(level, colony, war, -d.price());
                    if (!player.getInventory().add(book)) player.drop(book, false);
                }
            }
            price = -d.price();
        }
        Wars.peace(level, war, price);
        if (player != null) {
            Msg.chat(player, Msg.message("law.says", Msg.message("law.elder"),
                    Msg.message("law.peace", net.betterthanvillage.law.Law.village(colony), ColonyConfig.get().law.truce_days)));
        }
    }
}
