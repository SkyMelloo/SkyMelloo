package com.melloo.skymelloo.client.mixin;

import com.melloo.skymelloo.client.cosmetics.MagicMissileManager;
import com.melloo.skymelloo.client.social.ModPresenceManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Mirrors other SkyMelloo users' Magic Missile locally, approximated from the vanilla swing
// animation (no real cast packet exists) combined with empty main-hand + known cosmetic user.
@Mixin(LivingEntity.class)
public abstract class RemoteMissileTriggerMixin {

	@Inject(method = "swing(Lnet/minecraft/world/InteractionHand;)V", at = @At("HEAD"))
	private void skymelloo$onRemoteSwing(InteractionHand hand, CallbackInfo ci) {
		if (hand != InteractionHand.MAIN_HAND) {
			return;
		}
		LivingEntity self = (LivingEntity) (Object) this;
		Minecraft client = Minecraft.getInstance();
		if (!(self instanceof AbstractClientPlayer player) || player == client.player) {
			return;
		}
		if (!player.getMainHandItem().isEmpty()) {
			return;
		}
		if (ModPresenceManager.isModUser(player.getUUID()) && ModPresenceManager.hasCosmetic(player.getUUID(), "magicMissile")) {
			MagicMissileManager.spawnRemote(client, player);
		}
	}
}
