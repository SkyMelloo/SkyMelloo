package com.melloo.skymelloo.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Same idea as {@link ArmorInvisibilityMixin} but for held items - vanilla renders a held item
 * even on an otherwise-invisible entity, so without this the magic-missile-hit/death-double
 * "invisible" cases would still show a floating held item. Same {@link ForcedInvisibilityHolder}
 * fix as {@link ArmorInvisibilityMixin} - checks that instead of the vanilla flag, which is also
 * true for genuinely-invisible entities that should keep their held item visible as normal.
 * <p>
 * Targets the untyped {@code submit(..., EntityRenderState, ...)} overload, not the generically-typed
 * {@code submit(..., ArmedEntityRenderState, ...)} one - confirmed as a real, reported bug that the
 * typed overload alone never actually fires (same generics-bridge-method situation already documented
 * and worked around in {@link ForcedInvisibilityExtractionMixin} for {@code extractRenderState}; this
 * layer has the identical typed/untyped pair, and the untyped one is what's genuinely called).
 */
@Mixin(ItemInHandLayer.class)
public abstract class HeldItemInvisibilityMixin {
	@Inject(
			method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/EntityRenderState;FF)V",
			at = @At("HEAD"),
			cancellable = true
	)
	private void skymelloo$hideHeldItemWhenInvisible(
			PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
			EntityRenderState renderState, float limbSwing, float partialTick, CallbackInfo ci) {
		if (((ForcedInvisibilityHolder) (Object) renderState).skymelloo$isForcedInvisible()) {
			ci.cancel();
		}
	}
}
