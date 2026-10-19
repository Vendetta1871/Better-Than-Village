package net.betterthanvillage.construction;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.betterthanvillage.colony.ConstructionJob;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Bill of work for a plot (05 §2.4) and the block/material rules shared by builder, repair and catch-up. */
public final class BuildPlan {
    public static final int PLAN_VERSION = 9;
    private static final Set<String> IGNORED_PROPERTIES = Set.of("shape", "north", "south", "east", "west", "up", "down",
            "open", "occupied", "powered", "lit", "age", "distance", "persistent");
    private static final List<String> LATE = List.of("lantern", "torch", "ladder", "carpet", "sign", "button", "lever", "rail",
            "banner", "candle", "flower_pot", "pressure_plate");
    private static final List<String> WOOD_LIKE = List.of("planks", "wood", "log", "fence", "door", "trapdoor", "wooden_", "_stairs", "_slab");
    private static final List<String> STONE_LIKE = List.of("cobblestone", "stone", "andesite", "diorite", "granite");

    /** One plan step. */
    public record Step(BlockPos pos, BlockState state, boolean clear, boolean foundation) {
        public boolean placement() {
            return !clear && !foundation;
        }
    }

    private static final Map<String, List<Step>> CACHE = new HashMap<>();

    private BuildPlan() {
    }

    /** Loads (cached) the plan for a job, or null if the template is missing. */
    public static List<Step> load(ServerLevel level, ConstructionJob job) {
        String key = job.template + "@" + job.origin.asLong() + "/" + job.sizeX + "," + job.sizeY + "," + job.sizeZ;
        synchronized (CACHE) {
            List<Step> cached = CACHE.get(key);
            if (cached != null) return cached;
        }
        Optional<StructureTemplate> template = HouseCatalog.template(level, job.template);
        if (template.isEmpty()) {
            return null;
        }
        List<Step> plan = build(job, template.get());
        synchronized (CACHE) {
            if (CACHE.size() > 64) CACHE.clear();
            CACHE.put(key, plan);
        }
        return plan;
    }

