package com.melloo.skymelloo.client.mixin;

import com.melloo.skymelloo.client.fishing.FishingMinigameManager;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Detects clicking a fishing-minigame target, which sit beyond normal interaction reach, so
// hitResult never picks them up - an explicit aim-cone check is used instead.
@Mixin(Minecraft.class)
public abstract class FishingTargetHitMixin {

	@Inject(method = "startAttack", at = @At("HEAD"))
	private void skymelloo$onFishingTargetClick(CallbackInfoReturnable<Boolean> cir) {
		Minecraft client = (Minecraft) (Object) this;
		FishingMinigameManager.tryHit(client);
	}
}
