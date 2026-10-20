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
import net.betterthanvillage.colony.Professions;
import net.betterthanvillage.economy.Gathering;
import net.betterthanvillage.economy.Household;
import net.betterthanvillage.economy.Households;
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
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/** /btv commands (01 §10, 06 §8). */
public final class ColonyCommands {
    private static final SuggestionProvider<CommandSourceStack> ROLES = (ctx, b) -> SharedSuggestionProvider.suggest(
            ColonyRole.TRADES.stream().map(ColonyRole::serializedName).toList(), b);

    private ColonyCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("btv")
                // Debug dump (storage coordinates, thief UUID) and expensive: operators only.
                .then(Commands.literal("status").requires(s -> s.hasPermission(2)).executes(ColonyCommands::status))
                .then(Commands.literal("offers").requires(s -> s.hasPermission(2)).executes(ColonyCommands::offers))
                .then(Commands.literal("villagers").requires(s -> s.hasPermission(2)).executes(ColonyCommands::villagers))
                .then(Commands.literal("land").requires(s -> s.hasPermission(2)).executes(ColonyCommands::land)
                        .then(Commands.literal("sell").executes(ColonyCommands::sellHouse))
                        .then(Commands.literal("illegal").executes(ColonyCommands::illegalBuild))
                        .then(Commands.literal("grow").then(Commands.argument("blocks", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 256))
                                .executes(ctx -> {
                                    Colony c = colonyNear(ctx.getSource());
                                    if (c == null) return 0;
                                    // Debug: grows the territory as construction would, past the maximum if asked.
                                    c.growRadius(c.radius() + com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "blocks"));
                                    c.fortifications.nextPlan = 0;
                                    ColonyManager.markDirty(ctx.getSource().getLevel());
                                    ok(ctx.getSource(), Component.literal("Territory radius " + c.radius()));
                                    return 1;
                                }))))
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

    /** Debug: movement and activity state of every member. */
    private static int villagers(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        Colony c = colonyNear(source);
        if (c == null) {
            source.sendFailure(Msg.command("status.none"));
            return 0;
        }
        long now = level.getGameTime();
        for (Villager v : ColonyManager.members(level, c)) {
            StringJoiner running = new StringJoiner(",");
            v.getBrain().getRunningBehaviors().forEach(b -> {
                if (VillagerUtil.isModTask(b)) running.add(b.getClass().getSimpleName());
            });
            BlockPos target = net.betterthanvillage.ai.nav.Nav.currentTarget(v, now);
            var path = v.getNavigation().getPath();
            String line = c.roleOf(v.getUUID()).serializedName() + (v.isBaby() ? "(baby)" : "") + "@" + v.blockPosition().toShortString()
                    + " act=" + VillagerUtil.currentActivity(v) + " sleeping=" + v.isSleeping()
                    + " forcedIdle=" + net.betterthanvillage.ai.nav.AntiStuck.isForcedIdle(v, now)
                    + " navTarget=" + (target == null ? "-" : target.toShortString())
                    + " path=" + (path == null ? "-" : (path.isDone() ? "done" : path.getNextNodeIndex() + "/" + path.getNodeCount())
                    + (path.canReach() ? "" : " unreachable") + " end=" + (path.getEndNode() == null ? "-" : path.getEndNode().asBlockPos().toShortString()))
                    + " walk=" + v.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET)
                            .map(w -> w.getTarget().currentBlockPosition().toShortString()).orElse("-")
                    + " home=" + v.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME).map(g -> g.pos().toShortString()).orElse("-")
                    + " tasks=[" + running + "] status=" + WorkTracker.status(v);
            ok(source, Component.literal(line));
        }
        return 1;
    }

    /** Debug: what every adult member would offer a neutral player right now. */
    private static int offers(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        Colony c = colonyNear(source);
        if (c == null) {
            source.sendFailure(Msg.command("status.none"));
            return 0;
        }
        for (Villager v : ColonyManager.adultMembers(level, c)) {
            if (v.getTradingPlayer() != null) continue;
            String line = c.roleOf(v.getUUID()).serializedName() + ": " + Trading.describeOffers(level, c, v);
            ok(source, Component.literal(line));
        }
        return 1;
    }

    /**
     * Debug map of the land around the colony, 2 chunks per character: '#' its territory, '@' its bell, a letter
     * per country owning the domain ('A' the colony's own), '.' nobody's land.
     */
    private static int land(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        Colony c = colonyNear(source);
        if (c == null) {
            source.sendFailure(Msg.command("status.none"));
            return 0;
        }
        List<UUID> countries = new ArrayList<>();
        countries.add(c.country);
        Map<UUID, Integer> chunks = new java.util.LinkedHashMap<>();
        net.minecraft.world.level.ChunkPos center = new net.minecraft.world.level.ChunkPos(c.core());
        int half = 18;
        for (int row = -half; row <= half; row++) {
            StringBuilder line = new StringBuilder();
            for (int col = -half; col <= half; col++) {
                net.minecraft.world.level.ChunkPos chunk = new net.minecraft.world.level.ChunkPos(center.x + col * 2, center.z + row * 2);
                UUID owner = net.betterthanvillage.colony.Domain.countryAt(level, chunk);
                if (owner != null) chunks.merge(owner, 1, Integer::sum);
                BlockPos mid = chunk.getMiddleBlockPosition(c.core().getY());
                char ch;
                if (row == 0 && col == 0) ch = '@';
                else if (c.containsXZ(mid)) ch = '#';
                else if (owner == null) ch = '.';
                else {
                    if (!countries.contains(owner)) countries.add(owner);
                    ch = (char) ('A' + Math.min(25, countries.indexOf(owner)));
                }
                line.append(ch);
            }
            ok(source, Component.literal(line.toString()));
        }
        StringJoiner legend = new StringJoiner(", ");
        for (int i = 0; i < countries.size(); i++) {
            legend.add((char) ('A' + Math.min(25, i)) + "=" + countries.get(i).toString().substring(0, 8) + " (" + chunks.getOrDefault(countries.get(i), 0) * 4 + " chunks)");
        }
        StringJoiner houses = new StringJoiner(", ");
        for (LandSales.House h : LandSales.emptyHouses(level, c)) houses.add(h.box().getCenter().toShortString() + " beds=" + h.beds().size());
        ok(source, Component.literal("Empty houses: [" + houses + "]; player plots: " + ColonyManager.data(level).land.size()
                + "; to demolish: " + net.betterthanvillage.construction.LandWork.demolitionCount(c)
                + "; pits: " + net.betterthanvillage.construction.LandWork.pitCount(c)
                + "; wall rings: " + c.fortifications.oldRings + " + " + c.fortifications.radius + " built=" + c.fortifications.managed.size()
                + " pending=" + c.fortifications.pending.size()
                + "; lamp post sites: " + net.betterthanvillage.construction.LampPosts.siteCount(c)));
        List<ColonyStorage.Slot> treasury = ColonyStorage.treasury(level, c);
        java.util.function.BiFunction<String, java.util.function.Predicate<ItemStack>, String> n = (name, p) ->
                name + "=" + net.betterthanvillage.economy.Chests.count(treasury, p);
        ok(source, Component.literal("Treasury materials: " + String.join(", ",
                n.apply("cobble", st -> st.is(net.minecraft.tags.ItemTags.STONE_CRAFTING_MATERIALS)),
                n.apply("logs", st -> st.is(net.minecraft.tags.ItemTags.LOGS)), n.apply("planks", st -> st.is(net.minecraft.tags.ItemTags.PLANKS)),
                n.apply("fences", st -> st.is(net.minecraft.tags.ItemTags.WOODEN_FENCES)), n.apply("torches", st -> st.is(Items.TORCH)),
                n.apply("coal", st -> st.is(Items.COAL) || st.is(Items.CHARCOAL)), n.apply("dirt", st -> st.is(Items.DIRT)))));
        ok(source, Component.literal("Countries: " + legend + "; territory radius=" + c.radius() + " max=" + net.betterthanvillage.colony.Territory.maxRadius(c)
                + " seat=" + (c.seat == null ? "-" : c.seat.toString())));
        return 1;
    }

    /**
     * Debug: a 3x3x3 cobblestone build of a placeholder player 24 blocks east of the bell, on the surface, whose
     * owner got the elder's notice long ago: it is illegal and due for demolition.
     */
    private static int illegalBuild(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        Colony c = colonyNear(source);
        if (c == null) {
            source.sendFailure(Msg.command("status.none"));
            return 0;
        }
        UUID owner = new UUID(0, 2);
        long now = level.getGameTime();
        BlockPos base = c.core().east(24);
        base = new BlockPos(base.getX(), level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base.getX(), base.getZ()), base.getZ());
        int placed = 0;
        for (BlockPos p : BlockPos.betweenClosed(base, base.offset(2, 2, 2))) {
            if (!level.getBlockState(p).canBeReplaced()) continue;
            level.setBlock(p, net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState(), 3);
            ColonyManager.data(level).recordPlacement(p.immutable(), owner, now);
            placed++;
        }
        net.betterthanvillage.colony.LandWarning w = new net.betterthanvillage.colony.LandWarning();
        w.player = owner;
        w.colony = c.id();
        w.pos = base;
        w.delivered = now - LandNotices.GRACE - 1;
        ColonyManager.data(level).landWarnings.add(w);
        ColonyManager.markDirty(level);
        ok(source, Component.literal("Illegal build of " + placed + " blocks at " + base.toShortString()));
        return 1;
    }

    /** Debug: sells the first empty house on offer to a placeholder buyer, as if a player had bought it. */
    private static int sellHouse(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        Colony c = colonyNear(source);
        if (c == null) {
            source.sendFailure(Msg.command("status.none"));
            return 0;
        }
        List<LandSales.HouseOffer> offers = LandSales.houseOffers(level, c);
        if (offers.isEmpty()) {
            source.sendFailure(Component.literal("No empty house for sale"));
            return 0;
        }
        ItemStack map = offers.get(0).map();
        LandSales.sellHouse(level, c, new UUID(0, 1), map);
        ok(source, Component.literal("Sold " + map.getHoverName().getString() + " map=" + map.get(net.minecraft.core.component.DataComponents.MAP_ID)
                + " decorations=" + map.get(net.minecraft.core.component.DataComponents.MAP_DECORATIONS)));
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
            if (role == ColonyRole.WARRIOR) {
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
        int golems = net.betterthanvillage.defense.GolemManager.golemCount(level, c);
        out.add(Msg.command("status.golems", golems, Math.max(4, (c.population() + 5) / 6),
                ColonyStorage.count(level, c, s -> s.is(Items.IRON_INGOT)),
                ColonyStorage.count(level, c, s -> s.is(Items.PUMPKIN) || s.is(Items.CARVED_PUMPKIN)), Math.max(0, c.nextGolemTick - level.getServer().getTickCount())));
        Gathering.LastReport last = Gathering.lastReport(c);
        StringJoiner index = new StringJoiner(", ");
        c.marketIndex.forEach((family, v) -> {
            if (Math.abs(v - 1.0) >= 0.05) index.add(family.toLowerCase(java.util.Locale.ROOT) + String.format("=%.2f", v));
        });
        out.add(Msg.command("status.economy", ColonyStorage.count(level, c, s -> s.is(Items.EMERALD)),
                last == null ? "-" : (last.index() % 2 == 0 ? "morning " : "evening ") + (last.index() / 2),
                last == null ? 0 : last.attendees(), last == null ? 0 : last.report().trades, last == null ? 0 : last.report().emeralds,
                last == null ? 0 : last.report().unmetBids, index.toString()));
        out.add(householdStatus(level, c));
        out.add(debtStatus(level, c));
        StringJoiner orders = new StringJoiner(", ");
        for (net.betterthanvillage.economy.BirthOrder o : c.birthOrders) {
            orders.add(o.profession.serializedName() + (o.parentA == null ? " (treasury)" : "") + " job=" + (o.job == null ? "-" : o.job.toString().substring(0, 8)));
        }
        out.add(Msg.command("status.births", c.birthOrders.size(), orders.toString(), c.conscripts.size()));
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
        out.add(Msg.command("status.construction", spare, Professions.targetSpareBeds(c.population()), c.completedHouseCount(),
                ColonyConfig.get().village.max_constructed_houses, houseState(level, c, activeJobs)));
        StringJoiner repairs = new StringJoiner("; ");
        int shown = 0;
        for (BlockPos pos : c.repairs.keySet()) {
            if (shown++ >= 8) break;
            repairs.add(pos.toShortString());
        }
        out.add(Msg.command("status.repairs", c.repairs.size(), repairs.toString()));
        out.add(Msg.command("status.land", c.radius(), net.betterthanvillage.colony.Territory.maxRadius(c),
                net.betterthanvillage.colony.Territory.area(c), ColonyConfig.get().land.territory_max_area,
                net.betterthanvillage.economy.Commissions.budget(level, c)));
        out.add(Msg.command("status.walls", c.fortifications.radius, c.fortifications.managed.size(), c.fortifications.pending.size(),
                c.fortifications.skipped, Math.max(0, c.fortifications.nextPlan - now)));
        List<String> gate = Census.populationGate(level, c);
        int ready = 0;
        for (Villager v : adults) if (v.getAge() == 0) ready++;
        EnumMap<ColonyRole, Integer> professions = Professions.counts(c, adults);
        professions.values().removeIf(n -> n == 0);
        out.add(Msg.command("status.demand", professions.toString(), OfflineSim.summary(level, c),
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

    private static Component debtStatus(ServerLevel level, Colony c) {
        long today = net.betterthanvillage.economy.Credit.day(level);
        int open = 0, overdue = 0, owed = 0;
        StringJoiner list = new StringJoiner(", ");
        for (net.betterthanvillage.economy.Debt d : ColonyManager.data(level).debts.values()) {
            if (!c.id().equals(d.debtor.colony()) && !c.id().equals(d.creditor.colony())) continue;
            open++;
            owed += d.owed();
            if (d.overdue(today)) overdue++;
            if (list.length() < 600) list.add(d.debtorName + "->" + d.creditorName + " " + d.owed() + " due " + d.dueDay + (d.strikes > 0 ? " strikes " + d.strikes : ""));
        }
        Gathering.LastReport last = Gathering.lastReport(c);
        return Msg.command("status.debts", open, owed, overdue, net.betterthanvillage.economy.Credit.villageDebt(level, c),
                net.betterthanvillage.economy.Credit.villageEmeralds(level, c), last == null ? 0 : last.report().credit,
                last == null ? 0 : last.report().repaid, list.toString());
    }

    private static Component householdStatus(ServerLevel level, Colony c) {
        int homeless = 0, chestless = 0, emeralds = 0, pending = 0;
        StringJoiner list = new StringJoiner(", ");
        for (Household h : c.households.values()) {
            if (h.bed == null) homeless++;
            if (!h.hasChest()) chestless++;
            int e = Households.emeralds(level, h);
            emeralds += e;
            pending += h.pendingCapital;
            int items = ColonyStorage.count(ColonyStorage.usable(level, h), s -> !s.is(Items.EMERALD));
            int food = 0;
            for (ColonyStorage.Slot slot : ColonyStorage.usable(level, h)) food += ColonyStorage.foodValue(slot.container());
list.add(c.tradeOf(h.owner).serializedName() + (c.conscripts.containsKey(h.owner) ? "(conscript)" : "")
                    + (h.lineage == 0 ? "(first of line)" : "") + (h.idlePoor ? "(idle poor)" : "") + (h.inDefault ? "(in default)" : "") + " bed=" + (h.bed == null ? "-" : h.bed.toShortString())
                    + " chests=" + h.chests.size() + " em=" + e + " items=" + items + " food=" + food + " income=" + h.income
                    + " hungry=" + h.hungryDays + String.format(" tax=%.1f", h.taxDue) + " owed=" + h.wagesOwed + " labor=" + h.labor
                    + String.format(" g=%.2f", h.greed));
        }
        return Msg.command("status.households", c.households.size(), homeless, chestless, emeralds, pending, list.toString());
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
        int changed = Professions.assign(level, c, ColonyManager.adultMembers(level, c));
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
        FilmMode.assignRole(level, c, ColonyRole.MASON, Math.max(0, 2 - count(level, c, ColonyRole.MASON)), core);
        FilmMode.assignRole(level, c, ColonyRole.FARMER, Math.max(0, 4 - count(level, c, ColonyRole.FARMER)), core);
        FilmMode.assignRole(level, c, ColonyRole.CARPENTER, Math.max(0, 2 - count(level, c, ColonyRole.CARPENTER)), core);
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
