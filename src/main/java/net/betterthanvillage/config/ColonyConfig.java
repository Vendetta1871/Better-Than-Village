package net.betterthanvillage.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Mod configuration stored as pretty JSON in {@code config/better_than_village.json}.
 * Loaded once at mod construction, validated, clamped and always rewritten.
 */
public final class ColonyConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String FILE_NAME = "better_than_village.json";

    private static ColonyConfig instance = new ColonyConfig();

    public Village village = new Village();
    public Roles roles = new Roles();
    public Debug debug = new Debug();
    public Work work = new Work();
    public Economy economy = new Economy();
    public Land land = new Land();
    public Law law = new Law();
    public World world = new World();
    public Politics politics = new Politics();
    public Warfare warfare = new Warfare();
    public int config_version = 2;

    public static final class Village {
        public boolean offline_development = true;
        public int offline_max_days = 6;
        public int offline_blocks_per_tick = 8;
        public int offline_arrival_blocks_per_tick = 512;
        public int max_population_per_bell = 64;
        public int build_delay_ticks = 10;
        public int max_constructed_houses = 48;
    }

    public static final class Roles {
        public boolean enable_village_walls = true;
        public boolean enable_auto_expansion = true;
        public boolean builder_respects_player_builds = true;
        public boolean allow_terrain_changes = true;
        public boolean golems_break_attacker_pillars = true;
    }

    public static final class Debug {
        public boolean render_build_boxes = false;
        /** Logs every villager teleport with the code path that caused it. */
        public boolean log_teleports = false;
        /** Logs every removal of food from colony stock with its caller. */
        public boolean log_food = false;
    }

    public static final class Work {
        public int minimum_break_ticks = 10;
        public int placement_ticks = 10;
        public int farming_ticks = 8;
        public int deep_mining_ticks = 1200;
        /** Lets miners teleport to the end of a long mine route instead of walking it. */
        public boolean miner_route_teleport = false;
    }

    /** docs_v2/03-economy.md. */
    public static final class Economy {
        /** Multiplies the starting emeralds handed out when a generated village is settled. */
        public double starting_capital_scale = 1.0;
        /** Share of the starting emeralds that goes to the treasury. */
        public double treasury_share = 0.3;
        /** Money tax: share of a household's income since the last collection. */
        public double tax_rate = 0.15;
        /** Levy in kind: share of produced goods. */
        public double levy_rate = 0.10;
        /** Emeralds per day the treasury pays the elder. */
        public int elder_salary = 1;
        /** Emeralds per day the treasury pays each warrior. */
        public int warrior_wage = 1;
        /** The treasury owes a warrior at most this many days of unpaid wages. */
        public int max_owed_wage_days = 5;
        /** Units of public work (blocks placed, repaired, ...) paid with one emerald. */
        public int labor_units_per_emerald = 32;
        /** Credit any household gets, before income and goods count. */
        public int credit_base = 3;
        /** Days of average income a household may borrow. */
        public double credit_income_days = 3;
        /** A village owes at most this multiple of the emeralds it holds. */
        public double village_debt_cap = 2.0;
        /** Interest per in-game day. */
        public double interest_per_day = 0.01;
        /** Days until a debt is due. */
        public int debt_term_days = 3;
        /** Multiplies the chance that a miner's stone yields an ore (0 turns it off). */
        public double quarry_ore_chance_scale = 1.0;
        /** Emeralds a pair pays the treasury for a plot and a house with the child's workplace. */
        public int birth_house_price = 24;
        /** Days a trade may earn nothing before its villager is conscripted. */
        public int conscription_idle_days = 3;
        /** The treasury must cover all warriors' wages for this many days before it conscripts anyone. */
        public int conscription_budget_days = 5;
    }

    /** docs_v2/04-land.md. */
    public static final class Land {
        /** Territory radius of a new village (the shape's outer bound). */
        public int territory_start_radius = 64;
        /** Largest territory construction may grow to, in square blocks (about a radius of 160). */
        public int territory_max_area = 80425;
        /** Depth of the border noise as a share of the radius: the border lies between (1 - noise) and 1. */
        public double territory_noise = 0.25;
        /** A chunk farther than this many blocks from every generated village belongs to no country. */
        public int domain_cap = 512;
        /** Rings are kept when the territory outgrows them and a new ring is built outside; off: the ring moves out. */
        public boolean layered_walls = true;
        /** Emeralds the treasury gives a search party to found a new village. */
        public int expansion_grant = 12;
        /** A house a player orders on its plot costs 4 emeralds plus one per this many blocks (materials and work). */
        public int house_order_blocks_per_emerald = 6;
    }

    /** docs_v2/05-law.md. */
    public static final class Law {
        /** Seconds a warned player has to stop a continuous offence (weapon in hand, building) before war. */
        public int grace_seconds = 30;
        /** Village gossip (minor and major negative, summed over the members) about a player that keeps a warning active. */
        public int warning_gossip = 20;
        /** Minutes a player who attacked under a white flag cannot surrender. */
        public int perfidy_minutes = 10;
        /** Emeralds a peace treaty costs when the player and the country are equally strong. */
        public int treaty_base_price = 16;
        /** In-game days of truce after a peace treaty. */
        public int truce_days = 3;
        /** Villagers and golems lost to the player after which the country asks for peace. */
        public int weariness_losses = 4;
        /** In-game days of war (with at least one loss) after which the country asks for peace. */
        public int weariness_days = 5;
    }

    /** docs_v2/08-simulation.md. */
    public static final class World {
        /** Unloaded villages keep their economy running on ledgers; off: the old catch-up on return. */
        public boolean abstract_simulation = true;
        /** Milliseconds per server tick the world scheduler may use. */
        public double tick_budget_ms = 2.0;
        /** Game ticks between two abstract steps of a village (an in-game hour). */
        public int step_ticks = 1000;
        /** Pure computation (market clearing of abstract villages, caravan planning) runs on worker threads. */
        public boolean async_workers = true;
        /** Treasuries trade by caravan between villages. */
        public boolean caravans = true;
        /** Caravans travel this many blocks per in-game hour (1000 ticks) while abstract. */
        public int caravan_blocks_per_hour = 120;
        /** Farthest village a caravan goes to. */
        public int caravan_range = 1500;
        /** In-game days between two caravans of a village. */
        public int caravan_interval_days = 2;
    }

    /** docs_v2/06-politics.md. */
    public static final class Politics {
        /** Below this loyalty a village's elder may ignore the ruler's orders. */
        public int disobedience_loyalty = 30;
        /** Loyalty of a daughter village when founded. */
        public int daughter_loyalty = 80;
        /** Loyalty of a conquered village. */
        public int conquered_loyalty = 20;
        /** The ruler's patience lost per ignored order (out of 100). */
        public int patience_per_ignored_order = 25;
        /** The ruler's patience recovered per day of tolerance. */
        public int patience_recovery = 5;
        /** Relations at or below this score turn peace into hostility. */
        public int hostility_score = -40;
        /** Relations at or above this score (with steady trade) make an alliance. */
        public int alliance_score = 50;
        /** A conquest war needs this strength ratio over the target and its allies. */
        public double conquest_advantage = 1.5;
        /** Emeralds per border chunk far from the owner's villages (up to 4 times that close to them). */
        public double border_price_per_chunk = 0.15;
        /** Days after which a war between countries ends in a plain truce whatever happened. */
        public int war_max_days = 30;
        /** Days of truce after a peace between countries. */
        public int truce_days = 5;
    }

    public static final class Warfare {
        /** Wars between countries are fought by campaigns (off: declared wars only end by truce). */
        public boolean campaigns = true;
        /** Share of each village's warriors that stays home as a garrison (at least one stays). */
        public double garrison_share = 0.34;
        /** A campaign needs at least this many soldiers. */
        public int min_army = 2;
        /** Game ticks between the call to arms and the march. */
        public int mobilize_ticks = 2000;
        /** An army marches this many blocks per in-game hour while abstract. */
        public int army_blocks_per_hour = 150;
        /** The outpost stands this far outside the target's outermost ring. */
        public int outpost_distance = 24;
        /** Labour (soldier-hours times 10) an outpost takes. */
        public int outpost_work = 60;
        /** The attackers break off after losing their outpost this many times. */
        public int outpost_max_losses = 3;
        /** Base power of a warrior in the strength model; gear and class add to it. */
        public double warrior_power = 2.0;
        /** Each intact wall ring multiplies the defenders' power by 1 + this. */
        public double ring_bonus = 0.35;
        /** Power of a village golem (live, one golem holds off about five armed villagers). */
        public double golem_power = 10.0;
        /** At most this many village golems per village (player golems never count). */
        public int golem_cap = 1;
        /** Vanilla golem summoning by villagers (gossip, panic); off: villages only build golems from iron and a pumpkin. */
        public boolean natural_golems = false;
        /** Warriors may serve as cavalry on horses from the herder's stables (off: cavalry fights as infantry). */
        public boolean cavalry = true;
        /** Share of the warriors on night watch in peacetime (reinforced to two thirds in war). */
        public double night_watch_share = 0.5;
        /** Weariness a ruling elder tolerates before asking for peace (greed moves it by up to 30). */
        public int weariness_tolerance = 50;
        /** Chance per night that a hostile village sends an agent against a neighbour. */
        public double night_ops_chance = 0.25;
        /** Farthest target of a night operation. */
        public int night_ops_range = 700;
        /** Countries campaign against a player at war whose strength score is below theirs times this. */
        public double player_offensive_ratio = 0.8;
        /** A campaign against a player's base gives up after this many days. */
        public int player_campaign_days = 3;
    }

    public static ColonyConfig get() {
        return instance;
    }

    public static void load() {
        Path dir = FMLPaths.CONFIGDIR.get();
        Path file = dir.resolve(FILE_NAME);
        ColonyConfig loaded = null;
        try {
            Files.createDirectories(dir);
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    loaded = GSON.fromJson(reader, ColonyConfig.class);
                } catch (JsonParseException | IllegalStateException e) {
                    loaded = null;
                }
                if (loaded == null) {
                    Path backup = dir.resolve(FILE_NAME + ".invalid-" + UUID.randomUUID() + ".bak");
                    Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.error("Invalid config {}, copied to {} and using defaults", file, backup);
                }
            }
            if (loaded == null) {
                loaded = new ColonyConfig();
            }
            loaded.validate();
            instance = loaded;
            write(dir, file, loaded);
        } catch (IOException | JsonParseException e) {
            if (loaded == null) {
                loaded = new ColonyConfig();
            }
            loaded.validate();
            instance = loaded;
            LOGGER.error("Could not load/write {}; using validated settings without replacing the original", file, e);
        }
    }

    private static void write(Path dir, Path file, ColonyConfig config) throws IOException {
        Path tmp = Files.createTempFile(dir, "better-than-village-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void validate() {
        if (village == null) village = new Village();
        if (roles == null) roles = new Roles();
        if (debug == null) debug = new Debug();
        if (work == null) work = new Work();
        if (economy == null) economy = new Economy();
        if (land == null) land = new Land();
        if (law == null) law = new Law();
        if (world == null) world = new World();
        if (politics == null) politics = new Politics();
        if (warfare == null) warfare = new Warfare();
        if (config_version < 2) {
            roles.golems_break_attacker_pillars = true;
            config_version = 2;
        }
        config_version = Math.max(2, config_version);
        village.offline_max_days = clamp(village.offline_max_days, 0, 30);
        village.offline_blocks_per_tick = clamp(village.offline_blocks_per_tick, 1, 32);
        village.offline_arrival_blocks_per_tick = clamp(village.offline_arrival_blocks_per_tick, 32, 1024);
        village.max_population_per_bell = clamp(village.max_population_per_bell, 2, 200);
        village.build_delay_ticks = clamp(village.build_delay_ticks, 2, 100);
        village.max_constructed_houses = clamp(village.max_constructed_houses, 4, 200);
        work.minimum_break_ticks = clamp(work.minimum_break_ticks, 4, 100);
        work.placement_ticks = clamp(work.placement_ticks, 4, 100);
        work.farming_ticks = clamp(work.farming_ticks, 4, 100);
        work.deep_mining_ticks = clamp(work.deep_mining_ticks, 400, 12000);
        economy.starting_capital_scale = clamp(economy.starting_capital_scale, 0, 10);
        economy.treasury_share = clamp(economy.treasury_share, 0, 0.9);
        economy.tax_rate = clamp(economy.tax_rate, 0, 0.9);
        economy.levy_rate = clamp(economy.levy_rate, 0, 0.9);
        economy.elder_salary = clamp(economy.elder_salary, 0, 64);
        economy.warrior_wage = clamp(economy.warrior_wage, 0, 64);
        economy.labor_units_per_emerald = clamp(economy.labor_units_per_emerald, 1, 4096);
        land.house_order_blocks_per_emerald = clamp(land.house_order_blocks_per_emerald, 1, 1000);
        economy.max_owed_wage_days = clamp(economy.max_owed_wage_days, 0, 100);
        economy.credit_base = clamp(economy.credit_base, 0, 1000);
        economy.credit_income_days = clamp(economy.credit_income_days, 0, 100);
        economy.village_debt_cap = clamp(economy.village_debt_cap, 0, 100);
        economy.interest_per_day = clamp(economy.interest_per_day, 0, 1);
        economy.debt_term_days = clamp(economy.debt_term_days, 1, 100);
        economy.conscription_idle_days = clamp(economy.conscription_idle_days, 1, 100);
        economy.birth_house_price = clamp(economy.birth_house_price, 1, 4096);
        economy.quarry_ore_chance_scale = clamp(economy.quarry_ore_chance_scale, 0, 100);
        economy.conscription_budget_days = clamp(economy.conscription_budget_days, 1, 100);
        land.territory_start_radius = clamp(land.territory_start_radius, 32, 256);
        land.territory_max_area = clamp(land.territory_max_area, 8000, 800000);
        land.territory_noise = clamp(land.territory_noise, 0, 0.6);
        land.domain_cap = clamp(land.domain_cap, 128, 2048);
        land.expansion_grant = clamp(land.expansion_grant, 0, 4096);
        law.grace_seconds = clamp(law.grace_seconds, 10, 60);
        law.warning_gossip = clamp(law.warning_gossip, 1, 1000);
        law.perfidy_minutes = clamp(law.perfidy_minutes, 0, 1440);
        law.treaty_base_price = clamp(law.treaty_base_price, 1, 64);
        law.truce_days = clamp(law.truce_days, 0, 100);
        law.weariness_losses = clamp(law.weariness_losses, 1, 1000);
        law.weariness_days = clamp(law.weariness_days, 1, 100);
        world.tick_budget_ms = clamp(world.tick_budget_ms, 0.2, 20);
        world.step_ticks = clamp(world.step_ticks, 100, 24000);
        world.caravan_blocks_per_hour = clamp(world.caravan_blocks_per_hour, 20, 2000);
        world.caravan_range = clamp(world.caravan_range, 128, 8192);
        world.caravan_interval_days = clamp(world.caravan_interval_days, 1, 100);
        politics.disobedience_loyalty = clamp(politics.disobedience_loyalty, 0, 100);
        politics.daughter_loyalty = clamp(politics.daughter_loyalty, 0, 100);
        politics.conquered_loyalty = clamp(politics.conquered_loyalty, 0, 100);
        politics.patience_per_ignored_order = clamp(politics.patience_per_ignored_order, 1, 100);
        politics.patience_recovery = clamp(politics.patience_recovery, 0, 100);
        politics.hostility_score = clamp(politics.hostility_score, -100, 0);
        politics.alliance_score = clamp(politics.alliance_score, 0, 100);
        politics.conquest_advantage = clamp(politics.conquest_advantage, 1, 10);
        politics.border_price_per_chunk = clamp(politics.border_price_per_chunk, 0, 64.0);
        politics.war_max_days = clamp(politics.war_max_days, 1, 1000);
        politics.truce_days = clamp(politics.truce_days, 0, 100);
        warfare.garrison_share = clamp(warfare.garrison_share, 0, 1);
        warfare.min_army = clamp(warfare.min_army, 1, 64);
        warfare.mobilize_ticks = clamp(warfare.mobilize_ticks, 0, 48000);
        warfare.army_blocks_per_hour = clamp(warfare.army_blocks_per_hour, 20, 2000);
        warfare.outpost_distance = clamp(warfare.outpost_distance, 8, 128);
        warfare.outpost_work = clamp(warfare.outpost_work, 1, 10000);
        warfare.outpost_max_losses = clamp(warfare.outpost_max_losses, 1, 100);
        warfare.warrior_power = clamp(warfare.warrior_power, 0.1, 100);
        warfare.ring_bonus = clamp(warfare.ring_bonus, 0, 10);
        warfare.golem_power = clamp(warfare.golem_power, 0, 100);
        warfare.golem_cap = clamp(warfare.golem_cap, 0, 16);
        warfare.night_watch_share = clamp(warfare.night_watch_share, 0, 1);
        warfare.weariness_tolerance = clamp(warfare.weariness_tolerance, 1, 1000);
        warfare.night_ops_chance = clamp(warfare.night_ops_chance, 0, 1);
        warfare.night_ops_range = clamp(warfare.night_ops_range, 64, 8192);
        warfare.player_offensive_ratio = clamp(warfare.player_offensive_ratio, 0.01, 100);
        warfare.player_campaign_days = clamp(warfare.player_campaign_days, 1, 100);
    }

    private static double clamp(double v, double lo, double hi) {
        return Double.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : lo;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
