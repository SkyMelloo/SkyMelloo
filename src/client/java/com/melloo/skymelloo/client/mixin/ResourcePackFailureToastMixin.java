package com.melloo.skymelloo.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;

/**
 * Turns a specific, previously-diagnosed disconnect ("Failed to load resource pack!" - Hypixel
 * kicking the client because its own SkyBlock resource pack failed to load, usually from a
 * corrupted/incomplete local download cache, confirmed directly from a real game log/crash: a burst
 * of "zip file closed"/"Failed to open item model ..." errors right before the disconnect, followed
 * by a multi-second freeze while the resource reload finished failing on every single item in that
 * pack) into a clear, actionable toast instead of a silent, confusing freeze-then-kick.
 * <p>
 * Also automatically clears the downloaded-resource-pack cache ({@link Minecraft#clearDownloadedResourcePacks()},
 * the real, public vanilla API for this - not poking at Lunar Client's own private download cache
 * directory) the moment this is detected, so the very next connection attempt is forced to
 * re-download a fresh copy instead of retrying against the same corrupted local cache.
 * <p>
 * This can't prevent the underlying download corruption itself (that's Lunar Client's own closed-
 * source pack-download pipeline, not something a Fabric mod can safely reach into) or the freeze
 * (a normal resource-reload block on the render thread, just abnormally long because every item in a
 * broken pack fails and logs individually) - but reconnecting almost always re-downloads a clean copy,
 * so the toast says exactly that instead of leaving the user guessing.
 * <p>
 * Every {@link DisconnectedScreen} constructor funnels into the one taking a
 * {@link DisconnectionDetails} (confirmed via bytecode - the plain-Component-reason constructors just
 * wrap it in {@code new DisconnectionDetails(reason)} and delegate), so hooking {@code init()} and
 * reading the shadowed field covers every path that can show this screen.
 */
@Mixin(DisconnectedScreen.class)
public abstract class ResourcePackFailureToastMixin {
	@Shadow
	@Final
	private DisconnectionDetails details;

	@Inject(method = "init", at = @At("HEAD"))
	private void skymelloo$explainResourcePackFailure(CallbackInfo ci) {
		if (details == null) {
			return;
		}
		String reason = details.reason().getString().toLowerCase(Locale.ROOT);
		if (!reason.contains("failed to load resource pack")) {
			return;
		}
		Minecraft.getInstance().clearDownloadedResourcePacks();
		SystemToast.add(
				Minecraft.getInstance().getToastManager(),
				SystemToast.SystemToastId.PACK_LOAD_FAILURE,
				Component.literal("SkyMelloo: Resource Pack Fehler erkannt"),
				Component.literal("Hypixels Pack war wahrscheinlich unvollständig heruntergeladen - Cache wurde automatisch geleert, einfach neu verbinden. Tritt es wieder auf, Spiel neu starten.")
		);
	}
}
