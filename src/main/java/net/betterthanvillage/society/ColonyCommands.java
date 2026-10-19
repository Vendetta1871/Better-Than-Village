package net.betterthanvillage.society;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.betterthanvillage.ai.VillagerUtil;
import net.betterthanvillage.ai.work.WorkTracker;
import net.betterthanvillage.colony.Census;
import net.betterthanvillage.colony.Colony;
import net.betterthanvillage.colony.ColonyManager;
import net.betterthanvillage.colony.ColonyRole;
import net.betterthanvillage.colony.ColonyStorage;
import net.betterthanvillage.colony.ConstructionJob;
import net.betterthanvillage.colony.JobStatus;
import net.betterthanvillage.colony.RoleBalancer;
import net.betterthanvillage.colony.StorageKind;
import net.betterthanvillage.colony.StoragePlacer;
import net.betterthanvillage.colony.TerrainSurvey;
import net.betterthanvillage.config.ColonyConfig;
import net.betterthanvillage.registry.ModRegistries;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** /btv commands (01 §10, 06 §8). */
public final class ColonyCommands {
    private static final SuggestionProvider<CommandSourceStack> ROLES = (ctx, b) -> SharedSuggestionProvider.suggest(
            List.of("builder", "guard", "lumberjack", "miner", "farmer", "fisherman", "rancher"), b);

