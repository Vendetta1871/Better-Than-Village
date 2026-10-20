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
    public int config_version = 2;

    public static final class Village {
        public boolean offline_development = true;
        public int offline_max_days = 6;
        public int offline_blocks_per_tick = 8;
        public int offline_arrival_blocks_per_tick = 512;
        public int max_population_per_bell = 64;
        public int expansion_radius_max = 256;
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
        public double tax_rate = 0.10;
        /** Levy in kind: share of produced goods. */
        public double levy_rate = 0.05;
        /** Emeralds per day the treasury pays the elder. */
        public int elder_salary = 1;
        /** Emeralds per day the treasury pays each warrior. */
        public int warrior_wage = 1;
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
        if (config_version < 2) {
            roles.golems_break_attacker_pillars = true;
            config_version = 2;
        }
        config_version = Math.max(2, config_version);
        village.offline_max_days = clamp(village.offline_max_days, 0, 30);
        village.offline_blocks_per_tick = clamp(village.offline_blocks_per_tick, 1, 32);
        village.offline_arrival_blocks_per_tick = clamp(village.offline_arrival_blocks_per_tick, 32, 1024);
        village.max_population_per_bell = clamp(village.max_population_per_bell, 2, 200);
        village.expansion_radius_max = clamp(village.expansion_radius_max, 96, 512);
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
        economy.credit_base = clamp(economy.credit_base, 0, 1000);
        economy.credit_income_days = clamp(economy.credit_income_days, 0, 100);
        economy.village_debt_cap = clamp(economy.village_debt_cap, 0, 100);
        economy.interest_per_day = clamp(economy.interest_per_day, 0, 1);
        economy.debt_term_days = clamp(economy.debt_term_days, 1, 100);
        economy.conscription_idle_days = clamp(economy.conscription_idle_days, 1, 100);
        economy.birth_house_price = clamp(economy.birth_house_price, 1, 4096);
        economy.quarry_ore_chance_scale = clamp(economy.quarry_ore_chance_scale, 0, 100);
        economy.conscription_budget_days = clamp(economy.conscription_budget_days, 1, 100);
    }

    private static double clamp(double v, double lo, double hi) {
        return Double.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : lo;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
