package net.betterthanvillage.world;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.Professions;
import net.betterthanvillage.colony.SpawnSpots;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.Villager;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Moving entities between the loaded world and the records of travelling groups (docs_v2/08-simulation.md
 * §Materialization): caravans and armies store the entity data of a member that leaves entity-ticking chunks and
 * spawn it again where the group reaches loaded chunks. A member whose entity never left its unloaded home chunk
 * walks as a stand-in with its id; the original is kept out of the world if it loads meanwhile.
 */
public final class Materializer {
    /** Our own spawns: the join listeners let them in. */
    private static boolean spawning;
    /** Entities being stored: their removal is no death. */
    private static final Set<UUID> STASHING = new HashSet<>();

    private Materializer() {
    }

    public static void clearCaches() {
        STASHING.clear();
        spawning = false;
    }

    public static boolean spawning() {
        return spawning;
    }

    public static boolean stashing(UUID id) {
        return STASHING.contains(id);
    }

    /** Stores the entity's data and removes it from the world; null if it cannot be saved. */
    public static CompoundTag stash(Entity e) {
        CompoundTag tag = new CompoundTag();
        if (!e.saveAsPassenger(tag)) return null;
        discard(e);
        return tag;
    }

    /** Removes the entity without it counting as a death. */
    public static void discard(Entity e) {
        STASHING.add(e.getUUID());
        try {
            if (e.isPassenger()) e.stopRiding();
            e.discard();
        } finally {
            STASHING.remove(e.getUUID());
        }
    }

    /** Spawns an entity from stored data at the position; null if it could not be added. */
    public static Entity spawn(ServerLevel level, CompoundTag data, BlockPos pos) {
        Entity e = EntityType.loadEntityRecursive(data, level, ent -> {
            ent.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, ent.getYRot(), 0);
            return ent;
        });
        if (e == null) return null;
        if (e instanceof Mob mob) mob.setPersistenceRequired();
        return add(level, e) ? e : null;
    }

    /**
     * A villager from its stored data, or (data null) a stand-in with its id and the colony's look for its role.
     * The role is applied for the home colony.
     */
    public static Villager spawnVillager(ServerLevel level, CompoundTag data, UUID id, BlockPos pos, Colony home) {
        Villager v;
        if (data != null) {
            Entity e = EntityType.loadEntityRecursive(data, level, ent -> {
                ent.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, ent.getYRot(), 0);
                return ent;
            });
            if (!(e instanceof Villager loaded)) return null;
            v = loaded;
        } else {
            v = SpawnSpots.createVillager(level, pos, MobSpawnType.MOB_SUMMONED);
            if (v == null) return null;
            v.setUUID(id);
        }
        v.setPersistenceRequired();
        if (!add(level, v)) return null;
        if (home != null) Professions.applyRole(level, home, v);
        return v;
    }

    private static boolean add(ServerLevel level, Entity e) {
        if (level.getEntity(e.getUUID()) != null) return false;
        spawning = true;
        try {
            return level.addFreshEntity(e);
        } finally {
            spawning = false;
        }
    }
}
