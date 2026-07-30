package dev.devinwilson.mcsilicon;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.LongByReference;

/**
 * libSystem bindings.
 *
 * <p>Uses JNA, which Minecraft already ships (OSHI depends on it) — so this costs no bundled
 * dependency and no native build step. The FFM API would be tidier, but it is still a preview
 * feature on the Java 21 that this Minecraft requires, and forcing {@code --enable-preview} onto
 * players is a worse trade.
 *
 * <p>Every entry point degrades to a no-op off macOS or if a symbol is missing.
 */
public final class Darwin {

    /** qos_class_t values from {@code <sys/qos.h>}. */
    public static final int QOS_USER_INTERACTIVE = 0x21;
    public static final int QOS_USER_INITIATED = 0x19;
    public static final int QOS_DEFAULT = 0x15;
    public static final int QOS_UTILITY = 0x11;
    public static final int QOS_BACKGROUND = 0x09;
    public static final int QOS_UNSPECIFIED = 0x00;

    public static final boolean IS_MACOS =
            System.getProperty("os.name", "").toLowerCase().contains("mac");

    /** {@code THREAD_TIME_CONSTRAINT_POLICY} from {@code <mach/thread_policy.h>}. */
    private static final int THREAD_TIME_CONSTRAINT_POLICY = 2;
    /** {@code integer_t} count of {@code thread_time_constraint_policy_data_t}. */
    private static final int TIME_CONSTRAINT_COUNT = 4;

    private interface LibSystem extends Library {
        int pthread_set_qos_class_self_np(int qosClass, int relativePriority);

        int qos_class_self();

        int sysctlbyname(String name, Pointer oldp, LongByReference oldlenp, Pointer newp, long newlen);

        /** Mach port for the calling thread. Each call takes a reference that must be released. */
        int mach_thread_self();

        int mach_task_self();

        int mach_port_deallocate(int task, int port);

        /** Fills {@code {numer, denom}}; mach absolute units convert to ns by numer/denom. */
        int mach_timebase_info(int[] info);

        int thread_policy_set(int thread, int flavor, int[] policyInfo, int count);
    }

    private static final LibSystem LIB;
    /** Non-null when the bindings failed to load; surfaced in the tuning report. */
    public static final String LOAD_ERROR;

    static {
        LibSystem lib = null;
        String err = null;
        if (IS_MACOS) {
            try {
                lib = Native.load("System", LibSystem.class);
                lib.qos_class_self(); // fail fast here rather than at a call site
            } catch (Throwable t) {
                lib = null;
                err = t.toString();
            }
        } else {
            err = "not macOS";
        }
        LIB = lib;
        LOAD_ERROR = err;
    }

    public static boolean available() {
        return LIB != null;
    }

