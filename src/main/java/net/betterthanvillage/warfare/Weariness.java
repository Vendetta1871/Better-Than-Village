package net.betterthanvillage.warfare;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.Domain;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.economy.Chests;
import net.betterthanvillage.economy.Debt;
import net.betterthanvillage.economy.Household;
import net.betterthanvillage.politics.BorderOffer;
import net.betterthanvillage.politics.Countries;
import net.betterthanvillage.politics.Diplomacy;
import net.betterthanvillage.politics.Politics;
import net.betterthanvillage.politics.Relation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * War weariness and peace between countries (docs_v2/07-warfare.md §War weariness, docs_v2/06-politics.md §Peace
 * between countries). Each side's weariness grows with the share of its warriors lost (the main factor), what the
 * war cost, its length, villages lost or besieged and unrest at home. When it passes the ruling elder's tolerance
 * (greedy elders fight on longer) the side asks for peace; a winning, much stronger side may refuse. The terms: a
 * tribute to the winner, the disputed border land, debts of the winner written off; then the truce.
 */
public final class Weariness {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Tribute share of the asking capital's emeralds, and its cap. */
    private static final double TRIBUTE_SHARE = 0.25;
    private static final int TRIBUTE_CAP = 48;

    private Weariness() {
    }

    private static ColonyConfig.Warfare cfg() {
        return ColonyConfig.get().warfare;
    }

    public static double weariness(ServerLevel level, Relation r, UUID country) {
        Relation.Side s = r.side(country);
        double share = s.losses / (double) Math.max(1, s.warriors);
        double days = Math.max(0, level.getGameTime() - r.since) / 24000.0;
        int unrest = 0;
        for (Colony v : Countries.villages(level, country)) {
            if (v.loyalty < ColonyConfig.get().politics.disobedience_loyalty) unrest++;
        }
        return 60 * share + 2 * days + 25 * s.villagesLost + 0.5 * s.besiegedHours + s.spent / 4.0 + 5 * unrest;
    }

    /** The ruling elder's tolerance: the configured base, moved by up to 30 by the elder's greed. */
    public static double tolerance(ServerLevel level, UUID country) {
        double t = cfg().weariness_tolerance;
        Colony capital = Countries.capital(level, country);
        if (capital != null && capital.elder != null) {
            Household h = capital.households.get(capital.elder);
            if (h != null) t += 100 * h.greed;
        }
        return Math.max(5, t);
    }

    /** Daily during a war: the sides' weariness, and peace when one has had enough. */
    public static void daily(ServerLevel level, Relation r) {
        r.sideA.weariness = weariness(level, r, r.a);
        r.sideB.weariness = weariness(level, r, r.b);
        for (UUID asker : new UUID[]{r.a, r.b}) {
            UUID other = r.other(asker);
            double w = r.side(asker).weariness, tol = tolerance(level, asker);
            if (w < tol) continue;
            if (refuses(level, r, asker, other)) {
                LOGGER.info("[war] {} asks {} for peace (weariness {} of {}), refused", Countries.plainName(level, asker),
                        Countries.plainName(level, other), Math.round(w), Math.round(tol));
                continue;
            }
            LOGGER.info("[war] {} asks {} for peace (weariness {} of {})", Countries.plainName(level, asker), Countries.plainName(level, other),
                    Math.round(w), Math.round(tol));
            String terms = terms(level, r, asker, other);
            Diplomacy.makePeace(level, r, terms);
            return;
        }
    }

    /** The winner refuses while it is fresh, much stronger and has an army at the asker's walls. */
    private static boolean refuses(ServerLevel level, Relation r, UUID asker, UUID winner) {
        if (r.side(winner).weariness >= tolerance(level, winner) / 2) return false;
        if (Countries.strength(level, winner) < 1.5 * Countries.strength(level, asker)) return false;
        for (Campaign c : Campaigns.of(level, winner)) {
            if (asker.equals(c.defender) && c.phase == Campaign.Phase.SIEGE) return true;
        }
        return false;
    }

    /** The asker pays: a tribute, the disputed land, the winner's debts to it; loyalty follows the outcome. */
    private static String terms(ServerLevel level, Relation r, UUID asker, UUID winner) {
        List<String> out = new ArrayList<>();
        Colony from = Countries.capital(level, asker), to = Countries.capital(level, winner);
        if (from != null && to != null) {
            List<ColonyStorage.Slot> a = ColonyStorage.treasury(level, from), b = ColonyStorage.treasury(level, to);
            int n = Math.min(TRIBUTE_CAP, (int) Math.floor(Chests.count(a, s -> s.is(Items.EMERALD)) * TRIBUTE_SHARE));
            if (!b.isEmpty()) n = Math.min(n, Chests.room(b, new ItemStack(Items.EMERALD)));
            if (n > 0) {
                List<ItemStack> rest = Chests.put(b, Chests.take(a, s -> s.is(Items.EMERALD), n));
                int paid = n - Chests.total(rest);
                if (!rest.isEmpty()) Chests.put(a, rest);
                if (paid > 0) out.add("tribute " + paid);
            }
        }
        ColonySavedData data = ColonyManager.data(level);
        // The disputed border land goes to the winner.
        for (BorderOffer o : new ArrayList<>(data.borderOffers)) {
            if (!o.buyer.equals(winner) || !o.owner.equals(asker)) continue;
            int moved = 0;
            for (long ch : o.chunks) {
                ChunkPos pos = new ChunkPos(ch);
                if (asker.equals(Domain.countryAt(level, pos))) {
                    Domain.transfer(level, pos, winner);
                    moved++;
                }
            }
            data.borderOffers.remove(o);
            out.add(moved + " border chunks");
        }
        // Debts the winner's villages owed the asker's are written off.
        int notes = 0;
        for (Debt d : new ArrayList<>(data.debts.values())) {
            if (d.creditor.kind() != Debt.Kind.TREASURY || d.debtor.kind() != Debt.Kind.TREASURY) continue;
            Colony cr = data.colony(d.creditor.colony()), db = data.colony(d.debtor.colony());
            if (cr != null && db != null && asker.equals(cr.country) && winner.equals(db.country)) {
                data.debts.remove(d.id);
                notes++;
            }
        }
        if (notes > 0) out.add(notes + " debts written off");
        // Defeat costs loyalty, a shared victory wins it.
        for (Colony v : Countries.villages(level, asker)) Politics.adjustLoyalty(level, v, -5);
        for (Colony v : Countries.villages(level, winner)) Politics.adjustLoyalty(level, v, 3);
        ColonyManager.markDirty(level);
        return out.isEmpty() ? "plain truce" : String.join(", ", out);
    }

    /**
     * Daily: in every war, a side without an army in the field sends one when it is strong enough: the aggressor at
     * four fifths of the enemy's strength, the defender (a counter-attack) only when stronger.
     */
    public static void campaigns(ServerLevel level) {
        if (!cfg().campaigns) return;
        for (Relation r : new ArrayList<>(ColonyManager.data(level).relations.values())) {
            if (r.state != Relation.State.WAR) continue;
            for (UUID side : new UUID[]{r.a, r.b}) {
                UUID other = r.other(side);
                boolean active = false;
                for (Campaign c : Campaigns.of(level, side)) if (other.equals(c.defender)) active = true;
                if (active) continue;
                double need = side.equals(r.aggressor) ? 0.8 : 1.2;
                if (Countries.strength(level, side) >= need * Countries.strength(level, other)) Campaigns.launch(level, side, other);
            }
        }
    }
}
