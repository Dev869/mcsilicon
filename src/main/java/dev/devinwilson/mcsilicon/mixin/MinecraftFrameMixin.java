package dev.devinwilson.mcsilicon.mixin;

import dev.devinwilson.mcsilicon.bench.FrameBench;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame boundary for the benchmark. {@code Minecraft.runTick} is called once per frame from
 * {@code run()}, despite the name, so the interval between successive entries is the frametime.
 */
@Mixin(Minecraft.class)
public class MinecraftFrameMixin {

    @Inject(method = "runTick(Z)V", at = @At("HEAD"))
    private void mcsilicon$frameBoundary(boolean renderLevel, CallbackInfo ci) {
        FrameBench.onFrame();
    }
}
