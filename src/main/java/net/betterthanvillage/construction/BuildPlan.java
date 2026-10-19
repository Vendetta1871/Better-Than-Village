package net.betterthanvillage.construction;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.betterthanvillage.colony.ConstructionJob;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.betterthanvillage.colony.Colony;

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
        String key = job.template + "@" + job.origin.asLong() + "/" + job.sizeX + "," + job.sizeY + "," + job.sizeZ + "/" + job.rotation;
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
        Rotation rotation = rotation(job);
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        Set<BlockPos> keep = new HashSet<>();
        for (StructureTemplate.StructureBlockInfo info : HouseCatalog.blocks(template)) {
            BlockState s = info.state();
            BlockPos p = toWorld(job, info.pos());
            if (s.is(Blocks.JIGSAW)) {
                s = jigsawFinalState(info);
                if (s == null) {
                    // structure_void (or unreadable) final state: leave the world as it is
                    keep.add(p);
                    continue;
                }
            }
            if (s.is(Blocks.STRUCTURE_BLOCK)) {
                continue;
            }
            blocks.put(p, s.rotate(rotation));
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

    public static Rotation rotation(ConstructionJob job) {
        return Rotation.values()[Math.floorMod(job.rotation, 4)];
    }

    /**
     * World position of a template-local position: the template is rotated about its origin and
     * shifted so that the rotated footprint starts at the job origin (job.sizeX/sizeZ are the
     * rotated footprint; rotation 0 is the plain offset).
     */
    public static BlockPos toWorld(ConstructionJob job, BlockPos local) {
        return toWorld(job.origin, rotation(job), job.sizeX, job.sizeZ, local);
    }

    /** As {@link #toWorld(ConstructionJob, BlockPos)} for a rotated footprint rx * rz at origin. */
    public static BlockPos toWorld(BlockPos origin, Rotation rotation, int rx, int rz, BlockPos local) {
        BlockPos p = StructureTemplate.calculateRelativePosition(new StructurePlaceSettings().setRotation(rotation), local);
        // rx/rz are rotated sizes: the unrotated x size is rz for quarter turns
        int ox = switch (rotation) {
            case CLOCKWISE_90 -> rx - 1;
            case CLOCKWISE_180 -> rx - 1;
            default -> 0;
        };
        int oz = switch (rotation) {
            case CLOCKWISE_180 -> rz - 1;
            case COUNTERCLOCKWISE_90 -> rz - 1;
            default -> 0;
        };
        return origin.offset(p.getX() + ox, p.getY(), p.getZ() + oz);
    }

    /**
     * Final check of a finished plan: returns the index of the first structural placement step that
     * is undone again (damage while building; only when rewinding is allowed), else -1. Unbuilt
     * functional blocks (and, without rewinding, structural ones) become repair entries.
     */
    static int verify(ServerLevel level, Colony colony, ConstructionJob job, List<Step> plan, boolean rewind) {
        if (plan == null) return -1;
        for (int i = 0; i < plan.size(); i++) {
            Step s = plan.get(i);
            if (s.clear() || !level.isLoaded(s.pos()) || colony.repairs.containsKey(s.pos()) || satisfied(level, s)) continue;
            if (HouseConstructionTask.placementProtected(level, s.pos(), level.getBlockState(s.pos()))) continue;
            BuildCost.Cost c = HouseConstructionTask.cost(level, s);
            if (c.kind() == BuildCost.Kind.STRUCTURAL && rewind) return i;
            if (c.kind() != BuildCost.Kind.OPTIONAL && !s.foundation()) Damage.defer(level, colony, s.pos(), s.state());
        }
        return -1;
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

    public static boolean woodLike(BlockState state) {
        String path = path(state.getBlock());
        return WOOD_LIKE.stream().anyMatch(path::contains);
    }

    public static boolean stoneLike(BlockState state) {
        String path = path(state.getBlock());
        return STONE_LIKE.stream().anyMatch(path::contains);
    }

    /** The stack is an ingredient of some way to pay for the state (see BuildCost). */
    public static boolean accepts(BlockState state, ItemStack stack) {
        return BuildCost.useful(BuildCost.of(state), stack);
    }
}
