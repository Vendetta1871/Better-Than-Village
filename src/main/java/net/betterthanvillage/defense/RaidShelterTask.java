package net.betterthanvillage.defense;

import net.betterthanvillage.ai.ColonyTask;
import net.betterthanvillage.ai.nav.Nav;
import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Colony;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;

import java.util.HashMap;

/** Civilians hide in enclosed shelters during raids near the colony; the only SHELTER task (02 §6.2). */
public final class RaidShelterTask extends ColonyTask {
    public static final String STATUS_NONE = "рейд: нет доступного укрытия";
    public static final String STATUS_HIDING = "прячется от рейда: ";

    private Colony colony;
    private BlockPos shelter;
    private BlockPos checkedPos;
    private long checkedAt;
    private boolean checkedEnclosed;
    private long nextSearch;
    private long nextMove;

    public RaidShelterTask() {
        super(12000);
    }

    @Override
    protected boolean shouldRun(ServerLevel level, Villager v, Colony colony) {
        if (!RaidShelter.isActive(level, colony) || !RaidShelter.civilian(colony, v)) return false;
        this.colony = colony;
        return true;
    }

    @Override
    protected void start(ServerLevel level, Villager v, long now) {
        RaidShelter.release(v);
        shelter = null;
        checkedPos = null;
        nextSearch = nextMove = now;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager v, long now) {
        return colony != null && RaidShelter.isActive(level, colony) && RaidShelter.civilian(colony, v);
    }

    @Override
    protected void tick(ServerLevel level, Villager v, long now) {
        if (v.isSleeping()) v.stopSleeping();
        if (shelter != null && (!Nav.standable(level, shelter) || level.canSeeSky(shelter)
                || (now % 100 == 0 && !RaidShelter.enclosed(level, shelter, new HashMap<>())))) {
            dropShelter(v);
        }
        if (shelter == null && now >= nextSearch) {
            shelter = RaidShelter.findShelter(level, colony, v);
            nextSearch = now + 100;
        }
        if (shelter == null) {
            WorkTracker.status(v, STATUS_NONE);
            return;
        }
        WorkTracker.status(v, STATUS_HIDING + shelter.getX() + ", " + shelter.getY() + ", " + shelter.getZ());
        v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        if (v.distanceToSqr(shelter.getX() + 0.5, shelter.getY(), shelter.getZ() + 0.5) <= 1 && currentEnclosed(level, v, now)) {
            v.getNavigation().stop();
            return;
        }
        if (now >= nextMove) {
            Nav.approach(level, v, shelter, 1.05, Nav.R2_EXACT);
            if (Nav.isBlacklisted(v, shelter, now)) dropShelter(v);
            nextMove = now + 20;
        }
    }

    /** Enclosure of the villager's own block, recomputed on block change or after 100 ticks. */
    private boolean currentEnclosed(ServerLevel level, Villager v, long now) {
        BlockPos here = v.blockPosition();
        if (!here.equals(checkedPos) || now - checkedAt >= 100) {
            checkedPos = here;
            checkedAt = now;
            checkedEnclosed = RaidShelter.enclosed(level, here, new HashMap<>());
        }
        return checkedEnclosed;
    }

    private void dropShelter(Villager v) {
        shelter = null;
        RaidShelter.release(v);
    }

    @Override
    protected void stop(ServerLevel level, Villager v, long now) {
        RaidShelter.release(v);
        v.getNavigation().stop();
        shelter = null;
        colony = null;
    }
}
