package dev.atlasclient.mixin;

import dev.atlasclient.render.EspGlow;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives ESP-glowing entities the ESP colour instead of the team colour. Optional, visual only. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    @Inject(method = "updateRenderState(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/entity/state/EntityRenderState;F)V",
            at = @At("TAIL"), require = 0)
    private void atlasclient$espGlowColour(Entity entity, EntityRenderState state, float tickProgress, CallbackInfo ci) {
        int c = EspGlow.colorFor(entity);
        if (c >= 0 && state.outlineColor != 0) {
            state.outlineColor = 0xFF000000 | c;
        }
    }
}
