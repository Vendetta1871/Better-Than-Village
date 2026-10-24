package net.betterthanvillage.client.demo;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.betterthanvillage.BetterThanVillage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Staged demo scenes for the README media (dev runs only, inert in a release jar). A scene ({@code demo/<name>.txt},
 * see {@link DemoScene} and {@code demo/README.md}) builds its world once ({@code [setup]}, saved as a snapshot under
 * {@code run/demo/snapshots}) and then plays the same way on every replay ({@code [play]}, from a fresh copy of the
 * snapshot), with the camera at a fixed spot chosen in game with {@code /demo cam}.
 * <p>
 * Client commands: {@code /demo list | play | loop | record | setup <scene>}, {@code /demo cam [name]},
 * {@code /demo goto [name]}, {@code /demo stop}. Environment: {@code BTV_DEMO=<scene>[,<scene>...]} plays the scenes at
 * start, {@code BTV_DEMO_RECORD} records them ({@code #shot}, {@code #record}) into {@code run/demo/out},
 * {@code BTV_DEMO_QUIT} closes the game afterwards, {@code BTV_DEMO_DIR} points to the scene files (default
 * {@code ../demo} from the run directory). Every step logs with the {@code [demo]} prefix.
 */
@EventBusSubscriber(modid = BetterThanVillage.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class DemoDirector {
    private static final String WORLD = "btv-demo";
    private static final String VARS_FILE = "btv-demo-vars.properties";
    private static final String HASH_FILE = "btv-demo-setup.hash";
    private static final String SEED = "btv-test";
    private static final Pattern LOCATION = Pattern.compile("\\[(-?\\d+), [~\\d-]+, (-?\\d+)\\]");
    private static final Pattern VARIABLE = Pattern.compile("\\{([A-Za-z0-9_+*\\- ]+)\\}");

    private static final Deque<Job> JOBS = new ArrayDeque<>();
    private static final Map<String, Long> VARS = new LinkedHashMap<>();
    /** Scenes whose snapshot is queued to be built. */
    private static final Set<String> PLANNED = new HashSet<>();
    private static boolean inJob;
    private static boolean autoStarted;
    private static String current;
    private static String looping;
    private static int failures;

    private DemoDirector() {
    }

    private interface Job {
        /** @return true when the job is done */
        boolean tick(Minecraft mc) throws Exception;
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        if (FMLEnvironment.production) return;
        LogTap.install();
        NeoForge.EVENT_BUS.addListener(DemoDirector::onTick);
        NeoForge.EVENT_BUS.addListener(DemoDirector::onCommands);
        NeoForge.EVENT_BUS.register(FrameRecorder.class);
    }

    // ---------------------------------------------------------------- paths

    private static Path gameDir() {
        return Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
    }

    static Path scenesDir() {
        String dir = System.getenv("BTV_DEMO_DIR");
        return dir != null ? Path.of(dir) : gameDir().resolveSibling("demo");
    }

    private static Path snapshot(String scene) {
        return gameDir().resolve("demo/snapshots").resolve(scene);
    }

    private static Path out() {
        return gameDir().resolve("demo/out");
    }

    private static Path world() {
        return gameDir().resolve("saves").resolve(WORLD);
    }

    // ---------------------------------------------------------------- driver

    private static void onTick(ClientTickEvent.Post event) {
        // Loading and leaving a world run nested client ticks; the job that started them is still on the stack.
        if (inJob) return;
        Minecraft mc = Minecraft.getInstance();
        if (!autoStarted && (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) {
            autoStarted = true;
            // A fresh run directory starts with the onboarding screen and the tutorial toasts.
            mc.options.onboardAccessibility = false;
            mc.options.pauseOnLostFocus = false;
            mc.options.tutorialStep = TutorialSteps.NONE;
            mc.options.save();
            if (mc.screen instanceof AccessibilityOnboardingScreen) mc.setScreen(new TitleScreen());
            autoStart();
        }
        Job job = JOBS.peek();
        if (job == null) return;
        inJob = true;
        try {
            if (job.tick(mc)) JOBS.remove(job);
        } catch (Exception e) {
            BetterThanVillage.LOGGER.error("[demo] FAIL: {}", e.getMessage(), e);
            say("FAIL: " + e.getMessage());
            failures++;
            boolean quit = System.getenv("BTV_DEMO_QUIT") != null;
            clearQueue();
            looping = null;
            FrameRecorder.stop();
            if (quit) mc.stop();
        } finally {
            inJob = false;
        }
    }

    private static void clearQueue() {
        JOBS.clear();
        PLANNED.clear();
    }

    private static void autoStart() {
        String scenes = System.getenv("BTV_DEMO");
        if (scenes == null || scenes.isBlank()) return;
        boolean record = System.getenv("BTV_DEMO_RECORD") != null;
        try {
            for (String scene : scenes.split(",")) enqueue(scene.strip(), record, false);
        } catch (IOException e) {
            BetterThanVillage.LOGGER.error("[demo] FAIL: {}", e.getMessage());
            clearQueue();
        }
        if (System.getenv("BTV_DEMO_QUIT") != null) {
            JOBS.add(mc -> {
                BetterThanVillage.LOGGER.info("[demo] {}: {} failed commands", failures == 0 ? "PASS" : "DONE", failures);
                if (mc.level != null) leave(mc);
                mc.stop();
                return true;
            });
        }
    }

    /** Queues a scene: its snapshot (and the snapshots it starts from) when missing or stale, then its play section. */
    private static void enqueue(String name, boolean record, boolean rebuild) throws IOException {
        DemoScene scene = DemoScene.load(scenesDir(), name);
        // A scene without setup plays on the snapshot of the scene it starts from.
        DemoScene base = scene;
        while (base.setup.isEmpty() && base.from != null) base = DemoScene.load(scenesDir(), base.from);
        planSnapshot(base, rebuild, new HashSet<>());
        JOBS.add(new OpenWorld(base.name));
        JOBS.add(new RunSection(scene, scene.play, record, false));
        JOBS.add(mc -> {
            mc.options.hideGui = false;
            say("scene " + name + " done" + (record ? ", media in " + out() : "") + "; /demo play " + name + " replays it");
            if (name.equals(looping)) enqueue(name, false, false);
            return true;
        });
    }

    private static void planSnapshot(DemoScene scene, boolean rebuild, Set<String> seen) throws IOException {
        if (!seen.add(scene.name)) throw new IOException("@from cycle at " + scene.name);
        // Already queued for an earlier scene of the same run.
        if (!rebuild && PLANNED.contains(scene.name)) return;
        String hash = setupHash(scene);
        Path stored = snapshot(scene.name).resolve(HASH_FILE);
        if (!rebuild && Files.isRegularFile(stored) && Files.readString(stored).strip().equals(hash)) return;
        if (scene.from != null) {
            planSnapshot(DemoScene.load(scenesDir(), scene.from), false, seen);
            JOBS.add(new OpenWorld(scene.from));
        } else {
            JOBS.add(new OpenWorld(null));
        }
        PLANNED.add(scene.name);
        JOBS.add(new RunSection(scene, scene.setup, false, true));
        JOBS.add(mc -> {
            PLANNED.remove(scene.name);
            leave(mc);
            writeVars(world());
            Files.writeString(world().resolve(HASH_FILE), hash);
            DemoFiles.copy(world(), snapshot(scene.name));
            BetterThanVillage.LOGGER.info("[demo] snapshot {} saved", scene.name);
            return true;
        });
    }

    /** Changes to the setup of a scene or of a scene it starts from make its snapshot stale. */
    private static String setupHash(DemoScene scene) throws IOException {
        String base = scene.from == null ? "" : setupHash(DemoScene.load(scenesDir(), scene.from));
        String text = base + "\n" + String.join("\n", scene.setup);
        return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8)).toString();
    }

    // ---------------------------------------------------------------- worlds

    private static void leave(Minecraft mc) {
        FrameRecorder.stop();
        if (mc.level == null) return;
        boolean local = mc.isLocalServer();
        mc.level.disconnect();
        if (local) {
            mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
        } else {
            mc.disconnect();
        }
        mc.setScreen(new TitleScreen());
    }

    /** Opens a copy of a scene's snapshot as the demo world, or a fresh world with the test seed (snapshot null). */
    private static final class OpenWorld implements Job {
        private final String snapshot;
        private boolean opened;
        private int ticks;

        OpenWorld(String snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public boolean tick(Minecraft mc) throws Exception {
            if (!opened) {
                opened = true;
                leave(mc);
                VARS.clear();
                if (snapshot == null) {
                    BetterThanVillage.LOGGER.info("[demo] creating a fresh world (seed {})", SEED);
                    DemoFiles.delete(world());
                    GameRules rules = new GameRules();
                    LevelSettings settings = new LevelSettings(WORLD, GameType.SPECTATOR, false, Difficulty.NORMAL, true, rules,
                            WorldDataConfiguration.DEFAULT);
                    WorldOptions options = new WorldOptions(WorldOptions.parseSeed(SEED).orElseThrow(), true, false);
                    mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, options, WorldPresets::createNormalWorldDimensions,
                            new TitleScreen());
                } else {
                    BetterThanVillage.LOGGER.info("[demo] opening snapshot {}", snapshot);
                    DemoFiles.copy(snapshot(snapshot), world());
                    readVars(world());
                    mc.createWorldOpenFlows().openWorld(WORLD, () -> mc.setScreen(new TitleScreen()));
                }
                return false;
            }
            IntegratedServer server = mc.getSingleplayerServer();
            boolean in = mc.level != null && mc.player != null && server != null && server.isReady() && mc.screen == null;
            if (!in) {
                if (++ticks > 20 * 300) throw new IllegalStateException("the demo world did not open");
                return false;
            }
            // A moment for the chunks around the camera
            return ++ticks > 40;
        }
    }

    private static void readVars(Path dir) throws IOException {
        Path file = dir.resolve(VARS_FILE);
        if (!Files.isRegularFile(file)) return;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file)) {
            p.load(r);
        }
        p.forEach((k, v) -> VARS.put((String) k, Long.parseLong((String) v)));
    }

    private static void writeVars(Path dir) throws IOException {
        Properties p = new Properties();
        VARS.forEach((k, v) -> p.setProperty(k, Long.toString(v)));
        try (Writer w = Files.newBufferedWriter(dir.resolve(VARS_FILE))) {
            p.store(w, "demo scene variables");
        }
    }

    // ---------------------------------------------------------------- sections

    /** Runs the lines of a section; commands go to the integrated server one after another. */
    private static final class RunSection implements Job {
        private final DemoScene scene;
        private final List<String> lines;
        private final boolean record;
        private final boolean setup;
        private int pc;
        private boolean started;
        private CompletableFuture<List<String>> pending;
        private String pendingCommand;
        private boolean waitSprint;
        private boolean waitRecord;
        /** End of the clip when nothing is recorded: a replay takes as long as a recording. */
        private long clipUntil;
        private long sleepUntil;
        private Pattern waitPattern;
        private long waitUntil;
        private Pattern untilPattern;
        private String untilCommand;
        private long untilDeadline;
        private long untilNext;
        private Pattern capturePattern;
        private String captureName;

        RunSection(DemoScene scene, List<String> lines, boolean record, boolean setup) {
            this.scene = scene;
            this.lines = lines;
            this.record = record;
            this.setup = setup;
        }

        @Override
        public boolean tick(Minecraft mc) throws Exception {
            IntegratedServer server = mc.getSingleplayerServer();
            if (mc.player == null || server == null) throw new IllegalStateException("left the demo world");
            if (!started) {
                started = true;
                current = scene.name;
                LogTap.skipToEnd();
                BetterThanVillage.LOGGER.info("[demo] scene {}: {} ({} lines)", scene.name, setup ? "setup" : "play", lines.size());
            }
            long now = System.nanoTime();
            if (setup && mc.player.tickCount % 20 == 0) {
                // Says what is going on: building the snapshot is not the scene yet.
                mc.gui.setOverlayMessage(Component.literal("[demo] building the snapshot of " + scene.name + ": line " + pc + "/" + lines.size()), false);
            }
            if (pending != null) {
                if (!pending.isDone()) return false;
                List<String> out = pending.join();
                pending = null;
                if (capturePattern != null) {
                    replies(pendingCommand, out);
                    Long value = out.stream().flatMap(r -> Arrays.stream(r.split("\n"))).map(capturePattern::matcher)
                            .filter(m -> m.find() && m.groupCount() >= 1).map(m -> Long.parseLong(m.group(1))).findFirst().orElse(null);
                    BetterThanVillage.LOGGER.info("[demo] capture {} = {}", captureName, value == null ? "NOT FOUND" : value);
                    if (value == null) failures++;
                    else VARS.put(captureName, value);
                    capturePattern = null;
                } else if (untilPattern == null) {
                    replies(pendingCommand, out);
                } else {
                    // A poll of #until: only its outcome is logged.
                    String hit = out.stream().flatMap(r -> Arrays.stream(r.split("\n"))).filter(l -> untilPattern.matcher(l).find()).findFirst().orElse(null);
                    if (hit != null || now >= untilDeadline) {
                        BetterThanVillage.LOGGER.info("[demo] until {}: {}", untilPattern, hit == null ? "TIMEOUT" : hit);
                        if (hit == null) failures++;
                        untilPattern = null;
                    } else {
                        untilNext = now + 2_000_000_000L;
                    }
                }
            }
            if (untilPattern != null) {
                if (now < untilNext) return false;
                command(mc, untilCommand);
                return false;
            }
            if (waitSprint) {
                if (server.tickRateManager().isSprinting()) return false;
                waitSprint = false;
            }
            if (sleepUntil != 0) {
                if (System.nanoTime() < sleepUntil) return false;
                sleepUntil = 0;
            }
            if (waitRecord) {
                if (FrameRecorder.busy() || System.nanoTime() < clipUntil) return false;
                waitRecord = false;
            }
            if (waitPattern != null) {
                String hit = LogTap.find(waitPattern);
                if (hit == null && System.nanoTime() < waitUntil) return false;
                BetterThanVillage.LOGGER.info("[demo] wait-log {}: {}", waitPattern, hit == null ? "TIMEOUT" : hit);
                if (hit == null) failures++;
                waitPattern = null;
            }
            while (pc < lines.size()) {
                if (!step(mc, lines.get(pc++))) return false;
            }
            return !FrameRecorder.busy();
        }

        /** Runs one line; false when it started something to wait for. */
        private boolean step(Minecraft mc, String line) throws IOException {
            String[] w = line.split("\\s+");
            switch (w[0]) {
                case "#sleep" -> {
                    sleepUntil = System.nanoTime() + (long) (Double.parseDouble(w[1]) * 1e9);
                    return false;
                }
                case "#ticks" -> {
                    command(mc, "tick sprint " + substitute(w[1]));
                    waitSprint = true;
                    return false;
                }
                case "#wait-log" -> {
                    Matcher m = Pattern.compile("(.*)\\s+(\\d+)$").matcher(line.substring(9).strip());
                    String regex = m.matches() ? m.group(1) : line.substring(9).strip();
                    waitPattern = Pattern.compile(regex);
                    waitUntil = System.nanoTime() + (m.matches() ? Long.parseLong(m.group(2)) : 120) * 1_000_000_000L;
                    return false;
                }
                case "#until" -> {
                    // #until SECONDS REGEX :: COMMAND - runs the command every two seconds until a reply line matches.
                    String rest = line.substring(6).strip();
                    int sep = rest.indexOf(" :: ");
                    String[] head = sep < 0 ? new String[0] : rest.substring(0, sep).split("\\s+", 2);
                    if (head.length < 2) throw new IOException(scene.name + ": #until SECONDS REGEX :: COMMAND, got " + line);
                    untilDeadline = System.nanoTime() + (long) (Double.parseDouble(head[0]) * 1e9);
                    untilPattern = Pattern.compile(head[1]);
                    untilCommand = substitute(rest.substring(sep + 4).strip());
                    untilNext = 0;
                    return false;
                }
                case "#capture" -> {
                    // #capture NAME REGEX :: COMMAND - the first group of the first matching reply line becomes NAME.
                    String rest = line.substring(8).strip();
                    int sep = rest.indexOf(" :: ");
                    String[] head = sep < 0 ? new String[0] : rest.substring(0, sep).split("\\s+", 2);
                    if (head.length < 2) throw new IOException(scene.name + ": #capture NAME REGEX :: COMMAND, got " + line);
                    captureName = head[0];
                    capturePattern = Pattern.compile(head[1]);
                    command(mc, substitute(rest.substring(sep + 4).strip()));
                    return false;
                }
                case "#set" -> {
                    VARS.put(w[1], eval(String.join(" ", Arrays.copyOfRange(w, 2, w.length))));
                    return true;
                }
                case "#camera" -> {
                    String name = w.length > 1 ? w[1] : "main";
                    DemoScene.Camera cam = scene.cameras.get(name);
                    if (cam == null) {
                        BetterThanVillage.LOGGER.warn("[demo] scene {} has no camera {} (fly there and /demo cam {})", scene.name, name, name);
                        return true;
                    }
                    mc.options.hideGui = true;
                    command(mc, String.format(java.util.Locale.ROOT, "execute in minecraft:overworld run tp @s %.2f %.2f %.2f %.1f %.1f",
                            cam.x(), cam.y(), cam.z(), cam.yaw(), cam.pitch()));
                    return false;
                }
                case "#gui" -> {
                    mc.options.hideGui = !w[1].equals("on");
                    return true;
                }
                case "#shot" -> {
                    if (record) FrameRecorder.shot(out().resolve(w[1] + ".png"));
                    return true;
                }
                case "#record" -> {
                    if (record) FrameRecorder.startClip(out().resolve(w[1]), Double.parseDouble(w[2]));
                    else clipUntil = System.nanoTime() + (long) (Double.parseDouble(w[2]) * 1e9);
                    return true;
                }
                case "#wait-record" -> {
                    waitRecord = true;
                    return false;
                }
                default -> {
                    if (line.startsWith("#")) throw new IOException(scene.name + ": unknown directive " + line);
                    command(mc, substitute(line));
                    return false;
                }
            }
        }

        private void command(Minecraft mc, String command) {
            pendingCommand = command;
            pending = run(mc, command);
        }
    }

    /** Runs a command on the integrated server as the player (permission 4); the replies come back as text. */
    private static CompletableFuture<List<String>> run(Minecraft mc, String command) {
        IntegratedServer server = mc.getSingleplayerServer();
        UUID id = mc.player.getUUID();
        CompletableFuture<List<String>> result = new CompletableFuture<>();
        server.execute(() -> {
            List<String> out = new ArrayList<>();
            CommandSource sink = new CommandSource() {
                @Override
                public void sendSystemMessage(Component message) {
                    TextColor color = message.getStyle().getColor();
                    boolean failed = color != null && color.equals(TextColor.fromLegacyFormat(ChatFormatting.RED));
                    out.add((failed ? "FAILED: " : "") + message.getString());
                }

                @Override
                public boolean acceptsSuccess() {
                    return true;
                }

                @Override
                public boolean acceptsFailure() {
                    return true;
                }

                @Override
                public boolean shouldInformAdmins() {
                    return false;
                }
            };
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            CommandSourceStack source = (player != null ? player.createCommandSourceStack() : server.createCommandSourceStack())
                    .withSource(sink).withPermission(4);
            try {
                server.getCommands().performPrefixedCommand(source, command);
            } catch (RuntimeException e) {
                out.add("FAILED: " + e);
            }
            result.complete(out);
        });
        return result;
    }

    private static void replies(String command, List<String> replies) {
        BetterThanVillage.LOGGER.info("[demo] > {}", command);
        for (String reply : replies) {
            for (String line : reply.split("\n")) {
                BetterThanVillage.LOGGER.info("[demo]   {}", line);
                LogTap.add(line);
            }
            if (reply.startsWith("FAILED: ")) failures++;
            Matcher m = LOCATION.matcher(reply);
            if (m.find()) {
                VARS.put("LX", Long.parseLong(m.group(1)));
                VARS.put("LZ", Long.parseLong(m.group(2)));
            }
        }
    }

    // ---------------------------------------------------------------- variables

    /** {@code {LX+16}}-style expressions on the scene variables; other braces (NBT) stay as they are. */
    private static String substitute(String line) {
        Matcher m = VARIABLE.matcher(line);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String expr = m.group(1);
            boolean known = Arrays.stream(expr.split("[^A-Za-z0-9_]+")).anyMatch(VARS::containsKey);
            m.appendReplacement(sb, Matcher.quoteReplacement(known ? Long.toString(eval(expr)) : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Sums of products of integers and variables. */
    private static long eval(String expr) {
        long sum = 0;
        for (String term : expr.replace(" ", "").replace("-", "+-").split("\\+")) {
            if (term.isEmpty()) continue;
            long product = 1;
            for (String factor : term.split("\\*")) {
                boolean negative = factor.startsWith("-");
                String name = negative ? factor.substring(1) : factor;
                long value;
                if (name.matches("\\d+")) {
                    value = Long.parseLong(name);
                } else if (VARS.containsKey(name)) {
                    value = VARS.get(name);
                } else {
                    throw new IllegalArgumentException("unknown variable " + name + " in " + expr);
                }
                product *= negative ? -value : value;
            }
            sum += product;
        }
        return sum;
    }

    // ---------------------------------------------------------------- commands

    private static void onCommands(RegisterClientCommandsEvent event) {
        SuggestionProvider<CommandSourceStack> scenes = (ctx, b) -> SharedSuggestionProvider.suggest(DemoScene.list(scenesDir()), b);
        event.getDispatcher().register(Commands.literal("demo")
                .executes(DemoDirector::list)
                .then(Commands.literal("list").executes(DemoDirector::list))
                .then(Commands.literal("play").then(Commands.argument("scene", StringArgumentType.word()).suggests(scenes)
                        .executes(ctx -> start(ctx, false, false, false))))
                .then(Commands.literal("loop").then(Commands.argument("scene", StringArgumentType.word()).suggests(scenes)
                        .executes(ctx -> start(ctx, false, false, true))))
                .then(Commands.literal("record").then(Commands.argument("scene", StringArgumentType.word()).suggests(scenes)
                        .executes(ctx -> start(ctx, true, false, false))))
                .then(Commands.literal("setup").then(Commands.argument("scene", StringArgumentType.word()).suggests(scenes)
                        .executes(ctx -> start(ctx, false, true, false))))
                .then(Commands.literal("cam")
                        .executes(ctx -> saveCamera("main"))
                        .then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> saveCamera(StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("goto")
                        .executes(ctx -> gotoCamera("main"))
                        .then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> gotoCamera(StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("stop").executes(ctx -> {
                    clearQueue();
                    looping = null;
                    FrameRecorder.stop();
                    Minecraft.getInstance().options.hideGui = false;
                    say("stopped");
                    return 1;
                })));
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        List<String> names = DemoScene.list(scenesDir());
        if (names.isEmpty()) say("no scenes in " + scenesDir());
        for (String name : names) {
            String info;
            try {
                DemoScene scene = DemoScene.load(scenesDir(), name);
                boolean fresh = Files.isRegularFile(snapshot(name).resolve(HASH_FILE))
                        && Files.readString(snapshot(name).resolve(HASH_FILE)).strip().equals(setupHash(scene));
                info = (fresh ? "snapshot ready" : "needs setup") + ", cameras " + scene.cameras.keySet()
                        + (scene.from != null ? ", from " + scene.from : "");
            } catch (IOException e) {
                info = e.getMessage();
            }
            say(name + (name.equals(current) ? " (current)" : "") + ": " + info);
        }
        return names.size();
    }

    private static int start(CommandContext<CommandSourceStack> ctx, boolean record, boolean rebuild, boolean loop) {
        String name = StringArgumentType.getString(ctx, "scene");
        clearQueue();
        FrameRecorder.stop();
        looping = loop ? name : null;
        try {
            enqueue(name, record, rebuild);
        } catch (IOException e) {
            clearQueue();
            say("cannot start " + name + ": " + e.getMessage());
            return 0;
        }
        say((rebuild ? "rebuilding " : record ? "recording " : "playing ") + name);
        return 1;
    }

    private static int saveCamera(String name) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (current == null || p == null) {
            say("play a scene first");
            return 0;
        }
        DemoScene.Camera cam = new DemoScene.Camera(name, p.getX(), p.getY(), p.getZ(), Mth.wrapDegrees(p.getYRot()), p.getXRot());
        try {
            DemoScene.load(scenesDir(), current).saveCamera(cam);
        } catch (IOException e) {
            say("cannot save: " + e.getMessage());
            return 0;
        }
        say(current + ": " + cam.line());
        return 1;
    }

    private static int gotoCamera(String name) {
        Minecraft mc = Minecraft.getInstance();
        try {
            DemoScene.Camera cam = current == null ? null : DemoScene.load(scenesDir(), current).cameras.get(name);
            if (cam == null || mc.getSingleplayerServer() == null) {
                say("no camera " + name);
                return 0;
            }
            run(mc, String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f", cam.x(), cam.y(), cam.z(), cam.yaw(), cam.pitch()));
        } catch (IOException e) {
            say(e.getMessage());
            return 0;
        }
        return 1;
    }

    private static void say(String text) {
        BetterThanVillage.LOGGER.info("[demo] {}", text);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.gui.getChat().addMessage(Component.literal("[demo] " + text).withStyle(ChatFormatting.GRAY));
    }
}
