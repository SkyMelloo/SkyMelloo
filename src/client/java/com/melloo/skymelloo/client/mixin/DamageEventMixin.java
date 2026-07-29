package com.melloo.skymelloo.client.mixin;

import com.melloo.skymelloo.client.combat.DeathRecapManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The server sends this packet for every hit an entity takes (not just the killing blow), purely so
 * the client can play the right hurt sound/animation - it carries a real {@link DamageSource}
 * (attacker entity included), unlike the local player's own damage calculation which happens entirely
 * server-side and never runs client-side at all. Repurposed by {@link DeathRecapManager} to build a
 * real "what hit me" combat log instead of guessing at the nearest hostile mob.
 */
@Mixin(ClientPacketListener.class)
public abstract class DamageEventMixin {

	@Inject(method = "handleDamageEvent", at = @At("HEAD"))
	private void skymelloo$onDamageEvent(ClientboundDamageEventPacket packet, CallbackInfo ci) {
		ClientPacketListener self = (ClientPacketListener) (Object) this;
		ClientLevel level = self.getLevel();
		if (level == null) {
			return;
		}
		Entity damaged = level.getEntity(packet.entityId());
		if (damaged == null) {
			return;
		}
		DamageSource source = packet.getSource(level);
		DeathRecapManager.onDamageEvent(damaged, source);
	}
}
