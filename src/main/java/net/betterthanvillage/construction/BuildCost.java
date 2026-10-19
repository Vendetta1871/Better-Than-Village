package net.betterthanvillage.construction;

import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Material cost of a placed block, shared by live construction, catch-up and repairs. Logs and
 * cobblestone only stand in for wood-like/stone-like structure; functional blocks need their exact
 * item or the listed recipe ingredients, so breaking colony blocks and having them rebuilt never
 * yields more than was paid.
 */
public final class BuildCost {
    /**
     * FREE: placed without material. STRUCTURAL: the builder waits for material. DEFERRABLE: an
     * unpaid block becomes a repair entry and is built when material arrives. OPTIONAL: decoration,
     * skipped when unpaid.
     */
    public enum Kind { FREE, STRUCTURAL, DEFERRABLE, OPTIONAL }

    /** Ingredient: matching items and count; bulk parts (logs, stone, dirt) keep a storage reserve. */
    public record Part(Predicate<ItemStack> match, int count, boolean bulk) {
    }

    /** One way to pay; refund is given back after paying (empty bucket), or null. */
    public record Option(List<Part> parts, Item refund) {
    }

    public record Cost(Kind kind, List<Option> options) {
        public boolean free() {
            return kind == Kind.FREE;
        }
    }

    /** Item source for paying and counting. */
    public interface Source {
        int count(Part part);

        /** Removes n matching items; only called after count() confirmed them. */
        void take(Part part, int n);

        void give(ItemStack stack);
    }

    /** Reserve of bulk material left in storage for tools and torches. */
    static final int BULK_RESERVE = 8;
    static final Cost FREE = new Cost(Kind.FREE, List.of());
    /** Foundation fill: any stone, dirt or log. */
    static final Cost FOUNDATION = new Cost(Kind.STRUCTURAL, List.of(option(stone(1)), option(dirt(1)), option(logs(1))));

    private static final Map<BlockState, Cost> CACHE = new HashMap<>();

    private BuildCost() {
    }

    // ---------------------------------------------------------------- table

    /** Cost of a block at a position: the second half of a door/bed/double plant is free when its partner stands. */
    public static Cost at(Level level, BlockPos pos, BlockState state) {
        BlockPos partner = partner(pos, state);
        if (partner != null && level.isLoaded(partner) && level.getBlockState(partner).is(state.getBlock())) return FREE;
        return of(state);
    }

    /** Other half of a two-block piece, or null. */
    static BlockPos partner(BlockPos pos, BlockState state) {
        Block b = state.getBlock();
        if (b instanceof DoorBlock) return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        if (b instanceof DoublePlantBlock) return state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        if (b instanceof BedBlock) {
            Direction facing = state.getValue(BedBlock.FACING);
            return state.getValue(BedBlock.PART) == BedPart.FOOT ? pos.relative(facing) : pos.relative(facing.getOpposite());
        }
        return null;
    }

    public static Cost of(BlockState state) {
        synchronized (CACHE) {
            Cost c = CACHE.get(state);
            if (c == null) {
                c = compute(state);
                CACHE.put(state, c);
            }
            return c;
        }
    }

