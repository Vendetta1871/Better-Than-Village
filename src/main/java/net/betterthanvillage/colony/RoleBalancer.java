package net.betterthanvillage.colony;

import net.betterthanvillage.ai.VillagerUtil;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.construction.Fortifications;
import net.betterthanvillage.defense.RaidShelter;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Demand weights, target apportionment, rebalancing and role visuals. */
public final class RoleBalancer {
    public static final String ROLE_NAME_TAG = "btv_role_name";
    /** Set once the colony has applied a role profession to a villager. */
    public static final String MANAGED_TAG = "btv_managed";
    public static final long ROLE_COOLDOWN = 2400;
    private static final Set<String> RECOGNISED_NAMES = Set.of("Builder", "Guard", "Militia", "Lumberjack", "Miner", "Farmer", "Fisherman",
            "Rancher", "Elder", "Founder", "Строитель", "Ополченец", "Лесоруб", "Шахтёр", "Фермер", "Рыбак", "Животновод", "Старейшина", "Основатель");

    private RoleBalancer() {
    }

    public static int targetSpareBeds(int population) {
        return Math.max(4, Math.min(16, (population + 3) / 4));
    }

    // ---------------------------------------------------------------- demand

    public static EnumMap<ColonyRole, Integer> demand(ServerLevel level, Colony colony) {
        long now = level.getGameTime();
        int p = Math.max(1, colony.lastPopulation);
        int f = colony.cachedFood, l = colony.cachedLogs, s = colony.cachedStone, i = colony.cachedIron;
        int spare = Math.max(0, colony.lastBeds - colony.lastPopulation);
        int t = targetSpareBeds(colony.lastPopulation);
        EnumMap<ColonyRole, Integer> w = new EnumMap<>(ColonyRole.class);
        for (ColonyRole role : ColonyRole.values()) {
            w.put(role, 0);
        }
        w.put(ColonyRole.BUILDER, spare < t ? 100 : colony.hasActiveConstruction() ? 90 : 25);
        boolean lowFood = f < Math.max(48, 8 * p);
        boolean midFood = f < Math.max(96, 20 * p);
        w.put(ColonyRole.FARMER, lowFood ? 75 : midFood ? 60 : 25);
        w.put(ColonyRole.FISHERMAN, lowFood ? 55 : midFood ? 40 : 15);
        w.put(ColonyRole.RANCHER, lowFood ? 40 : midFood ? 30 : 15);
        int wood = Math.max(64, 8 * p);
        w.put(ColonyRole.LUMBERJACK, l < Math.max(16, wood / 4) ? 100 : l * 4 < s ? 100 : l < wood ? 70 : 20);
        int m = Math.max(128, 16 * p);
        int miner = s < m / 4 ? 100 : s < m ? 65 : 15;
        if (i < Math.max(8, p)) {
            miner = Math.max(60, miner);
        }
        w.put(ColonyRole.MINER, miner);
        int guard;
        if (!ColonyConfig.get().roles.enable_guards) {
            guard = 0;
        } else if (colony.recentThreat(now, 2400)) {
            guard = 100;
        } else {
            guard = p >= 12 ? 35 : 20;
        }
        w.put(ColonyRole.GUARD, guard);

        TerrainSurvey.State survey = TerrainSurvey.state(colony);
        if (survey != null) {
            if (survey.published && survey.complete) {
                if (survey.forest == 0) w.put(ColonyRole.LUMBERJACK, Math.min(15, w.get(ColonyRole.LUMBERJACK)));
                if (survey.water == 0) w.put(ColonyRole.FISHERMAN, 10);
                if (survey.soil == 0 && colony.farmland.isEmpty()) w.put(ColonyRole.FARMER, 15);
            }
            if (survey.animals < 2 && colony.livestockPen == null) {
                w.put(ColonyRole.RANCHER, 10);
            }
            if (lowFood && survey.foodDelta < 0) {
                for (ColonyRole r : List.of(ColonyRole.FARMER, ColonyRole.FISHERMAN, ColonyRole.RANCHER)) {
                    if (w.get(r) > 15) w.put(r, Math.min(100, w.get(r) + 15));
                }
            }
            if (l < 32 && survey.logDelta < 0 && w.get(ColonyRole.LUMBERJACK) > 15) {
                w.put(ColonyRole.LUMBERJACK, 100);
            }
            if (s < 96 && survey.stoneDelta < 0) {
                w.put(ColonyRole.MINER, 100);
            }
            if (ColonyConfig.get().roles.enable_village_walls && Fortifications.hasPendingWall(colony)) {
                w.put(ColonyRole.BUILDER, Math.max(60, w.get(ColonyRole.BUILDER)));
                if (s < 256) w.put(ColonyRole.MINER, Math.max(85, w.get(ColonyRole.MINER)));
            }
        }
        return w;
    }

