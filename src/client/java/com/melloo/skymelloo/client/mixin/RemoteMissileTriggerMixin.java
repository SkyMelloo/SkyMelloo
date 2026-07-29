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

/**
 * Mirrors other SkyMelloo users' Magic Missile locally, so it's visible to everyone running the
 * mod, not just the shooter. There's no packet for the real trigger condition (a "Cast Spell" menu
 * click - see {@link com.melloo.skymelloo.client.gui.SkyMellooMenuScreen}, purely local input
 * handling), so this approximates it from the one signal every client already receives for any
 * remote entity: the vanilla swing animation, via {@code ClientPacketListener.handleAnimate} calling
 * {@code LivingEntity.swing(InteractionHand)} for main-hand attacks (action id 0).
 * {@link com.melloo.skymelloo.client.cosmetics.MagicMissileManager#trigger} deliberately swings the
 * casting player's own main hand for exactly this reason: casting moved from an incidental
 * empty-hand punch to a deliberate menu click, so the swing that used to happen for free as part
 * of a real vanilla attack now has to be triggered manually instead. Combined with "mainhand
 * currently empty" and "known SkyMelloo user with Magic
 * Missile enabled" this is a good approximation, not a certainty - a real bare-fisted punch can
 * still false-positive, same tradeoff every other client-side-only cosmetic in this mod already
 * accepts.
 */
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
