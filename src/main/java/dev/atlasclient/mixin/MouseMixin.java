package dev.atlasclient.mixin;

import dev.atlasclient.build.PlacementTool;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ctrl + scroll with the placement tool in hand nudges the schematic instead of changing the
 * hotbar slot. Every other scroll goes to the game unchanged. Optional (require = 0).
 */
@Mixin(Mouse.class)
public abstract class MouseMixin {
    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true, require = 0)
    private void atlasclient$placementToolScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (PlacementTool.onScroll(vertical)) {
            ci.cancel();
        }
    }
}
