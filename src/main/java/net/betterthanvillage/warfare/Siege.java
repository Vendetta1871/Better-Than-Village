package net.betterthanvillage.warfare;

import com.mojang.logging.LogUtils;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.FortBlock;
import net.betterthanvillage.colony.SafeEditScope;
import net.betterthanvillage.construction.Fortifications;
import net.betterthanvillage.defense.ThreatManager;
import net.betterthanvillage.politics.Countries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Sieges (docs_v2/07-warfare.md §Sieges). Abstract: a breached ring loses a few wall columns facing the army, which
 * are taken down when the village loads (its masons rebuild them). Live: the soldiers ({@link SoldierTask}) break,
 * tunnel under or climb the walls ring by ring; the village falls when its warriors and golem are gone and a soldier
 * holds the bell.
 */
public final class Siege {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Wall columns a breach in an abstract round takes down. */
    private static final int BREACH_COLUMNS = 3;
    /** A soldier this close to the bell holds it. */
    static final double HOLD_BELL = 10;

    private Siege() {
    }

    // ---------------------------------------------------------------- abstract breaches

    /** The outermost intact ring is breached on the side facing the army. */
    static void breach(ServerLevel level, Colony target, Campaign c) {
        List<Integer> rings = Fortifications.builtRings(target);
        if (c.ringsBroken >= rings.size()) return;
        int r = rings.get(c.ringsBroken);
        double dx = c.x - target.core().getX(), dz = c.z - target.core().getZ();
        double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        double wall = Fortifications.wallDistance(target, r, dx / len, dz / len);
        double px = target.core().getX() + dx / len * wall, pz = target.core().getZ() + dz / len * wall;
        // The ring's built columns nearest to that point.
        Set<Long> columns = new LinkedHashSet<>();
        List<FortBlock> blocks = new ArrayList<>();
        for (FortBlock b : target.fortifications.managed.values()) {
            if (Fortifications.ringOf(target, b.pos()) == r) blocks.add(b);
        }
        blocks.sort(Comparator.comparingDouble(b -> sq(b.pos().getX() + 0.5 - px) + sq(b.pos().getZ() + 0.5 - pz)));
        for (FortBlock b : blocks) {
            long col = ((long) b.pos().getX() << 32) | (b.pos().getZ() & 0xFFFFFFFFL);
            if (columns.size() >= BREACH_COLUMNS && !columns.contains(col)) break;
            columns.add(col);
            target.pendingBreaches.add(b.pos());
        }
        ColonyManager.markDirty(level);
        LOGGER.info("[war] {} breaches ring {} of {} ({} blocks to take down)", Campaigns.shortId(c), r, Countries.villageName(target),
                target.pendingBreaches.size());
    }

    private static double sq(double v) {
        return v * v;
    }

    /** A live village takes down the wall blocks breached while it was abstract. */
    public static void applyBreaches(ServerLevel level, Colony colony) {
        if (colony.pendingBreaches.isEmpty()) return;
        int n = 0;
        for (Iterator<BlockPos> it = colony.pendingBreaches.iterator(); it.hasNext() && n < 16; ) {
            BlockPos pos = it.next();
            if (!level.isLoaded(pos)) continue;
            it.remove();
            n++;
            FortBlock b = colony.fortifications.managed.get(pos);
            if (b != null && level.getBlockState(pos).getBlock() == b.state().getBlock()) {
                SafeEditScope.run(() -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3));
            }
        }
        if (n > 0) ColonyManager.markDirty(level);
    }

    // ---------------------------------------------------------------- live siege

    /** Once a second while soldiers fight near a player: health, golems, the fall of the village or a retreat. */
    static void tickLive(ServerLevel level, Campaign c, long now) {
        for (Campaign.Soldier s : c.present(now)) {
            Entity e = level.getEntity(s.id);
            if (e instanceof LivingEntity le && le.isAlive()) {
                s.hp = Math.max(0.05, le.getHealth() / le.getMaxHealth());
                if (s.golem && e instanceof IronGolem g) golem(level, c, g);
                if (e instanceof Villager v && v.getHealth() < v.getMaxHealth() && now % 100 == 0) v.heal(1);
            }
        }
        if (c.kind == Campaign.Kind.PLAYER) {
            PlayerRaid.tickLive(level, c, now);
            return;
        }
        Colony target = ColonyManager.data(level).colony(c.target);
        if (target == null) return;
        if (Battle.armyPower(c, now) < 0.3 * c.initialPower) {
            Campaigns.retreat(level, c, "losses too heavy");
            return;
        }
        if (!level.isLoaded(target.core()) || !level.isPositionEntityTicking(target.core())) return;
        if (fallen(level, c, target)) Conquest.conquer(level, c, target);
    }

    /** No warrior or golem of the village is left standing and a soldier holds the bell. */
    private static boolean fallen(ServerLevel level, Campaign c, Colony target) {
        AABB box = ThreatManager.colonyBox(target);
        for (UUID id : Battle.defenders(level, target)) {
            Entity e = level.getEntity(id);
            if (e instanceof LivingEntity le && le.isAlive() && box.contains(e.position())) return false;
        }
        if (target.golem != null && level.getEntity(target.golem) instanceof IronGolem g && g.isAlive()) return false;
        BlockPos core = target.core();
        for (Campaign.Soldier s : c.soldiers) {
            if (!s.golem && level.getEntity(s.id) instanceof Villager v && v.isAlive()
                    && v.distanceToSqr(core.getX() + 0.5, core.getY(), core.getZ() + 0.5) <= HOLD_BELL * HOLD_BELL) {
                return true;
            }
        }
        return false;
    }

    /** A golem on campaign goes for the nearest defender, else follows the soldiers to the bell. */
    private static void golem(ServerLevel level, Campaign c, IronGolem g) {
        LivingEntity t = g.getTarget();
        if (t == null || !t.isAlive() || !Campaigns.defenderOf(level, c, t)) {
            t = null;
            double best = 24 * 24;
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, g.getBoundingBox().inflate(24), LivingEntity::isAlive)) {
                if (!Campaigns.defenderOf(level, c, e)) continue;
                double d = e.distanceToSqr(g);
                if (d < best) {
                    best = d;
                    t = e;
                }
            }
            g.setTarget(t);
        }
        if (t == null && c.kind == Campaign.Kind.WAR && net.betterthanvillage.colony.PathBudget.tryAcquire(level)) {
            Colony target = ColonyManager.data(level).colony(c.target);
            if (target != null) {
                BlockPos core = target.core();
                g.getNavigation().moveTo(core.getX() + 0.5, core.getY(), core.getZ() + 0.5, 0.8);
            }
        }
    }
}
