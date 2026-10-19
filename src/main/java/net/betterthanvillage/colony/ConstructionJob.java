package net.betterthanvillage.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.UUID;

/** A construction project ("plot"): a house template placed at an origin, built block by block. */
public final class ConstructionJob {
    public static final ResourceLocation DEFAULT_TEMPLATE = ResourceLocation.withDefaultNamespace("village/plains/houses/plains_small_house_1");

    public UUID id = UUID.randomUUID();
    public ResourceLocation template = DEFAULT_TEMPLATE;
    public BlockPos origin = BlockPos.ZERO;
    public int sizeX = 1;
    public int sizeY = 1;
    public int sizeZ = 1;
    /** Template rotation, stored as Rotation ordinal (0 = NONE); sizeX/sizeZ are the rotated footprint. */
    public int rotation;
    public UUID builder;
    public int nextBlock;
    public int totalBlocks;
    public int planVersion;
    public long lastProgressTick = Long.MIN_VALUE;
    public JobStatus status = JobStatus.QUEUED;
    public UUID owner;
    public int propertyLevel;
    public boolean completedOnce;
    public long retryAfter;
    public boolean catchup;
    public int supplyStallTicks;
    /** Consecutive blocked cycles without progress (plot is abandoned after 3). */
    public int blockCount;
    /** Runtime: first tick of the current catch-up obstacle stall (not persisted). */
    public long catchupStallSince = Long.MIN_VALUE;
    /** Runtime: the completion check already rewound this plot once (not persisted). */
    public boolean verified;
    private long lastStallTick = Long.MIN_VALUE;

    public BoundingBox box() {
        return new BoundingBox(origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + sizeX - 1, origin.getY() + sizeY - 1, origin.getZ() + sizeZ - 1);
    }

    public boolean contains(BlockPos pos) {
        return box().isInside(pos);
    }

    /** QUEUED or BUILDING, or BLOCKED with a pending retry. */
    public boolean isActive() {
        return status == JobStatus.QUEUED || status == JobStatus.BUILDING || (status == JobStatus.BLOCKED && retryAfter > 0);
    }

    public boolean isComplete() {
        return status == JobStatus.COMPLETE;
    }

    public boolean countsAsCompleted() {
        return completedOnce || status == JobStatus.COMPLETE;
    }

    /** Supply stall grows by 20 per distinct tick, capped at 1200. */
    public void addSupplyStall(long now) {
        if (now != lastStallTick) {
            lastStallTick = now;
            supplyStallTicks = Math.min(1200, supplyStallTicks + 20);
        }
    }

    public void releaseBuilder() {
        builder = null;
        if (status == JobStatus.BUILDING) {
            status = JobStatus.QUEUED;
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("retry_after", retryAfter);
        tag.putBoolean("catchup", catchup);
        tag.putInt("supply_stall_ticks", supplyStallTicks);
        tag.putInt("block_count", blockCount);
        tag.putString("id", id.toString());
        tag.putString("template", template.toString());
        tag.putInt("architecture_version", 1);
        tag.putLong("origin", origin.asLong());
        tag.putInt("size_x", sizeX);
        tag.putInt("size_y", sizeY);
        tag.putInt("size_z", sizeZ);
        tag.putInt("rotation", rotation);
        NbtHelper.putUuid(tag, "builder", builder);
        tag.putInt("next_block", nextBlock);
        tag.putInt("total_blocks", totalBlocks);
        tag.putInt("plan_version", planVersion);
        tag.putLong("last_progress_tick", lastProgressTick);
        tag.putString("status", status.name());
        NbtHelper.putUuid(tag, "owner", owner);
        tag.putInt("property_level", propertyLevel);
        tag.putBoolean("completed_once", completedOnce);
        return tag;
    }

    public static ConstructionJob load(CompoundTag tag) {
        ConstructionJob job = new ConstructionJob();
        job.retryAfter = tag.getLong("retry_after");
        job.catchup = tag.getBoolean("catchup");
        job.supplyStallTicks = NbtHelper.clamp(tag.getInt("supply_stall_ticks"), 0, 1200);
        job.blockCount = NbtHelper.clamp(tag.getInt("block_count"), 0, 3);
        UUID id = NbtHelper.uuid(tag, "id");
        job.id = id != null ? id : UUID.randomUUID();
        ResourceLocation template = ResourceLocation.tryParse(tag.getString("template"));
        job.template = template != null ? template : DEFAULT_TEMPLATE;
        job.origin = BlockPos.of(tag.getLong("origin"));
        job.sizeX = Math.max(1, tag.contains("size_x") ? tag.getInt("size_x") : 1);
        job.sizeY = Math.max(1, tag.contains("size_y") ? tag.getInt("size_y") : 1);
        job.sizeZ = Math.max(1, tag.contains("size_z") ? tag.getInt("size_z") : 1);
        job.rotation = Math.floorMod(tag.getInt("rotation"), 4);
        job.builder = NbtHelper.uuid(tag, "builder");
        job.nextBlock = Math.max(0, tag.getInt("next_block"));
        job.totalBlocks = Math.max(0, tag.getInt("total_blocks"));
        job.planVersion = Math.max(0, tag.getInt("plan_version"));
        job.lastProgressTick = tag.contains("last_progress_tick") ? tag.getLong("last_progress_tick") : Long.MIN_VALUE;
        job.status = JobStatus.byName(tag.getString("status"));
        job.owner = NbtHelper.uuid(tag, "owner");
        job.propertyLevel = NbtHelper.clamp(tag.getInt("property_level"), 0, 3);
        job.completedOnce = tag.contains("completed_once") ? tag.getBoolean("completed_once") : job.status == JobStatus.COMPLETE;
        if (job.owner != null && job.completedOnce) {
            job.status = JobStatus.COMPLETE;
            job.builder = null;
        }
        return job;
    }
}