    private static List<Step> build(ConstructionJob job, StructureTemplate template) {
        BoundingBox box = job.box();
        BlockPos origin = job.origin;
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        Set<BlockPos> keep = new HashSet<>();
        for (StructureTemplate.StructureBlockInfo info : HouseCatalog.blocks(template)) {
            BlockState s = info.state();
            if (s.is(Blocks.JIGSAW)) {
                s = jigsawFinalState(info);
                if (s == null) {
                    // structure_void (or unreadable) final state: leave the world as it is
                    keep.add(origin.offset(info.pos()));
                    continue;
                }
            }
            if (s.is(Blocks.STRUCTURE_BLOCK)) {
                continue;
            }
            blocks.put(origin.offset(info.pos()), s);
        }
        List<Step> steps = new ArrayList<>();
        for (int x = box.minX() - 1; x <= box.maxX() + 1; x++) {
            for (int z = box.minZ() - 1; z <= box.maxZ() + 1; z++) {
                for (int y = box.minY() + 1; y <= box.maxY() + 2; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (keep.contains(p)) continue;
                    BlockState t = blocks.get(p);
                    steps.add(new Step(p, t == null || t.is(Blocks.STRUCTURE_VOID) ? Blocks.AIR.defaultBlockState() : t, true, false));
                }
            }
        }
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                // the template's own floor block replaces the top foundation block
                BlockState floor = blocks.get(new BlockPos(x, box.minY(), z));
                int top = floor != null && !floor.isAir() && !floor.is(Blocks.STRUCTURE_VOID) ? box.minY() - 1 : box.minY();
                for (int y = top; y >= box.minY() - 4; y--) {
                    steps.add(new Step(new BlockPos(x, y, z), Blocks.COBBLESTONE.defaultBlockState(), false, true));
                }
            }
        }
        blocks.forEach((p, s) -> {
            if (!s.isAir() && !s.is(Blocks.STRUCTURE_VOID)) {
                steps.add(new Step(p, s, false, false));
            }
        });
        steps.sort(Comparator.<Step>comparingInt(BuildPlan::category)
                .thenComparingInt(s -> s.clear() ? -s.pos().getY() : s.pos().getY())
                .thenComparingInt(s -> -s.pos().getZ())
                .thenComparingInt(s -> -s.pos().getX()));
        return List.copyOf(steps);
    }

    /** Final state of a template jigsaw block (as JigsawReplacementProcessor), or null for structure_void/unreadable. */
    static BlockState jigsawFinalState(StructureTemplate.StructureBlockInfo info) {
        if (info.nbt() == null) return null;
        BlockState s;
        try {
            s = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), info.nbt().getString("final_state"), true).blockState();
        } catch (CommandSyntaxException e) {
            return null;
        }
        return s.is(Blocks.STRUCTURE_VOID) ? null : s;
    }

    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    private static int category(Step s) {
        if (s.clear()) return 0;
        if (s.foundation()) return 1;
        String path = path(s.state().getBlock());
        if (path.contains("chain")) return 3;
        Block b = s.state().getBlock();
        if (b instanceof BushBlock || b instanceof DoorBlock || b instanceof BedBlock) return 4;
        for (String late : LATE) {
            if (path.contains(late)) return 4;
        }
        return 2;
    }

    static String path(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).getPath();
    }

    // ---------------------------------------------------------------- matching

    /** Same block and equal properties except the 14 ignored ones (and chest "type"). */
    public static boolean matches(BlockState world, BlockState target) {
        if (world.getBlock() != target.getBlock()) return false;
        boolean chest = world.getBlock() instanceof ChestBlock;
        for (Property<?> p : target.getProperties()) {
            if (IGNORED_PROPERTIES.contains(p.getName()) || (chest && p.getName().equals("type"))) continue;
            if (!world.hasProperty(p) || !world.getValue(p).equals(target.getValue(p))) return false;
        }
        return true;
    }

    /** Step satisfied by the current world (05 §2.5). */
    public static boolean satisfied(ServerLevel level, Step s) {
        BlockState w = level.getBlockState(s.pos());
        if (matches(w, s.state())) return true;
        if (s.clear()) return w.isAir() || !Terrain.clearable(w);
        if (s.foundation()) return w.isFaceSturdy(level, s.pos(), Direction.UP) && w.getFluidState().isEmpty();
        return false;
    }

    // ---------------------------------------------------------------- materials

    /** Item needed to place a state; AIR = free. */
    public static Item materialItem(BlockState state) {
        Item item = state.getBlock().asItem();
        if (item != Items.AIR) return item;
        if (state.is(Blocks.FARMLAND) || state.is(Blocks.DIRT_PATH)) return Items.DIRT;
        if (state.is(Blocks.WATER)) return Items.WATER_BUCKET;
        if (state.is(Blocks.LAVA)) return Items.LAVA_BUCKET;
        if (state.is(Blocks.PUMPKIN_STEM) || state.is(Blocks.ATTACHED_PUMPKIN_STEM)) return Items.PUMPKIN_SEEDS;
        if (state.is(Blocks.MELON_STEM) || state.is(Blocks.ATTACHED_MELON_STEM)) return Items.MELON_SEEDS;
        if (state.is(Blocks.REDSTONE_WIRE)) return Items.REDSTONE;
        if (state.is(Blocks.TRIPWIRE)) return Items.STRING;
        return Items.AIR;
    }

    /** Second half of a two-block piece (upper door, bed head, upper double plant): paid with the first half. */
    public static boolean secondaryHalf(BlockState state) {
        if (state.getBlock() instanceof DoorBlock) return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
        if (state.getBlock() instanceof BedBlock) return state.getValue(BedBlock.PART) == BedPart.HEAD;
        if (state.getBlock() instanceof DoublePlantBlock) return state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER;
        return false;
    }

    /** Placement costs no material. */
    public static boolean free(BlockState state) {
        return materialItem(state) == Items.AIR || secondaryHalf(state);
    }

    public static boolean woodLike(BlockState state) {
        String path = path(state.getBlock());
        return WOOD_LIKE.stream().anyMatch(path::contains);
    }

    public static boolean stoneLike(BlockState state) {
        String path = path(state.getBlock());
        return STONE_LIKE.stream().anyMatch(path::contains);
    }

    /** Accepted item for a state (exact item, or any #logs / #stone_crafting_materials substitute, or sand for sandstone). */
    public static boolean accepts(BlockState state, ItemStack stack) {
        if (stack.isEmpty()) return false;
        Item needed = materialItem(state);
        if (needed != Items.AIR && stack.is(needed)) return true;
        if (stack.is(ItemTags.LOGS) || stack.is(ItemTags.STONE_CRAFTING_MATERIALS)) return true;
        return path(state.getBlock()).contains("sandstone") && (stack.is(Items.SAND) || stack.is(Items.RED_SAND));
    }
}
