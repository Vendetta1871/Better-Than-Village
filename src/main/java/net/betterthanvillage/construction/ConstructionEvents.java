package net.betterthanvillage.construction;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.SafeEditScope;
import net.betterthanvillage.society.ColonyCommands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** NeoForge game-event listeners and /btv commands of the construction subsystem (05 §8.1). */
public final class ConstructionEvents {
    private ConstructionEvents() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(ConstructionEvents::onRegisterCommands);
        bus.addListener(ConstructionEvents::onServerStopped);
        net.betterthanvillage.society.Deeds.siteFinder = HousePlanner::findCommissionSite;
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        Damage.clearCaches();
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("btv")
                .then(Commands.literal("jobs").requires(s -> s.hasPermission(2)).executes(ConstructionEvents::jobs))
                .then(Commands.literal("repair").requires(s -> s.hasPermission(2)).executes(ConstructionEvents::repair))
                .then(Commands.literal("house").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("biome", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(HouseCatalog.BIOMES, b))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(HouseCatalog.get(ctx.getSource().getLevel(),
                                                StringArgumentType.getString(ctx, "biome")).all().stream().map(HouseCatalog.Entry::name), b))
                                        .executes(ctx -> house(ctx, null))
                                        .then(Commands.argument("floor", BlockPosArgument.blockPos())
                                                .executes(ctx -> house(ctx, BlockPosArgument.getLoadedBlockPos(ctx, "floor"))))))));
    }

    /** /btv jobs: construction plots, road head and wall state of the nearest colony (debug view). */
    private static int jobs(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Colony colony = ColonyCommands.colonyNear(source);
        if (colony == null) {
            source.sendFailure(Component.translatable("command.better_than_village.repair.none"));
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        for (ConstructionJob job : colony.jobs) {
            sb.append(job.template.getPath()).append(" @").append(job.origin.toShortString()).append(' ').append(job.status)
                    .append(' ').append(job.nextBlock).append('/').append(job.totalBlocks).append(" retry ").append(job.retryAfter)
                    .append(job.owner != null ? " owned" : "").append(job.catchup ? " catchup" : "").append('\n');
        }
        sb.append("road head ").append(colony.roadQueue.isEmpty() ? "-" : colony.roadQueue.get(0).toShortString())
                .append(" skipped ").append(colony.skippedRoadSegments).append(" stall ").append(colony.roadStallTicks).append('\n');
        var f = colony.fortifications;
        sb.append("wall radius ").append(f.radius).append(" built ").append(f.managed.size()).append(" pending ").append(f.pending.size())
                .append(" skipped ").append(f.skipped).append(" abandoned ").append(f.abandoned.size()).append('\n');
        for (var v : SiteSearch.builders(source.getLevel(), colony)) {
            sb.append("builder @").append(v.blockPosition().toShortString()).append(" target ")
                    .append(v.getBrain().getMemory(net.betterthanvillage.registry.ModRegistries.BUILD_TARGET_POS.get()).map(BlockPos::toShortString).orElse("-"))
                    .append(" hand ").append(v.getMainHandItem()).append('\n');
        }
        sb.append("site cursor ").append(colony.houseSiteCursor).append(" next plan ").append(colony.nextHousePlanTick);
        String text = sb.toString();
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    /** /btv repair: repair audit of the nearest colony. */
    private static int repair(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Colony colony = ColonyCommands.colonyNear(source);
        if (colony == null) {
            source.sendFailure(Component.translatable("command.better_than_village.repair.none"));
            return 0;
        }
        int n = Damage.audit(source.getLevel(), colony);
        source.sendSuccess(() -> Component.translatable("command.better_than_village.repair.done", n), true);
        return n;
    }

    /** /btv house &lt;biome&gt; &lt;name&gt; [floor]: test placement of a catalog house, not registered as a plot. */
    private static int house(CommandContext<CommandSourceStack> ctx, BlockPos floorArg) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        String biome = StringArgumentType.getString(ctx, "biome");
        String name = StringArgumentType.getString(ctx, "name");
        BlockPos floor = floorArg != null ? floorArg : BlockPos.containing(source.getPosition()).offset(-6, -1, 6);
        HouseCatalog.Entry entry = HouseCatalog.BIOMES.contains(biome) ? HouseCatalog.byName(level, biome, name) : null;
        Optional<StructureTemplate> template = entry == null ? Optional.empty() : HouseCatalog.template(level, entry.id());
        if (template.isEmpty()) {
            source.sendFailure(Component.translatable("command.better_than_village.house.missing"));
            return 0;
        }
        var size = template.get().getSize();
        BlockPos origin = floor;
        BoundingBox box = new BoundingBox(origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + size.getX() - 1, origin.getY() + size.getY() - 1, origin.getZ() + size.getZ() - 1);
        if (box.minY() < level.getMinBuildHeight() || box.maxY() >= level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(new BlockPos(box.minX(), 0, box.minZ()))
                || !level.getWorldBorder().isWithinBounds(new BlockPos(box.maxX(), 0, box.maxZ()))) {
            source.sendFailure(Component.translatable("command.better_than_village.house.bounds"));
            return 0;
        }
        if (!Terrain.loaded(level, box.minX(), box.minZ(), box.maxX(), box.maxZ())) {
            source.sendFailure(Component.translatable("command.better_than_village.house.unloaded"));
            return 0;
        }
        AABB aabb = AABB.of(box);
        if (!level.getEntitiesOfClass(LivingEntity.class, aabb, e -> e.isAlive() && !e.isSpectator()).isEmpty()) {
            source.sendFailure(Component.translatable("command.better_than_village.house.entity"));
            return 0;
        }
        for (Colony c : ColonyManager.data(level).colonies()) {
            for (ConstructionJob job : c.jobs) {
                if (job.box().intersects(box)) {
                    source.sendFailure(Component.translatable("command.better_than_village.house.overlap"));
                    return 0;
                }
            }
        }
        Map<BlockPos, BlockState> previous = new LinkedHashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            BlockState s = level.getBlockState(p);
            boolean ground = s.is(BlockTags.DIRT) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.SAND)
                    || s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY) || s.is(Blocks.SNOW_BLOCK);
            if ((ColonyManager.data(level).isPlayerBlock(p) && !s.isAir()) || level.getBlockEntity(p) != null || !s.getFluidState().isEmpty()
                    || (!s.isAir() && !s.canBeReplaced() && (p.getY() > floor.getY() || !ground))) {
                source.sendFailure(Component.translatable("command.better_than_village.house.occupied", p.toShortString(), size.getX(), size.getZ()));
                return 0;
            }
            previous.put(p.immutable(), s);
        }
        StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true)
                .addProcessor(net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor.INSTANCE);
        boolean ok;
        try {
            ok = SafeEditScope.call(() -> template.get().placeInWorld(level, origin, origin, settings, level.getRandom(), 3));
        } catch (RuntimeException e) {
            restore(level, previous);
            source.sendFailure(Component.translatable("command.better_than_village.house.failed"));
            throw e;
        }
        if (!ok) {
            restore(level, previous);
            source.sendFailure(Component.translatable("command.better_than_village.house.failed"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.better_than_village.house.done", entry.name(), floor.toShortString(),
                size.getX(), size.getZ()), true);
        return 1;
    }

    private static void restore(ServerLevel level, Map<BlockPos, BlockState> previous) {
        SafeEditScope.run(() -> previous.forEach((p, s) -> level.setBlock(p, s, 18)));
    }
}
