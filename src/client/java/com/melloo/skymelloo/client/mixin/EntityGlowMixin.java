package com.melloo.skymelloo.client.mixin;

import com.melloo.skymelloo.client.esp.EspManager;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forces the vanilla glow-outline (the same effect used by the Glowing status effect,
 * which already renders through walls) onto entities selected by {@link EspManager}.
 * Purely client-side rendering: no packets are sent, no server-side entity state changes.
 */
@Mixin(Entity.class)
public abstract class EntityGlowMixin {

	@Inject(method = "isCurrentlyGlowing", at = @At("HEAD"), cancellable = true)
	private void skymelloo$forceGlow(CallbackInfoReturnable<Boolean> cir) {
		Entity self = (Entity) (Object) this;
		if (EspManager.shouldGlow(self)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
	private void skymelloo$glowColor(CallbackInfoReturnable<Integer> cir) {
		Entity self = (Entity) (Object) this;
		if (EspManager.shouldGlow(self)) {
			cir.setReturnValue(EspManager.getGlowColor(self));
		}
	}

	@Inject(method = "shouldShowName", at = @At("HEAD"), cancellable = true)
	private void skymelloo$forceShowName(CallbackInfoReturnable<Boolean> cir) {
		Entity self = (Entity) (Object) this;
		if (EspManager.shouldShowItemName(self)) {
			cir.setReturnValue(true);
		}
	}
}
