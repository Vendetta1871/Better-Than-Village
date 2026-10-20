package net.betterthanvillage.colony;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Per-colony wall planner state (owned by the construction subsystem). */
public final class FortificationState {
    /** Territory radius the current (outermost) ring was laid out for; 0 before the first ring. */
    public int radius;
    /** Territory radii of the older rings, kept and maintained inside the current one. */
    public final java.util.List<Integer> oldRings = new java.util.ArrayList<>();
    public int skipped;
    public long nextPlan;
    public final Set<BlockPos> surveyed = new LinkedHashSet<>();
    public final Set<BlockPos> abandoned = new LinkedHashSet<>();
    /** Built wall blocks, by position. */
    public final Map<BlockPos, FortBlock> managed = new LinkedHashMap<>();
    /** Wall blocks still to be built or removed, by position. */
    public final Map<BlockPos, FortBlock> pending = new LinkedHashMap<>();

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("radius", radius);
        tag.putIntArray("old_rings", oldRings.stream().mapToInt(Integer::intValue).toArray());
        tag.putInt("skipped", skipped);
        tag.putLong("next_plan", nextPlan);
        tag.putLongArray("surveyed", NbtHelper.packPositions(surveyed));
        tag.putLongArray("abandoned", NbtHelper.packPositions(abandoned));
        tag.put("managed", saveBlocks(managed));
        tag.put("pending", saveBlocks(pending));
        return tag;
    }

    private static ListTag saveBlocks(Map<BlockPos, FortBlock> blocks) {
        ListTag list = new ListTag();
        for (FortBlock block : blocks.values()) {
            list.add(block.save());
        }
        return list;
    }

    public void load(CompoundTag tag, HolderGetter<Block> blocks) {
        radius = NbtHelper.clamp(tag.getInt("radius"), 0, 512);
        oldRings.clear();
        for (int r : tag.getIntArray("old_rings")) oldRings.add(r);
        skipped = Math.max(0, tag.getInt("skipped"));
        nextPlan = tag.getLong("next_plan");
        surveyed.clear();
        surveyed.addAll(NbtHelper.unpackPositions(tag.getLongArray("surveyed")));
        abandoned.clear();
        for (BlockPos pos : NbtHelper.unpackPositions(tag.getLongArray("abandoned"))) {
            if (abandoned.size() >= 32768) break;
            abandoned.add(pos);
        }
        loadBlocks(tag.getList("managed", Tag.TAG_COMPOUND), managed, 16384, blocks);
        loadBlocks(tag.getList("pending", Tag.TAG_COMPOUND), pending, 32768, blocks);
    }

    private static void loadBlocks(ListTag list, Map<BlockPos, FortBlock> out, int max, HolderGetter<Block> blocks) {
        out.clear();
        for (int i = 0; i < list.size() && out.size() < max; i++) {
            FortBlock block = FortBlock.load(list.getCompound(i), blocks);
            out.put(block.pos(), block);
        }
    }
}
