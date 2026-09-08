package com.melloo.skymelloo.client.mixin;

import com.melloo.skymelloo.client.util.LunarPackCacheCleaner;
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

// Turns Hypixel's "Failed to load resource pack!" disconnect into a clear toast, and clears both
// the vanilla resource-pack cache and Lunar Client's own separate cache (see LunarPackCacheCleaner).
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
		LunarPackCacheCleaner.clearNowAndRetry();
		SystemToast.add(
				Minecraft.getInstance().getToastManager(),
				SystemToast.SystemToastId.PACK_LOAD_FAILURE,
				Component.translatable("skymelloo.toast.resource_pack_failure.title"),
				Component.translatable("skymelloo.toast.resource_pack_failure.description")
		);
	}
}
