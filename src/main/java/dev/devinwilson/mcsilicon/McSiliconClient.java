package dev.devinwilson.mcsilicon;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.util.Util;

public final class McSiliconClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Config cfg = Config.get();
        Machine machine = Machine.probe();

        if (cfg.qosEnabled && cfg.promoteWorkerPools && Darwin.available()) {
            promotePools(cfg);
        }
        if (cfg.writeTuningReport) {
            TuningReport.write(machine, cfg);
        }
        warnAboutObviousLosses(machine);
    }

    /**
     * Chunk meshing and world gen run on the background pool. Its threads start at
     * QOS_CLASS_DEFAULT, which macOS is free to park on the slowest core tier under load.
     *
     * <p>The IO pool is deliberately left alone: it is an unbounded cached pool whose threads are
     * blocked on disk rather than CPU-bound, and it reaps them after a minute — so promoting them
     * buys nothing and the replacements would start at DEFAULT anyway.
     */
    private static void promotePools(Config cfg) {
        var exec = Util.backgroundExecutor().service();
        int width = Qos.widthOf(exec);
        int done = Qos.promoteExecutor(exec, cfg.workerQos, width, 2000L);
        McSilicon.LOG.info("[mcsilicon] background pool: {}/{} threads -> {}",
                done, width, Darwin.qosName(cfg.workerQos));
    }

    /** The two things that cost far more performance than any mod can win back. */
    private static void warnAboutObviousLosses(Machine machine) {
        if (machine.translated()) {
            McSilicon.LOG.error("[mcsilicon] Running under Rosetta 2 (x86_64 translation). "
                    + "Use an arm64 Java runtime — this costs more performance than everything "
                    + "mcsilicon does put together.");
        }
        if (machine.appleSilicon() && !"aarch64".equals(System.getProperty("os.arch"))) {
            McSilicon.LOG.error("[mcsilicon] Apple Silicon CPU but os.arch={} — the launcher picked "
                    + "an x86_64 JVM.", System.getProperty("os.arch"));
        }
    }
}
