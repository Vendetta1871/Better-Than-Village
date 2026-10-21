package net.betterthanvillage.politics;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.Domain;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.economy.Chests;
import net.betterthanvillage.economy.Credit;
import net.betterthanvillage.economy.Debt;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Border changes by treaty (docs_v2/06-politics.md §Border changes): a search party wants a site inside a foreign
 * domain, its country offers to buy the chunks around it, the owner accepts or refuses. A refusal, or a buyer who
 * cannot pay, is kept as a border dispute (a cause of war) and the buyer waits before asking again.
 */
public final class Borders {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Days a buyer waits after a refusal before offering again. */
    private static final long RETRY_DAYS = 3;

    private Borders() {
    }

    private static ColonyConfig.Politics cfg() {
        return ColonyConfig.get().politics;
    }

    /** The owner's chunks a new village at the site would need (its largest territory). */
    public static List<ChunkPos> chunksFor(ServerLevel level, BlockPos site, UUID owner) {
        int r = (int) Math.ceil(Math.sqrt(ColonyConfig.get().land.territory_max_area / Math.PI));
        List<ChunkPos> out = new ArrayList<>();
        ChunkPos center = new ChunkPos(site);
        int rc = (r >> 4) + 1;
        for (int dx = -rc; dx <= rc; dx++) {
            for (int dz = -rc; dz <= rc; dz++) {
                ChunkPos c = new ChunkPos(center.x + dx, center.z + dz);
                double bx = c.getMiddleBlockX() - site.getX(), bz = c.getMiddleBlockZ() - site.getZ();
                if (bx * bx + bz * bz > (double) r * r) continue;
                if (owner.equals(Domain.countryAt(level, c))) out.add(c);
            }
        }
        return out;
    }

    /**
     * The buyer's country offers to buy the land for a site. Returns true if the land now belongs to the buyer.
     */
    public static boolean acquire(ServerLevel level, Colony buyer, BlockPos site) {
        UUID owner = Domain.countryAt(level, new ChunkPos(site));
        if (owner == null || owner.equals(buyer.country) || buyer.country == null) return true;
        ColonySavedData data = ColonyManager.data(level);
        long now = level.getGameTime();
        for (BorderOffer o : data.borderOffers) {
            if (o.buyer.equals(buyer.country) && o.owner.equals(owner) && now - o.made < RETRY_DAYS * 24000L) return false;
        }
        if (Countries.get(level, owner) == null) {
            // A generated village that never registered: nobody can sell its land yet.
            return false;
        }
        List<ChunkPos> chunks = chunksFor(level, site, owner);
        int price = price(level, owner, site, chunks.size());
        BorderOffer offer = new BorderOffer();
        offer.buyer = buyer.country;
        offer.owner = owner;
        offer.colony = buyer.id();
        offer.site = site.immutable();
        offer.chunks = chunks.stream().mapToLong(ChunkPos::toLong).toArray();
        offer.price = price;
        offer.made = now;
        String why = decline(level, buyer.country, owner, site);
        if (why != null) {
            offer.refused = true;
            refuse(level, offer, why);
            return false;
        }
        if (!pay(level, buyer, owner, price)) {
            offer.refused = false;
            refuse(level, offer, "the buyer cannot pay " + price);
            return false;
        }
        for (ChunkPos c : chunks) Domain.transfer(level, c, buyer.country);
        data.borderOffers.removeIf(o -> o.buyer.equals(buyer.country) && o.owner.equals(owner));
        Diplomacy.traded(level, buyer.country, owner);
        LOGGER.info("[politics] {} sells {} border chunks around {} to {} for {}", Countries.plainName(level, owner), chunks.size(),
                site.toShortString(), Countries.plainName(level, buyer.country), price);
        return true;
    }

