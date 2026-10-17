package com.cbc_more_content.mixin.compat.veil;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.rendertype.VeilRenderType;
import foundry.veil.impl.client.render.dynamicbuffer.DynamicBufferShard;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderStateShard.OutputStateShard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Runs after Veil (priority 200); the plugin forwards its misplaced clear wrapper unchanged. */
@Mixin(value = LevelRenderer.class, priority = 100)
public abstract class VeilWeatherBufferMixin {
    /**
     * Set by the mixin plugin only when it actually moved Veil's weather clear. Without
     * that repair (Veil's buffers off, e.g. under Iris) every clear stays vanilla's.
     */
    @Unique
    private static final boolean WARNAUTICS_WEATHER_REPAIRED = Boolean.getBoolean("warnautics.veilWeatherRepaired");

    @Unique
    private final DynamicBufferShard warnautics$weatherBuffer =
            new DynamicBufferShard("weather", this::getWeatherTarget);

    @Shadow
    public abstract RenderTarget getWeatherTarget();

    @WrapOperation(
            method = "renderLevel",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/RenderStateShard$OutputStateShard;clearRenderState()V"))
    private void warnautics$clearWeatherAtEnd(OutputStateShard state, Operation<Void> original) {
        if (!WARNAUTICS_WEATHER_REPAIRED) {
            original.call(state);
            return;
        }
        String name = VeilRenderType.getName(state);
        if ("weather_target".equals(name)) {
            warnautics$weatherBuffer.clearRenderState();
        } else if (!"particles_target".equals(name)
                || !VeilRenderSystem.renderer().getDynamicBufferManger().isEnabled()) {
            original.call(state);
        }
    }
}
