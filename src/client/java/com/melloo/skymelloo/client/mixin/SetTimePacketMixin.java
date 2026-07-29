package com.melloo.skymelloo.client.mixin;

import com.melloo.skymelloo.client.gui.TpsEstimator;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds every real time-sync packet to {@link TpsEstimator} - see its own doc comment for why this is the real, measurable TPS signal rather than a made-up number. Read-only, never touches the callback. */
@Mixin(ClientPacketListener.class)
public abstract class SetTimePacketMixin {
	@Inject(method = "handleSetTime", at = @At("HEAD"))
	private void skymelloo$onSetTime(ClientboundSetTimePacket packet, CallbackInfo ci) {
		TpsEstimator.onSetTimePacket(packet.gameTime());
	}
}
