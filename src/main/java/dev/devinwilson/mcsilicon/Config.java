package dev.devinwilson.mcsilicon;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** config/mcsilicon.properties. Plain java.util.Properties — no config library, no codec. */
public final class Config {

    public boolean qosEnabled = true;
    public int renderQos = Darwin.QOS_USER_INTERACTIVE;
    public int serverQos = Darwin.QOS_USER_INTERACTIVE;
    public boolean promoteWorkerPools = true;
    public int workerQos = Darwin.QOS_USER_INITIATED;

    /**
     * Mach real-time scheduling for the render thread. Off by default: it supersedes QoS, and it
     * has not yet been shown to beat it on any machine. Flip it on, run bench.sh, keep whichever
     * won on your hardware.
     */
    public boolean realtimeEnabled = false;
    /** 0 = non-periodic: budget applies per wakeup rather than on a fixed cadence. */
    public int realtimePeriodUs = 0;
    public int realtimeComputationUs = 3000;
    public int realtimeConstraintUs = 6000;

    public boolean writeTuningReport = true;

    /** Frametime benchmark. Driven by bench.sh via -Dmcsilicon.* overrides. */
    public boolean benchEnabled = false;
    public int benchWarmupSeconds = 10;
    public int benchDurationSeconds = 30;
    public boolean benchExitWhenDone = false;
    public String benchLabel = "default";

    private static Config instance;

