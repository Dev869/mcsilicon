package dev.devinwilson.mcsilicon.bench;

import dev.devinwilson.mcsilicon.Config;
import dev.devinwilson.mcsilicon.Darwin;
import dev.devinwilson.mcsilicon.McSilicon;
import dev.devinwilson.mcsilicon.Machine;
import dev.devinwilson.mcsilicon.SiliconPreLaunch;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;

/**
 * Records frame-to-frame intervals and reports the distribution.
 *
 * <p>Percentiles of frametime, not average FPS: average FPS hides exactly the stutter that
 * scheduling changes are supposed to affect. A run that gains 4 average FPS but doubles its 99th
 * percentile frametime got worse, and only the percentiles say so.
 *
 * <p>Only runs while a level is loaded, and drops everything recorded so far if the player leaves,
 * so menu frames never enter the sample.
 */
public final class FrameBench {

    private enum State { WAITING, WARMUP, RECORDING, DONE }

    private static final int MAX_SAMPLES = 2_000_000;

    private static State state = State.WAITING;
    private static long lastFrameNs;
    private static long phaseStartNs;
    private static long[] samples;
    private static int count;
    /** Why the frame rate is externally limited, or null if it is not. */
    private static String capped;
    private static int unfocusedFrames;
    /** Framebuffer size at the start of recording. */
    private static int fbWidth, fbHeight, logicalWidth, logicalHeight;

    /**
     * The retina condition renders a quarter of the pixels, so its frametimes are not comparable
     * to the others without knowing that. Recording the framebuffer size keeps the comparison
     * honest instead of letting a resolution drop read as a free speedup.
     */
    private static void captureFramebuffer() {
        try {
            var w = Minecraft.getInstance().getWindow();
            fbWidth = w.getWidth();
            fbHeight = w.getHeight();
            logicalWidth = w.getScreenWidth();
            logicalHeight = w.getScreenHeight();
        } catch (Throwable t) {
            fbWidth = fbHeight = logicalWidth = logicalHeight = 0;
        }
    }

    /** Everything that makes this run's numbers not mean what they look like. */
    private static String invalidReason() {
        if (capped != null) return capped;
        if (unfocusedFrames > 0) {
            return "window unfocused for " + unfocusedFrames + " frames";
        }
        return null;
    }

