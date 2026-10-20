package net.betterthanvillage.economy;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.JobStatus;
import net.betterthanvillage.colony.Professions;
import net.betterthanvillage.colony.SafeEditScope;
import net.betterthanvillage.colony.SpawnSpots;
import net.betterthanvillage.config.ColonyConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Births paid by the parents (docs_v2/03-economy.md §Births and housing). Once a day the village looks for a pair
 * of adults that can afford a house; they pay the treasury for the plot and the house, which comes with the
 * workplace of the child's profession (chosen now, from one parent). The child is born when the house stands and
 * owns its bed and household from the first day. The treasury pays only to keep a small village alive.
 */
public final class Births {
    /** Each parent keeps at least this many emeralds after paying. */
    private static final int PARENT_RESERVE = 2;
    /** An order without a site after this many days is cancelled and refunded. */
    private static final int SITE_DAYS = 3;
    /** Below this many adults the treasury pays for births. */
    private static final int MIN_VIABLE_ADULTS = 4;

    private Births() {
    }

    private static int price() {
        return ColonyConfig.get().economy.birth_house_price;
    }

    /** The open order still waiting for a house site, or null. */
    public static BirthOrder unsited(Colony colony) {
        for (BirthOrder o : colony.birthOrders) {
            if (o.job == null) return o;
        }
        return null;
    }

    /** Daily step (morning gathering): finish orders whose house stands, then maybe place a new one. */
    public static void step(ServerLevel level, Colony colony, Map<UUID, Villager> members) {
        long today = Credit.day(level);
        for (BirthOrder o : new ArrayList<>(colony.birthOrders)) {
            ConstructionJob job = job(colony, o.job);
            if (o.job == null) {
                if (today - o.placedDay > SITE_DAYS) cancel(level, colony, o);
                continue;
            }
            if (job == null || (job.status == JobStatus.BLOCKED && job.retryAfter == 0 && !job.completedOnce)) {
                cancel(level, colony, o);
            } else if (job.isComplete()) {
                deliver(level, colony, o, job);
            }
        }
        if (colony.birthOrders.isEmpty() && level.getGameTime() >= colony.nextBreedingTick) {
            order(level, colony, members, today);
        }
    }

    // ---------------------------------------------------------------- ordering

    private static void order(ServerLevel level, Colony colony, Map<UUID, Villager> members, long today) {
        int population = 0;
        List<Villager> adults = new ArrayList<>();
        for (Villager v : members.values()) {
            population++;
            if (!v.isBaby()) adults.add(v);
        }
        if (population >= ColonyConfig.get().village.max_population_per_bell || !ColonyManager.terrainChangesAllowed(level)) return;
        int price = price();
        // Candidate pairs: fed, not idle poor, with a household; the richest two that can pay.
        List<Household> able = new ArrayList<>();
        java.util.Map<Household, Integer> cash = new java.util.HashMap<>();
        for (Villager v : adults) {
            Household h = colony.households.get(v.getUUID());
            if (h == null || !h.hasChest() || h.idlePoor || h.hungryDays > 0 || colony.isFounderParty(h.owner)) continue;
            int e = Households.emeralds(level, h);
            cash.put(h, e);
            if (e > PARENT_RESERVE) able.add(h);
        }
        able.sort(Comparator.comparingInt((Household h) -> -cash.get(h)));
        if (able.size() >= 2) {
            Household a = able.get(0), b = able.get(1);
            int ea = cash.get(a) - PARENT_RESERVE, eb = cash.get(b) - PARENT_RESERVE;
            if (ea + eb >= price) {
                int pa = Math.min(ea, (int) Math.round(price * (double) ea / (ea + eb)));
                int pb = price - pa;
                if (pb <= eb) {
                    List<ColonyStorage.Slot> treasury = ColonyStorage.treasury(level, colony);
                    int movedA = Treasury.move(ColonyStorage.usable(level, a), treasury, pa);
                    int movedB = Treasury.move(ColonyStorage.usable(level, b), treasury, pb);
                    BirthOrder o = new BirthOrder(UUID.randomUUID());
                    o.parentA = a.owner;
                    o.parentB = b.owner;
                    o.paidA = movedA;
                    o.paidB = movedB;
                    ColonyRole inherited = Professions.inherit(colony, a.owner, b.owner, level.random);
                    o.profession = inherited != null ? inherited : ColonyRole.FARMER;
                    o.greed = Math.max(-0.3f, Math.min(0.3f, (a.greed + b.greed) / 2 + (level.random.nextFloat() - 0.5f) * 0.1f));
                    place(level, colony, o, today);
                    return;
                }
            }
        }
        // Treasury fallback: a village too small or missing a guaranteed trade.
        ColonyRole missing = null;
        for (ColonyRole r : Professions.GUARANTEED) {
            boolean has = colony.pendingProfessions.containsValue(r);
            for (Villager v : adults) has |= colony.tradeOf(v.getUUID()) == r;
            if (!has) {
                missing = r;
                break;
            }
        }
        if (adults.size() >= MIN_VIABLE_ADULTS && missing == null) return;
        List<ColonyStorage.Slot> treasury = ColonyStorage.treasury(level, colony);
        if (Chests.count(treasury, s -> s.is(Items.EMERALD)) < price) return;
        BirthOrder o = new BirthOrder(UUID.randomUUID());
        o.paidTreasury = price;
        o.profession = missing != null ? missing : ColonyRole.FARMER;
        o.greed = (level.random.nextFloat() * 2f - 1f) * 0.3f;
        // The emeralds stay in the treasury, which builds the house as public work.
        place(level, colony, o, today);
    }

