package dev.devinwilson.mcsilicon;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Runs before any game class loads, on the process main thread.
 *
 * <p>On macOS the main thread <em>is</em> Minecraft's render thread — GLFW requires window and
 * event handling to happen there — so promoting it here promotes the single most latency-critical
 * thread in the game, and does so before the window exists.
 */
public final class SiliconPreLaunch implements PreLaunchEntrypoint {

    /** Set once here so the client entrypoint can report it without re-querying. */
    public static volatile int renderThreadQos = Darwin.QOS_UNSPECIFIED;
    /** Whether the render thread ended up on the Mach real-time scheduler. */
    public static volatile boolean renderThreadRealtime;

    @Override
    public void onPreLaunch() {
        if (!Darwin.IS_MACOS) return;

        Config cfg = Config.get();

        // Independent of qos.enabled: this is a process-wide power hint, not thread scheduling, and
        // someone turning QoS promotion off still wants their wakeups on time.
        if (cfg.latencyCritical) {
            if (Activity.beginLatencyCritical()) {
                McSilicon.LOG.info("[mcsilicon] App Nap and timer coalescing disabled for this process");
            } else {
                McSilicon.LOG.warn("[mcsilicon] could not go latency-critical: {}", Activity.error);
            }
        }

        if (!cfg.qosEnabled) {
            McSilicon.LOG.info("[mcsilicon] QoS promotion disabled in config");
            return;
        }
        if (!Darwin.available()) {
            McSilicon.LOG.warn("[mcsilicon] libSystem unavailable ({}), QoS promotion skipped",
                    Darwin.LOAD_ERROR);
            return;
        }

        int before = Darwin.selfQos();
        renderThreadQos = Qos.promoteSelf(cfg.renderQos);
        McSilicon.LOG.info("[mcsilicon] render/main thread {} -> {}",
                Darwin.qosName(before), Darwin.qosName(renderThreadQos));

        // After the QoS call, not before: time-constraint policy supersedes the class, so setting
        // it second leaves the stronger of the two in effect. If it fails, QoS still stands.
        if (cfg.realtimeEnabled) {
            renderThreadRealtime = Darwin.setSelfRealtime(
                    cfg.realtimePeriodUs * 1_000L,
                    cfg.realtimeComputationUs * 1_000L,
                    cfg.realtimeConstraintUs * 1_000L);
            if (renderThreadRealtime) {
                McSilicon.LOG.info("[mcsilicon] render/main thread -> real-time "
                                + "(period {}us, computation {}us, constraint {}us)",
                        cfg.realtimePeriodUs, cfg.realtimeComputationUs, cfg.realtimeConstraintUs);
            } else {
                McSilicon.LOG.warn("[mcsilicon] real-time policy rejected, staying on QoS {}",
                        Darwin.qosName(renderThreadQos));
            }
        }
    }
}
