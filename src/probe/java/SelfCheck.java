import dev.devinwilson.mcsilicon.Activity;
import dev.devinwilson.mcsilicon.Darwin;
import dev.devinwilson.mcsilicon.Machine;
import dev.devinwilson.mcsilicon.Qos;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Checks the assumptions mcsilicon rests on, against the real machine.
 * Run: ./gradlew selfCheck
 */
public final class SelfCheck {

    public static void main(String[] args) throws Exception {
        require(Darwin.IS_MACOS, "not macOS");
        require(Darwin.available(), "libSystem bindings unavailable: " + Darwin.LOAD_ERROR);

        // 1. QoS can be set on the calling thread and read back.
        System.out.println("main QoS before = " + Darwin.qosName(Darwin.selfQos()));
        require(Darwin.setSelfQos(Darwin.QOS_USER_INTERACTIVE, 0), "pthread_set_qos_class_self_np failed");
        int after = Darwin.selfQos();
        System.out.println("main QoS after  = " + Darwin.qosName(after));
        require(after == Darwin.QOS_USER_INTERACTIVE, "QoS did not stick: " + Darwin.qosName(after));

        // 2. Child threads do NOT inherit. This is why promoteExecutor has to exist at all;
        //    if this ever starts passing, the pool saturation trick becomes dead code.
        AtomicInteger child = new AtomicInteger();
        Thread t = new Thread(() -> child.set(Darwin.selfQos()), "child");
        t.start();
        t.join();
        System.out.println("child QoS       = " + Darwin.qosName(child.get()) + " (inheritance not expected)");
        require(child.get() != Darwin.QOS_USER_INTERACTIVE,
                "child inherited QoS - promoteExecutor is now redundant, simplify it away");

        // 3. promoteExecutor reaches every distinct worker thread in a pool.
        int width = 4;
        ExecutorService pool = Executors.newFixedThreadPool(width);
        try {
            int promoted = Qos.promoteExecutor(pool, Darwin.QOS_USER_INITIATED, width, 2000L);
            System.out.println("pool promoted   = " + promoted + "/" + width);
            require(promoted == width, "only promoted " + promoted + " of " + width);

            Set<String> seen = ConcurrentHashMap.newKeySet();
            AtomicInteger wrong = new AtomicInteger();
            for (int i = 0; i < width * 8; i++) {
                pool.execute(() -> {
                    seen.add(Thread.currentThread().getName());
                    if (Darwin.selfQos() != Darwin.QOS_USER_INITIATED) wrong.incrementAndGet();
                });
            }
            pool.shutdown();
            require(pool.awaitTermination(10, TimeUnit.SECONDS), "pool did not drain");
            System.out.println("distinct workers= " + seen.size() + ", wrong QoS = " + wrong.get());
            require(wrong.get() == 0, wrong.get() + " tasks ran at the wrong QoS");
        } finally {
            pool.shutdownNow();
        }

        // 4. Mach real-time policy is accepted. On a scratch thread, not main: the policy sticks
        //    for the life of the thread, and an RT main would distort every check after it.
        AtomicInteger rt = new AtomicInteger(-1);
        Thread rtThread = new Thread(
                () -> rt.set(Darwin.setSelfRealtime(0L, 3_000_000L, 6_000_000L) ? 1 : 0), "rt");
        rtThread.start();
        rtThread.join();
        System.out.println("realtime policy = " + (rt.get() == 1 ? "accepted" : "REJECTED"));
        require(rt.get() == 1, "thread_policy_set(THREAD_TIME_CONSTRAINT_POLICY) failed");

        // A zero budget must be refused rather than reported as a successful promotion. Caught by
        // Darwin's own argument check before the kernel sees it, which is the point: that guard is
        // what stands between a typo'd config and a thread that looks promoted but reserved nothing.
        AtomicInteger bad = new AtomicInteger(-1);
        Thread badThread = new Thread(
                () -> bad.set(Darwin.setSelfRealtime(0L, 0L, 0L) ? 1 : 0), "rt-bad");
        badThread.start();
        badThread.join();
        require(bad.get() == 0, "zero-budget real-time request was accepted");

        // 5. NSProcessInfo latency-critical activity. This one goes through objc_msgSend, where a
        //    mistyped call crashes rather than returning an error, so exercising it here is the
        //    point — a probe process is a much better place to find that out than a game launch.
        boolean latency = Activity.beginLatencyCritical();
        System.out.println("latency-critical= " + (latency ? "granted" : "FAILED: " + Activity.error));
        require(latency, "beginActivityWithOptions: failed: " + Activity.error);
        require(Activity.beginLatencyCritical(), "second call should be a no-op returning true");

        // 6. Hardware probe returns something usable.
        Machine m = Machine.probe();
        System.out.println("cpu             = " + m.cpu());
        System.out.println("tiers           = " + m.describeTiers());
        System.out.println("fast/preferred  = " + m.fastCores() + "/" + m.preferredCores());
        System.out.println("memory          = " + m.memoryGigabytes() + " GB");
        System.out.println("rosetta         = " + m.translated());
        require(!m.cpu().isBlank(), "sysctl string read failed");
        require(m.memoryGigabytes() > 0, "sysctl long read failed");
        require(m.preferredCores() >= 1, "core count nonsense");

        System.out.println("\nALL CHECKS PASSED");
    }

    private static void require(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }
}