    private static Cost compute(BlockState s) {
        Block b = s.getBlock();
        String path = BuildPlan.path(b);
        if (s.is(Blocks.WATER)) return new Cost(Kind.OPTIONAL, List.of(new Option(List.of(item(Items.WATER_BUCKET, 1)), Items.BUCKET)));
        if (s.is(Blocks.LAVA)) return new Cost(Kind.OPTIONAL, List.of(new Option(List.of(item(Items.LAVA_BUCKET, 1)), Items.BUCKET)));
        if (s.isAir() || s.canBeReplaced()) return FREE;
        Item exact = BuildPlan.materialItem(s);
        List<Option> opts = new ArrayList<>();
        if (exact != Items.AIR) opts.add(option(item(exact, 1)));
        // decoration: only the exact item (potted plants: the pot)
        if (b instanceof FlowerPotBlock && b != Blocks.FLOWER_POT) {
            return new Cost(Kind.OPTIONAL, List.of(option(item(Items.FLOWER_POT, 1))));
        }
        if ((b instanceof BushBlock && !(b instanceof net.minecraft.world.level.block.CropBlock)) || b instanceof DoublePlantBlock
                || b instanceof CactusBlock || b instanceof SugarCaneBlock || b instanceof SeaPickleBlock
                || s.is(BlockTags.BANNERS) || path.contains("banner")) {
            return new Cost(Kind.OPTIONAL, opts);
        }
        // functional blocks: exact item or recipe ingredients
        Kind f = Kind.DEFERRABLE;
        if (b instanceof BedBlock) return cost(f, opts, option(wool(3), logs(1)));
        if ((b instanceof ChestBlock && b == Blocks.CHEST) || b instanceof BarrelBlock) return cost(f, opts, option(logs(2)));
        if (b == Blocks.CRAFTING_TABLE || b instanceof ComposterBlock) return cost(f, opts, option(logs(1)));
        if (s.is(BlockTags.WOODEN_DOORS) || s.is(BlockTags.WOODEN_TRAPDOORS) || s.is(BlockTags.WOODEN_FENCES) || s.is(BlockTags.FENCE_GATES)
                || s.is(BlockTags.WOODEN_PRESSURE_PLATES) || s.is(BlockTags.WOODEN_BUTTONS) || b instanceof LadderBlock || s.is(BlockTags.ALL_SIGNS)) {
            return cost(f, opts, option(logs(1)));
        }
        if (b == Blocks.FURNACE) return cost(f, opts, option(stone(8)));
        if (b == Blocks.SMOKER) return cost(f, opts, option(stone(8), logs(4)));
        if (b == Blocks.CAMPFIRE) return cost(f, opts, option(logs(4), coal(1)));
        if (s.is(BlockTags.IMPERMEABLE) || path.endsWith("glass_pane") || path.endsWith("glass")) {
            // colonies smelt glass from sand or cobblestone
            return cost(f, opts, option(sand(1)), option(stone(1)));
        }
        if (s.is(BlockTags.WOOL)) return cost(f, opts, option(wool(1)));
        if (s.is(BlockTags.WOOL_CARPETS)) return cost(f, opts, option(wool(1)));
        if (b == Blocks.TORCH || b == Blocks.WALL_TORCH) return cost(f, opts, option(coal(1), new Part(x -> x.is(Items.STICK) || x.is(ItemTags.LOGS), 1, false)));
        if (b == Blocks.LANTERN) return cost(f, opts, option(item(Items.IRON_INGOT, 1), coal(1)), option(item(Items.IRON_NUGGET, 8), coal(1)));
        if (b == Blocks.HAY_BLOCK) return cost(f, opts, option(item(Items.WHEAT, 9)));
        if (b == Blocks.CLAY) return cost(f, opts, option(item(Items.CLAY_BALL, 4)));
        // any other functional block (block entities, workstations, redstone): exact item only
        if (s.hasBlockEntity() || path.contains("cutter") || path.contains("grindstone") || path.contains("redstone") || path.contains("glowstone")) {
            return new Cost(opts.isEmpty() ? Kind.OPTIONAL : Kind.DEFERRABLE, opts);
        }
        // structure
        Kind st = Kind.STRUCTURAL;
        if (s.is(BlockTags.LOGS) || s.is(BlockTags.PLANKS) || s.is(BlockTags.WOODEN_STAIRS) || s.is(BlockTags.WOODEN_SLABS)) {
            return cost(st, opts, option(logs(1)), option(new Part(x -> x.is(ItemTags.PLANKS), 1, true)));
        }
        if (s.is(BlockTags.SAND) || b == Blocks.GRAVEL) return cost(st, opts, option(sand(1)));
        if (path.contains("sandstone")) return cost(st, opts, option(sand(1)), option(stone(1)));
        if (s.is(BlockTags.DIRT) || b == Blocks.DIRT_PATH || b == Blocks.FARMLAND || b == Blocks.SNOW_BLOCK) {
            return cost(st, opts, option(dirt(1)), option(stone(1)));
        }
        if (s.is(BlockTags.STAIRS) || s.is(BlockTags.SLABS) || s.is(BlockTags.WALLS) || s.is(BlockTags.BASE_STONE_OVERWORLD)
                || s.is(BlockTags.TERRACOTTA) || b == Blocks.PACKED_ICE || b == Blocks.BLUE_ICE
                || path.contains("cobblestone") || path.contains("stone") || path.contains("brick") || path.contains("deepslate")) {
            return cost(st, opts, option(stone(1)));
        }
        return new Cost(opts.isEmpty() ? Kind.OPTIONAL : Kind.DEFERRABLE, opts);
    }

    private static Cost cost(Kind kind, List<Option> exact, Option... recipes) {
        List<Option> all = new ArrayList<>(exact);
        all.addAll(List.of(recipes));
        return new Cost(kind, List.copyOf(all));
    }

    static Option option(Part... parts) {
        return new Option(List.of(parts), null);
    }

    static Part item(Item item, int n) {
        return new Part(s -> s.is(item), n, buildingMaterial(new ItemStack(item)));
    }

