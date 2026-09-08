package com.melloo.skymelloo.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Vanilla only hides the body of an invisible entity, not equipped armor. Checks
// ForcedInvisibilityHolder, not the vanilla isInvisible flag, which is also true for entities that should keep visible armor.
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
