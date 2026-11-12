package dev.atlasclient.mixin;

import dev.atlasclient.render.EspGlow;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ESP "GLOW" mode: entities chosen by an ESP module get the vanilla outline effect. Visual only.
 * Optional (require = 0): if the target ever changes, the injection is skipped and ESP's other modes
 * keep working; the ESP status line then reports the hook as inactive.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "hasOutline", at = @At("HEAD"), cancellable = true, require = 0)
    private void atlasclient$espGlow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (EspGlow.shouldGlow(entity)) {
            cir.setReturnValue(true);
        }
    }
}