    /** Bulk building material (logs, planks, stone, dirt, sand): fetched by the stack, storage reserve kept. */
    public static boolean buildingMaterial(ItemStack s) {
        return s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS) || s.is(ItemTags.STONE_CRAFTING_MATERIALS) || s.is(Items.COBBLESTONE)
                || s.is(ItemTags.DIRT) || s.is(ItemTags.SAND);
    }

    static Part logs(int n) {
        return new Part(s -> s.is(ItemTags.LOGS), n, true);
    }

    static Part stone(int n) {
        return new Part(s -> s.is(ItemTags.STONE_CRAFTING_MATERIALS) || s.is(Items.COBBLESTONE), n, true);
    }

    static Part dirt(int n) {
        return new Part(s -> s.is(ItemTags.DIRT), n, true);
    }

    static Part sand(int n) {
        return new Part(s -> s.is(ItemTags.SAND), n, true);
    }

    static Part wool(int n) {
        return new Part(s -> s.is(ItemTags.WOOL), n, false);
    }

    static Part coal(int n) {
        return new Part(s -> s.is(Items.COAL) || s.is(Items.CHARCOAL), n, false);
    }

    // ---------------------------------------------------------------- paying

    /** First option fully covered by the source, or null. */
    public static Option payable(Cost cost, Source src) {
        for (Option o : cost.options()) {
            boolean ok = true;
            for (Part p : o.parts()) {
                if (src.count(p) < p.count()) {
                    ok = false;
                    break;
                }
            }
            if (ok) return o;
        }
        return null;
    }

    /** Pays the first payable option; false (nothing taken) when none is payable. FREE costs succeed. */
    public static boolean pay(Cost cost, Source src) {
        if (cost.free()) return true;
        Option o = payable(cost, src);
        if (o == null) return false;
        for (Part p : o.parts()) src.take(p, p.count());
        if (o.refund() != null) src.give(new ItemStack(o.refund()));
        return true;
    }

    /** True if the stack is an ingredient of any option. */
    public static boolean useful(Cost cost, ItemStack stack) {
        if (stack.isEmpty()) return false;
        for (Option o : cost.options()) {
            for (Part p : o.parts()) {
                if (p.match().test(stack)) return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- sources

    /** Main hand + inventory of a villager. */
    public static Source inventory(Villager v) {
        return new Source() {
            @Override
            public int count(Part part) {
                int n = 0;
                ItemStack hand = v.getMainHandItem();
                if (part.match().test(hand)) n += hand.getCount();
                for (int i = 0; i < v.getInventory().getContainerSize(); i++) {
                    ItemStack s = v.getInventory().getItem(i);
                    if (!s.isEmpty() && part.match().test(s)) n += s.getCount();
                }
                return n;
            }

            @Override
            public void take(Part part, int n) {
                ItemStack hand = v.getMainHandItem();
                if (n > 0 && part.match().test(hand)) {
                    int k = Math.min(n, hand.getCount());
                    hand.shrink(k);
                    n -= k;
                    if (hand.isEmpty()) v.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                }
                for (int i = 0; i < v.getInventory().getContainerSize() && n > 0; i++) {
                    ItemStack s = v.getInventory().getItem(i);
                    if (s.isEmpty() || !part.match().test(s)) continue;
                    int k = Math.min(n, s.getCount());
                    s.shrink(k);
                    n -= k;
                }
                v.getInventory().setChanged();
            }

            @Override
            public void give(ItemStack stack) {
                ItemStack rest = v.getInventory().addItem(stack);
                if (!rest.isEmpty()) v.spawnAtLocation(rest);
            }
        };
    }

    /** Usable colony storage; bulk parts keep {@link #BULK_RESERVE} items back. */
    public static Source storage(ServerLevel level, Colony colony) {
        return new Source() {
            @Override
            public int count(Part part) {
                int n = ColonyStorage.count(level, colony, part.match());
                return part.bulk() ? Math.max(0, n - BULK_RESERVE) : n;
            }

            @Override
            public void take(Part part, int n) {
                ColonyStorage.consume(level, colony, part.match(), n);
            }

            @Override
            public void give(ItemStack stack) {
                ColonyStorage.deposit(level, colony, stack);
            }
        };
    }

    /** Catch-up: the virtual catch-up stock first, then storage. */
    public static Source stockThenStorage(ServerLevel level, Colony colony) {
        Source storage = storage(level, colony);
        return new Source() {
            private int stock(Part part) {
                int n = 0;
                for (Map.Entry<Item, Integer> e : colony.catchupStock.entrySet()) {
                    if (e.getValue() > 0 && part.match().test(new ItemStack(e.getKey()))) n += e.getValue();
                }
                return n;
            }

            @Override
            public int count(Part part) {
                return stock(part) + storage.count(part);
            }

            @Override
            public void take(Part part, int n) {
                var it = colony.catchupStock.entrySet().iterator();
                while (it.hasNext() && n > 0) {
                    Map.Entry<Item, Integer> e = it.next();
                    if (!part.match().test(new ItemStack(e.getKey()))) continue;
                    int k = Math.min(n, e.getValue());
                    n -= k;
                    if (e.getValue() - k <= 0) it.remove();
                    else e.setValue(e.getValue() - k);
                }
                if (n > 0) storage.take(part, n);
            }

            @Override
            public void give(ItemStack stack) {
                storage.give(stack);
            }
        };
    }

    /** Sum of two sources (counting only: used to plan a fetch). */
    public static Source union(Source a, Source b) {
        return new Source() {
            @Override
            public int count(Part part) {
                return a.count(part) + b.count(part);
            }

            @Override
            public void take(Part part, int n) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void give(ItemStack stack) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** Items of a container matching a part. */
    static int count(Container c, Part part) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && part.match().test(s)) n += s.getCount();
        }
        return n;
    }

    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }
}
