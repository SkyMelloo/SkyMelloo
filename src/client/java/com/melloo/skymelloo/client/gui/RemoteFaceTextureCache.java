package com.melloo.skymelloo.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches a flat player-face avatar image over HTTP (mc-heads.net, the same public avatar service
 * sky.melloo.me itself already uses) and registers it as a real Minecraft texture, keyed by UUID.
 * Unlike PartyHud's local tab-list skin blit (only available for players actually visible in the
 * current server instance right now), this works for ANY player regardless of where they are on
 * the network - needed for the Social menu's friends list, since a friend can be offline or
 * anywhere else entirely.
 */
public final class RemoteFaceTextureCache {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private static final Map<UUID, Identifier> resolved = new ConcurrentHashMap<>();
	private static final Map<UUID, Boolean> inFlight = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> failedAt = new ConcurrentHashMap<>();
	private static final long RETRY_AFTER_MS = 60_000;

	private RemoteFaceTextureCache() {
	}

	/** Cached texture Identifier for this player's face, or null if not resolved yet - calling this kicks off a fetch if one isn't already pending or recently failed, so just poll it again next frame. */
	public static Identifier get(UUID uuid) {
		if (uuid == null) {
			return null;
		}
		Identifier cached = resolved.get(uuid);
		if (cached != null) {
			return cached;
		}
		if (Boolean.TRUE.equals(inFlight.get(uuid))) {
			return null;
		}
		Long lastFail = failedAt.get(uuid);
		if (lastFail != null && System.currentTimeMillis() - lastFail < RETRY_AFTER_MS) {
			return null;
		}
		fetch(uuid);
		return null;
	}

	private static void fetch(UUID uuid) {
		inFlight.put(uuid, true);
		String dashless = uuid.toString().replace("-", "");
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("https://mc-heads.net/avatar/" + dashless + "/32"))
				.timeout(Duration.ofSeconds(8))
				.GET().build();
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
				.thenAccept(response -> {
					if (response.statusCode() != 200) {
						throw new RuntimeException("HTTP " + response.statusCode());
					}
					NativeImage image;
					try {
						image = NativeImage.read(response.body());
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
					Minecraft.getInstance().execute(() -> {
						DynamicTexture texture = new DynamicTexture(() -> "skymelloo-face-" + dashless, image);
						Identifier id = Identifier.fromNamespaceAndPath("skymelloo", "friend_face_" + dashless);
						Minecraft.getInstance().getTextureManager().register(id, texture);
						resolved.put(uuid, id);
						inFlight.remove(uuid);
					});
				})
				.exceptionally(error -> {
					failedAt.put(uuid, System.currentTimeMillis());
					inFlight.remove(uuid);
					return null;
				});
	}
}
