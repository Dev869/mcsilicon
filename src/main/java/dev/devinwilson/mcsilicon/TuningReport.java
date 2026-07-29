package dev.devinwilson.mcsilicon;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes config/mcsilicon-tuning.txt: what this machine is, what the mod did, and the launcher
 * settings the mod cannot change from inside the JVM.
 *
 * <p>The JVM flags matter more than anything in this mod's own code — a bad heap size or GC choice
 * costs more frametime than QoS placement wins back — but they have to be set before the JVM
 * starts, so all we can do is compute and print them.
 */
public final class TuningReport {

    public static void write(Machine m, Config cfg) {
        Path out = FabricLoader.getInstance().getConfigDir().resolve("mcsilicon-tuning.txt");
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, build(m, cfg));
            McSilicon.LOG.info("[mcsilicon] {} ({}), {} GB RAM — tuning notes: {}",
                    m.cpu(), m.describeTiers(), m.memoryGigabytes(), out);
        } catch (IOException e) {
            McSilicon.LOG.warn("[mcsilicon] could not write {}", out, e);
        }
    }

    private static String build(Machine m, Config cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("mcsilicon tuning notes\n");
        sb.append("======================\n\n");

        sb.append("MACHINE\n");
        sb.append("  cpu            ").append(m.cpu()).append('\n');
        sb.append("  core tiers     ").append(m.describeTiers())
                .append("   (tier 0 is the fastest)\n");
        sb.append("  memory         ").append(m.memoryGigabytes()).append(" GB unified\n");
        sb.append("  os.arch        ").append(System.getProperty("os.arch")).append('\n');
        sb.append("  rosetta        ").append(m.translated() ? "YES - see below" : "no").append('\n');
        sb.append("  java           ").append(System.getProperty("java.version"))
                .append(" (").append(System.getProperty("java.vendor")).append(")\n\n");

        sb.append("WHAT MCSILICON DID\n");
        if (!Darwin.available()) {
            sb.append("  nothing - libSystem bindings unavailable: ").append(Darwin.LOAD_ERROR).append('\n');
        } else if (!cfg.qosEnabled) {
            sb.append("  nothing - qos.enabled=false\n");
        } else {
            sb.append("  render thread  -> ").append(Darwin.qosName(SiliconPreLaunch.renderThreadQos)).append('\n');
            sb.append("  server thread  -> ").append(Darwin.qosName(cfg.serverQos)).append('\n');
            sb.append("  worker pools   -> ")
                    .append(cfg.promoteWorkerPools ? Darwin.qosName(cfg.workerQos) : "left alone")
                    .append('\n');
        }
        sb.append("\n  macOS schedules by QoS class, not Java thread priority. UTILITY and\n");
        sb.append("  BACKGROUND are confined to the slowest core tier; the JVM starts every\n");
        sb.append("  thread at DEFAULT and does not inherit, so each one is raised explicitly.\n");
        if (!m.hasEfficiencyTier()) {
            sb.append("\n  Expect this to do very little on THIS machine. QoS promotion pays off by\n");
            sb.append("  keeping work off the efficiency cores, and this chip does not have any -\n");
            sb.append("  every tier is a fast one, so DEFAULT was already landing on good cores.\n");
            sb.append("  Measured with bench.sh here, the difference was inside run-to-run noise.\n");
            sb.append("  It matters on an M1-M4, where 4 of 8-10 cores are efficiency cores.\n");
        }
        sb.append('\n');

        int heap = m.suggestedHeapGb();
        int bg = Math.max(2, m.preferredCores() - 1);
        sb.append("JVM ARGUMENTS (set these in your launcher - the mod cannot)\n\n");
        sb.append("  ").append(String.join(" ", jvmArgs(heap, bg))).append("\n\n");
        sb.append("  -Xmx/-Xms ").append(heap).append("G   Equal min and max avoids heap resizing pauses.\n");
        sb.append("                 Bigger is not better: a larger heap means longer GC work,\n");
        sb.append("                 and Minecraft does not use ").append(m.memoryGigabytes()).append(" GB.\n");
        sb.append("  UseZGC         Generational ZGC keeps pauses under a millisecond, which is\n");
        sb.append("                 what frametime consistency depends on. G1 is the alternative\n");
        sb.append("                 if you see higher average FPS but choppier frame pacing.\n");
        sb.append("  AlwaysPreTouch Faults the heap in at startup instead of mid-game.\n");
        sb.append("  max.bg.threads Caps Minecraft's worker pools at ").append(bg).append(", ");
        if (m.hasEfficiencyTier()) {
            sb.append("the fast core count,\n");
            sb.append("                 so background work is not spread onto the efficiency tier.\n\n");
        } else {
            sb.append("one less than the core count.\n");
            sb.append("                 This chip has no efficiency tier, so the cap only exists to\n");
            sb.append("                 leave a core free for the render thread.\n\n");
        }

        sb.append("CURRENT JVM FLAGS\n");
        List<String> actual = ManagementFactory.getRuntimeMXBean().getInputArguments();
        if (actual.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (String a : actual) sb.append("  ").append(a).append('\n');
        }
        sb.append('\n');

        sb.append("THINGS WORTH MORE THAN THIS MOD\n");
        if (m.translated()) {
            sb.append("  * You are running under Rosetta 2. Switch to an arm64 Java runtime.\n");
            sb.append("    Nothing else on this list comes close to that loss.\n");
        }
        sb.append("  * Sodium - rewrites the chunk renderer. Largest single FPS gain available.\n");
        sb.append("  * Lithium - server-side tick optimisations, no behaviour changes.\n");
        sb.append("  * FerriteCore and ModernFix - cut memory use and load time.\n");
        sb.append("  mcsilicon deliberately does not duplicate any of these; it only does the\n");
        sb.append("  macOS-specific work none of them do.\n\n");

        return sb.toString();
    }

    private static List<String> jvmArgs(int heapGb, int bgThreads) {
        return List.of(
                "-Xmx" + heapGb + "G",
                "-Xms" + heapGb + "G",
                "-XX:+UseZGC",
                "-XX:+AlwaysPreTouch",
                "-Dmax.bg.threads=" + bgThreads);
    }

    private TuningReport() {
    }
}