    /**
     * VSync or a framerate limit pins every frame to the same interval, which flattens the
     * distribution and hides exactly the difference an A/B is looking for. A run under either is
     * measuring the display, not the scheduler, so it gets labelled invalid rather than reported
     * as if it meant something.
     */
    private static String frameCap() {
        try {
            var options = Minecraft.getInstance().options;
            if (options.enableVsync().get()) return "vsync is on";
            int limit = options.framerateLimit().get();
            // 260 is Minecraft's "unlimited" sentinel.
            if (limit < 260) return "framerate limit is " + limit;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Called at the top of every frame. Must stay cheap — this is on the hot path. */
    public static void onFrame() {
        Config cfg = Config.get();
        if (!cfg.benchEnabled || state == State.DONE) return;

        long now = System.nanoTime();
        long previous = lastFrameNs;
        lastFrameNs = now;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null) {
            // Not in a world (or just left one) — discard and wait for a clean start.
            if (state != State.WAITING) {
                McSilicon.LOG.info("[mcsilicon] bench reset — left the world");
                state = State.WAITING;
                count = 0;
            }
            return;
        }

        if (state == State.WAITING) {
            state = State.WARMUP;
            phaseStartNs = now;
            capped = frameCap();
            unfocusedFrames = 0;
            samples = new long[Math.min(MAX_SAMPLES, Math.max(1024, cfg.benchDurationSeconds * 2000))];
            count = 0;
            McSilicon.LOG.info("[mcsilicon] bench warmup: {}s, then recording {}s",
                    cfg.benchWarmupSeconds, cfg.benchDurationSeconds);
            if (capped != null) {
                McSilicon.LOG.error("[mcsilicon] bench INVALID: {} — every frame is pinned to the "
                        + "display interval, so this measures the cap and not the game. Turn off "
                        + "VSync and set the framerate limit to unlimited.", capped);
            }
            return;
        }

        if (previous == 0L) return;
        long delta = now - previous;

        switch (state) {
            case WARMUP -> {
                if (now - phaseStartNs >= cfg.benchWarmupSeconds * 1_000_000_000L) {
                    state = State.RECORDING;
                    phaseStartNs = now;
                    captureFramebuffer();
                    McSilicon.LOG.info("[mcsilicon] bench recording: framebuffer {}x{}, window {}x{}, scale {}",
                            fbWidth, fbHeight, logicalWidth, logicalHeight,
                            logicalWidth > 0 ? (float) fbWidth / logicalWidth : 0f);
                }
            }
            case RECORDING -> {
                // An unfocused or occluded window makes Minecraft skip rendering entirely, which
                // produces thousand-FPS "frames" that are not gameplay and wreck the sample. One
                // lost frame of focus invalidates the run rather than quietly skewing it.
                if (unfocusedFrames == 0 && !mc.isWindowActive()) {
                    McSilicon.LOG.error("[mcsilicon] bench INVALID: window lost focus while "
                            + "recording — Minecraft stops rendering and the frametimes become "
                            + "meaningless. Leave the game window frontmost for the whole run.");
                }
                if (!mc.isWindowActive()) unfocusedFrames++;
                if (count < samples.length) samples[count++] = delta;
                if (now - phaseStartNs >= cfg.benchDurationSeconds * 1_000_000_000L
                        || count == samples.length) {
                    state = State.DONE;
                    finish(cfg, now - phaseStartNs);
                }
            }
            default -> {
            }
        }
    }

    private static void finish(Config cfg, long elapsedNs) {
        if (count < 2) {
            McSilicon.LOG.warn("[mcsilicon] bench collected {} frames, nothing to report", count);
            return;
        }
        Stats s = Stats.of(Arrays.copyOf(samples, count), elapsedNs);
        samples = null;

        McSilicon.LOG.info("[mcsilicon] bench '{}': {} frames, mean {} fps, p50 {} ms, p99 {} ms",
                cfg.benchLabel, s.frames, fmt(s.meanFps), fmt(s.p50Ms), fmt(s.p99Ms));

        writeReport(cfg, s);
        appendRow(cfg, s);

        if (cfg.benchExitWhenDone) {
            McSilicon.LOG.info("[mcsilicon] bench done, stopping the game");
            Minecraft.getInstance().stop();
        }
    }

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    private static void writeReport(Config cfg, Stats s) {
        Machine m = Machine.probe();
        Path out = dir().resolve("mcsilicon-bench-" + safe(cfg.benchLabel) + ".txt");
        StringBuilder sb = new StringBuilder();
        sb.append("mcsilicon frametime benchmark\n");
        sb.append("============================\n\n");
        sb.append("run label        ").append(cfg.benchLabel).append('\n');
        sb.append("machine          ").append(m.cpu()).append("  (").append(m.describeTiers()).append(")\n\n");
        String invalid = invalidReason();
        if (invalid != null) {
            sb.append("*** INVALID: ").append(invalid).append(" ***\n");
            if (capped != null) {
                sb.append("Every frame was pinned to the display interval, so these numbers\n");
                sb.append("describe the cap and not the game. Turn VSync off and set the framerate\n");
                sb.append("limit to unlimited, then run again.\n\n");
            } else {
                sb.append("Minecraft skips rendering while its window is not frontmost, so those\n");
                sb.append("frames are not gameplay and drag the whole distribution with them.\n");
                sb.append("Leave the game window focused for the entire run.\n\n");
            }
        }
        sb.append("CONDITIONS\n");
        sb.append("  qos.enabled            ").append(cfg.qosEnabled).append('\n');
        sb.append("  qos.render             ").append(cfg.qosEnabled
                ? Darwin.qosName(cfg.renderQos) : "not promoted").append('\n');
        sb.append("  qos.worker             ").append(workerState(cfg)).append('\n');
        sb.append("  framebuffer            ").append(fbWidth).append('x').append(fbHeight)
                .append("  (").append(fbWidth * (long) fbHeight / 1000).append("k pixels per frame)\n");
        sb.append("  render distance        ").append(renderDistance()).append('\n');
        sb.append("  measured               ").append(fmt(s.seconds)).append(" s, ")
                .append(s.frames).append(" frames\n\n");
        sb.append("FRAMETIME (milliseconds — lower is better)\n");
        sb.append("  min              ").append(fmt(s.minMs)).append('\n');
        sb.append("  p50 (median)     ").append(fmt(s.p50Ms)).append('\n');
        sb.append("  p95              ").append(fmt(s.p95Ms)).append('\n');
        sb.append("  p99              ").append(fmt(s.p99Ms)).append('\n');
        sb.append("  p99.9            ").append(fmt(s.p999Ms)).append('\n');
        sb.append("  max              ").append(fmt(s.maxMs)).append('\n');
        sb.append("  mean             ").append(fmt(s.meanMs)).append('\n');
        sb.append("  stutters         ").append(s.stutters)
                .append("  (frames over 2x the median)\n\n");
        sb.append("THROUGHPUT\n");
        sb.append("  mean fps         ").append(fmt(s.meanFps)).append('\n');
        sb.append("  1% low fps       ").append(fmt(s.onePercentLowFps))
                .append("  (1000 / p99 frametime)\n\n");
        sb.append("Compare two runs with bench.sh. Judge on p99 and 1% low, not mean fps —\n");
        sb.append("scheduling changes show up as stutter before they show up as throughput.\n");
        try {
            Files.writeString(out, sb.toString());
            McSilicon.LOG.info("[mcsilicon] bench report: {}", out);
        } catch (IOException e) {
            McSilicon.LOG.warn("[mcsilicon] could not write {}", out, e);
        }
    }

    /** One tab-separated row per run, so bench.sh can diff without parsing prose. */
    private static void appendRow(Config cfg, Stats s) {
        Path tsv = dir().resolve("mcsilicon-bench.tsv");
        String header = "label\tvalid\tqos\trt\tworkers\tframebuffer\tscale\tframes\tmeanFps"
                + "\tlow1pct\tp50\tp95\tp99\tp999\tmax\tstutters\n";
        String row = String.join("\t",
                cfg.benchLabel,
                invalidReason() == null ? "yes" : "NO:" + invalidReason().replace(' ', '_'),
                String.valueOf(cfg.qosEnabled),
                // What the kernel actually granted, not what the config asked for.
                SiliconPreLaunch.renderThreadRealtime ? "yes" : "no",
                workerState(cfg),
                fbWidth + "x" + fbHeight,
                logicalWidth > 0 ? fmt((double) fbWidth / logicalWidth) : "?",
                String.valueOf(s.frames),
                fmt(s.meanFps), fmt(s.onePercentLowFps),
                fmt(s.p50Ms), fmt(s.p95Ms), fmt(s.p99Ms), fmt(s.p999Ms), fmt(s.maxMs),
                String.valueOf(s.stutters)) + "\n";
        try {
            if (!Files.exists(tsv)) Files.writeString(tsv, header);
            Files.writeString(tsv, row, StandardOpenOption.APPEND);
        } catch (IOException e) {
            McSilicon.LOG.warn("[mcsilicon] could not append {}", tsv, e);
        }
    }

    /**
     * promoteWorkerPools stays true in config even when qos.enabled turns everything off, so
     * reporting it on its own would credit a run with a promotion that never happened.
     */
    private static String workerState(Config cfg) {
        return cfg.qosEnabled && cfg.promoteWorkerPools ? Darwin.qosName(cfg.workerQos) : "none";
    }

    private static String renderDistance() {
        try {
            return String.valueOf(Minecraft.getInstance().options.renderDistance().get());
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** Frametime distribution. All millisecond fields; {@code samples} is consumed and sorted. */
    private record Stats(int frames, double seconds, double minMs, double p50Ms, double p95Ms,
                         double p99Ms, double p999Ms, double maxMs, double meanMs,
                         double meanFps, double onePercentLowFps, int stutters) {

        static Stats of(long[] raw, long elapsedNs) {
            long total = 0;
            for (long v : raw) total += v;
            long[] sorted = raw.clone();
            Arrays.sort(sorted);

            double p50 = ms(pick(sorted, 0.50));
            int stutters = 0;
            double threshold = pick(sorted, 0.50) * 2.0;
            for (long v : raw) if (v > threshold) stutters++;

            double seconds = elapsedNs / 1e9;
            double p99 = ms(pick(sorted, 0.99));
            return new Stats(
                    raw.length,
                    seconds,
                    ms(sorted[0]),
                    p50,
                    ms(pick(sorted, 0.95)),
                    p99,
                    ms(pick(sorted, 0.999)),
                    ms(sorted[sorted.length - 1]),
                    ms((double) total / raw.length),
                    raw.length / seconds,
                    p99 > 0 ? 1000.0 / p99 : 0,
                    stutters);
        }

        private static long pick(long[] sorted, double q) {
            int i = (int) Math.ceil(q * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
        }

        private static double ms(double ns) {
            return ns / 1e6;
        }
    }

    private FrameBench() {
    }
}