    public static Config get() {
        if (instance == null) instance = load();
        return instance;
    }

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("mcsilicon.properties");
    }

    private static Config load() {
        Config c = new Config();
        Path p = path();
        Properties props = new Properties();
        if (Files.isRegularFile(p)) {
            try (Reader r = Files.newBufferedReader(p)) {
                props.load(r);
            } catch (IOException e) {
                McSilicon.LOG.warn("[mcsilicon] could not read {}, using defaults", p, e);
            }
        }

        c.qosEnabled = bool(props, "qos.enabled", c.qosEnabled);
        c.renderQos = Darwin.qosByName(str(props, "qos.render", "USER_INTERACTIVE"), c.renderQos);
        c.serverQos = Darwin.qosByName(str(props, "qos.server", "USER_INTERACTIVE"), c.serverQos);
        c.promoteWorkerPools = bool(props, "qos.promoteWorkerPools", c.promoteWorkerPools);
        c.workerQos = Darwin.qosByName(str(props, "qos.worker", "USER_INITIATED"), c.workerQos);

        c.realtimeEnabled = bool(props, "realtime.enabled", c.realtimeEnabled);
        c.realtimePeriodUs = integer(props, "realtime.periodUs", c.realtimePeriodUs);
        c.realtimeComputationUs = integer(props, "realtime.computationUs", c.realtimeComputationUs);
        c.realtimeConstraintUs = integer(props, "realtime.constraintUs", c.realtimeConstraintUs);
        c.clampRealtime();

        c.writeTuningReport = bool(props, "diagnostics.writeTuningReport", c.writeTuningReport);

        c.benchEnabled = bool(props, "bench.enabled", c.benchEnabled);
        c.benchWarmupSeconds = integer(props, "bench.warmupSeconds", c.benchWarmupSeconds);
        c.benchDurationSeconds = integer(props, "bench.durationSeconds", c.benchDurationSeconds);
        c.benchExitWhenDone = bool(props, "bench.exitWhenDone", c.benchExitWhenDone);
        c.benchLabel = str(props, "bench.label", c.benchLabel);

        // Saving now would bake the overrides into the user's file and make the next launch
        // inherit a benchmark's settings. Skip the rewrite whenever any override is in play.
        if (!overridden) c.save();
        return c;
    }

    private static boolean overridden;

    /**
     * A {@code -Dmcsilicon.<key>} system property beats the file, so a benchmark script can flip
     * one setting per run without touching config the user owns.
     */
    private static String str(Properties p, String key, String fallback) {
        String override = System.getProperty("mcsilicon." + key);
        if (override != null) {
            overridden = true;
            return override;
        }
        String v = p.getProperty(key);
        return v == null ? fallback : v;
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        String v = str(p, key, null);
        return v == null ? fallback : Boolean.parseBoolean(v.trim());
    }

    private static int integer(Properties p, String key, int fallback) {
        String v = str(p, key, null);
        if (v == null) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Longest computation budget accepted, in microseconds. */
    private static final int MAX_COMPUTATION_US = 20_000;

    /**
     * Forces the real-time triple into a shape the kernel will accept and the machine will
     * survive. A computation budget that is large, or nearly equal to its deadline, reserves so
     * much of a core that the rest of the system starves — including the threads feeding this one.
     */
    private void clampRealtime() {
        realtimePeriodUs = Math.max(0, realtimePeriodUs);
        realtimeComputationUs = Math.max(100, Math.min(realtimeComputationUs, MAX_COMPUTATION_US));
        // The deadline has to leave room for the budget, and a period (when set) has to leave room
        // for the deadline, or the thread is late by construction.
        realtimeConstraintUs = Math.max(realtimeConstraintUs, realtimeComputationUs);
        if (realtimePeriodUs > 0) {
            realtimeConstraintUs = Math.min(realtimeConstraintUs, realtimePeriodUs);
            realtimeComputationUs = Math.min(realtimeComputationUs, realtimeConstraintUs);
        }
    }

    /** Rewrites the file so new keys show up with their documented defaults after an update. */
    public void save() {
        Path p = path();
        try {
            Files.createDirectories(p.getParent());
            try (Writer w = Files.newBufferedWriter(p)) {
                w.write("""
                        # mcsilicon
                        # QoS classes, fastest to slowest:
                        #   USER_INTERACTIVE, USER_INITIATED, DEFAULT, UTILITY, BACKGROUND
                        # On Apple Silicon, UTILITY and BACKGROUND are confined to the slowest core
                        # tier, so never set the render thread below DEFAULT.

                        """);
                w.write("qos.enabled=" + qosEnabled + "\n");
                w.write("qos.render=" + Darwin.qosName(renderQos) + "\n");
                w.write("qos.server=" + Darwin.qosName(serverQos) + "\n");
                w.write("qos.promoteWorkerPools=" + promoteWorkerPools + "\n");
                w.write("qos.worker=" + Darwin.qosName(workerQos) + "\n");
                w.write("""

                        # Mach real-time scheduling for the render thread. QoS asks for a fast core;
                        # this asks for a deadline — computationUs of CPU guaranteed within every
                        # constraintUs. It replaces QoS on that thread rather than adding to it.
                        #
                        # Off by default because it is not free: the budget is reserved whether the
                        # frame needs it or not, and too large a one starves the threads feeding it.
                        # Measure with bench.sh before trusting it. periodUs=0 means non-periodic.
                        """);
                w.write("realtime.enabled=" + realtimeEnabled + "\n");
                w.write("realtime.periodUs=" + realtimePeriodUs + "\n");
                w.write("realtime.computationUs=" + realtimeComputationUs + "\n");
                w.write("realtime.constraintUs=" + realtimeConstraintUs + "\n");
                w.write("\ndiagnostics.writeTuningReport=" + writeTuningReport + "\n");
                w.write("""

                        # Frametime benchmark. Records frame-to-frame intervals once a world is
                        # loaded and writes percentiles to config/mcsilicon-bench-<label>.txt plus
                        # a row in config/mcsilicon-bench.tsv. Driven by bench.sh, which overrides
                        # these with -Dmcsilicon.bench.* so this file is left alone.
                        """);
                w.write("bench.enabled=" + benchEnabled + "\n");
                w.write("bench.warmupSeconds=" + benchWarmupSeconds + "\n");
                w.write("bench.durationSeconds=" + benchDurationSeconds + "\n");
                w.write("bench.exitWhenDone=" + benchExitWhenDone + "\n");
                w.write("bench.label=" + benchLabel + "\n");
            }
        } catch (IOException e) {
            McSilicon.LOG.warn("[mcsilicon] could not write {}", p, e);
        }
    }

    private Config() {
    }
}
