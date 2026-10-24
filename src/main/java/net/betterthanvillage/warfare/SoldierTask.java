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
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A soldier on campaign near a player (docs_v2/07-warfare.md §Sieges): fights the warriors and golems of the enemy it
 * meets, and in a siege storms the target ring by ring. The army takes each ring at one breach, on the side it came
 * from: a gate, or a wall stretch that a few infantry break (or a staircase built up to its top, or a tunnel under it,
 * when breaking failed). The others wait outside it, archers giving covering fire, riders on foot; once the breach is
 * open, or anyone got inside, everybody goes through. Inside the last ring the army makes for the bell and holds it.
 */
public final class SoldierTask extends ColonyTask {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final Predicate<ItemStack> MELEE = s -> s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES);
    private static final Predicate<ItemStack> BOW = s -> s.is(Items.BOW) || s.is(Items.CROSSBOW);
    /** Ticks a soldier makes no progress toward its goal before it is helped on. */
    private static final int STUCK_TICKS = 300;
    /** Ticks a soldier chases an enemy without closing in before it gives that one up. */
    private static final int CHASE_TICKS = 200;
    /** Infantry breaking one breach at a time. */
    private static final int BREACH_WORKERS = 3;
    /** Failed passages through an open breach before the army opens another one. */
    private static final int BREACH_FAILURES = 3;

    enum Tactic { BREAK, CLIMB, TUNNEL }

    /** One step of a breach: a block to take down, to put up, or a gate to open. */
    record Step(BlockPos pos, BlockState place, boolean open) {
        Step(BlockPos pos, BlockState place) {
            this(pos, place, false);
        }
    }

    /** The army's breach in one ring (Campaign.breaches): every soldier goes through there. */
    static final class Breach {
        int ring;
        Tactic tactic;
        BlockPos column;
        /** A point a few blocks inside the ring the soldiers walk to once the breach is open. */
        BlockPos inside;
        /** Where the army waits outside while the breach is worked, and the archers' spot farther back. */
        BlockPos outside;
        BlockPos cover;
        final List<Step> steps = new ArrayList<>();
        /** The infantry working it. */
        final Set<UUID> workers = new LinkedHashSet<>();
        boolean open;
        long checkedAt;
        int failures;
    }

    private Campaign campaign;
    private LivingEntity target;
    private long attackReadyAt;
    private long retargetAt;
    private long moveAt;
    private long swapAt;
    private final BlockWorkTimer timer = new BlockWorkTimer();
    private BlockPos goal;
    private long progressAt;
    private double progressBest = Double.MAX_VALUE;
    /** The enemy being chased (entity id), the closest it came and when. */
    private int chased = -1;
    private double chaseBest;
    private long chaseAt;
    /** Enemies this soldier could not get at (entity id -> until): the fence between them, a cliff. */
    private final Map<Integer, Long> unreachable = new HashMap<>();

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
        goal = null;
        target = null;
        chased = -1;
    }

    @Override
    protected void tick(ServerLevel level, Villager v, long now) {
        // A rider's horse follows the path the rider chose last tick.
        if ((now + v.getId()) % 10 == 0) Army.steer(level, v);
        if (now >= retargetAt) {
            unreachable.values().removeIf(until -> until <= now);
            target = pickTarget(level, v, campaign.phase == Campaign.Phase.SIEGE ? 20 : 12);
            // A comrade in a fight nearby: everyone joins it instead of meeting the enemy one by one.
            boolean home = campaign.phase == Campaign.Phase.RETURN || campaign.phase == Campaign.Phase.HOME;
            if (target == null && !home && now - campaign.focusTick <= 100 && level.getEntity(campaign.focus) instanceof LivingEntity f
                    && f.isAlive() && f.distanceToSqr(v) <= 40 * 40 && !unreachable.containsKey(f.getId())) {
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
        if (campaign != null) {
            for (Breach b : campaign.breaches.values()) b.workers.remove(v.getUUID());
        }
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
            if (!enemy || unreachable.containsKey(e.getId())) continue;
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
        if (target.getId() != chased) {
            chased = target.getId();
            chaseBest = d2;
            chaseAt = now;
        } else if (d2 < chaseBest - 1) {
            chaseBest = d2;
            chaseAt = now;
        }
        boolean visible = v.hasLineOfSight(target);
        boolean useBow = ranged && d2 >= 16 && visible;
        if (now >= swapAt && Combat.swapWeapon(level, v, useBow ? BOW : MELEE)) swapAt = now + 20;
        v.getLookControl().setLookAt(target, 30f, 30f);
        if (BOW.test(v.getMainHandItem()) && d2 <= 225 && visible) {
            boolean clear = Combat.lineOfFireClear(level, v, target);
            if (d2 >= 49 && clear) v.getNavigation().stop();
            else if (now >= moveAt && PathBudget.tryAcquire(level)) {
                Army.travel(level, v, target.blockPosition(), 0.75);
                moveAt = now + 12;
            }
            if (now >= attackReadyAt && clear) {
                Combat.shoot(level, v, target);
                attackReadyAt = now + 30;
                chaseAt = now;
            }
        } else if (d2 > 4) {
            if (now >= moveAt) {
                Army.travel(level, v, target.blockPosition(), 0.85);
                moveAt = now + 12;
            }
        } else if (now >= attackReadyAt && visible) {
            attackReadyAt = now + Combat.melee(level, v, target);
            chaseAt = now;
        }
        if (now - chaseAt > CHASE_TICKS) {
            // Seen but out of reach (behind the wall, across a ravine): back to the assault, a comrade gets it.
            unreachable.put(target.getId(), now + 600);
            if (campaign.focus == target.getId()) campaign.focus = -1;
            target = null;
            chased = -1;
            WaypointNavigator.reset(v);
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
        // The outermost ring still between the soldier and the bell; the rings it is inside of are open.
        int ring = -1;
        double wall = 0;
        for (int r : Fortifications.builtRings(t)) {
            double wd = Fortifications.wallDistance(t, r, ux, uz);
            if (wd < dist - 1.5) {
                if (ring < 0 || wd > wall) {
                    ring = r;
                    wall = wd;
                }
            } else {
                Breach passed = campaign.breaches.get(r);
                if (passed != null && !passed.open) {
                    passed.open = true;
                    passed.checkedAt = now;
                    LOGGER.info("[war] soldier {} is inside ring {}: the army follows through {}", v.getUUID().toString().substring(0, 8),
                            r, passed.column.toShortString());
                }
            }
        }
        if (ring < 0) {
            // Inside every ring: to the bell.
            walkTo(level, v, core, now, null);
            return;
        }
        Breach b = campaign.breaches.get(ring);
        if (b == null) {
            b = plan(level, t, ring, campaign.breachTries.getOrDefault(ring, 0));
            if (b == null) {
                // Nothing built on this side: the ring is open here.
                BlockPos in = Campaigns.surface(level, (int) Math.floor(core.getX() + ux * (wall - 4)), (int) Math.floor(core.getZ() + uz * (wall - 4)));
                walkTo(level, v, in, now, null);
                return;
            }
            campaign.breaches.put(ring, b);
        }
        Campaign.Soldier me = campaign.soldier(v.getUUID());
        if (me != null && v.getVehicle() instanceof net.minecraft.world.entity.animal.horse.AbstractHorse && dist - wall < 12) {
            // Riders storm a wall on foot: a horse fits no breach a villager would make.
            me.dismounted = true;
            v.stopRiding();
            WaypointNavigator.reset(v);
        }
        if (b.open && now - b.checkedAt >= 40) {
            b.checkedAt = now;
            if (b.tactic == Tactic.BREAK && next(level, b, 0) != null) {
                // Walled up again (masons, a player): it is worked once more.
                b.open = false;
                LOGGER.info("[war] breach in ring {} at {} closed again", b.ring, b.column.toShortString());
            }
        }
        if (b.open) {
            walkTo(level, v, b.inside, now, b);
            return;
        }
        b.workers.removeIf(id -> campaign.soldier(id) == null || !(level.getEntity(id) instanceof Villager w) || !w.isAlive());
        boolean ranged = me != null && me.cls == WarriorClass.RANGED;
        int crew = b.tactic == Tactic.BREAK ? BREACH_WORKERS : 1;
        if (!ranged && !v.isPassenger() && !b.workers.contains(v.getUUID()) && b.workers.size() < crew) b.workers.add(v.getUUID());
        if (b.workers.contains(v.getUUID())) {
            int slot = 0;
            for (UUID id : b.workers) {
                if (id.equals(v.getUUID())) break;
                slot++;
            }
            work(level, v, b, slot, now);
            return;
        }
        timer.cancel(level);
        walkTo(level, v, ranged ? b.cover : b.outside, now, null);
    }

    /**
     * Walks to the point and waits there; a soldier that makes no progress is helped over the obstacle (never over a
     * wall). A breach that keeps failing to let it through is given up for another one.
     */
    private void walkTo(ServerLevel level, Villager v, BlockPos to, long now, Breach through) {
        if (!to.equals(goal)) {
            goal = to;
            progressAt = now;
            progressBest = Double.MAX_VALUE;
        }
        double d = v.distanceToSqr(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        if (d <= 4) {
            v.getNavigation().stop();
            progressAt = now;
            return;
        }
        Campaigns.go(level, v, to, 0.8);
        if (d < progressBest - 4) {
            progressBest = d;
            progressAt = now;
            return;
        }
        if (now - progressAt <= STUCK_TICKS) return;
        progressAt = now;
        progressBest = Double.MAX_VALUE;
        if (through != null && ++through.failures >= BREACH_FAILURES) {
            // The breach does not let the army through: another one, with the next tactic.
            campaign.breaches.remove(through.ring);
            campaign.breachTries.merge(through.ring, 1, Integer::sum);
            LOGGER.info("[war] breach in ring {} at {} given up", through.ring, through.column.toShortString());
            return;
        }
        // Never over a wall (Army.hop): the army gets in only through a breach it made.
        BlockPos hop = Campaigns.surface(level, (v.getBlockX() + to.getX()) / 2, (v.getBlockZ() + to.getZ()) / 2);
        Army.hop(v, hop.getX() + 0.5, hop.getY(), hop.getZ() + 0.5);
        Nav.reset(v);
        WaypointNavigator.reset(v);
    }

    /** The unfinished step for the worker in this slot (the workers spread over the first ones), or null when none is left. */
    private static Step next(ServerLevel level, Breach b, int slot) {
        Step first = null, pick = null;
        int k = 0;
        for (Step s : b.steps) {
            if (finished(level, s)) continue;
            if (first == null) first = s;
            if (k++ == slot) {
                pick = s;
                break;
            }
        }
        return pick != null ? pick : first;
    }

    /** The worker's next step of the breach: approach it, then break or place the block. */
    private void work(ServerLevel level, Villager v, Breach b, int slot, long now) {
        Step step = next(level, b, b.tactic == Tactic.BREAK ? slot : 0);
        if (step == null) {
            b.open = true;
            b.checkedAt = now;
            LOGGER.info("[war] soldier {} opened a {} breach in ring {} at {}", v.getUUID().toString().substring(0, 8),
                    b.tactic.name().toLowerCase(java.util.Locale.ROOT), b.ring, b.column.toShortString());
            timer.cancel(level);
            return;
        }
        double d = v.distanceToSqr(step.pos().getX() + 0.5, step.pos().getY() + 0.5, step.pos().getZ() + 0.5);
        if (d > 16) {
            timer.cancel(level);
            if (!step.pos().equals(goal)) {
                goal = step.pos();
                progressAt = now;
                progressBest = Double.MAX_VALUE;
            }
            Nav.approach(level, v, step.pos(), 0.8, 9);
            if (d < progressBest - 1) {
                progressBest = d;
                progressAt = now;
            } else if (now - progressAt > STUCK_TICKS) {
                // Cannot get close: stand next to the step.
                BlockPos stand = Campaigns.surface(level, step.pos().getX() + Integer.signum(v.getBlockX() - step.pos().getX()) * 2,
                        step.pos().getZ() + Integer.signum(v.getBlockZ() - step.pos().getZ()) * 2);
                Army.hop(v, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
                Nav.reset(v);
                progressAt = now;
                progressBest = Double.MAX_VALUE;
            }
            return;
        }
        v.getNavigation().stop();
        v.getLookControl().setLookAt(step.pos().getX() + 0.5, step.pos().getY() + 0.5, step.pos().getZ() + 0.5);
        if (step.open()) {
            if (timer.tickAction(level, v, step.pos(), 10)) {
                BlockState gate = level.getBlockState(step.pos());
                if (gate.getBlock() instanceof FenceGateBlock && !gate.getValue(FenceGateBlock.OPEN)) {
                    v.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                    SafeEditScope.run(() -> level.setBlock(step.pos(), gate.setValue(FenceGateBlock.OPEN, true), 3));
                    level.playSound(null, step.pos(), net.minecraft.sounds.SoundEvents.FENCE_GATE_OPEN, net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f);
                }
                timer.reset();
            }
        } else if (step.place() == null) {
            if (timer.tick(level, v, step.pos(), true)) {
                SafeEditScope.call(() -> level.destroyBlock(step.pos(), false, v));
            }
        } else if (timer.tickAction(level, v, step.pos(), 10)) {
            if (level.getBlockState(step.pos()).canBeReplaced() && level.getEntities(v, new net.minecraft.world.phys.AABB(step.pos())).isEmpty()) {
                SafeEditScope.run(() -> level.setBlock(step.pos(), step.place(), 3));
            }
            timer.reset();
        }
    }

    private static boolean finished(ServerLevel level, Step s) {
        BlockState here = level.getBlockState(s.pos());
        if (s.open()) return !(here.getBlock() instanceof FenceGateBlock) || here.getValue(FenceGateBlock.OPEN);
        if (s.place() == null) return here.isAir() || !here.getFluidState().isEmpty() || !breakable(level, s.pos(), here);
        return !here.canBeReplaced();
    }

    private static boolean breakable(ServerLevel level, BlockPos pos, BlockState s) {
        return s.getDestroySpeed(level, pos) >= 0 && !s.hasBlockEntity() && !s.is(net.betterthanvillage.registry.ModTags.BUILDER_PROTECTED)
                && !Protection.playerProperty(level, pos) && !Protection.beaconNear(level, pos);
    }

    // ---------------------------------------------------------------- planning a breach

    /**
     * The army's breach in the ring, on the side it came from (the outpost): a wall gate if one is close, else the
     * wall nearest that point, broken through (the first try), climbed or tunnelled under (after failed ones). Null if
     * the ring has no built wall near, or terrain may not be changed (the army then just walks in and is helped over).
     */
    private Breach plan(ServerLevel level, Colony t, int ring, int tries) {
        BlockPos core = t.core();
        double ax = campaign.outpost != null ? campaign.outpost.getX() : campaign.x;
        double az = campaign.outpost != null ? campaign.outpost.getZ() : campaign.z;
        double ox = ax - core.getX(), oz = az - core.getZ(), ol = Math.max(1, Math.hypot(ox, oz));
        double wd = Fortifications.wallDistance(t, ring, ox / ol, oz / ol);
        int px = (int) Math.floor(core.getX() + ox / ol * wd), pz = (int) Math.floor(core.getZ() + oz / ol * wd);
        BlockPos near = null, gate = null;
        double nearD = 48 * 48, gateD = 16 * 16;
        for (FortBlock fb : t.fortifications.managed.values()) {
            BlockPos p = fb.pos();
            double d = sq(p.getX() - px) + sq(p.getZ() - pz);
            if (d > nearD && d > gateD) continue;
            if (Fortifications.ringOf(t, p) != ring || level.getBlockState(p).isAir()) continue;
            if (fb.state().getBlock() instanceof FenceGateBlock && d < gateD) {
                gateD = d;
                gate = p;
            }
            if (d < nearD) {
                nearD = d;
                near = p;
            }
        }
        if (near == null && gate == null) return null;
        if (!ColonyManager.terrainChangesAllowed(level)) return null;
        // The gate is opened on the first try (and every third); the other tries take on the wall next to it.
        if (gate != null && Math.floorMod(tries, Tactic.values().length) != 0) {
            if (near == null || near.equals(gate)) near = wallNear(level, t, ring, gate);
            gate = null;
            if (near == null) return null;
        }
        Breach b = new Breach();
        b.ring = ring;
        b.column = gate != null ? gate : near;
        int[] unit = axis(b.column.getX() - core.getX(), b.column.getZ() - core.getZ());
        b.inside = Campaigns.surface(level, b.column.getX() - unit[0] * 4, b.column.getZ() - unit[1] * 4);
        b.outside = Campaigns.surface(level, b.column.getX() + unit[0] * 5, b.column.getZ() + unit[1] * 5);
        // Archers stand farther back and to one side, shooting over the wall.
        b.cover = Campaigns.surface(level, b.column.getX() + unit[0] * 9 - unit[1] * 3, b.column.getZ() + unit[1] * 9 + unit[0] * 3);
        int base = Integer.MAX_VALUE, top = Integer.MIN_VALUE;
        for (int y = b.column.getY() - 4; y <= b.column.getY() + 5; y++) {
            FortBlock f = t.fortifications.managed.get(new BlockPos(b.column.getX(), y, b.column.getZ()));
            if (f != null && !(f.state().getBlock() instanceof FenceGateBlock && y > b.column.getY() + 1)) {
                base = Math.min(base, y);
                top = Math.max(top, y);
            }
        }
        if (base == Integer.MAX_VALUE) base = top = b.column.getY();
        Tactic tactic = gate != null ? Tactic.BREAK : Tactic.values()[Math.floorMod(tries, Tactic.values().length)];
        if (tactic == Tactic.TUNNEL && !tunnel(level, t, b, unit, base)) tactic = Tactic.BREAK;
        if (tactic == Tactic.CLIMB && !climb(level, t, b, unit, base, top)) tactic = Tactic.BREAK;
        if (tactic == Tactic.BREAK) breakThrough(level, t, b, ring, base, gate != null);
        b.tactic = tactic;
        if (b.steps.isEmpty()) b.open = true;
        LOGGER.info("[war] the army plans a {} breach in ring {} at {} ({} steps)", tactic.name().toLowerCase(java.util.Locale.ROOT), ring,
                b.column.toShortString(), b.steps.size());
        return b;
    }

    /** The wall block of the ring nearest the gate that is no gate itself (within 8), or null. */
    private static BlockPos wallNear(ServerLevel level, Colony t, int ring, BlockPos gate) {
        BlockPos best = null;
        double bestD = 8 * 8 + 1;
        for (FortBlock fb : t.fortifications.managed.values()) {
            BlockPos p = fb.pos();
            if (fb.state().getBlock() instanceof FenceGateBlock || level.getBlockState(p).isAir()) continue;
            double d = sq(p.getX() - gate.getX()) + sq(p.getZ() - gate.getZ());
            if (d >= 4 && d < bestD && Fortifications.ringOf(t, p) == ring) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** Infantry: the gate opened, or the ring's blocks of the column and its neighbours at feet and head height. */
    private static void breakThrough(ServerLevel level, Colony t, Breach b, int ring, int base, boolean gate) {
        b.steps.clear();
        if (gate) {
            b.steps.add(new Step(b.column, null, true));
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