    private static void refuse(ServerLevel level, BorderOffer offer, String why) {
        ColonySavedData data = ColonyManager.data(level);
        data.borderOffers.removeIf(o -> o.buyer.equals(offer.buyer) && o.owner.equals(offer.owner));
        data.borderOffers.add(offer);
        data.setDirty();
        if (offer.refused) Diplomacy.incident(level, offer.buyer, offer.owner, 5);
        LOGGER.info("[politics] {} does not get the land around {} from {}: {}", Countries.plainName(level, offer.buyer),
                offer.site.toShortString(), Countries.plainName(level, offer.owner), why);
    }

    /** Price: chunks times the base price, more the closer the site is to the owner's villages. */
    static int price(ServerLevel level, UUID owner, BlockPos site, int chunks) {
        double nearest = nearestVillage(level, owner, site);
        double closeness = 1 - Math.min(1, nearest / ColonyConfig.get().land.domain_cap);
        return (int) Math.ceil(chunks * cfg().border_price_per_chunk * (1 + 3 * closeness));
    }

    private static double nearestVillage(ServerLevel level, UUID owner, BlockPos site) {
        double best = Double.MAX_VALUE;
        for (Colony c : Countries.villages(level, owner)) {
            best = Math.min(best, Math.sqrt(c.core().distSqr(site)));
        }
        return best;
    }

    /**
     * The owner's decision: never during war or hostility, not with bad relations unless the buyer is much
     * stronger, and not while its own villages are full (it needs the land for its own search parties). Returns the reason for a refusal, or null to accept.
     */
    static String decline(ServerLevel level, UUID buyer, UUID owner, BlockPos site) {
        Relation.State state = Diplomacy.state(level, buyer, owner);
        if (state == Relation.State.WAR || state == Relation.State.HOSTILITY) return "relations are " + state.key();
        double score = Diplomacy.relation(level, buyer, owner).score;
        int ratio = Countries.strength(level, buyer) * 10 / Math.max(1, Countries.strength(level, owner));
        if (score < -10 && !(ratio >= 20 && score >= -30)) return "relations are bad (" + Math.round(score) + ")";
        // A country whose villages are full keeps its free land for its own search parties.
        for (Colony c : Countries.villages(level, owner)) {
            if (net.betterthanvillage.colony.Territory.atMax(c)) return "the land is needed by its own villages";
        }
        return null;
    }

    /** The buyer's treasury pays the owner's capital; what it lacks becomes a debt between the two treasuries. */
    private static boolean pay(ServerLevel level, Colony buyer, UUID owner, int price) {
        Colony capital = Countries.capital(level, owner);
        if (capital == null) return false;
        List<ColonyStorage.Slot> from = ColonyStorage.treasury(level, buyer);
        List<ColonyStorage.Slot> to = ColonyStorage.treasury(level, capital);
        int cash = Math.min(price, Chests.count(from, s -> s.is(Items.EMERALD)));
        if (!to.isEmpty()) cash = Math.min(cash, Chests.room(to, new ItemStack(Items.EMERALD)));
        else cash = 0;
        int owed = price - cash;
        // A debt note for at most half the price.
        if (owed * 2 > price) return false;
        if (cash > 0) Chests.put(to, Chests.take(from, s -> s.is(Items.EMERALD), cash));
        if (owed > 0) {
            Debt.Party creditor = Debt.Party.treasury(capital.id()), debtor = Debt.Party.treasury(buyer.id());
            String cn = "Treasury " + capital.core().getX() + "," + capital.core().getZ(), dn = "Treasury " + buyer.core().getX() + "," + buyer.core().getZ();
            Credit.lend(level, creditor, debtor, owed, to.isEmpty() ? null : to, cn, dn);
        }
        return true;
    }

    public static List<String> status(ServerLevel level) {
        List<String> out = new ArrayList<>();
        for (BorderOffer o : ColonyManager.data(level).borderOffers) {
            out.add("border dispute: " + Countries.plainName(level, o.buyer) + " wants " + o.chunks.length + " chunks around "
                    + (o.site == null ? "-" : o.site.toShortString()) + " of " + Countries.plainName(level, o.owner) + " for " + o.price
                    + (o.refused ? " (refused)" : " (could not pay)"));
        }
        return out;
    }
}
