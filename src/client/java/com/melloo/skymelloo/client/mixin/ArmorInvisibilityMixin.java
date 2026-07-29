package com.melloo.skymelloo.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla only hides the BODY of an invisible entity - equipped armor still renders regardless
 * (the same reason an invisibility-potion drinker's armor floats visibly instead of vanishing).
 * {@link MissileHitInvisibilityMixin} forces isInvisible() true for magic-missile hits and hidden
 * death-double originals, so cancelling armor submission for those cases makes them actually go
 * fully invisible. Checks {@link ForcedInvisibilityHolder} rather than the vanilla
 * {@code renderState.isInvisible} flag directly - that flag is ALSO true for genuinely-invisible
 * entities (a potion effect, or a Hypixel mob whose custom model relies on real invisibility +
 * visible armor), which used to incorrectly lose their armor here too.
 * <p>
 * Targets the untyped {@code submit(..., EntityRenderState, ...)} overload, not the generically-typed
 * {@code submit(..., HumanoidRenderState, ...)} one - see {@link HeldItemInvisibilityMixin}'s doc
 * comment for why (confirmed as a real bug: the typed overload alone never actually fires).
 */
@Mixin(HumanoidArmorLayer.class)
public abstract class ArmorInvisibilityMixin {
	@Inject(
			method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/EntityRenderState;FF)V",
			at = @At("HEAD"),
			cancellable = true
	)
	private void skymelloo$hideArmorWhenInvisible(
			PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
			EntityRenderState renderState, float limbSwing, float partialTick, CallbackInfo ci) {
		if (((ForcedInvisibilityHolder) (Object) renderState).skymelloo$isForcedInvisible()) {
			ci.cancel();
		}
	}
}
