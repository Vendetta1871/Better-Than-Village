package net.betterthanvillage.ai;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import net.betterthanvillage.construction.HouseConstructionTask;
import net.betterthanvillage.construction.HouseRepairTask;
import net.betterthanvillage.construction.RoadTask;
import net.betterthanvillage.construction.WallTask;
import net.betterthanvillage.defense.GuardCombatTask;
import net.betterthanvillage.defense.GuardEquipmentTask;
import net.betterthanvillage.defense.RaidShelterTask;
import net.betterthanvillage.food.FarmerTask;
import net.betterthanvillage.food.FisherTask;
import net.betterthanvillage.food.RancherTask;
import net.betterthanvillage.gather.LumberjackTask;
import net.betterthanvillage.gather.MinerTask;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;

/** Installs the colony activities on a villager brain (called after vanilla registerBrainGoals). */
public final class ColonyBrain {
    private ColonyBrain() {
    }

    public static void install(Brain<Villager> brain) {
        brain.addActivity(ModRegistries.COLONY_TRAVEL.get(), ImmutableList.of(task(0, new net.betterthanvillage.warfare.SoldierTask()),
                task(0, new net.betterthanvillage.warfare.NightOpTask())));
        brain.addActivity(ModRegistries.COLONY_SHELTER.get(), ImmutableList.of(task(0, new RaidShelterTask())));
        brain.addActivity(Activity.FIGHT, ImmutableList.of(task(0, new GuardCombatTask())));
        brain.addActivity(ModRegistries.COLONY_WORK.get(), ImmutableList.of(
                task(0, new StarterWarehouseTask()),
                task(0, new HouseRepairTask()),
                task(0, new RoadTask()),
                task(1, new HouseConstructionTask()),
                task(1, new WallTask()),
                task(1, new GuardEquipmentTask()),
                task(2, new FarmerTask()),
                task(3, new net.betterthanvillage.construction.LampPostTask()),
                task(3, new LumberjackTask()),
                task(3, new net.betterthanvillage.construction.LandWorkTask()),
                task(4, new MinerTask()),
                task(5, new FisherTask()),
                task(6, new RancherTask()),
                task(7, new net.betterthanvillage.economy.WorkshopTask())));
    }

    private static Pair<Integer, BehaviorControl<? super Villager>> task(int priority, BehaviorControl<? super Villager> task) {
        return Pair.of(priority, task);
    }
}
