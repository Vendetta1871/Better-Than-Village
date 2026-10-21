package net.betterthanvillage.world;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Worker threads for pure computation on snapshots (docs_v2/08-simulation.md §Spreading the work): the server
 * thread hands over an immutable snapshot, a worker computes, and the result is applied on the server thread
 * within the scheduler's time budget. Workers never touch live world objects; the apply step validates the result
 * against the current state.
 */
public final class SimWorkers {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static ExecutorService executor;
    private static final Deque<Pending<?>> PENDING = new ArrayDeque<>();

    private record Pending<T>(CompletableFuture<T> future, Consumer<T> apply, Runnable failed) {
        void run() {
            T result;
            try {
                result = future.join();
            } catch (RuntimeException e) {
                LOGGER.warn("[world] background job failed", e);
                if (failed != null) failed.run();
                return;
            }
            apply.accept(result);
        }
    }

    private SimWorkers() {
    }

    private static synchronized ExecutorService executor() {
        if (executor == null) {
            executor = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "btv-sim-worker");
                t.setDaemon(true);
                return t;
            });
        }
        return executor;
    }

    /**
     * Computes on a worker (or right here when async work is off) and applies the result on the server thread.
     * failed runs on the server thread if the computation threw.
     */
    public static <T> void submit(Supplier<T> compute, Consumer<T> apply, Runnable failed) {
        CompletableFuture<T> future;
        if (net.betterthanvillage.config.ColonyConfig.get().world.async_workers) {
            future = CompletableFuture.supplyAsync(compute, executor());
        } else {
            future = new CompletableFuture<>();
            try {
                future.complete(compute.get());
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        }
        PENDING.add(new Pending<>(future, apply, failed));
    }

    /** Applies finished results until the deadline (System.nanoTime). */
    public static void drain(long deadline) {
        Iterator<Pending<?>> it = PENDING.iterator();
        while (it.hasNext()) {
            Pending<?> p = it.next();
            if (!p.future().isDone()) continue;
            it.remove();
            p.run();
            if (System.nanoTime() > deadline) return;
        }
    }

    public static int pending() {
        return PENDING.size();
    }

    /** Server stop: running jobs are abandoned, their results never applied. */
    public static synchronized void shutdown() {
        PENDING.clear();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