    /** Largest-remainder apportionment of n villagers over the work roles. */
    public static EnumMap<ColonyRole, Integer> targets(Map<ColonyRole, Integer> weights, int n) {
        EnumMap<ColonyRole, Integer> out = new EnumMap<>(ColonyRole.class);
        for (ColonyRole role : ColonyRole.values()) out.put(role, 0);
        if (n <= 0) return out;
        List<ColonyRole> kept = new ArrayList<>();
        int sum = 0;
        for (ColonyRole role : ColonyRole.WORK_ROLES) {
            int w = weights.getOrDefault(role, 0);
            if (w > 0) {
                kept.add(role);
                sum += w;
            }
        }
        int k = kept.size();
        int base = n >= k ? 1 : 0;
        int remaining = n - base * k;
        if (sum == 0) {
            out.put(ColonyRole.CITIZEN, n);
            return out;
        }
        EnumMap<ColonyRole, Double> frac = new EnumMap<>(ColonyRole.class);
        int total = 0;
        for (ColonyRole role : kept) {
            double exact = (double) weights.get(role) * remaining / sum;
            int whole = (int) Math.floor(exact);
            out.put(role, base + whole);
            frac.put(role, exact - whole);
            total += base + whole;
        }
        while (total < n) {
            ColonyRole best = null;
            for (ColonyRole role : kept) {
                double f = frac.get(role);
                if (f < 0) continue;
                if (best == null || f > frac.get(best) || (f == frac.get(best) && weights.get(role) > weights.get(best))) {
                    best = role;
                }
            }
            if (best == null) best = ColonyRole.FARMER;
            out.put(best, out.get(best) + 1);
            frac.put(best, -1.0);
            total++;
        }
        return out;
    }

    // ---------------------------------------------------------------- rebalance

    /**
     * A villager with vanilla trading progress whose profession the colony never managed: it keeps its
     * profession and trades, takes no colony role and does not count for role demand.
     */
    public static boolean isIndependentTrader(Colony colony, Villager v) {
        if (v.getVillagerXp() <= 0 && v.getVillagerData().getLevel() <= 1) return false;
        if (v.getVillagerData().getProfession() == VillagerProfession.NONE || v.getTags().contains(MANAGED_TAG)) return false;
        return colony == null || colony.roleOf(v.getUUID()) == ColonyRole.CITIZEN;
    }

    /** Returns the number of villagers whose role changed. */
    public static int rebalance(ServerLevel level, Colony colony, boolean forced) {
        return rebalance(level, colony, forced, ColonyManager.adultMembers(level, colony));
    }

