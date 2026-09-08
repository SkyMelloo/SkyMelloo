package com.melloo.skymelloo.client.mixin;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.cosmetics.MagicMissileManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Two overrides of isInvisible() in one mixin so priority is an explicit if/else: (1) magic-missile
// cosmetic hit always wins; (2) optional reveal of other players' real vanilla invisibility.
@Mixin(Entity.class)
public abstract class MissileHitInvisibilityMixin {

	@Inject(method = "isInvisible", at = @At("HEAD"), cancellable = true)
	private void skymelloo$overrideInvisibility(CallbackInfoReturnable<Boolean> cir) {
		Entity self = (Entity) (Object) this;
		if (MagicMissileManager.isTemporarilyInvisible(self)) {
			cir.setReturnValue(true);
			return;
		}
		// Never overrides the local player's own invisibility.
		if (self instanceof Player player && player != Minecraft.getInstance().player
				&& SkyMellooConfig.HANDLER.instance().showInvisiblePlayersEnabled) {
			cir.setReturnValue(false);
		}
	}
}
