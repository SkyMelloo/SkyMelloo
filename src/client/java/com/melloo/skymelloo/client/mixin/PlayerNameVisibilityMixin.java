package com.melloo.skymelloo.client.mixin;

import com.melloo.skymelloo.client.highlight.HighlightManager;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// shouldShowName hides nametags for entities invisible to the local player. Highlighted players
// still show theirs even while invisible, keyed off HighlightManager#isHighlightTarget.
@Mixin(LivingEntityRenderer.class)
public abstract class PlayerNameVisibilityMixin {

	@Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("RETURN"), cancellable = true)
	private void skymelloo$forceShowInvisiblePlayer(LivingEntity entity, double distance, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValue() && entity instanceof Player player && HighlightManager.isHighlightTarget(player)) {
			cir.setReturnValue(true);
		}
	}
}