    public static int rebalance(ServerLevel level, Colony colony, boolean forced, List<Villager> adultMembers) {
        long now = level.getGameTime();
        List<Villager> adults = new ArrayList<>();
        for (Villager v : adultMembers) {
            if (!isIndependentTrader(colony, v)) adults.add(v);
        }
        adults.sort(Comparator.comparing(Villager::getUUID));
        List<Villager> eligible = new ArrayList<>();
        EnumMap<ColonyRole, Integer> current = new EnumMap<>(ColonyRole.class);
        for (ColonyRole role : ColonyRole.values()) current.put(role, 0);
        int citizens = 0;
        for (Villager v : adults) {
            ColonyRole role = colony.roleOf(v.getUUID());
            current.merge(role, 1, Integer::sum);
            if (role == ColonyRole.CITIZEN) citizens++;
            if (role != ColonyRole.ELDER && role != ColonyRole.FOUNDER) eligible.add(v);
        }
        EnumMap<ColonyRole, Integer> weights = demand(level, colony);
        EnumMap<ColonyRole, Integer> targets = targets(weights, eligible.size());
        int p = Math.max(1, colony.lastPopulation);
        boolean foodOk = colony.cachedFood >= Math.max(48, 8 * p);
        int spare = colony.lastBeds - colony.lastPopulation;
        long interval = foodOk && spare != 0 ? 2400 : 1200;
        boolean allowed = forced || (colony.lastProfessionRebalanceTick != Colony.NEVER && now - colony.lastProfessionRebalanceTick >= interval);
        int budget = citizens > 0 ? citizens : (allowed ? 1 : 0);
        List<ColonyRole> order = new ArrayList<>(ColonyRole.WORK_ROLES);
        order.sort(Comparator.comparingInt((ColonyRole r) -> targets.get(r) - current.get(r)).reversed()
                .thenComparing(Comparator.comparingInt((ColonyRole r) -> weights.get(r)).reversed()));
        int changed = 0;
        outer:
        for (ColonyRole role : order) {
            while (current.get(role) < targets.get(role) && budget > 0) {
                Villager pick = null;
                for (Villager v : eligible) {
                    ColonyRole vr = colony.roleOf(v.getUUID());
                    if (vr == role) continue;
                    if (citizens > 0) {
                        if (vr != ColonyRole.CITIZEN) continue;
                    } else {
                        if (current.get(vr) <= targets.get(vr)) continue;
                        if (!canChangeJob(level, colony, v, now)) continue;
                    }
                    if (vr == ColonyRole.CITIZEN) {
                        pick = v;
                        break;
                    }
                    if (pick == null) pick = v;
                }
                if (pick == null) break;
                ColonyRole old = colony.roleOf(pick.getUUID());
                VillagerUtil.stopModTasks(level, pick);
                Nav.reset(pick);
                colony.setRole(pick.getUUID(), role, now);
                current.merge(old, -1, Integer::sum);
                current.merge(role, 1, Integer::sum);
                if (old == ColonyRole.CITIZEN) citizens--;
                changed++;
                budget--;
                if (budget <= 0) break outer;
            }
        }
        if (changed > 0 || colony.lastProfessionRebalanceTick == Colony.NEVER) {
            colony.lastProfessionRebalanceTick = now;
        }
        for (Villager v : adultMembers) {
            applyRole(level, colony, v);
        }
        if (changed > 0) ColonyManager.markDirty(level);
        return changed;
    }

    public static boolean canChangeJob(ServerLevel level, Colony colony, Villager v, long now) {
        if (v.isSleeping() || v.isTrading() || RaidShelter.isActive(level, colony) || colony.isFounderParty(v.getUUID())) return false;
        if (now - colony.roleChangedAt(v.getUUID()) < ROLE_COOLDOWN) return false;
        for (ConstructionJob job : colony.jobs) {
            if (v.getUUID().equals(job.builder) && (job.status == JobStatus.QUEUED || job.status == JobStatus.BUILDING)) return false;
        }
        boolean idle = !VillagerUtil.anyModTaskRunning(v)
                || (now - WorkTracker.lastProgress(v) >= 600 && v.getNavigation().isDone());
        if (!idle) return false;
        int carried = 0;
        for (int i = 0; i < v.getInventory().getContainerSize(); i++) {
            ItemStack s = v.getInventory().getItem(i);
            if (s.is(ItemTags.LOGS) || s.is(ItemTags.STONE_CRAFTING_MATERIALS) || BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().startsWith("raw_")) {
                carried += s.getCount();
            }
        }
        return carried <= (colony.roleOf(v.getUUID()) == ColonyRole.MINER ? 16 : 0);
    }

    // ---------------------------------------------------------------- visuals

    /** Sets villager type, profession and name tag for the villager's colony role. */
    public static void applyRole(ServerLevel level, Colony colony, Villager v) {
        if (isIndependentTrader(colony, v)) {
            // vanilla profession, trades and name stay untouched
            v.getBrain().setMemory(ModRegistries.COLONY_ROLE.get(), ColonyRole.CITIZEN);
            return;
        }
        ColonyRole role = colony != null ? colony.roleOf(v.getUUID()) : ColonyRole.CITIZEN;
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
        if (data.getProfession() != role.profession()) {
            v.setVillagerData(data.setProfession(role.profession()));
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
        v.getBrain().setMemory(ModRegistries.COLONY_ROLE.get(), role);
    }

    private static boolean isRecognisedRoleName(Component name) {
        if (name.getContents() instanceof TranslatableContents t && t.getKey().startsWith("entity.better_than_village.role.")) {
            return true;
        }
        return RECOGNISED_NAMES.contains(name.getString());
    }
}
