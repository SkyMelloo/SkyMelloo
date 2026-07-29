package com.melloo.skymelloo.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Same fix as {@link ArmorInvisibilityMixin}, for the separate layer that renders a worn
 * player-head/mob-head/pumpkin item on the helmet slot - {@code CustomHeadLayer} isn't part of
 * {@code HumanoidArmorLayer} at all, so a magic-missile-hit or hidden-death-double player wearing
 * a head item kept showing that head floating in place even though the rest of them vanished.
 * Same {@link ForcedInvisibilityHolder} fix as {@link ArmorInvisibilityMixin} - checks that
 * instead of the vanilla flag, which is also true for genuinely-invisible entities/mobs that
 * should keep their (possibly custom-model-relevant) head visible as normal.
 * <p>
 * Targets the untyped {@code submit(..., EntityRenderState, ...)} overload - see
 * {@link HeldItemInvisibilityMixin}'s doc comment for why.
 */
@Mixin(CustomHeadLayer.class)
public abstract class PlayerHeadInvisibilityMixin {
	@Inject(
			method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/EntityRenderState;FF)V",
			at = @At("HEAD"),
			cancellable = true
	)
	private void skymelloo$hideHeadWhenInvisible(
			PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
			EntityRenderState renderState, float limbSwing, float partialTick, CallbackInfo ci) {
		if (((ForcedInvisibilityHolder) (Object) renderState).skymelloo$isForcedInvisible()) {
			ci.cancel();
		}
	}
}
