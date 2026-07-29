package dev.devinwilson.mcsilicon;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread QoS promotion.
 *
 * <p>macOS schedules by QoS class, not by Java thread priority: BACKGROUND and UTILITY are pushed
 * onto the slowest core tier, while USER_INTERACTIVE and USER_INITIATED get the fastest. The JVM
 * starts every thread at QOS_CLASS_DEFAULT and does <em>not</em> propagate the creating thread's
 * class, so each thread has to raise its own — hence the saturation trick for pools.
 */
public final class Qos {

    /** Raises the calling thread. Returns the class actually in effect afterwards. */
    public static int promoteSelf(int qos) {
        Darwin.setSelfQos(qos, 0);
        return Darwin.selfQos();
    }

    /**
     * Raises every worker thread of {@code executor} by occupying all of them at once: each task
     * sets its own QoS, reports in, then parks until the others have arrived so the pool cannot
     * reuse one thread for several tasks.
     *
     * <p>Entirely best-effort. If the pool is smaller than {@code threads}, or busy, or refuses the
     * work, it gives up at the timeout and returns however many it reached.
     *
     * @return number of distinct threads confirmed promoted
     */
    public static int promoteExecutor(Executor executor, int qos, int threads, long timeoutMs) {
        if (executor == null || threads <= 0 || !Darwin.available()) return 0;
        // Hard ceiling: each task parks a thread, so an over-large count on a growable pool
        // spawns threads until the JVM runs out of them.
        threads = Math.min(threads, MAX_PROMOTE);

        AtomicInteger promoted = new AtomicInteger();
        CountDownLatch arrived = new CountDownLatch(threads);
        CountDownLatch release = new CountDownLatch(1);

        for (int i = 0; i < threads; i++) {
            try {
                executor.execute(() -> {
                    if (Darwin.setSelfQos(qos, 0)) promoted.incrementAndGet();
                    arrived.countDown();
                    try {
                        // Bounded, so a stalled caller can never wedge a game worker thread.
                        release.await(timeoutMs + 1000L, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (RuntimeException rejected) {
                break;
            }
        }

        try {
            arrived.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            release.countDown();
        }
        return promoted.get();
    }

    /** Never park more than this many threads at once, whatever a pool claims its size is. */
    private static final int MAX_PROMOTE = 64;

    /**
     * Best guess at how many worker threads {@code executor} has.
     *
     * <p>A cached pool reports {@code Integer.MAX_VALUE} as its maximum size, so an unbounded
     * pool is measured by the threads it currently has rather than the ones it would happily
     * create on demand.
     */
    public static int widthOf(Executor executor) {
        if (executor instanceof ForkJoinPool p) return clamp(p.getParallelism());
        if (executor instanceof ThreadPoolExecutor p) {
            int max = p.getMaximumPoolSize();
            return clamp(max >= Integer.MAX_VALUE / 2 ? p.getPoolSize() : max);
        }
        return clamp(Runtime.getRuntime().availableProcessors());
    }

    private static int clamp(int n) {
        return Math.max(1, Math.min(n, MAX_PROMOTE));
    }

    private Qos() {
    }
}
