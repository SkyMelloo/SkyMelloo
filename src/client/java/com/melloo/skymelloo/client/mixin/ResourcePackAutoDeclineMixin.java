package com.melloo.skymelloo.client.mixin;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.social.ResourcePackStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Does exactly what manually opening "Edit Server" and setting "Server Resource Packs" to
 * "Disabled" does - flips {@link ServerData#setResourcePackStatus}. Originally this was a mixin
 * into vanilla's {@code ConnectScreen.startConnecting}, but that turned out to not actually take
 * effect in practice (confirmed: the user still had to manually re-disable it via Edit Server) -
 * Lunar Client has its own heavily reskinned multiplayer/connect UI that doesn't necessarily route
 * through vanilla's ConnectScreen class at all. This targets {@link ClientCommonPacketListenerImpl}'s
 * constructor instead - the one place EVERY connection unavoidably passes through right after
 * {@code this.serverData = cookie.serverData()} is set (confirmed via bytecode), regardless of
 * which screen/button/reconnect-flow (vanilla, Lunar's own UI, or a same-session Hypixel server
 * transfer) actually initiated it.
 * <p>
 * From there, vanilla's own {@code handleResourcePackPush} logic does the right thing: optional
 * packs get silently skipped (no prompt, no download - the actual crash/freeze-prone step,
 * root-caused earlier from Lunar Client's logs - a "Bad PNG Signature" in a corrupted local cache
 * of Hypixel's pack, which forced a resource-pack-load abort and disconnect), while packs the
 * server marks {@code required} still prompt, so a required pack never silently kicks you.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ResourcePackAutoDeclineMixin {

	@Inject(method = "<init>", at = @At("RETURN"))
	private void skymelloo$disablePacksForHypixel(Minecraft minecraft, Connection connection, CommonListenerCookie cookie, CallbackInfo ci) {
		if (!SkyMellooConfig.HANDLER.instance().autoDeclineHypixelResourcePacks) {
			return;
		}
		ServerData serverData = cookie.serverData();
		if (serverData == null || serverData.ip == null || !serverData.ip.toLowerCase().contains("hypixel")) {
			return;
		}
		serverData.setResourcePackStatus(ServerData.ServerPackStatus.DISABLED);
		ResourcePackStatus.markDeclined();
	}
}
