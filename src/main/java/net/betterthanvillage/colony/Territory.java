package net.betterthanvillage.colony;

import net.betterthanvillage.config.ColonyConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.Random;

/**
 * The territory of a village (docs_v2/04-land.md §Two levels): a noisy blob around the bell. Its border in a
 * direction lies at {@code radius * factor(angle)}, where the factor is a smooth periodic noise in
 * [1 - noise, 1] seeded by the village id, so the shape stays the same across reloads. {@link Colony#radius()}
 * is the outer bound of the shape (scan boxes keep using it); the territory grows by raising it, up to a maximum
 * given as an area.
 */
public final class Territory {
    private static final int SAMPLES = 128;
    private static final String SHAPE_KEY = "btv_territory_shape";

    private Territory() {
    }

    /** Shape samples of a village, cached in its runtime state. */
    private static double[] shape(Colony colony) {
        return colony.runtime(SHAPE_KEY, () -> buildShape(colony));
    }

    private static double[] buildShape(Colony colony) {
        long seed = colony.id().getMostSignificantBits() ^ colony.id().getLeastSignificantBits();
        Random random = new Random(seed);
        int harmonics = 4;
        double[] amp = new double[harmonics];
        double[] phase = new double[harmonics];
        for (int k = 0; k < harmonics; k++) {
            amp[k] = (0.5 + random.nextDouble()) / (k + 1);
            phase[k] = random.nextDouble() * Math.PI * 2;
        }
        double[] raw = new double[SAMPLES];
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = 0; i < SAMPLES; i++) {
            double a = i * Math.PI * 2 / SAMPLES;
            double v = 0;
            for (int k = 0; k < harmonics; k++) v += amp[k] * Math.cos((k + 2) * a + phase[k]);
            raw[i] = v;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        double noise = ColonyConfig.get().land.territory_noise;
        double[] out = new double[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            double g = max > min ? (raw[i] - min) / (max - min) : 1;
            out[i] = 1 - noise * (1 - g);
        }
        return out;
    }

    /** Border distance in a direction, as a share of the radius: in [1 - noise, 1]. */
    public static double factor(Colony colony, double dx, double dz) {
        double[] s = shape(colony);
        double a = Math.atan2(dz, dx);
        if (a < 0) a += Math.PI * 2;
        double t = a / (Math.PI * 2) * SAMPLES;
        int i = (int) Math.floor(t) % SAMPLES;
        double f = t - Math.floor(t);
        return s[i] * (1 - f) + s[(i + 1) % SAMPLES] * f;
    }

    /** Smallest border factor: a circle of radius {@code radius * minFactor} lies wholly inside. */
    public static double minFactor(Colony colony) {
        double min = 1;
        for (double f : shape(colony)) min = Math.min(min, f);
        return min;
    }

    /**
     * Grows the territory so a circle of radius r around the bell (plus margin) lies inside it; false (and no
     * growth) if that would pass the maximum.
     */
    public static boolean growToHoldCircle(Colony colony, int r, double margin) {
        int need = (int) Math.ceil((r + margin) / minFactor(colony));
        if (need > maxRadius(colony)) return colony.radius() >= need;
        colony.growRadius(need);
        return true;
    }

    /** True if the column lies inside the territory widened by margin blocks. */
    public static boolean contains(Colony colony, double x, double z, double margin) {
        double dx = x - colony.core().getX(), dz = z - colony.core().getZ();
        double d2 = dx * dx + dz * dz;
        double outer = colony.radius() + margin;
        if (d2 > outer * outer) return false;
        double reach = colony.radius() * factor(colony, dx, dz) + margin;
        return reach > 0 && d2 <= reach * reach;
    }

    public static boolean contains(Colony colony, BlockPos pos, double margin) {
        return contains(colony, pos.getX(), pos.getZ(), margin);
    }

    /** True if the whole box (widened by margin blocks) lies inside the territory. */
    public static boolean containsBox(Colony colony, int minX, int minZ, int maxX, int maxZ, double margin) {
        return contains(colony, minX - margin, minZ - margin, 0) && contains(colony, maxX + margin, minZ - margin, 0)
                && contains(colony, minX - margin, maxZ + margin, 0) && contains(colony, maxX + margin, maxZ + margin, 0);
    }

    /** Radius the shape needs to reach the column plus margin blocks. */
    public static int radiusFor(Colony colony, double x, double z, double margin) {
        double dx = x - colony.core().getX(), dz = z - colony.core().getZ();
        return (int) Math.ceil((Math.sqrt(dx * dx + dz * dz) + margin) / factor(colony, dx, dz));
    }

    /** Radius the shape needs to hold the box plus margin blocks. */
    public static int radiusFor(Colony colony, int minX, int minZ, int maxX, int maxZ, double margin) {
        return Math.max(Math.max(radiusFor(colony, minX, minZ, margin), radiusFor(colony, maxX, minZ, margin)),
                Math.max(radiusFor(colony, minX, maxZ, margin), radiusFor(colony, maxX, maxZ, margin)));
    }

    public static int radiusFor(Colony colony, BoundingBox box, double margin) {
        return radiusFor(colony, box.minX(), box.minZ(), box.maxX(), box.maxZ(), margin);
    }

    /** Mean square of the shape factor: the area is {@code PI * radius^2 * meanSquare}. */
    private static double meanSquare(Colony colony) {
        double sum = 0;
        for (double f : shape(colony)) sum += f * f;
        return sum / SAMPLES;
    }

    /** Area of the territory in square blocks. */
    public static long area(Colony colony) {
        return Math.round(Math.PI * colony.radius() * colony.radius() * meanSquare(colony));
    }

    /** The largest radius construction may grow the territory to: the configured maximum area. */
    public static int maxRadius(Colony colony) {
        return (int) Math.floor(Math.sqrt(ColonyConfig.get().land.territory_max_area / (Math.PI * meanSquare(colony))));
    }

    /** True once construction cannot grow the territory any more. */
    public static boolean atMax(Colony colony) {
        return colony.radius() >= maxRadius(colony);
    }

    /**
     * Grows the territory (never past the maximum) so it holds the box plus margin blocks; returns true if it
     * holds it afterwards.
     */
    public static boolean growToHold(Colony colony, int minX, int minZ, int maxX, int maxZ, double margin) {
        int need = radiusFor(colony, minX, minZ, maxX, maxZ, margin);
        colony.growRadius(Math.min(need, maxRadius(colony)));
        return colony.radius() >= need;
    }

    /** Grows the territory by step blocks, up to the maximum; returns false if it was already at the maximum. */
    public static boolean grow(Colony colony, int step) {
        int max = maxRadius(colony);
        if (colony.radius() >= max) return false;
        colony.growRadius(Math.min(max, colony.radius() + step));
        return true;
    }
}
