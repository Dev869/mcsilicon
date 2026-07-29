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

    private interface LibSystem extends Library {
        int pthread_set_qos_class_self_np(int qosClass, int relativePriority);

        int qos_class_self();

        int sysctlbyname(String name, Pointer oldp, LongByReference oldlenp, Pointer newp, long newlen);
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