    private static void place(ServerLevel level, Colony colony, BirthOrder o, long today) {
        o.placedDay = today;
        colony.birthOrders.add(o);
        colony.nextHousePlanTick = level.getGameTime();
        colony.nextBreedingTick = level.getGameTime() + 24000;
        ColonyManager.markDirty(level);
    }

    private static void cancel(ServerLevel level, Colony colony, BirthOrder o) {
        List<ColonyStorage.Slot> treasury = ColonyStorage.treasury(level, colony);
        refund(level, colony, treasury, o.parentA, o.paidA);
        refund(level, colony, treasury, o.parentB, o.paidB);
        colony.birthOrders.remove(o);
        ColonyManager.markDirty(level);
    }

    private static void refund(ServerLevel level, Colony colony, List<ColonyStorage.Slot> treasury, UUID parent, int amount) {
        Household h = parent == null ? null : colony.households.get(parent);
        if (h != null && amount > 0) Treasury.move(treasury, ColonyStorage.usable(level, h), amount);
    }

    private static ConstructionJob job(Colony colony, UUID id) {
        if (id == null) return null;
        for (ConstructionJob job : colony.jobs) {
            if (job.id.equals(id)) return job;
        }
        return null;
    }

    // ---------------------------------------------------------------- birth

    /** The house stands: its workplace is set up, the child is born by its bed and gets its household. */
    private static void deliver(ServerLevel level, Colony colony, BirthOrder o, ConstructionJob job) {
        BoundingBox box = job.box();
        BlockPos center = box.getCenter();
        if (!level.isLoaded(center)) return;
        if (!o.workplaceDone) {
            placeWorkstation(level, colony, box, o.profession);
            o.workplaceDone = true;
        }
        PoiManager poi = level.getPoiManager();
        int r = Math.max(box.getXSpan(), box.getZSpan());
        BlockPos bed = poi.getInRange(t -> t.is(PoiTypes.HOME), center, r, PoiManager.Occupancy.HAS_SPACE)
                .map(rec -> rec.getPos()).filter(p -> box.isInside(p)).findFirst().orElse(null);
        BlockPos spot = bed != null ? standNear(level, bed) : null;
        if (spot == null) spot = SpawnSpots.find(level, colony, level.getGameTime());
        if (spot == null) return;
        Villager baby = SpawnSpots.createVillager(level, spot, MobSpawnType.BREEDING);
        if (baby == null) return;
        baby.setAge(-24000);
        if (!level.addFreshEntity(baby)) return;
        if (bed != null) {
            BlockPos owned = bed;
            if (poi.take(t -> t.is(PoiTypes.HOME), (t, p) -> p.equals(owned), owned, 1).isPresent()) {
                baby.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), owned));
            }
        }
        colony.pendingProfessions.put(baby.getUUID(), o.profession);
        Households.createChild(level, colony, baby.getUUID(), bed, o.greed);
        for (UUID parent : new UUID[]{o.parentA, o.parentB}) {
            if (parent != null && level.getEntity(parent) instanceof Villager p) p.setAge(6000);
        }
        level.sendParticles(ParticleTypes.HEART, spot.getX() + 0.5, spot.getY() + 1, spot.getZ() + 0.5, 8, 0.3, 0.3, 0.3, 0);
        colony.birthOrders.remove(o);
        ColonyManager.markDirty(level);
    }

    private static BlockPos standNear(ServerLevel level, BlockPos bed) {
        for (BlockPos p : BlockPos.betweenClosed(bed.offset(-2, -1, -2), bed.offset(2, 1, 2))) {
            if (level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                    && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                    && !level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty()) {
                return p.immutable();
            }
        }
        return null;
    }

    /** The vanilla workstation of the child's profession, inside the house (or right by it). */
    static Block workstation(ColonyRole role) {
        return switch (role) {
            case FARMER -> Blocks.COMPOSTER;
            case HERDER -> Blocks.LOOM;
            case MASON -> Blocks.STONECUTTER;
            case CARPENTER -> Blocks.FLETCHING_TABLE;
            case SMITH -> Blocks.BLAST_FURNACE;
            case HEALER -> Blocks.BREWING_STAND;
            case MINER -> Blocks.SMITHING_TABLE;
            case LIBRARIAN -> Blocks.LECTERN;
            case WARRIOR -> Blocks.GRINDSTONE;
            case ELDER -> Blocks.CARTOGRAPHY_TABLE;
            default -> null;
        };
    }

    private static void placeWorkstation(ServerLevel level, Colony colony, BoundingBox box, ColonyRole role) {
        Block block = workstation(role);
        if (block == null) return;
        BoundingBox around = box.inflatedBy(2);
        for (BlockPos p : BlockPos.betweenClosed(around.minX(), around.minY(), around.minZ(), around.maxX(), around.maxY(), around.maxZ())) {
            if (!Households.chestFits(level, colony, p)) continue;
            BlockPos at = p.immutable();
            SafeEditScope.run(() -> level.setBlock(at, block.defaultBlockState(), 3));
            return;
        }
    }
}