    private ColonyCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("btv")
                .then(Commands.literal("status").executes(ColonyCommands::status))
                .then(film()));
    }

    /** Colony at the command position, else the nearest colony core. */
    public static Colony colonyNear(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPos.containing(source.getPosition());
        Colony colony = ColonyManager.colonyAt(level, pos);
        if (colony == null) {
            double best = Double.MAX_VALUE;
            for (Colony c : ColonyManager.data(level).colonies()) {
                double d = c.core().distSqr(pos);
                if (d < best) {
                    best = d;
                    colony = c;
                }
            }
        }
        return colony;
    }

    /** Helper for subcommands added by other features. */
    public static Map<ColonyRole, Integer> roleCounts(ServerLevel level, Colony c) {
        EnumMap<ColonyRole, Integer> counts = new EnumMap<>(ColonyRole.class);
        for (Villager v : ColonyManager.adultMembers(level, c)) {
            counts.merge(c.roleOf(v.getUUID()), 1, Integer::sum);
        }
        return counts;
    }

    private static void ok(CommandSourceStack source, Component text) {
        source.sendSuccess(() -> text, false);
    }

    // ---------------------------------------------------------------- status

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        Colony c = colonyNear(source);
        if (c == null) {
            source.sendFailure(Msg.command("status.none"));
            return 0;
        }
        for (Component line : statusLines(level, c)) {
            ok(source, line);
        }
        return 1;
    }

    static List<Component> statusLines(ServerLevel level, Colony c) {
        long now = level.getGameTime();
        List<Villager> members = ColonyManager.members(level, c);
        List<Villager> adults = ColonyManager.adultMembers(level, c);
        Map<ColonyRole, Integer> counts = roleCounts(level, c);
        int activeJobs = 0, completed = 0;
        for (ConstructionJob job : c.jobs) {
            if (job.isActive()) activeJobs++;
            if (job.isComplete()) completed++;
        }
        int activeWorkers = 0;
        StringJoiner workers = new StringJoiner(", ");
        StringJoiner militia = new StringJoiner(", ");
        StringJoiner targets = new StringJoiner(", ");
        for (Villager v : adults) {
            ColonyRole role = c.roleOf(v.getUUID());
            if (VillagerUtil.anyModTaskRunning(v)) activeWorkers++;
            if (role == ColonyRole.CITIZEN) continue;
            String status = WorkTracker.status(v);
            String entry = role.serializedName() + "@" + v.blockPosition().toShortString() + " "
                    + VillagerUtil.currentActivity(v) + ": " + (status.isEmpty() ? "ожидание работы" : status);
            if (role == ColonyRole.GUARD) {
                militia.add(entry + " hp=" + Math.round(v.getHealth()) + " main=" + v.getMainHandItem().getItem());
            }
            workers.add(entry);
            for (var mem : ModRegistries.targetMemories()) {
                v.getBrain().getMemory(mem).ifPresent(p -> targets.add(role.serializedName() + "->" + p.toShortString()));
            }
        }
        long threatAge = c.lastThreatTick == Colony.NEVER ? -1 : now - c.lastThreatTick;
        List<Component> out = new ArrayList<>();
        out.add(Msg.command("status.main", c.core().toShortString(), c.radius(), c.population(), c.beds(), c.cachedFood,
                ColonyStorage.carriedFood(members), c.cachedLogs, c.cachedStone, c.cachedIron, c.cachedDiamonds, c.storages.size(),
                counts.toString(), c.jobs.size(), activeWorkers, threatAge < 0 ? "-" : String.valueOf(threatAge)));
        out.add(Msg.command("status.workers", workers.toString()));
        out.add(Msg.command("status.militia", militia.toString()));
        int golems = Economy.golemCount(level, c);
        out.add(Msg.command("status.golems", golems, Math.max(4, (c.population() + 5) / 6),
                ColonyStorage.count(level, c, s -> s.is(Items.IRON_INGOT)),
                ColonyStorage.count(level, c, s -> s.is(Items.PUMPKIN) || s.is(Items.CARVED_PUMPKIN)), Math.max(0, c.nextGolemTick - level.getServer().getTickCount())));
        out.add(Msg.command("status.economy", ColonyStorage.count(level, c, s -> s.is(Items.EMERALD)), c.soldTrades, c.boughtTrades,
                Math.max(0, c.nextEconomyTick - now)));
        boolean theft = now < c.theftUntil;
        out.add(Msg.command("status.theft", theft ? c.reportedThief.toString() : "-", c.stolenItems, theft ? (c.theftUntil - now) / 20 : 0));
        out.add(Msg.command("status.development", String.valueOf(c.elder), String.valueOf(c.founder),
                c.founderTarget == null ? "-" : c.founderTarget.toShortString(), c.founderVisits, c.founderCapital,
                c.propertyOwner == null ? "-" : c.propertyOwner + "/" + c.propertyLevel));
        out.add(Msg.command("status.road", c.roadQueue.size(), c.roadAttemptHead == null ? "-" : c.roadAttemptHead.toShortString(),
                c.roadStallTicks, c.skippedRoadSegments, c.mineRoute.size(), c.deepMiningTrips, c.deepMiningYield,
                c.livestockPen == null ? "-" : c.livestockPen.toShortString()));
        StringJoiner storages = new StringJoiner(", ");
        for (BlockPos pos : c.storages) {
            storages.add(pos.toShortString() + " " + ColonyStorage.kindOf(c, pos).displayRu);
        }
        out.add(Msg.command("status.storages", storages.toString(), ColonyStorage.emptySlots(level, c),
                ColonyStorage.count(level, c, s -> s.is(Items.EGG)), ColonyStorage.count(level, c, s -> s.is(Items.MILK_BUCKET))));
        out.add(Msg.command("status.targets", targets.toString()));
        int spare = Math.max(0, c.beds() - c.population());
        out.add(Msg.command("status.construction", spare, RoleBalancer.targetSpareBeds(c.population()), c.completedHouseCount(),
                ColonyConfig.get().village.max_constructed_houses, houseState(level, c, activeJobs)));
        StringJoiner repairs = new StringJoiner("; ");
        int shown = 0;
        for (BlockPos pos : c.repairs.keySet()) {
            if (shown++ >= 8) break;
            repairs.add(pos.toShortString());
        }
        out.add(Msg.command("status.repairs", c.repairs.size(), repairs.toString()));
        out.add(Msg.command("status.walls", c.fortifications.radius, c.fortifications.managed.size(), c.fortifications.pending.size(),
                c.fortifications.skipped, Math.max(0, c.fortifications.nextPlan - now)));
        List<String> gate = Census.populationGate(level, c);
        int ready = 0;
        for (Villager v : adults) if (v.getAge() == 0) ready++;
        EnumMap<ColonyRole, Integer> demand = RoleBalancer.demand(level, c);
        out.add(Msg.command("status.demand", demand.toString() + "%", OfflineSim.summary(level, c),
                gate.isEmpty() ? "колония разрешает" : String.join(", ", gate), ready, Math.max(0, (c.nextBreedingTick - now) / 20)));
        TerrainSurvey.State survey = TerrainSurvey.state(c);
        String surveyText = survey == null ? "разведка не начата" : (survey.complete ? "готова" : "идёт") + ", лес=" + survey.forest
                + ", вода=" + survey.water + ", почва=" + survey.soil + ", животных=" + survey.animals
                + ", изменение запасов еды/дерева/камня за 30с=" + survey.foodDelta + "/" + survey.logDelta + "/" + survey.stoneDelta;
        out.add(Msg.command("status.survey", surveyText));
        out.add(Msg.command("status.realestate", ColonyManager.data(level).land.stream().filter(p -> p.colony().equals(c.id())).count(),
                Prices.smallHousePrice(c), Prices.mediumHousePrice(c), Prices.landPrice(c)));
        return out;
    }

    private static String houseState(ServerLevel level, Colony c, int activeJobs) {
        long now = level.getGameTime();
        if (!ColonyConfig.get().roles.enable_auto_expansion) return "авторасширение выключено в конфиге";
        if (c.population() == 0) return "нет жителей";
        if (activeJobs > 0) {
            return c.roadQueue.isEmpty() ? "участок выбран, активно задание дома" : "участок выбран, строится дорога (осталось " + c.roadQueue.size() + ")";
        }
        int max = ColonyConfig.get().village.max_constructed_houses;
        if (c.completedHouseCount() >= max) return "городская цель достигнута: домов " + c.completedHouseCount() + "/" + max;
        if (c.houseSiteCursor >= 0 && c.nextHousePlanTick <= now) return "поиск, проверен кандидат " + c.houseSiteCursor;
        if (c.nextHousePlanTick > now) return "повтор поиска через " + (c.nextHousePlanTick - now) / 20 + " с";
        return "город растёт: домов " + c.completedHouseCount() + "/" + max + ", поиск запускается";
    }

    // ---------------------------------------------------------------- film

    private static LiteralArgumentBuilder<CommandSourceStack> film() {
        return Commands.literal("film").requires(s -> s.hasPermission(2))
                .executes(ColonyCommands::filmHelp)
                .then(Commands.literal("help").executes(ColonyCommands::filmHelp))
                .then(Commands.literal("on").executes(ctx -> {
                    FilmMode.setOn(ctx.getSource().getLevel(), true);
                    ok(ctx.getSource(), Msg.command("film.on"));
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    FilmMode.setOn(ctx.getSource().getLevel(), false);
                    ok(ctx.getSource(), Msg.command("film.off"));
                    return 1;
                }))
                .then(Commands.literal("status").executes(ColonyCommands::filmStatus))
                .then(Commands.literal("supply").executes(ColonyCommands::filmSupply))
                .then(Commands.literal("population").then(Commands.argument("count", IntegerArgumentType.integer())
                        .executes(ctx -> withColony(ctx, c -> filmPopulation(ctx, c, IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(Commands.literal("max").executes(ctx -> withColony(ctx, c -> filmMax(ctx, c))))
                .then(Commands.literal("stop").executes(ctx -> withColony(ctx, c -> {
                    boolean stopped = FilmMode.stopMax(ctx.getSource().getLevel(), c);
                    ok(ctx.getSource(), Msg.command(stopped ? "film.stop.done" : "film.stop.none"));
                    return stopped ? 1 : 0;
                })))
                .then(Commands.literal("rebalance").executes(ctx -> withColony(ctx, c -> filmRebalance(ctx, c))))
                .then(Commands.literal("develop")
                        .executes(ctx -> withColony(ctx, c -> filmDevelop(ctx, c, 3)))
                        .then(Commands.literal("prepare").executes(ctx -> withColony(ctx, c -> filmPrepare(ctx, c))))
                        .then(Commands.literal("status").executes(ColonyCommands::filmStatus))
                        .then(Commands.argument("days", IntegerArgumentType.integer())
                                .executes(ctx -> withColony(ctx, c -> filmDevelop(ctx, c, IntegerArgumentType.getInteger(ctx, "days"))))))
                .then(Commands.literal("expedition")
                        .executes(ctx -> withColony(ctx, c -> filmExpedition(ctx, c, null)))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> withColony(ctx, c -> filmExpedition(ctx, c, BlockPosArgument.getBlockPos(ctx, "pos"))))))
                .then(Commands.literal("build").executes(ctx -> withColony(ctx, c -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    c.nextHousePlanTick = 0;
                    ok(ctx.getSource(), Msg.command("film.build", houseState(level, c, (int) c.jobs.stream().filter(ConstructionJob::isActive).count())));
                    return 1;
                })))
                .then(Commands.literal("walls").executes(ctx -> withColony(ctx, c -> {
                    if (!ColonyConfig.get().roles.enable_village_walls) {
                        ctx.getSource().sendFailure(Msg.command("film.walls.disabled"));
                        return 0;
                    }
                    c.fortifications.nextPlan = 0;
                    ok(ctx.getSource(), Msg.command("film.walls"));
                    return 1;
                })))
                .then(Commands.literal("shortage")
                        .then(Commands.literal("food").executes(ctx -> withColony(ctx, c -> filmShortage(ctx, c, true))))
                        .then(Commands.literal("wood").executes(ctx -> withColony(ctx, c -> filmShortage(ctx, c, false)))))
                .then(Commands.literal("role").then(Commands.argument("role", StringArgumentType.word()).suggests(ROLES)
                        .executes(ctx -> withColony(ctx, c -> filmRole(ctx, c, 1)))
                        .then(Commands.argument("count", IntegerArgumentType.integer())
                                .executes(ctx -> withColony(ctx, c -> filmRole(ctx, c, IntegerArgumentType.getInteger(ctx, "count")))))))
                .then(Commands.literal("change").then(Commands.argument("role", StringArgumentType.word()).suggests(ROLES)
                        .executes(ctx -> withColony(ctx, c -> filmChange(ctx, c)))));
    }

    private interface ColonyAction {
        int run(Colony colony);
    }

    /** Film subcommands need film mode in this dimension and a registered colony at the command position. */
    private static int withColony(CommandContext<CommandSourceStack> ctx, ColonyAction action) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        if (!FilmMode.isOn(level)) {
            source.sendFailure(Msg.command("film.need_on"));
            return 0;
        }
        Colony colony = ColonyManager.colonyAt(level, BlockPos.containing(source.getPosition()));
        if (colony == null) {
            source.sendFailure(Msg.command("film.no_colony"));
            return 0;
        }
        return action.run(colony);
    }

    private static int filmHelp(CommandContext<CommandSourceStack> ctx) {
        for (int i = 1; i <= 5; i++) {
            ok(ctx.getSource(), Msg.command("film.help." + i));
        }
        return 1;
    }

    private static int filmStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        Colony c = ColonyManager.colonyAt(level, BlockPos.containing(source.getPosition()));
        if (c == null) c = colonyNear(source);
        String film = FilmMode.isOn(level) ? "on" : "off";
        if (c == null) {
            ok(source, Msg.command("film.status", film, "-", 0, 0, "-", "-", 0, "-"));
            return 1;
        }
        FilmMode.MaxRun run = FilmMode.run(c);
        long now = level.getGameTime();
        String max = run == null ? "выключен" : run.built(c) + "/" + run.target + ", осталось " + Math.max(0, run.end - now) / 20 + " с";
        int built = run == null ? 0 : run.built(c);
        ok(source, Msg.command("film.status", film, max, ColonyManager.members(level, c).size(), built, OfflineSim.summary(level, c),
                c.founder == null ? "нет" : c.founderReturning ? "возврат" : "в пути", c.founderCompanions.size(),
                c.founderTarget == null ? "-" : c.founderTarget.toShortString()));
        return 1;
    }

    private static int filmSupply(CommandContext<CommandSourceStack> ctx) {
        return withColony(ctx, c -> {
            FilmMode.Supply s = FilmMode.supply(ctx.getSource().getLevel(), c);
            if (s.added() < 0) {
                ctx.getSource().sendFailure(Msg.command("film.supply.no_storage"));
                return 0;
            }
            ok(ctx.getSource(), Msg.command("film.supply.done", s.added(), s.overflow()));
            return 1;
        });
    }

    private static int filmPopulation(CommandContext<CommandSourceStack> ctx, Colony c, int n) {
        ServerLevel level = ctx.getSource().getLevel();
        int cap = Math.min(64, ColonyConfig.get().village.max_population_per_bell);
        if (n < 2 || n > cap) {
            ctx.getSource().sendFailure(Msg.command("film.population.range", cap));
            return 0;
        }
        int added = FilmMode.population(level, c, n);
        Census.run(level, c);
        ok(ctx.getSource(), Msg.command("film.population.done", added, c.population(), cap, ColonyManager.members(level, c).size()));
        return 1;
    }

    private static boolean underAttack(ServerLevel level, Colony c) {
        return c.recentThreat(level.getGameTime(), 200) || net.betterthanvillage.defense.RaidShelter.isActive(level, c);
    }

    private static int filmMax(CommandContext<CommandSourceStack> ctx, Colony c) {
        ServerLevel level = ctx.getSource().getLevel();
        if (FilmMode.maxRunning(c)) {
            ctx.getSource().sendFailure(Msg.command("film.max.running"));
            return 0;
        }
        if (!ColonyManager.terrainChangesAllowed(level) || !ColonyConfig.get().roles.enable_auto_expansion || !ColonyConfig.get().village.offline_development) {
            ctx.getSource().sendFailure(Msg.command("film.max.config"));
            return 0;
        }
        if (underAttack(level, c)) {
            ctx.getSource().sendFailure(Msg.command("film.max.attack"));
            return 0;
        }
        if (c.founder != null || c.deferredCatchupTicks > 0) {
            ctx.getSource().sendFailure(Msg.command("film.max.busy"));
            return 0;
        }
        FilmMode.startMax(level, c);
        ok(ctx.getSource(), Msg.command("film.max.started", FilmMode.run(c).target));
        return 1;
    }

    private static int filmRebalance(CommandContext<CommandSourceStack> ctx, Colony c) {
        ServerLevel level = ctx.getSource().getLevel();
        ColonyStorage.recount(level, c);
        TerrainSurvey.step(level, c);
        Map<java.util.UUID, ColonyRole> before = new LinkedHashMap<>();
        for (Villager v : ColonyManager.adultMembers(level, c)) before.put(v.getUUID(), c.roleOf(v.getUUID()));
        int changed = RoleBalancer.rebalance(level, c, true);
        StringJoiner list = new StringJoiner(", ");
        before.forEach((id, role) -> {
            ColonyRole now = c.roleOf(id);
            if (now != role) list.add(role.serializedName() + " -> " + now.serializedName());
        });
        if (changed > 0) {
            ok(ctx.getSource(), Msg.command("film.rebalance.changed", changed, list.toString()));
        } else {
            ok(ctx.getSource(), Msg.command("film.rebalance.none"));
        }
        return 1;
    }

    private static int filmDevelop(CommandContext<CommandSourceStack> ctx, Colony c, int days) {
        ServerLevel level = ctx.getSource().getLevel();
        if (FilmMode.maxRunning(c)) {
            ctx.getSource().sendFailure(Msg.command("film.develop.max_running"));
            return 0;
        }
        if (!ColonyManager.terrainChangesAllowed(level) || !ColonyConfig.get().village.offline_development
                || ColonyConfig.get().village.offline_max_days <= 0) {
            ctx.getSource().sendFailure(Msg.command("film.develop.config"));
            return 0;
        }
        if (c.population() < 2) {
            ctx.getSource().sendFailure(Msg.command("film.develop.population"));
            return 0;
        }
        if (days < 1 || days > 30) {
            ctx.getSource().sendFailure(Msg.command("film.develop.days"));
            return 0;
        }
        long now = level.getGameTime();
        c.simulatedUntil = now - days * 24000L;
        OfflineSim.bookkeeping(level, c);
        ok(ctx.getSource(), Msg.command("film.develop.done", days, c.catchupHouses, OfflineSim.summary(level, c)));
        return 1;
    }

    private static int filmPrepare(CommandContext<CommandSourceStack> ctx, Colony c) {
        ServerLevel level = ctx.getSource().getLevel();
        if (FilmMode.maxRunning(c) || c.founder != null || underAttack(level, c)) {
            ctx.getSource().sendFailure(Msg.command("film.prepare.busy"));
            return 0;
        }
        FilmMode.supply(level, c);
        if (ColonyStorage.emptySlots(level, c) < 27) {
            BlockPos site = StoragePlacer.findSite(level, c);
            if (site != null && ColonyStorage.consume(level, c, s -> s.is(ItemTags.LOGS), 4)) {
                StoragePlacer.placeDoubleChest(level, c, site, StorageKind.GENERAL);
            }
        }
        FilmMode.population(level, c, 24);
        Census.run(level, c);
        BlockPos core = c.core();
        FilmMode.assignRole(level, c, ColonyRole.BUILDER, Math.max(0, 2 - count(level, c, ColonyRole.BUILDER)), core);
        FilmMode.assignRole(level, c, ColonyRole.FARMER, Math.max(0, 4 - count(level, c, ColonyRole.FARMER)), core);
        FilmMode.assignRole(level, c, ColonyRole.LUMBERJACK, Math.max(0, 2 - count(level, c, ColonyRole.LUMBERJACK)), core);
        FilmMode.assignRole(level, c, ColonyRole.MINER, Math.max(0, 3 - count(level, c, ColonyRole.MINER)), core);
        ok(ctx.getSource(), Msg.command("film.prepare.done", roleCounts(level, c).toString()));
        return 1;
    }

    private static int count(ServerLevel level, Colony c, ColonyRole role) {
        return roleCounts(level, c).getOrDefault(role, 0);
    }

    private static int filmExpedition(CommandContext<CommandSourceStack> ctx, Colony c, BlockPos target) {
        ServerLevel level = ctx.getSource().getLevel();
        boolean before = c.founder != null;
        Component result = Colonization.startForced(level, c, target);
        if (!before && c.founder != null) {
            ok(ctx.getSource(), result);
            return 1;
        }
        ctx.getSource().sendFailure(result);
        return 0;
    }

    private static int filmShortage(CommandContext<CommandSourceStack> ctx, Colony c, boolean food) {
        if (FilmMode.maxRunning(c)) {
            ctx.getSource().sendFailure(Msg.command("film.shortage.max"));
            return 0;
        }
        int removed = FilmMode.shortage(ctx.getSource().getLevel(), c, food);
        ok(ctx.getSource(), Msg.command("film.shortage.done", Msg.command(food ? "film.shortage.food" : "film.shortage.wood"), removed));
        return 1;
    }

    private static int filmRole(CommandContext<CommandSourceStack> ctx, Colony c, int n) {
        ColonyRole role = ColonyRole.byName(StringArgumentType.getString(ctx, "role"));
        if (FilmMode.specialRole(role) || n < 1 || n > 16) {
            ctx.getSource().sendFailure(Msg.command("film.role.invalid"));
            return 0;
        }
        List<Villager> changed = FilmMode.assignRole(ctx.getSource().getLevel(), c, role, n, BlockPos.containing(ctx.getSource().getPosition()));
        ok(ctx.getSource(), Msg.command("film.role.done", role.serializedName(), changed.size()));
        return 1;
    }

    private static int filmChange(CommandContext<CommandSourceStack> ctx, Colony c) {
        ColonyRole role = ColonyRole.byName(StringArgumentType.getString(ctx, "role"));
        if (FilmMode.specialRole(role)) {
            ctx.getSource().sendFailure(Msg.command("film.role.invalid"));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos from = BlockPos.containing(ctx.getSource().getPosition());
        Villager nearest = null;
        double best = 144;
        for (Villager v : ColonyManager.adultMembers(level, c)) {
            ColonyRole r = c.roleOf(v.getUUID());
            if (r == ColonyRole.ELDER || r == ColonyRole.FOUNDER || c.isFounderParty(v.getUUID()) || v.isSleeping() || v.getTradingPlayer() != null) continue;
            double d = v.distanceToSqr(from.getX() + 0.5, from.getY(), from.getZ() + 0.5);
            if (d <= best) {
                best = d;
                nearest = v;
            }
        }
        if (nearest == null) {
            ctx.getSource().sendFailure(Msg.command("film.change.none"));
            return 0;
        }
        ColonyRole old = c.roleOf(nearest.getUUID());
        if (old == role) {
            ctx.getSource().sendFailure(Msg.command("film.change.same", role.serializedName()));
            return 0;
        }
        FilmMode.setRole(level, c, nearest, role);
        ok(ctx.getSource(), Msg.command("film.change.done", old.serializedName(), role.serializedName(), nearest.getUUID().toString(),
                nearest.blockPosition().toShortString()));
        return 1;
    }
}
