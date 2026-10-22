package net.betterthanvillage.warfare;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.ai.VillagerUtil;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.ai.nav.WaypointNavigator;
import net.betterthanvillage.ai.work.BlockWorkTimer;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.FortBlock;
import net.betterthanvillage.colony.PathBudget;
import net.betterthanvillage.colony.Protection;
import net.betterthanvillage.colony.SafeEditScope;
import net.betterthanvillage.construction.Fortifications;
import net.betterthanvillage.defense.Combat;
import net.betterthanvillage.law.Law;
import net.betterthanvillage.law.Wars;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A soldier on campaign near a player (docs_v2/07-warfare.md §Sieges): fights the warriors and golems of the enemy it
 * meets, and in a siege storms the target ring by ring. Before a wall it breaks a gate or a few wall columns
 * (infantry), digs under the wall where it stands on diggable ground, or builds a staircase up to its top; archers
 * give covering fire from outside. Inside the last ring it makes for the bell and holds it.
 */
public final class SoldierTask extends ColonyTask {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final Predicate<ItemStack> MELEE = s -> s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES);
    private static final Predicate<ItemStack> BOW = s -> s.is(Items.BOW) || s.is(Items.CROSSBOW);
    /** Ticks a soldier makes no progress toward its goal before it changes tactics. */
    private static final int STUCK_TICKS = 300;

    enum Tactic { BREAK, TUNNEL, CLIMB }

    /** One step of a breach: a block to take down or to put up. */
    private record Step(BlockPos pos, BlockState place) {
    }

    /** The breach this soldier works on. */
    private static final class Breach {
        int ring;
        Tactic tactic;
        BlockPos column;
        /** A point a few blocks inside the ring the soldier walks to once the breach is open. */
        BlockPos inside;
        final List<Step> steps = new ArrayList<>();
        int index;
        boolean done;
    }

    private Campaign campaign;
    private LivingEntity target;
    private long attackReadyAt;
    private long retargetAt;
    private long moveAt;
    private long swapAt;
    private Breach breach;
    private final BlockWorkTimer timer = new BlockWorkTimer();
    private long progressAt;
    private double progressBest = Double.MAX_VALUE;
    private int failedTactics;

    public SoldierTask() {
        super(2400);
    }

    @Override
    public boolean publicWork(Villager v) {
        return true;
    }

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        if (v.isSleeping() || !Campaigns.isSoldier(level, v)) return false;
        Campaign c = Campaigns.ofSoldier(level, v.getUUID());
        if (c.kind == Campaign.Kind.NIGHT) return false;
        if (c.phase == Campaign.Phase.SIEGE) {
            campaign = c;
            return true;
        }
        if ((level.getGameTime() + v.getId()) % 20 != 0) return false;
        campaign = c;
        return pickTarget(level, v, 10) != null;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager v, long now) {
        if (campaign == null || v.isSleeping() || !Campaigns.isSoldier(level, v)) return false;
        if (VillagerUtil.currentActivity(v) != ModRegistries.COLONY_TRAVEL.get()) return false;
        return campaign.phase == Campaign.Phase.SIEGE || (target != null && target.isAlive());
    }

    @Override
    protected void start(ServerLevel level, Villager v, long now) {
        attackReadyAt = retargetAt = moveAt = swapAt = now;
        progressAt = now;
        progressBest = Double.MAX_VALUE;
        target = null;
    }

    @Override
    protected void tick(ServerLevel level, Villager v, long now) {
        if (now >= retargetAt) {
            target = pickTarget(level, v, campaign.phase == Campaign.Phase.SIEGE ? 20 : 12);
            // A comrade in a fight nearby: everyone joins it instead of meeting the enemy one by one.
            boolean home = campaign.phase == Campaign.Phase.RETURN || campaign.phase == Campaign.Phase.HOME;
            if (target == null && !home && now - campaign.focusTick <= 100 && level.getEntity(campaign.focus) instanceof LivingEntity f
                    && f.isAlive() && f.distanceToSqr(v) <= 40 * 40) {
                target = f;
            }
            retargetAt = now + 10;
        }
        if (target != null) {
            campaign.focus = target.getId();
            campaign.focusTick = now;
        }
        if (target != null && !(campaign.kind == Campaign.Kind.PLAYER && campaign.phase == Campaign.Phase.SIEGE && PlayerRaid.looter(campaign, v))) {
            timer.cancel(level);
            fight(level, v, now);
            return;
        }
        if (campaign.phase != Campaign.Phase.SIEGE) return;
        if (campaign.kind == Campaign.Kind.PLAYER) {
            PlayerRaid.soldierTick(level, campaign, v, now);
            return;
        }
        Colony t = ColonyManager.data(level).colony(campaign.target);
        if (t != null) assault(level, v, t, now);
    }

    @Override
    protected void stop(ServerLevel level, Villager v, long now) {
        timer.cancel(level);
        WaypointNavigator.reset(v);
        target = null;
        campaign = null;
    }

    // ---------------------------------------------------------------- fighting

    /** The nearest enemy in sight: a defender of the target, or a player at war with the country (or hitting us). */
    private LivingEntity pickTarget(ServerLevel level, Villager v, int range) {
        LivingEntity best = null;
        double bestD = (double) range * range;
        LivingEntity hurtBy = v.getLastHurtByMob();
        // On the way home the army only defends itself.
        boolean home = campaign.phase == Campaign.Phase.RETURN || campaign.phase == Campaign.Phase.HOME;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, v.getBoundingBox().inflate(range, 6, range), LivingEntity::isAlive)) {
            if (e == v) continue;
            boolean enemy;
            if (e instanceof Player p) {
                enemy = !p.isCreative() && !p.isSpectator() && ((Wars.war(level, campaign.attacker, p.getUUID()) != null && Law.subject(p))
                        || (hurtBy == p && v.tickCount - v.getLastHurtByMobTimestamp() <= 200));
            } else if (e instanceof net.minecraft.world.entity.monster.Enemy && e instanceof net.minecraft.world.entity.Mob mob
                    && !(e instanceof net.minecraft.world.entity.monster.Creeper)) {
                // Monsters going for the army, or right next to it, are fought too.
                LivingEntity t = mob.getTarget();
                enemy = (t != null && Campaigns.ofSoldier(level, t.getUUID()) == campaign) || e.distanceToSqr(v) <= 36;
            } else {
                enemy = Campaigns.defenderOf(level, campaign, e);
            }
            if (enemy && home) {
                enemy = (hurtBy == e && v.tickCount - v.getLastHurtByMobTimestamp() <= 100)
                        || (e instanceof net.minecraft.world.entity.Mob m && m.getTarget() == v);
            }
            if (!enemy) continue;
            double d = e.distanceToSqr(v);
            if (d < bestD && (d <= 16 || v.hasLineOfSight(e))) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private void fight(ServerLevel level, Villager v, long now) {
        Campaign.Soldier me = campaign.soldier(v.getUUID());
        boolean ranged = me != null && me.cls == WarriorClass.RANGED;
        double d2 = v.distanceToSqr(target);
        boolean visible = v.hasLineOfSight(target);
        boolean useBow = ranged && d2 >= 16 && visible;
        if (now >= swapAt && Combat.swapWeapon(level, v, useBow ? BOW : MELEE)) swapAt = now + 20;
        v.getLookControl().setLookAt(target, 30f, 30f);
        if (BOW.test(v.getMainHandItem()) && d2 <= 225 && visible) {
            boolean clear = Combat.lineOfFireClear(level, v, target);
            if (d2 >= 49 && clear) v.getNavigation().stop();
            else if (now >= moveAt && PathBudget.tryAcquire(level)) {
                v.getNavigation().moveTo(target, 0.75);
                moveAt = now + 12;
            }
            if (now >= attackReadyAt && clear) {
                Combat.shoot(level, v, target);
                attackReadyAt = now + 30;
            }
        } else if (d2 > 4) {
            if (now >= moveAt) {
                WaypointNavigator.travel(level, v, target.blockPosition(), 0.85);
                moveAt = now + 12;
            }
        } else if (now >= attackReadyAt && visible) {
            attackReadyAt = now + Combat.melee(level, v, target);
        }
    }

    // ---------------------------------------------------------------- the assault

    private void assault(ServerLevel level, Villager v, Colony t, long now) {
        BlockPos core = t.core();
        double dx = v.getX() - (core.getX() + 0.5), dz = v.getZ() - (core.getZ() + 0.5);
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist <= Siege.HOLD_BELL - 2) {
            v.getNavigation().stop();
            return;
        }
        double ux = dx / Math.max(1, dist), uz = dz / Math.max(1, dist);
        int ring = -1;
        double wall = 0;
        for (int r : Fortifications.builtRings(t)) {
            double wd = Fortifications.wallDistance(t, r, ux, uz);
            if (wd < dist - 1.5) {
                ring = r;
                wall = wd;
                break;
            }
        }
        if (ring < 0) {
            // Inside every ring: to the bell.
            walkTo(level, v, core, now, null);
            return;
        }
        if (breach == null || breach.ring != ring) {
            breach = plan(level, v, t, ring, wall, ux, uz);
            progressAt = now;
            progressBest = Double.MAX_VALUE;
        }
        if (breach == null) {
            // Nothing built on this side: the ring is open here.
            BlockPos in = Campaigns.surface(level, (int) Math.floor(core.getX() + ux * (wall - 4)), (int) Math.floor(core.getZ() + uz * (wall - 4)));
            walkTo(level, v, in, now, null);
            return;
        }
        if (breach.done) {
            walkTo(level, v, breach.inside, now, breach);
            return;
        }
        work(level, v, now);
    }

    /** Walks to the point; a soldier that makes no progress tries another tactic, or (no griefing) climbs over. */
    private void walkTo(ServerLevel level, Villager v, BlockPos to, long now, Breach through) {
        Campaigns.go(level, v, to, 0.8);
        double d = v.distanceToSqr(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        if (d < progressBest - 4) {
            progressBest = d;
            progressAt = now;
        } else if (now - progressAt > STUCK_TICKS) {
            progressAt = now;
            progressBest = Double.MAX_VALUE;
            if (through != null) {
                // The breach did not open a way: another one, with another tactic.
                failedTactics++;
                breach = null;
            } else if (!ColonyManager.terrainChangesAllowed(level) || failedTactics > 3) {
                BlockPos hop = Campaigns.surface(level, (v.getBlockX() + to.getX()) / 2, (v.getBlockZ() + to.getZ()) / 2);
                v.teleportTo(hop.getX() + 0.5, hop.getY(), hop.getZ() + 0.5);
                Nav.reset(v);
            }
        }
    }

    /** The next step of the breach: approach it, then break or place the block. */
    private void work(ServerLevel level, Villager v, long now) {
        while (breach.index < breach.steps.size() && finished(level, breach.steps.get(breach.index))) breach.index++;
        if (breach.index >= breach.steps.size()) {
            breach.done = true;
            LOGGER.info("[war] soldier {} opened a {} breach in ring {} at {}", v.getUUID().toString().substring(0, 8),
                    breach.tactic.name().toLowerCase(java.util.Locale.ROOT), breach.ring, breach.column.toShortString());
            timer.cancel(level);
            progressAt = now;
            progressBest = Double.MAX_VALUE;
            return;
        }
        Step step = breach.steps.get(breach.index);
        double d = v.distanceToSqr(step.pos().getX() + 0.5, step.pos().getY() + 0.5, step.pos().getZ() + 0.5);
        if (d > 16) {
            timer.cancel(level);
            Nav.approach(level, v, step.pos(), 0.8, 9);
            if (d < progressBest - 1) {
                progressBest = d;
                progressAt = now;
            } else if (now - progressAt > STUCK_TICKS) {
                // Cannot get close: stand next to the step.
                BlockPos stand = Campaigns.surface(level, step.pos().getX() + Integer.signum(v.getBlockX() - step.pos().getX()) * 2,
                        step.pos().getZ() + Integer.signum(v.getBlockZ() - step.pos().getZ()) * 2);
                v.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
                Nav.reset(v);
                progressAt = now;
                progressBest = Double.MAX_VALUE;
            }
            return;
        }
        v.getNavigation().stop();
        v.getLookControl().setLookAt(step.pos().getX() + 0.5, step.pos().getY() + 0.5, step.pos().getZ() + 0.5);
        if (step.place() == null) {
            if (timer.tick(level, v, step.pos(), true)) {
                SafeEditScope.call(() -> level.destroyBlock(step.pos(), false, v));
                breach.index++;
            }
        } else if (timer.tickAction(level, v, step.pos(), 10)) {
            if (level.getBlockState(step.pos()).canBeReplaced() && level.getEntities(v, new net.minecraft.world.phys.AABB(step.pos())).isEmpty()) {
                SafeEditScope.run(() -> level.setBlock(step.pos(), step.place(), 3));
            }
            timer.reset();
            breach.index++;
        }
    }

    private static boolean finished(ServerLevel level, Step s) {
        BlockState here = level.getBlockState(s.pos());
        if (s.place() == null) return here.isAir() || !here.getFluidState().isEmpty() || !breakable(level, s.pos(), here);
        return !here.canBeReplaced();
    }

    private static boolean breakable(ServerLevel level, BlockPos pos, BlockState s) {
        return s.getDestroySpeed(level, pos) >= 0 && !s.hasBlockEntity() && !s.is(net.betterthanvillage.registry.ModTags.BUILDER_PROTECTED)
                && !Protection.playerProperty(level, pos) && !Protection.beaconNear(level, pos);
    }

    // ---------------------------------------------------------------- planning a breach

    /**
     * A breach in the ring near the soldier: a wall gate if one is close, else the nearest wall column with this
     * soldier's tactic (or breaking where the tactic does not fit). Null if the ring has no built wall near.
     */
    private Breach plan(ServerLevel level, Villager v, Colony t, int ring, double wall, double ux, double uz) {
        BlockPos near = null, gate = null;
        double nearD = 48 * 48, gateD = 16 * 16;
        BlockPos at = v.blockPosition();
        for (FortBlock b : t.fortifications.managed.values()) {
            BlockPos p = b.pos();
            double d = sq(p.getX() - at.getX()) + sq(p.getZ() - at.getZ());
            if (d > nearD && d > gateD) continue;
            if (Fortifications.ringOf(t, p) != ring || level.getBlockState(p).isAir()) continue;
            if (b.state().getBlock() instanceof FenceGateBlock && d < gateD) {
                gateD = d;
                gate = p;
            }
            if (d < nearD) {
                nearD = d;
                near = p;
            }
        }
        if (near == null && gate == null) return null;
        Breach b = new Breach();
        b.ring = ring;
        b.column = gate != null ? gate : near;
        int[] unit = axis(b.column.getX() - t.core().getX(), b.column.getZ() - t.core().getZ());
        BlockPos inCol = b.column.offset(-unit[0] * 4, 0, -unit[1] * 4);
        b.inside = Campaigns.surface(level, inCol.getX(), inCol.getZ());
        int base = Integer.MAX_VALUE, top = Integer.MIN_VALUE;
        for (int y = b.column.getY() - 4; y <= b.column.getY() + 5; y++) {
            FortBlock f = t.fortifications.managed.get(new BlockPos(b.column.getX(), y, b.column.getZ()));
            if (f != null && !(f.state().getBlock() instanceof FenceGateBlock && y > b.column.getY() + 1)) {
                base = Math.min(base, y);
                top = Math.max(top, y);
            }
        }
        if (base == Integer.MAX_VALUE) base = top = b.column.getY();
        Campaign.Soldier me = campaign.soldier(v.getUUID());
        int index = campaign.soldiers.indexOf(me);
        Tactic tactic = gate != null ? Tactic.BREAK : switch (Math.floorMod(index + failedTactics, 3)) {
            case 1 -> Tactic.TUNNEL;
            case 2 -> Tactic.CLIMB;
            default -> Tactic.BREAK;
        };
        if (!ColonyManager.terrainChangesAllowed(level)) return null;
        if (tactic == Tactic.TUNNEL && !tunnel(level, t, b, unit, base)) tactic = Tactic.BREAK;
        if (tactic == Tactic.CLIMB && !climb(level, t, b, unit, base, top)) tactic = Tactic.BREAK;
        if (tactic == Tactic.BREAK) breakThrough(level, t, b, ring, base, gate != null);
        b.tactic = tactic;
        if (b.steps.isEmpty()) b.done = true;
        net.betterthanvillage.ai.work.WorkTracker.status(v, "осада: " + tactic.name().toLowerCase(java.util.Locale.ROOT) + " у " + b.column.toShortString());
        LOGGER.info("[war] soldier {} plans a {} breach in ring {} at {} ({} steps)", v.getUUID().toString().substring(0, 8),
                tactic.name().toLowerCase(java.util.Locale.ROOT), ring, b.column.toShortString(), b.steps.size());
        return b;
    }

    /** Infantry: the gate, or the ring's blocks of the column and its neighbours at feet and head height. */
    private static void breakThrough(ServerLevel level, Colony t, Breach b, int ring, int base, boolean gate) {
        b.steps.clear();
        if (gate) {
            b.steps.add(new Step(b.column, null));
            return;
        }
        for (int y = base + 1; y >= base; y--) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos p = new BlockPos(b.column.getX() + dx, y, b.column.getZ() + dz);
                    FortBlock f = t.fortifications.managed.get(p);
                    if (f != null && Fortifications.ringOf(t, p) == ring) b.steps.add(new Step(p, null));
                }
            }
        }
    }

    /**
     * A tunnel under the wall along the radial axis: a step down outside, two blocks deep under the wall, a step up
     * inside. Only on diggable natural ground with no wall standing at the entry and exit.
     */
    private static boolean tunnel(ServerLevel level, Colony t, Breach b, int[] u, int base) {
        b.steps.clear();
        int[][] cells = {{2, 1}, {1, 2}, {0, 2}, {-1, 2}, {-2, 1}};
        for (int[] cell : cells) {
            int k = cell[0];
            BlockPos col = new BlockPos(b.column.getX() + u[0] * k, base, b.column.getZ() + u[1] * k);
            if (k != 0 && t.fortifications.managed.containsKey(col)) return false;
            for (int depth = 1; depth <= cell[1]; depth++) {
                BlockPos p = col.below(depth);
                BlockState s = level.getBlockState(p);
                if (s.isAir()) continue;
                if (!diggable(s) || !s.getFluidState().isEmpty() || !breakable(level, p, s)) return false;
                b.steps.add(new Step(p, null));
            }
            if (!level.getFluidState(col.below(cell[1] + 1)).isEmpty()) return false;
        }
        return true;
    }

    private static boolean diggable(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(net.minecraft.world.level.block.Blocks.GRAVEL)
                || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.CLAY) || s.is(BlockTags.TERRACOTTA)
                || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.MUD);
    }

    /** A staircase against the wall from outside, up to its top; the soldier drops down inside. */
    private static boolean climb(ServerLevel level, Colony t, Breach b, int[] u, int base, int top) {
        b.steps.clear();
        int h = top - base + 1;
        if (h > 5) return false;
        BlockState block = Blocks.COARSE_DIRT.defaultBlockState();
        for (int k = h; k >= 1; k--) {
            BlockPos col = new BlockPos(b.column.getX() + u[0] * k, base, b.column.getZ() + u[1] * k);
            if (t.fortifications.managed.containsKey(col)) return false;
            if (level.getBlockState(col.below()).canBeReplaced()) return false;
            for (int y = 0; y <= h - k; y++) {
                BlockPos p = col.above(y);
                if (level.getBlockState(p).canBeReplaced()) b.steps.add(new Step(p, block));
            }
        }
        return true;
    }

    /** The dominant axis of a direction, as a unit step. */
    private static int[] axis(double dx, double dz) {
        return Math.abs(dx) >= Math.abs(dz) ? new int[]{(int) Math.signum(dx), 0} : new int[]{0, (int) Math.signum(dz)};
    }

    private static double sq(double v) {
        return v * v;
    }

    /** Path check used by the soldiers' leader: true if the point can be reached as things are. */
    static boolean reachable(ServerLevel level, Villager v, BlockPos to) {
        if (!PathBudget.tryAcquire(level)) return false;
        Path path = v.getNavigation().createPath(to, 1);
        return path != null && path.canReach();
    }
}
