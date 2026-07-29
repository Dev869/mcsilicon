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

    @Override
    public void onPreLaunch() {
        if (!Darwin.IS_MACOS) return;

        Config cfg = Config.get();
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
    }
}