    /**
     * Sets the QoS class of the <em>calling</em> thread. The JVM starts every thread at
     * QOS_CLASS_DEFAULT and does not propagate the creating thread's class, so this has to run on
     * each thread that matters — see {@link Qos#promoteExecutor}.
     */
    public static boolean setSelfQos(int qosClass, int relativePriority) {
        if (LIB == null) return false;
        try {
            return LIB.pthread_set_qos_class_self_np(qosClass, relativePriority) == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** QoS class of the calling thread, or {@link #QOS_UNSPECIFIED} if unavailable. */
    public static int selfQos() {
        if (LIB == null) return QOS_UNSPECIFIED;
        try {
            return LIB.qos_class_self();
        } catch (Throwable t) {
            return QOS_UNSPECIFIED;
        }
    }

    public static String qosName(int qos) {
        return switch (qos) {
            case QOS_USER_INTERACTIVE -> "USER_INTERACTIVE";
            case QOS_USER_INITIATED -> "USER_INITIATED";
            case QOS_DEFAULT -> "DEFAULT";
            case QOS_UTILITY -> "UTILITY";
            case QOS_BACKGROUND -> "BACKGROUND";
            case QOS_UNSPECIFIED -> "UNSPECIFIED";
            default -> "0x" + Integer.toHexString(qos);
        };
    }

    public static int qosByName(String name, int fallback) {
        if (name == null) return fallback;
        return switch (name.trim().toUpperCase()) {
            case "USER_INTERACTIVE" -> QOS_USER_INTERACTIVE;
            case "USER_INITIATED" -> QOS_USER_INITIATED;
            case "DEFAULT" -> QOS_DEFAULT;
            case "UTILITY" -> QOS_UTILITY;
            case "BACKGROUND" -> QOS_BACKGROUND;
            default -> fallback;
        };
    }

    /** Mach timebase {numer, denom}, cached; {@code null} if unavailable. */
    private static volatile int[] timebase;

    /**
     * Converts nanoseconds to the mach absolute time units {@code thread_policy_set} expects.
     *
     * <p>Clamped to {@link Integer#MAX_VALUE} because the policy struct fields are 32-bit — a
     * silently truncated period would ask the kernel for a deadline nothing like the one intended.
     */
    private static int nanosToAbs(long nanos) {
        if (nanos <= 0) return 0;
        int[] tb = timebase;
        if (tb == null) {
            int[] out = new int[2];
            try {
                if (LIB.mach_timebase_info(out) != 0 || out[0] <= 0 || out[1] <= 0) return -1;
            } catch (Throwable t) {
                return -1;
            }
            timebase = tb = out;
        }
        // ns = abs * numer / denom, so abs = ns * denom / numer. Callers pass microsecond-scale
        // values clamped by Config, so this cannot overflow a long.
        return (int) Math.min(nanos * tb[1] / tb[0], Integer.MAX_VALUE);
    }

    /**
     * Puts the <em>calling</em> thread on the Mach real-time scheduler: a reservation of
     * {@code computationNs} of CPU within every {@code constraintNs} deadline, repeating every
     * {@code periodNs} (0 for non-periodic work).
     *
     * <p>This supersedes QoS for the thread — a time-constraint thread is scheduled by its deadline
     * rather than by class. Always requests preemptible; a non-preemptible thread that overruns its
     * budget can wedge the whole machine, and no frame is worth that.
     */
    public static boolean setSelfRealtime(long periodNs, long computationNs, long constraintNs) {
        if (LIB == null) return false;
        int period = nanosToAbs(periodNs);
        int computation = nanosToAbs(computationNs);
        int constraint = nanosToAbs(constraintNs);
        if (period < 0 || computation <= 0 || constraint <= 0) return false;

        int thread = 0;
        try {
            thread = LIB.mach_thread_self();
            if (thread == 0) return false;
            int[] policy = {period, computation, constraint, 1 /* preemptible */};
            return LIB.thread_policy_set(thread, THREAD_TIME_CONSTRAINT_POLICY, policy,
                    TIME_CONSTRAINT_COUNT) == 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (thread != 0) {
                try {
                    LIB.mach_port_deallocate(LIB.mach_task_self(), thread);
                } catch (Throwable ignored) {
                    // Leaks one port right on an already-failing path; nothing useful to do.
                }
            }
        }
    }

    /** {@code sysctlbyname} for an integer-valued key. */
    public static long sysctlLong(String name, long fallback) {
        if (LIB == null) return fallback;
        try (Memory out = new Memory(8)) {
            out.clear();
            LongByReference len = new LongByReference(8);
            if (LIB.sysctlbyname(name, out, len, null, 0) != 0) return fallback;
            return switch ((int) len.getValue()) {
                case 4 -> Integer.toUnsignedLong(out.getInt(0));
                case 8 -> out.getLong(0);
                default -> fallback;
            };
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** {@code sysctlbyname} for a string-valued key. */
    public static String sysctlString(String name, String fallback) {
        if (LIB == null) return fallback;
        try {
            LongByReference len = new LongByReference(0);
            if (LIB.sysctlbyname(name, null, len, null, 0) != 0) return fallback;
            long size = len.getValue();
            if (size <= 0 || size > 4096) return fallback;
            try (Memory out = new Memory(size)) {
                out.clear();
                if (LIB.sysctlbyname(name, out, len, null, 0) != 0) return fallback;
                return out.getString(0);
            }
        } catch (Throwable t) {
            return fallback;
        }
    }

    private Darwin() {
    }
}
