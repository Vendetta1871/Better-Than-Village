package net.betterthanvillage.ai;

import net.betterthanvillage.BetterThanVillage;
import net.betterthanvillage.ai.nav.Nav;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.WorkAtComposter;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Brain helpers shared by the controller and tasks. */
public final class VillagerUtil {
    private VillagerUtil() {
    }

    /** A task implemented by this mod; vanilla tasks wrapped in a gate are not mod tasks. */
    public static boolean isModTask(BehaviorControl<?> behavior) {
        return !(behavior instanceof ColonyGate<?> || behavior instanceof MovementGate<?>)
                && behavior.getClass().getName().startsWith(BetterThanVillage.PACKAGE_PREFIX);
    }

    /** The vanilla task behind a gate wrapper (or the task itself). */
    public static BehaviorControl<?> unwrap(BehaviorControl<?> behavior) {
        if (behavior instanceof ColonyGate<?> g) return g.inner();
        if (behavior instanceof MovementGate<?> m) return m.inner();
        return behavior;
    }

    public static List<BehaviorControl<? super Villager>> running(Villager villager) {
        return new ArrayList<>(villager.getBrain().getRunningBehaviors());
    }

    public static boolean anyModTaskRunning(Villager villager) {
        for (BehaviorControl<? super Villager> b : villager.getBrain().getRunningBehaviors()) {
            if (isModTask(b)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isRunning(Villager villager, Class<?> type) {
        for (BehaviorControl<? super Villager> b : villager.getBrain().getRunningBehaviors()) {
            if (type.isInstance(b)) {
                return true;
            }
        }
        return false;
    }

    public static void stopRunning(ServerLevel level, Villager villager, Predicate<BehaviorControl<?>> which) {
        long now = level.getGameTime();
        for (BehaviorControl<? super Villager> b : running(villager)) {
            if (which.test(b)) {
                b.doStop(level, villager, now);
            }
        }
    }

    /** Stops every running task whose class lives in this mod's packages. */
    public static void stopModTasks(ServerLevel level, Villager villager) {
        stopRunning(level, villager, VillagerUtil::isModTask);
    }

    public static void stopAllTasks(ServerLevel level, Villager villager) {
        stopRunning(level, villager, b -> true);
    }

    /** Stop navigation, drop walk target and path. */
    public static void stopWalking(Villager villager) {
        villager.getNavigation().stop();
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.PATH);
    }

    /** Full navigation reset: drop the mod's approach state too. */
    public static void resetNavigation(Villager villager) {
        Nav.reset(villager);
    }

    public static Activity currentActivity(Villager villager) {
        return villager.getBrain().getActiveNonCoreActivity().orElse(null);
    }

    /** Force a single activity (vanilla "doExclusively"). */
    public static void doExclusively(Villager villager, Activity activity) {
        Brain<Villager> brain = villager.getBrain();
        brain.setActiveActivityIfPossible(activity);
    }

    /** Return to the vanilla schedule. */
    public static void refreshActivities(ServerLevel level, Villager villager) {
        villager.getBrain().updateActivityFromSchedule(level.getDayTime(), level.getGameTime());
    }

    public static boolean isVanillaFarmerTask(BehaviorControl<?> b) {
        BehaviorControl<?> u = unwrap(b);
        return u instanceof WorkAtComposter || u.getClass().getSimpleName().equals("HarvestFarmland");
    }
}
