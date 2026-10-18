package net.betterthanvillage.registry;

import com.mojang.serialization.Codec;
import net.betterthanvillage.BetterThanVillage;
import net.betterthanvillage.ai.VillageStorageSensor;
import net.betterthanvillage.colony.ColonyRole;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.SensorType;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.Optional;

/** Brain activities, memories and sensors added to villagers. */
public final class ModRegistries {
    private static final String NS = BetterThanVillage.MOD_ID;

    public static final DeferredRegister<Activity> ACTIVITIES = DeferredRegister.create(Registries.ACTIVITY, NS);
    public static final DeferredRegister<MemoryModuleType<?>> MEMORIES = DeferredRegister.create(Registries.MEMORY_MODULE_TYPE, NS);
    public static final DeferredRegister<SensorType<?>> SENSORS = DeferredRegister.create(Registries.SENSOR_TYPE, NS);

    public static final DeferredHolder<Activity, Activity> COLONY_WORK = ACTIVITIES.register("colony_work", () -> new Activity(NS + ":colony_work"));
    public static final DeferredHolder<Activity, Activity> COLONY_SHELTER = ACTIVITIES.register("colony_shelter", () -> new Activity(NS + ":colony_shelter"));
    public static final DeferredHolder<Activity, Activity> COLONY_TRAVEL = ACTIVITIES.register("colony_travel", () -> new Activity(NS + ":colony_travel"));

    public static final Codec<ColonyRole> ROLE_CODEC = Codec.STRING.xmap(ColonyRole::byName, ColonyRole::serializedName);

    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<ColonyRole>> COLONY_ROLE = MEMORIES.register("colony_role", () -> new MemoryModuleType<>(Optional.of(ROLE_CODEC)));
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<BlockPos>> BUILD_TARGET_POS = pos("build_target_pos");
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<BlockPos>> TREE_TARGET_POS = pos("tree_target_pos");
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<BlockPos>> FARM_TARGET_POS = pos("farm_target_pos");
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<BlockPos>> MINE_TARGET_POS = pos("mine_target_pos");
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<BlockPos>> FISH_TARGET_POS = pos("fish_target_pos");
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<BlockPos>> ANIMAL_TARGET_POS = pos("animal_target_pos");
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<BoundingBox>> CURRENT_STRUCTURE_BOUNDS = MEMORIES.register("current_structure_bounds", () -> new MemoryModuleType<>(Optional.of(BoundingBox.CODEC)));
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<BlockPos>> STORAGE_CHEST_POS = pos("storage_chest_pos");
    public static final DeferredHolder<MemoryModuleType<?>, MemoryModuleType<LivingEntity>> THREAT_TARGET = MEMORIES.register("threat_target", () -> new MemoryModuleType<>(Optional.empty()));

    public static final DeferredHolder<SensorType<?>, SensorType<VillageStorageSensor>> VILLAGE_STORAGE = SENSORS.register("village_storage", () -> new SensorType<>(VillageStorageSensor::new));

    private static DeferredHolder<MemoryModuleType<?>, MemoryModuleType<BlockPos>> pos(String name) {
        return MEMORIES.register(name, () -> new MemoryModuleType<>(Optional.of(BlockPos.CODEC)));
    }

    /** The 10 custom memories, in the order they are appended to the villager profile. */
    public static List<MemoryModuleType<?>> memories() {
        return List.of(COLONY_ROLE.get(), BUILD_TARGET_POS.get(), TREE_TARGET_POS.get(), FARM_TARGET_POS.get(), MINE_TARGET_POS.get(),
                FISH_TARGET_POS.get(), ANIMAL_TARGET_POS.get(), CURRENT_STRUCTURE_BOUNDS.get(), STORAGE_CHEST_POS.get(), THREAT_TARGET.get());
    }

    /** Work-target memories, in the order the anti-stuck monitor reads them. */
    public static List<MemoryModuleType<BlockPos>> targetMemories() {
        return List.of(MINE_TARGET_POS.get(), BUILD_TARGET_POS.get(), TREE_TARGET_POS.get(), FARM_TARGET_POS.get(), FISH_TARGET_POS.get(),
                ANIMAL_TARGET_POS.get(), STORAGE_CHEST_POS.get());
    }

    public static void register(IEventBus modBus) {
        ACTIVITIES.register(modBus);
        MEMORIES.register(modBus);
        SENSORS.register(modBus);
        ModItems.register(modBus);
    }

    private ModRegistries() {
    }
}
