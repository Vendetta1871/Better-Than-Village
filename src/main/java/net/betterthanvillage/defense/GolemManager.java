package net.betterthanvillage.defense;

import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.ai.nav.WaypointNavigator;
import net.betterthanvillage.ai.work.ToolSupply;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonySavedData;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.PathBudget;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.registry.ModTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/** Iron golem management: count, census build, guard heal/build duty, golem tick and pillar breaker (02 §2.3, §3.6). */
public final class GolemManager {
    public static final int IRON_COST = 36;
    private static final Predicate<ItemStack> PUMPKIN = s -> s.is(Items.PUMPKIN) || s.is(Items.CARVED_PUMPKIN);
    private static final Predicate<ItemStack> IRON = s -> s.is(Items.IRON_INGOT);
    private static final Direction[] NESW = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private GolemManager() {
    }

    // ---------------------------------------------------------------- shared definitions

    /** Living iron golems in the core box expanded by radius on all three axes. */
    public static List<IronGolem> golems(ServerLevel level, Colony colony) {
        return level.getEntitiesOfClass(IronGolem.class, new AABB(colony.core()).inflate(colony.radius()), LivingEntity::isAlive);
    }

    /** The village keeps at most this many golems of its own (player golems never count). */
    public static int desired(Colony colony) {
        return ColonyConfig.get().warfare.golem_cap;
    }

    /** The village's own golems: not player-built, not an army's, standing in its territory or away on campaign. */
    public static int villageGolems(ServerLevel level, Colony colony) {
        int n = 0;
        for (IronGolem g : golems(level, colony)) {
            if (!g.isPlayerCreated() && !g.getPersistentData().getBoolean(net.betterthanvillage.warfare.Campaigns.GOLEM_TAG)) n++;
        }
        if (colony.golem != null && net.betterthanvillage.warfare.Campaigns.away(level, colony.golem)) n++;
        return n;
    }

    /** Census: remembers the village golem (the abstract strength model counts it while the village is unloaded). */
    public static void track(ServerLevel level, Colony colony) {
        if (colony.golem != null && net.betterthanvillage.warfare.Campaigns.away(level, colony.golem)) return;
        UUID found = null;
        for (IronGolem g : golems(level, colony)) {
            if (g.isPlayerCreated() || g.getPersistentData().getBoolean(net.betterthanvillage.warfare.Campaigns.GOLEM_TAG)) continue;
            if (ColonyManager.colonyAt(level, g.blockPosition()) != colony) continue;
            if (g.getUUID().equals(colony.golem)) {
                found = g.getUUID();
                break;
            }
            if (found == null) found = g.getUUID();
        }
        if (!java.util.Objects.equals(found, colony.golem)) {
            colony.golem = found;
            ColonyManager.markDirty(level);
        }
    }

    private static long serverTicks(ServerLevel level) {
        return level.getServer().getTickCount();
    }

    public static boolean canBuild(ServerLevel level, Colony colony) {
        return serverTicks(level) >= colony.nextGolemTick && villageGolems(level, colony) < desired(colony);
    }

    /** Any colony golem is hurt, or a new golem may be built and the storage holds its materials. */
    public static boolean workNeeded(ServerLevel level, Colony colony) {
        for (IronGolem g : golems(level, colony)) {
            if (g.getHealth() < g.getMaxHealth() && ThreatManager.inColony(colony, g)) return true;
        }
        return canBuild(level, colony) && ColonyStorage.has(level, colony, PUMPKIN)
                && ColonyStorage.count(level, colony, s -> s.is(Items.IRON_INGOT) || s.is(Items.RAW_IRON)) >= IRON_COST;
    }

