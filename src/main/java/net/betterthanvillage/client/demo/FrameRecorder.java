package net.betterthanvillage.client.demo;

import com.mojang.blaze3d.platform.NativeImage;
import net.betterthanvillage.BetterThanVillage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Grabs rendered frames for the demo scenes: single screenshots and clips (numbered PNG frames at a fixed rate of
 * wall-clock time, turned into GIFs by {@code demo/gif.sh}). PNG encoding runs off the render thread.
 */
final class FrameRecorder {
    static final int FPS = 15;
    private static final ExecutorService WRITER = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "btv-demo-frames");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger PENDING = new AtomicInteger();

    private static Path clipDir;
    private static long clipEnd;
    private static long nextFrame;
    private static int frame;
    private static Path shot;

    private FrameRecorder() {
    }

    /** Starts a clip into an emptied directory; it runs for the given seconds of wall-clock time. */
    static void startClip(Path dir, double seconds) throws IOException {
        if (Files.isDirectory(dir)) DemoFiles.delete(dir);
        Files.createDirectories(dir);
        long now = System.nanoTime();
        clipDir = dir;
        clipEnd = now + (long) (seconds * 1e9);
        nextFrame = now;
        frame = 0;
    }

    /** Saves the next rendered frame. */
    static void shot(Path file) {
        shot = file;
    }

    /** A clip or a screenshot is still being taken or written. */
    static boolean busy() {
        return clipDir != null || shot != null || PENDING.get() > 0;
    }

    static void stop() {
        clipDir = null;
        shot = null;
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Post event) {
        if (shot != null) {
            write(shot);
            shot = null;
        }
        if (clipDir == null) return;
        long now = System.nanoTime();
        if (now >= clipEnd) {
            BetterThanVillage.LOGGER.info("[demo] clip {} done, {} frames", clipDir.getFileName(), frame);
            clipDir = null;
            return;
        }
        if (now < nextFrame) return;
        // A late frame is not made up for: the clip just has fewer frames (and plays a little faster).
        nextFrame += 1_000_000_000L / FPS;
        if (nextFrame <= now) nextFrame = now + 1_000_000_000L / FPS;
        write(clipDir.resolve(String.format("%05d.png", ++frame)));
    }

    private static void write(Path file) {
        NativeImage image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget());
        PENDING.incrementAndGet();
        WRITER.execute(() -> {
            try (image) {
                Files.createDirectories(file.getParent());
                image.writeToFile(file);
            } catch (IOException e) {
                BetterThanVillage.LOGGER.warn("[demo] could not write {}", file, e);
            } finally {
                PENDING.decrementAndGet();
            }
        });
    }
}
