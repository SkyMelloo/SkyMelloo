package com.melloo.skymelloo.client.mixin;

import com.melloo.skymelloo.client.combat.DeathRecapManager;
import com.melloo.skymelloo.client.combat.PlayerKillTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class PlayerKillMixin {

	@Inject(method = "die", at = @At("HEAD"))
	private void skymelloo$onDie(DamageSource source, CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self instanceof Player victim) {
			PlayerKillTracker.onPlayerDied(victim, source.getEntity());
			if (victim == Minecraft.getInstance().player) {
				DeathRecapManager.onLocalPlayerDied();
			}
		}
	}
}
