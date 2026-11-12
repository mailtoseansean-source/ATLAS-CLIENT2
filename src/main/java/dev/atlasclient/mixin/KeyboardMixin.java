package dev.atlasclient.mixin;

import dev.atlasclient.input.KeyTaps;
import net.minecraft.client.Keyboard;
import net.minecraft.client.input.KeyInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records key presses so Atlas hotkeys never miss a quick tap. Only observes: the event continues
 * to the game unchanged. Optional (require = 0): if the target ever changes, keys still work
 * through the per-tick key state poll.
 */
@Mixin(Keyboard.class)
public abstract class KeyboardMixin {
    @Inject(method = "onKey", at = @At("HEAD"), require = 0)
    private void atlasclient$recordKey(long window, int action, KeyInput input, CallbackInfo ci) {
        if (action == 1) { // GLFW_PRESS
            KeyTaps.press(input.getKeycode());
        }
    }
}
