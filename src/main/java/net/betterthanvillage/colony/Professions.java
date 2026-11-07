package net.betterthanvillage.colony;

import net.betterthanvillage.BetterThanVillage;
import net.betterthanvillage.ai.VillagerUtil;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Fixed professions (docs_v2/02-professions.md): one-time assignment of newcomers, elder succession,
 * inheritance and the villager's look. Professions never change by demand; the only exception is retraining a
 * missing builder trade ({@link #ensureBuilders}).
 */
public final class Professions {
    public static final String ROLE_NAME_TAG = "btv_role_name";
    /** Set once the colony has applied a profession to a villager. */
    public static final String MANAGED_TAG = "btv_managed";
    private static final Set<String> RECOGNISED_NAMES = Set.of("Builder", "Guard", "Militia", "Lumberjack", "Miner", "Farmer", "Fisherman",
            "Rancher", "Elder", "Founder", "Строитель", "Ополченец", "Лесоруб", "Шахтёр", "Фермер", "Рыбак", "Животновод", "Старейшина", "Основатель");
    /** Professions every village must have (02 §Generation rules); the elder is kept separately. */
    public static final List<ColonyRole> GUARANTEED = List.of(ColonyRole.FARMER, ColonyRole.WARRIOR);

    private Professions() {
    }

    /** Spare beds the house planner aims for. */
    public static int targetSpareBeds(int population) {
        return Math.max(4, Math.min(16, (population + 3) / 4));
    }

    // ---------------------------------------------------------------- counts

    public static EnumMap<ColonyRole, Integer> counts(Colony colony, List<Villager> adults) {
        EnumMap<ColonyRole, Integer> counts = new EnumMap<>(ColonyRole.class);
        for (ColonyRole r : ColonyRole.values()) counts.put(r, 0);
        for (Villager v : adults) {
            if (colony.isFounderParty(v.getUUID())) continue;
            counts.merge(colony.roleOf(v.getUUID()), 1, Integer::sum);
        }
        return counts;
    }

    // ---------------------------------------------------------------- assignment

    /**
     * Gives every adult without a profession its profession for life: an inherited profession first, then a
     * vanilla profession converts by the table, an unemployed adult takes a free workstation of the village, else the first missing starter trade,
     * else warrior. The elder is chosen first. Returns the number of villagers assigned.
     */
    public static int assign(ServerLevel level, Colony colony, List<Villager> adults) {
        long now = level.getGameTime();
        maintainElder(level, colony, adults);
        List<Villager> open = new ArrayList<>();
        for (Villager v : adults) {
            if (colony.roleOf(v.getUUID()) == ColonyRole.CITIZEN && !colony.isFounderParty(v.getUUID())) open.add(v);
        }
        if (open.isEmpty()) {
            return 0;
        }
        open.sort(Comparator.comparing(Villager::getUUID));
        EnumMap<ColonyRole, Integer> counts = counts(colony, adults);
        // Vanilla professions first, so free workstations are matched against what is really left.
        List<Villager> unemployed = new ArrayList<>();
        for (Villager v : open) {
            ColonyRole role = colony.pendingProfessions.remove(v.getUUID());
            if (role == null) role = ColonyRole.fromVanilla(v.getVillagerData().getProfession());
            if (role == ColonyRole.ELDER) {
                role = ColonyRole.LIBRARIAN;
            }
            if (role == null) {
                unemployed.add(v);
                continue;
            }
            give(level, colony, v, role, now);
            counts.merge(role, 1, Integer::sum);
        }
        EnumMap<ColonyRole, Integer> sites = freeWorkstations(level, colony, counts);
        for (Villager v : unemployed) {
            ColonyRole role = starterRole(counts, sites);
            give(level, colony, v, role, now);
            counts.merge(role, 1, Integer::sum);
            sites.computeIfPresent(role, (r, n) -> n - 1);
        }
        ColonyManager.markDirty(level);
        return open.size();
    }

    /**
     * Trades the village cannot grow without: only a mason builds houses (and new houses free the beds births need),
     * only a carpenter fells trees once the village has a stock. Births alone cannot bring them back, since a birth
     * needs a free bed, so a village whose vanilla professions mapped to neither would stall for good.
     */
    public static final List<ColonyRole> BUILDERS = List.of(ColonyRole.MASON, ColonyRole.CARPENTER);

    /**
     * Retrains one adult of the most crowded trade (at least two holders) into each missing builder trade
     * (02 §Generation rules, 3): the one exception to fixed professions. Conscripts, the elder and the founder party
     * are never retrained. Returns the number of villagers retrained.
     */
    public static int ensureBuilders(ServerLevel level, Colony colony, List<Villager> adults) {
        long now = level.getGameTime();
        int changed = 0;
        for (ColonyRole missing : BUILDERS) {
            EnumMap<ColonyRole, Integer> counts = tradeCounts(colony, adults);
            if (counts.getOrDefault(missing, 0) > 0) continue;
            Villager donor = null;
            int donorCount = 1;
            for (Villager v : adults) {
                if (colony.isFounderParty(v.getUUID()) || colony.conscripts.containsKey(v.getUUID())) continue;
                ColonyRole trade = colony.tradeOf(v.getUUID());
                if (!ColonyRole.TRADES.contains(trade) || BUILDERS.contains(trade)) continue;
                int n = counts.getOrDefault(trade, 0);
                // Most crowded trade first; ties go to the lowest UUID so the choice is stable between censuses.
                if (n > donorCount || (n == donorCount && donor != null && v.getUUID().compareTo(donor.getUUID()) < 0)) {
                    donor = v;
                    donorCount = n;
                }
            }
            if (donor == null) continue;
            BetterThanVillage.LOGGER.info("[professions] colony {} has no {}: retraining {} {} at {}", colony.id(), missing,
                    colony.tradeOf(donor.getUUID()), donor.getUUID(), donor.blockPosition());
            give(level, colony, donor, missing, now);
            changed++;
        }
        if (changed > 0) ColonyManager.markDirty(level);
        return changed;
    }

    /** Adults per trade (the profession for life, conscripts counted under their trade), founder party excluded. */
    private static EnumMap<ColonyRole, Integer> tradeCounts(Colony colony, List<Villager> adults) {
        EnumMap<ColonyRole, Integer> counts = new EnumMap<>(ColonyRole.class);
        for (Villager v : adults) {
            if (colony.isFounderParty(v.getUUID())) continue;
            counts.merge(colony.tradeOf(v.getUUID()), 1, Integer::sum);
        }
        return counts;
    }

    /** Missing guaranteed trade, else a free workstation (starter order), else a missing starter trade, else warrior. */
    private static ColonyRole starterRole(Map<ColonyRole, Integer> counts, Map<ColonyRole, Integer> sites) {
        for (ColonyRole r : GUARANTEED) {
            if (counts.getOrDefault(r, 0) == 0) return r;
        }
        for (ColonyRole r : ColonyRole.STARTER_ORDER) {
            if (sites.getOrDefault(r, 0) > 0) return r;
        }
        for (ColonyRole r : ColonyRole.STARTER_ORDER) {
            if (counts.getOrDefault(r, 0) == 0) return r;
        }
        return ColonyRole.WARRIOR;
    }

    /** Vanilla workstations in the territory per profession, minus the villagers already holding it. */
    private static EnumMap<ColonyRole, Integer> freeWorkstations(ServerLevel level, Colony colony, Map<ColonyRole, Integer> counts) {
        EnumMap<ColonyRole, Integer> sites = new EnumMap<>(ColonyRole.class);
        PoiManager poi = level.getPoiManager();
        int radius = Math.min(colony.radius(), 96);
        for (VillagerProfession profession : BuiltInRegistries.VILLAGER_PROFESSION) {
            ColonyRole role = ColonyRole.fromVanilla(profession);
            if (role == null || role == ColonyRole.ELDER) continue;
            Predicate<Holder<PoiType>> type = profession.acquirableJobSite();
            long n = poi.getInRange(type, colony.core(), radius, PoiManager.Occupancy.ANY)
                    .filter(rec -> ColonyManager.colonyAt(level, rec.getPos()) == colony).count();
            sites.merge(role, (int) n, Integer::sum);
        }
        sites.replaceAll((r, n) -> Math.max(0, n - counts.getOrDefault(r, 0)));
        return sites;
    }

    private static void give(ServerLevel level, Colony colony, Villager v, ColonyRole role, long now) {
        colony.conscripts.remove(v.getUUID());
        VillagerUtil.stopModTasks(level, v);
        Nav.reset(v);
        colony.setRole(v.getUUID(), role, now);
        applyRole(level, colony, v);
    }

    // ---------------------------------------------------------------- elder

    /**
     * Keeps exactly one elder among the adults (02 §Elder). A missing elder is succeeded by the adult with the best
     * reputation (sum of the gossip other members hold about them); unassigned newcomers go first, and the last
     * holder of a guaranteed trade is only taken when nobody else is left. Founders never become elder.
     */
    public static void maintainElder(ServerLevel level, Colony colony, List<Villager> adults) {
        long now = level.getGameTime();
        BlockPos core = colony.core();
        Comparator<Villager> byCore = Comparator.comparingDouble(v -> v.distanceToSqr(core.getX() + 0.5, core.getY(), core.getZ() + 0.5));
        List<Villager> elders = new ArrayList<>();
        for (Villager v : adults) {
            if (colony.roleOf(v.getUUID()) == ColonyRole.ELDER) elders.add(v);
        }
        if (!elders.isEmpty()) {
            Villager keep = null;
            for (Villager v : elders) {
                if (v.getUUID().equals(colony.elder)) keep = v;
            }
            if (keep == null) keep = elders.stream().min(byCore).orElseThrow();
            for (Villager v : elders) {
                // A second elder (e.g. a merged village) becomes a librarian: the office is not a trade.
                if (v != keep) {
                    colony.setRole(v.getUUID(), ColonyRole.LIBRARIAN, now);
                    applyRole(level, colony, v);
                }
            }
            if (!keep.getUUID().equals(colony.elder)) {
                colony.elder = keep.getUUID();
                ColonyManager.markDirty(level);
            }
            return;
        }
        EnumMap<ColonyRole, Integer> counts = counts(colony, adults);
        List<Villager> candidates = new ArrayList<>();
        for (Villager v : adults) {
            if (!colony.isFounderParty(v.getUUID())) candidates.add(v);
        }
        Comparator<Villager> order = Comparator
                .comparingInt((Villager v) -> {
                    ColonyRole r = colony.roleOf(v.getUUID());
                    if (r == ColonyRole.CITIZEN) return v.getVillagerData().getProfession() == VillagerProfession.CARTOGRAPHER ? 0 : 1;
                    return GUARANTEED.contains(r) && counts.getOrDefault(r, 0) <= 1 ? 3 : 2;
                })
                .thenComparing(Comparator.comparingInt((Villager v) -> reputation(adults, v.getUUID())).reversed())
                .thenComparing(byCore);
        Villager pick = candidates.stream().min(order).orElse(null);
        if (pick == null) {
            if (colony.elder != null) {
                colony.elder = null;
                ColonyManager.markDirty(level);
            }
            return;
        }
        give(level, colony, pick, ColonyRole.ELDER, now);
        colony.elder = pick.getUUID();
        ColonyManager.markDirty(level);
    }

    /** Sum of the gossip the other adults hold about this villager. */
    public static int reputation(List<Villager> adults, UUID target) {
        int total = 0;
        for (Villager v : adults) {
            if (!v.getUUID().equals(target)) total += v.getGossips().getReputation(target, t -> true);
        }
        return total;
    }

    // ---------------------------------------------------------------- inheritance

    /**
     * The profession a child inherits (02 §Inheritance): one parent's trade, 50/50; the elder office is never
     * inherited, so the other parent's trade is used. Null when neither parent has a trade.
     */
    public static ColonyRole inherit(Colony colony, UUID a, UUID b, RandomSource random) {
        ColonyRole ra = trade(colony.tradeOf(a));
        ColonyRole rb = trade(colony.tradeOf(b));
        if (ra == null) return rb;
        if (rb == null) return ra;
        return random.nextBoolean() ? ra : rb;
    }

    private static ColonyRole trade(ColonyRole role) {
        return ColonyRole.TRADES.contains(role) ? role : null;
    }

    // ---------------------------------------------------------------- visuals

    /** Sets villager type, profession and name tag for the villager's profession. */
    public static void applyRole(ServerLevel level, Colony colony, Villager v) {
        // Conscripts look and are named as warriors while they serve (their trade stays theirs); the duty (memory) is
        // what they do.
        ColonyRole duty = colony != null ? colony.roleOf(v.getUUID()) : ColonyRole.CITIZEN;
        ColonyRole role = colony == null ? ColonyRole.CITIZEN : duty == ColonyRole.WARRIOR ? ColonyRole.WARRIOR : colony.tradeOf(v.getUUID());
        if (colony != null) {
            v.addTag(MANAGED_TAG);
        }
        BlockPos typePos = colony != null ? colony.core() : v.blockPosition();
        VillagerType type = VillagerType.byBiome(level.getBiome(typePos));
        VillagerData data = v.getVillagerData();
        if (data.getType() != type) {
            data = data.setType(type);
            v.setVillagerData(data);
        }
        VillagerProfession look = role.profession();
        if (data.getProfession() != look) {
            v.setVillagerData(data.setProfession(look));
        }
        Component name = v.getCustomName();
        boolean tagged = v.getTags().contains(ROLE_NAME_TAG);
        if (name != null && (!tagged || !isRecognisedRoleName(name))) {
            // player-given names win
        } else if (role == ColonyRole.CITIZEN) {
            if (tagged) {
                v.setCustomName(null);
                v.setCustomNameVisible(false);
                v.removeTag(ROLE_NAME_TAG);
            }
        } else {
            v.setCustomName(Component.translatable(role.translationKey()));
            v.setCustomNameVisible(true);
            v.addTag(ROLE_NAME_TAG);
        }
        v.getBrain().setMemory(ModRegistries.COLONY_ROLE.get(), duty);
    }

    private static boolean isRecognisedRoleName(Component name) {
        if (name.getContents() instanceof TranslatableContents t && t.getKey().startsWith("entity.better_than_village.role.")) {
            return true;
        }
        return RECOGNISED_NAMES.contains(name.getString());
    }
}