    /**
     * 2x2 footprint on natural ground (never a roof or a floor of a building) without fluid, three air
     * cells high, and no entity in the way.
     */
    public static boolean validSite(ServerLevel level, BlockPos pos) {
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                BlockPos cell = pos.offset(dx, 0, dz);
                BlockPos below = cell.below();
                if (!level.isLoaded(below) || !level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                        || !level.getFluidState(below).isEmpty() || !naturalGround(level.getBlockState(below))) {
                    return false;
                }
                for (int dy = 0; dy <= 2; dy++) {
                    if (!level.getBlockState(cell.above(dy)).isAir()) return false;
                }
            }
        }
        AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 2, pos.getY() + 3, pos.getZ() + 2);
        return level.getEntities((net.minecraft.world.entity.Entity) null, box, e -> true).isEmpty();
    }

    private static boolean naturalGround(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.GRAVEL)
                || s.is(Blocks.DIRT_PATH) || s.is(Blocks.SNOW_BLOCK) || s.is(BlockTags.TERRACOTTA) || s.is(Blocks.CLAY)
                || s.is(Blocks.MUD) || s.is(Blocks.PACKED_ICE);
    }

    /** First standable cell 2..4 blocks from the site in N, E, S, W order. */
    public static BlockPos standSpot(ServerLevel level, BlockPos site) {
        for (int d = 2; d <= 4; d++) {
            for (Direction dir : NESW) {
                BlockPos c = site.relative(dir, d);
                if (Nav.standable(level, c)) return c;
            }
        }
        return null;
    }

    /** Within the x/z footprint (+margin) of an active construction job. */
    public static boolean nearActiveJob(Colony colony, BlockPos pos, int margin) {
        for (ConstructionJob job : colony.jobs) {
            if (!job.isActive()) continue;
            BoundingBox b = job.box();
            if (pos.getX() >= b.minX() - margin && pos.getX() <= b.maxX() + margin
                    && pos.getZ() >= b.minZ() - margin && pos.getZ() <= b.maxZ() + margin) {
                return true;
            }
        }
        return false;
    }

    private static BlockPos surface(ServerLevel level, int x, int z) {
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    /** Spawns a non-player-created, persistent golem centred on the site; null on failure. */
    private static IronGolem spawnGolem(ServerLevel level, BlockPos site) {
        IronGolem golem = EntityType.IRON_GOLEM.create(level, null, site, MobSpawnType.MOB_SUMMONED, false, false);
        if (golem == null) return null;
        golem.moveTo(site.getX() + 1.0, site.getY(), site.getZ() + 1.0, golem.getYRot(), golem.getXRot());
        golem.setPlayerCreated(false);
        golem.setPersistenceRequired();
        return level.addFreshEntity(golem) ? golem : null;
    }

    private static boolean hasMaterials(ServerLevel level, Colony colony) {
        if (!ColonyStorage.has(level, colony, PUMPKIN)) return false;
        ToolSupply.smeltTo(level, colony, IRON_COST);
        return ColonyStorage.count(level, colony, IRON) >= IRON_COST;
    }

    private static void consumeMaterials(ServerLevel level, Colony colony) {
        ColonyStorage.consume(level, colony, IRON, IRON_COST);
        ColonyStorage.consume(level, colony, PUMPKIN, 1);
    }

    // ---------------------------------------------------------------- A. census build

    /** Called at every census: the guard nearest to the core builds a golem next to itself. */
    public static void step(ServerLevel level, Colony colony) {
        if (!canBuild(level, colony)) return;
        Villager guard = null;
        double best = Double.MAX_VALUE;
        for (Villager v : ColonyManager.adultMembers(level, colony)) {
            if (colony.roleOf(v.getUUID()) != ColonyRole.WARRIOR) continue;
            double d = v.blockPosition().distSqr(colony.core());
            if (d < best) {
                best = d;
                guard = v;
            }
        }
        if (guard == null || !hasMaterials(level, colony)) return;
        BlockPos site = null;
        for (int r = 3; r <= 12 && site == null; r++) {
            for (Direction dir : NESW) {
                BlockPos column = guard.blockPosition().relative(dir, r);
                if (!level.isLoaded(column)) continue;
                BlockPos pos = surface(level, column.getX(), column.getZ());
                // near the guard's own ground level: no building next to it on a cliff or roof
                if (Math.abs(pos.getY() - guard.getBlockY()) <= 8 && validSite(level, pos) && !nearActiveJob(colony, pos, 3)) site = pos;
            }
        }
        if (site == null || spawnGolem(level, site) == null) return;
        consumeMaterials(level, colony);
        guard.swing(InteractionHand.MAIN_HAND);
        level.sendParticles(ParticleTypes.POOF, site.getX() + 0.5, site.getY() + 1, site.getZ() + 0.5, 12, 0.8, 1.0, 0.8, 0.02);
        colony.nextGolemTick = serverTicks(level) + 300;
        ColonyManager.markDirty(level);
    }

    // ---------------------------------------------------------------- B. guard duty

    /** Guard-driven golem work: heal a damaged golem or build a new one (sub-state of the equipment task). */
    public static final class Duty {
        private IronGolem healTarget;
        private BlockPos site;
        private BlockPos stand;
        private long buildAt;

        public boolean active() {
            return healTarget != null || site != null;
        }

        public void reset() {
            healTarget = null;
            site = null;
            stand = null;
            buildAt = 0;
        }

        /** Plans golem work at the storage; true if something was planned. */
        public boolean plan(ServerLevel level, Colony colony, Villager guard) {
            long now = level.getGameTime();
            IronGolem hurt = null;
            double best = Double.MAX_VALUE;
            for (IronGolem g : golems(level, colony)) {
                // golems outside the colony circle never regenerate and may be out of reach
                if (g.getHealth() >= g.getMaxHealth() || !ThreatManager.inColony(colony, g)) continue;
                double d = g.distanceToSqr(guard);
                if (d < best) {
                    best = d;
                    hurt = g;
                }
            }
            if (hurt != null) {
                if (!carriesIron(guard)) {
                    if (!hasEmptySlot(guard.getInventory())) return false;
                    ToolSupply.smeltTo(level, colony, 1);
                    ItemStack ingot = ColonyStorage.withdrawOne(level, colony, IRON);
                    if (ingot.isEmpty()) return false;
                    guard.getInventory().addItem(ingot);
                }
                healTarget = hurt;
                return true;
            }
            if (!canBuild(level, colony) || !hasMaterials(level, colony)) return false;
            BlockPos core = colony.core();
            BlockPos bestSite = null;
            BlockPos bestStand = null;
            double bestScore = Double.MAX_VALUE;
            for (int dx = -32; dx <= 32; dx++) {
                for (int dz = -32; dz <= 32; dz++) {
                    int d2 = dx * dx + dz * dz;
                    if (d2 < 16 || d2 > 1024) continue;
                    int x = core.getX() + dx;
                    int z = core.getZ() + dz;
                    if (!level.hasChunkAt(new BlockPos(x, 0, z))) continue;
                    BlockPos pos = surface(level, x, z);
                    double score = pos.distSqr(guard.blockPosition()) + 0.2 * pos.distSqr(core);
                    if (score >= bestScore || !validSite(level, pos) || nearActiveJob(colony, pos, 3)) continue;
                    BlockPos s = standSpot(level, pos);
                    if (s == null) continue;
                    bestSite = pos;
                    bestStand = s;
                    bestScore = score;
                }
            }
            if (bestSite == null) return false;
            site = bestSite;
            stand = bestStand;
            buildAt = now + 60;
            return true;
        }

        /** One tick of the planned work; true when finished. */
        public boolean step(ServerLevel level, Colony colony, Villager guard) {
            long now = level.getGameTime();
            if (healTarget != null) {
                IronGolem g = healTarget;
                if (!g.isAlive() || g.getHealth() >= g.getMaxHealth()) return true;
                if (guard.distanceToSqr(g) > 9) {
                    if (now % 10 == 0 && PathBudget.tryAcquire(level)) guard.getNavigation().moveTo(g, 0.8);
                    return false;
                }
                if (takeIron(guard)) {
                    g.heal(25f);
                    guard.swing(InteractionHand.MAIN_HAND);
                    level.playSound(null, g.getX(), g.getY(), g.getZ(), SoundEvents.IRON_GOLEM_REPAIR, SoundSource.NEUTRAL, 1f, 1f);
                }
                return true;
            }
            if (site == null) return true;
            if (!validSite(level, site)) return true;
            if (guard.blockPosition().distSqr(stand) > 4) {
                buildAt = now + 60;
                if (now % 10 == 0) Nav.approach(level, guard, stand, 0.8);
                return false;
            }
            guard.getNavigation().stop();
            if (now % 15 == 0) guard.swing(InteractionHand.MAIN_HAND);
            if (now < buildAt) return false;
            if (canBuild(level, colony) && hasMaterials(level, colony) && spawnGolem(level, site) != null) {
                consumeMaterials(level, colony);
                colony.nextGolemTick = serverTicks(level) + 200;
                ColonyManager.markDirty(level);
            }
            return true;
        }
    }

    private static boolean carriesIron(Villager v) {
        SimpleContainer inv = v.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(Items.IRON_INGOT)) return true;
        }
        return false;
    }

    private static boolean takeIron(Villager v) {
        SimpleContainer inv = v.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(Items.IRON_INGOT)) {
                s.shrink(1);
                inv.setChanged();
                return true;
            }
        }
        return false;
    }

    private static boolean hasEmptySlot(SimpleContainer inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- golem tick (MobEntity.mobTick TAIL)

    /** Regeneration, swimming, colony targeting and the pillar breaker, once per golem tick. */
    public static void tickGolem(ServerLevel level, IronGolem golem) {
        // A golem on campaign follows its army (warfare.Siege), not the village it stands in.
        if (golem.getPersistentData().getBoolean(net.betterthanvillage.warfare.Campaigns.GOLEM_TAG)) return;
        long now = level.getGameTime();
        Colony colony = ColonyManager.colonyAt(level, golem.blockPosition());
        if (colony != null && Math.floorMod(now + golem.getId(), 20L) == 0) {
            if (golem.getHealth() < golem.getMaxHealth()) golem.heal(2f);
            golem.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 240, 0, false, false), golem);
            golem.setAbsorptionAmount(Math.min(golem.getMaxAbsorption(), golem.getAbsorptionAmount() + 1f));
        }
        // only a submerged golem is pushed up (wading through a stream or farm channel is left alone)
        if (golem.isUnderWater()) {
            Vec3 goal;
            LivingEntity target = golem.getTarget();
            if (target != null) goal = target.position();
            else if (colony != null) goal = Vec3.atCenterOf(colony.core());
            else goal = golem.position();
            double dx = goal.x - golem.getX();
            double dz = goal.z - golem.getZ();
            double h = Math.sqrt(dx * dx + dz * dz);
            Vec3 vel = golem.getDeltaMovement();
            double vx = h > 0.01 ? dx / h * 0.075 : vel.x * 0.5;
            double vz = h > 0.01 ? dz / h * 0.075 : vel.z * 0.5;
            golem.setDeltaMovement(vx, Math.max(vel.y, 0.11), vz);
        }
        if (Math.floorMod(now + golem.getId(), 4L) != 0) return;
        LivingEntity target = golem.getTarget();
        if (colony == null) {
            // a golem that chased a player out of the colony gives up instead of hunting them forever
            if (target instanceof Player && golem.getPersistentData().getBoolean(ThreatManager.MOBILIZED_TAG)) calmDown(golem);
            return;
        }
        if (target instanceof Villager tv && ThreatManager.memberOf(level, colony, tv)) {
            golem.setTarget(null);
            target = null;
        } else if (target instanceof Player p && (golem.isPlayerCreated() || !ThreatManager.isWanted(level, colony, p))) {
            // player anger never decays while targeting the player (NeutralMob): drop it once the alarm is over
            calmDown(golem);
            target = null;
        } else if (target != null && (!target.isAlive() || !ThreatManager.inColony(colony, target, ThreatManager.BORDER_MARGIN))) {
            // the margin keeps a golem from flip-flopping on a target standing at the border
            golem.setTarget(null);
            golem.getNavigation().stop();
            target = null;
        }
        if (target == null) {
            // only a golem without a valid target picks a new one: the nearest threat to itself
            LivingEntity threat = ThreatManager.nearestThreat(level, colony, golem, e -> e instanceof Player && golem.isPlayerCreated(), false);
            if (threat != null) {
                if (threat instanceof Player) golem.getPersistentData().putBoolean(ThreatManager.MOBILIZED_TAG, true);
                golem.setTarget(threat);
                WaypointNavigator.travel(level, golem, threat.blockPosition(), 1.8);
            }
        } else if (golem.distanceToSqr(target) > 24 * 24) {
            // long way: route with waypoints; close by, the vanilla melee goal chases the moving target itself
            WaypointNavigator.travel(level, golem, target.blockPosition(), 1.8);
        }
        breakPillar(level, golem, colony);
    }

    private static void calmDown(IronGolem golem) {
        golem.stopBeingAngry();
        golem.getNavigation().stop();
        golem.getPersistentData().remove(ThreatManager.MOBILIZED_TAG);
    }

    // ---------------------------------------------------------------- pillar breaker

    /** Knocks down the player-placed block an attacking player stands on (one block per call). */
    public static void breakPillar(ServerLevel level, IronGolem golem, Colony colony) {
        if (!ColonyConfig.get().roles.golems_break_attacker_pillars || !ColonyManager.terrainChangesAllowed(level)) return;
        if (!(golem.getTarget() instanceof Player player) || player.isCreative() || player.isSpectator()) return;
        double dy = player.getY() - golem.getY();
        double dx = player.getX() - golem.getX();
        double dz = player.getZ() - golem.getZ();
        if (dy < 2 || dy > 9 || dx * dx + dz * dz > 20 || Math.floorMod(level.getGameTime() + golem.getId(), 12L) != 0) return;
        BlockPos feet = player.blockPosition();
        BlockPos block = null;
        for (int k = 1; k <= 8; k++) {
            BlockPos p = feet.below(k);
            if (!level.getBlockState(p).isAir()) {
                block = p;
                break;
            }
        }
        if (block == null || !pillarMaterial(level, colony, block)) return;
        ColonySavedData data = ColonyManager.data(level);
        if (!data.isPlayerBlock(block) || net.betterthanvillage.colony.Protection.beaconNear(level, block)) return;
        if (level.destroyBlock(block, false, golem)) {
            data.forgetPlacement(block);
            WaypointNavigator.travel(level, golem, player.blockPosition(), 1.9);
        }
    }

    private static boolean pillarMaterial(ServerLevel level, Colony colony, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.isAir() || s.is(ModTags.BUILDER_PROTECTED) || s.hasBlockEntity() || s.getDestroySpeed(level, pos) < 0) return false;
        if (!(s.is(BlockTags.DIRT) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.COBBLESTONE)
                || s.is(Blocks.COBBLED_DEEPSLATE) || s.is(Blocks.NETHERRACK))) {
            return false;
        }
        int air = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(pos.relative(d)).isAir()) air++;
        }
        if (air < 2) return false;
        for (ConstructionJob job : colony.jobs) {
            BoundingBox b = job.box();
            if (pos.getX() >= b.minX() - 2 && pos.getX() <= b.maxX() + 2 && pos.getZ() >= b.minZ() - 2 && pos.getZ() <= b.maxZ() + 2
                    && pos.getY() >= b.minY() - 1 && pos.getY() <= b.maxY() + 2) {
                return false;
            }
        }
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-3, -3, -3), pos.offset(3, 3, 3))) {
            BlockState n = level.getBlockState(p);
            if (n.is(BlockTags.BEDS) || n.is(BlockTags.DOORS) || n.getBlock() instanceof BellBlock
                    || n.getBlock() instanceof ChestBlock || n.getBlock() instanceof BarrelBlock) {
                return false;
            }
        }
        return true;
    }

    /** Alive iron golems standing in this colony's territory (colony box, +-12 Y). */
    public static int golemCount(ServerLevel level, Colony colony) {
        BlockPos c = colony.core();
        int r = colony.radius();
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(c.getX() - r, c.getY() - 12, c.getZ() - r,
                c.getX() + r + 1, c.getY() + 13, c.getZ() + r + 1);
        int n = 0;
        for (IronGolem golem : level.getEntitiesOfClass(IronGolem.class, box, IronGolem::isAlive)) {
            if (ColonyManager.colonyAt(level, golem.blockPosition()) == colony) n++;
        }
        return n;
    }
}
