package com.melloo.skymelloo.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Same idea as ArmorInvisibilityMixin but for held items: vanilla renders a held item even on an
// otherwise-invisible entity. Targets the untyped submit overload - the typed one never fires.
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
