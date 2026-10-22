package net.betterthanvillage.warfare;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.Domain;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.economy.Chests;
import net.betterthanvillage.economy.Debt;
import net.betterthanvillage.economy.Household;
import net.betterthanvillage.politics.Countries;
import net.betterthanvillage.politics.Diplomacy;
import net.betterthanvillage.politics.Relation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Conquest (docs_v2/06-politics.md §Conquest): when a village's warriors are all destroyed and the attackers hold its
 * bell, its people become citizens of the winner with their houses, chests and professions; its treasury emeralds go
 * to the winner's capital as loot and the debt notes it held now pay that treasury; its share of the domain passes to
 * the winner; the old elder is deposed and the adult with the best standing takes the office; loyalty starts low.
 */
public final class Conquest {
    private static final Logger LOGGER = LogUtils.getLogger();

    private Conquest() {
    }

    public static void conquer(ServerLevel level, Campaign c, Colony village) {
        UUID winner = c.attacker, loser = village.country;
        ColonySavedData data = ColonyManager.data(level);
        Colony capital = Countries.capital(level, winner);
        if (capital == null || loser == null || loser.equals(winner)) return;
        boolean wasCapital = Countries.isCapital(level, village);
        Relation r = Diplomacy.relation(level, winner, loser);
        r.side(loser).villagesLost++;
        // Loot: the treasury's emeralds.
        int loot = 0;
        if (capital != village) {
            List<ColonyStorage.Slot> from = ColonyStorage.treasury(level, village), to = ColonyStorage.treasury(level, capital);
            int n = Chests.count(from, s -> s.is(Items.EMERALD));
            if (!to.isEmpty()) n = Math.min(n, Chests.room(to, new ItemStack(Items.EMERALD)));
            if (n > 0) {
                List<ItemStack> rest = Chests.put(to, Chests.take(from, s -> s.is(Items.EMERALD), n));
                loot = n - Chests.total(rest);
                if (!rest.isEmpty()) Chests.put(from, rest);
            }
        }
        // Debt notes held by the village's treasury now pay the winner's capital.
        int notes = 0;
        for (Debt d : new ArrayList<>(data.debts.values())) {
            if (d.creditor.kind() != Debt.Kind.TREASURY || !village.id().equals(d.creditor.colony())) continue;
            if (d.debtor.kind() == Debt.Kind.TREASURY && capital.id().equals(d.debtor.colony())) {
                // The winner owed the village: the note is torn up.
                data.debts.remove(d.id);
                notes++;
                continue;
            }
            Debt moved = new Debt(d.id, Debt.Party.treasury(capital.id()), d.debtor);
            moved.principal = d.principal;
            moved.balance = d.balance;
            moved.rate = d.rate;
            moved.createdDay = d.createdDay;
            moved.dueDay = d.dueDay;
            moved.lastAccrualDay = d.lastAccrualDay;
            moved.strikes = d.strikes;
            moved.seizable = d.seizable;
            moved.debtorName = d.debtorName;
            moved.creditorName = "Treasury " + capital.core().getX() + "," + capital.core().getZ();
            data.debts.put(d.id, moved);
            notes++;
        }
        // The village's share of the domain.
        int chunks = 0;
        int cap = ColonyConfig.get().land.domain_cap;
        ChunkPos center = new ChunkPos(village.core());
        List<Colony> loserVillages = Countries.villages(level, loser);
        for (int dx = -(cap >> 4) - 1; dx <= (cap >> 4) + 1; dx++) {
            for (int dz = -(cap >> 4) - 1; dz <= (cap >> 4) + 1; dz++) {
                ChunkPos ch = new ChunkPos(center.x + dx, center.z + dz);
                if (!loser.equals(Domain.countryAt(level, ch)) || nearestOf(loserVillages, ch) != village) continue;
                Domain.transfer(level, ch, winner);
                chunks++;
            }
        }
        // The old elder is deposed; the adult with the best standing becomes the new elder.
        UUID deposed = village.elder;
        if (deposed != null) {
            village.setRole(deposed, ColonyRole.LIBRARIAN, level.getGameTime());
            village.elder = null;
        }
        UUID elder = successor(level, village, deposed);
        if (elder != null) {
            village.conscripts.remove(elder);
            village.setRole(elder, ColonyRole.ELDER, level.getGameTime());
            village.elder = elder;
            if (level.getEntity(elder) instanceof Villager v) net.betterthanvillage.colony.Professions.applyRole(level, village, v);
        }
        if (deposed != null && level.getEntity(deposed) instanceof Villager v) net.betterthanvillage.colony.Professions.applyRole(level, village, v);
        int loyalty = ColonyConfig.get().politics.conquered_loyalty;
        Countries.join(level, village, winner, loyalty);
        if (wasCapital) Countries.get(level, loser);
        Countries.prune(level);
        ColonyManager.markDirty(level);
        LOGGER.info("[war] {} conquers {}: loot {} emeralds, {} debt notes, {} domain chunks, new elder {}", Countries.plainName(level, winner),
                Countries.villageName(village), loot, notes, chunks, elder == null ? "-" : elder.toString().substring(0, 8));
        Diplomacy.announce(level, Component.translatable("message.better_than_village.war.conquest", Countries.name(level, winner),
                Component.literal(Countries.villageName(village))), winner, loser);
        Campaigns.retreat(level, c, "the village is taken");
    }

    private static Colony nearestOf(List<Colony> villages, ChunkPos ch) {
        Colony best = null;
        double d = Double.MAX_VALUE;
        for (Colony v : villages) {
            double dx = ch.getMiddleBlockX() - v.core().getX(), dz = ch.getMiddleBlockZ() - v.core().getZ();
            double x = dx * dx + dz * dz;
            if (x < d) {
                d = x;
                best = v;
            }
        }
        return best;
    }

    /**
     * The new elder: among the adults of the village, a villager with a live entity is judged by the gossip of its
     * neighbours; otherwise the oldest household (the longest standing in the village) takes the office.
     */
    private static UUID successor(ServerLevel level, Colony village, UUID deposed) {
        UUID best = null;
        long bestScore = Long.MIN_VALUE;
        List<Villager> members = ColonyManager.isLive(level, village) ? ColonyManager.adultMembers(level, village) : List.of();
        for (Household h : village.households.values()) {
            UUID id = h.owner;
            if (id.equals(deposed) || village.roleOf(id) == ColonyRole.WARRIOR || village.isFounderParty(id)) continue;
            if (Campaigns.away(level, id) || net.betterthanvillage.world.Caravans.away(level, id)) continue;
            long score = -h.founded;
            if (!members.isEmpty()) {
                int rep = 0;
                for (Villager other : members) rep += other.getGossips().getReputation(id, t -> true);
                score = rep * 1_000_000L - h.founded / 1000;
            }
            if (score > bestScore) {
                bestScore = score;
                best = id;
            }
        }
        return best;
    }
}
