package com.melloo.skymelloo.client.mixin;

import net.fabricmc.loader.api.FabricLoader;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;

/**
 * Turns a specific, previously-diagnosed disconnect ("Failed to load resource pack!" - Hypixel
 * kicking the client because its own SkyBlock resource pack failed to load, usually from a
 * corrupted/incomplete local download cache, confirmed directly from a real game log/crash: a burst
 * of "zip file closed"/"Failed to open item model ..." errors right before the disconnect, followed
 * by a multi-second freeze while the resource reload finished failing on every single item in that
 * pack) into a clear, actionable toast instead of a silent, confusing freeze-then-kick.
 * <p>
 * Also automatically clears BOTH resource-pack caches the moment this is detected:
 * {@link Minecraft#clearDownloadedResourcePacks()} (the real, public vanilla API) AND, confirmed
 * directly from a real recurring case, Lunar Client's own separate per-profile pack cache at
 * {@code <gameDir>/downloads/}. The vanilla-only clear used to leave that second one untouched, and
 * Lunar re-uses whatever's already sitting there by content hash without re-verifying it - so
 * reconnecting after this toast could keep retrying against the exact same bad local copy and never
 * actually fix anything, which is exactly what was observed. This isn't a documented Lunar API (it's
 * a plain directory wipe of a layout confirmed by hand), so failures there are swallowed rather than
 * risking this screen itself over a bonus cleanup step.
 * <p>
 * This can't prevent the underlying download corruption itself (that's Lunar Client's own closed-
 * source pack-download pipeline, not something a Fabric mod can safely reach into) or the freeze
 * (a normal resource-reload block on the render thread, just abnormally long because every item in a
 * broken pack fails and logs individually) - but reconnecting almost always re-downloads a clean copy
 * now that both caches are actually cleared, so the toast says exactly that instead of leaving the
 * user guessing.
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
		skymelloo$clearLunarPackCache();
		SystemToast.add(
				Minecraft.getInstance().getToastManager(),
				SystemToast.SystemToastId.PACK_LOAD_FAILURE,
				Component.translatable("skymelloo.toast.resource_pack_failure.title"),
				Component.translatable("skymelloo.toast.resource_pack_failure.description")
		);
	}

	/** Best-effort wipe of Lunar Client's own private pack-download cache (see class doc comment) - not a documented API, so any failure here (e.g. a still-locked file) just leaves that one entry behind instead of risking this screen. */
	private static void skymelloo$clearLunarPackCache() {
		try {
			Path downloads = FabricLoader.getInstance().getGameDir().resolve("downloads");
			if (!Files.isDirectory(downloads)) {
				return;
			}
			try (var stream = Files.walk(downloads)) {
				stream.sorted(Comparator.reverseOrder()).forEach(path -> {
					try {
						Files.deleteIfExists(path);
					} catch (IOException ignored) {
						// A locked/in-use entry just survives this pass - matches this whole
						// cleanup's best-effort philosophy.
					}
				});
			}
		} catch (Exception ignored) {
		}
	}
}
