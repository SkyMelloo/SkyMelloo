package com.melloo.skymelloo.client.mixin;

import com.melloo.skymelloo.client.highlight.HighlightManager;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link LivingEntityRenderer#shouldShowName} hides nametags for entities that are invisible to
 * the local player (unlike {@link net.minecraft.world.entity.Entity#shouldShowName()}, which only
 * covers sneaking). Highlighting'd players still show their nametag even while invisible - safe to key
 * purely off {@link HighlightManager#isHighlightTarget}, which already refuses a REAL vanilla-invisible player
 * unless {@code showInvisiblePlayers} is on (off by default). When that
 * setting IS on, {@link InvisiblePlayerRevealMixin} already overrides {@code isInvisible()} to
 * false for them, so vanilla's own nametag logic shows their name without this mixin's help at all
 * - this only still matters for non-invisibility Highlighting cases (e.g. the kill-flash highlight).
 */
@Mixin(LivingEntityRenderer.class)
public abstract class PlayerNameVisibilityMixin {

	@Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("RETURN"), cancellable = true)
	private void skymelloo$forceShowInvisiblePlayer(LivingEntity entity, double distance, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValue() && entity instanceof Player player && HighlightManager.isHighlightTarget(player)) {
			cir.setReturnValue(true);
		}
	}
}
