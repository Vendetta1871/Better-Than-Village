package net.betterthanvillage.construction;

import net.betterthanvillage.BetterThanVillage;
import net.betterthanvillage.mixin.society.StructureTemplateAccess;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runtime building catalog built from the vanilla village house templates
 * ({@code minecraft:village/<biome>/houses/<biome>_<name>}), playing the role of the spec's
 * catalog (05 §1.6). Farms, pens, stables, accessories and meeting points are excluded; dwellings
 * ("homes") are small/medium/big houses with at least one bed.
 */
public final class HouseCatalog {
    public static final List<String> BIOMES = List.of("plains", "desert", "savanna", "snowy", "taiga");
    private static final Pattern HOME = Pattern.compile("(small|medium|big)_house_(\\d+)");
    private static final List<String> EXCLUDED = List.of("farm", "animal_pen", "accessory", "meeting_point", "stable");

    /** A catalog house: name without the biome prefix, template id, unrotated size and bed count. */
    public record Entry(String biome, String name, ResourceLocation id, Vec3i size, int beds) {
        int volume() {
            return size.getX() * size.getY() * size.getZ();
        }
    }

    /** Per-biome lists: homes in cycling order, all houses alphabetically, and the large commission house. */
    public record BiomeSet(List<Entry> homes, List<Entry> all, Entry largeCommission) {
    }

    private static StructureTemplateManager owner;
    private static Map<String, BiomeSet> catalog = Map.of();

    private HouseCatalog() {
    }

    public static ResourceLocation templateId(String biome, String name) {
        return ResourceLocation.withDefaultNamespace("village/" + biome + "/houses/" + biome + "_" + name);
    }

    public static synchronized BiomeSet get(ServerLevel level, String biome) {
        StructureTemplateManager manager = level.getStructureManager();
        if (manager != owner) {
            owner = manager;
            catalog = build(manager);
        }
        return catalog.get(BIOMES.contains(biome) ? biome : "plains");
    }

    /** Round-robin dwelling for the given count of completed plots (05 §2.2). */
    public static Entry home(ServerLevel level, String biome, int completed) {
        List<Entry> homes = get(level, biome).homes();
        return homes.isEmpty() ? null : homes.get(Math.floorMod(completed, homes.size()));
    }

    /**
     * Commission template by tier (05 §1.6): tier &lt; 2 is small_house_1; higher tiers use the
     * biome's largest dwelling (most beds, then largest volume) as the vanilla equivalent of the
     * spec's big/medium commission houses.
     */
    public static Entry commission(ServerLevel level, String biome, int tier) {
        BiomeSet set = get(level, biome);
        if (tier >= 2 && set.largeCommission() != null) {
            return set.largeCommission();
        }
        for (Entry e : set.homes()) {
            if (e.name().equals("small_house_1")) {
                return e;
            }
        }
        return set.homes().isEmpty() ? null : set.homes().get(0);
    }

    /** Looks up a house by biome and name (all list). */
    public static Entry byName(ServerLevel level, String biome, String name) {
        for (Entry e : get(level, biome).all()) {
            if (e.name().equals(name)) {
                return e;
            }
        }
        return null;
    }

    public static List<StructureTemplate.StructureBlockInfo> blocks(StructureTemplate template) {
        List<StructureTemplate.Palette> palettes = ((StructureTemplateAccess) template).btv$palettes();
        return palettes.isEmpty() ? List.of() : palettes.get(0).blocks();
    }

    public static Optional<StructureTemplate> template(ServerLevel level, ResourceLocation id) {
        try {
            return level.getStructureManager().get(id);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Map<String, BiomeSet> build(StructureTemplateManager manager) {
        Map<String, List<String>> names = new LinkedHashMap<>();
        for (String b : BIOMES) {
            names.put(b, new ArrayList<>());
        }
        manager.listTemplates().forEach(id -> {
            if (!id.getNamespace().equals("minecraft")) return;
            for (String b : BIOMES) {
                String prefix = "village/" + b + "/houses/" + b + "_";
                if (id.getPath().startsWith(prefix)) {
                    String name = id.getPath().substring(prefix.length());
                    if (EXCLUDED.stream().noneMatch(name::contains)) {
                        names.get(b).add(name);
                    }
                }
            }
        });
        Map<String, BiomeSet> out = new LinkedHashMap<>();
        for (String b : BIOMES) {
            List<Entry> all = new ArrayList<>();
            for (String name : names.get(b).stream().sorted().toList()) {
                ResourceLocation id = templateId(b, name);
                Optional<StructureTemplate> t = manager.get(id);
                if (t.isEmpty()) continue;
                int beds = 0;
                for (StructureTemplate.StructureBlockInfo info : blocks(t.get())) {
                    if (info.state().getBlock() instanceof BedBlock && info.state().getValue(BedBlock.PART) == BedPart.HEAD) {
                        beds++;
                    }
                }
                all.add(new Entry(b, name, id, t.get().getSize(), beds));
            }
            List<Entry> homes = new ArrayList<>();
            for (Entry e : all) {
                if (HOME.matcher(e.name()).matches() && e.beds() > 0) {
                    homes.add(e);
                }
            }
            boolean smallFirst = b.equals("savanna") || b.equals("taiga");
            homes.sort(Comparator.comparingInt((Entry e) -> sizeRank(e.name(), smallFirst)).thenComparingInt(e -> number(e.name())));
            Entry large = homes.stream().max(Comparator.comparingInt(Entry::beds).thenComparingInt(Entry::volume)).orElse(null);
            out.put(b, new BiomeSet(List.copyOf(homes), List.copyOf(all), large));
            BetterThanVillage.LOGGER.debug("House catalog {}: homes {}, all {}", b, homes.stream().map(Entry::name).toList(), all.size());
        }
        return out;
    }

    private static int sizeRank(String name, boolean smallFirst) {
        Matcher m = HOME.matcher(name);
        if (!m.matches()) return 9;
        return switch (m.group(1)) {
            case "big" -> smallFirst ? 2 : 0;
            case "medium" -> 1;
            default -> smallFirst ? 0 : 2;
        };
    }

    private static int number(String name) {
        Matcher m = HOME.matcher(name);
        return m.matches() ? Integer.parseInt(m.group(2)) : 0;
    }
}
