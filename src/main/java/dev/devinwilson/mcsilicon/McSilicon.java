package dev.devinwilson.mcsilicon;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class McSilicon implements ModInitializer {

    public static final String ID = "mcsilicon";
    public static final Logger LOG = LoggerFactory.getLogger(ID);

    @Override
    public void onInitialize() {
        // Fires on the server thread, which owns world ticking. On an integrated server that
        // thread is created long after pre-launch, so it needs its own promotion.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            Config cfg = Config.get();
            if (!cfg.qosEnabled || !Darwin.available()) return;
            int got = Qos.promoteSelf(cfg.serverQos);
            LOG.info("[mcsilicon] server thread '{}' -> {}",
                    Thread.currentThread().getName(), Darwin.qosName(got));
        });
    }
}
