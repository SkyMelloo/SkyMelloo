package com.melloo.skymelloo.client.social;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.melloo.skymelloo.client.api.ModAuthManager;
import com.melloo.skymelloo.client.api.SkyMellooApiClient;
import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.util.DebugLog;
import net.minecraft.client.Minecraft;

import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Syncs SkyMelloo settings to sky.melloo.me automatically once the Minecraft account is linked -
 * no separate opt-in toggle, linking itself is the on/off switch. Once per game launch: fetches
 * whatever's already saved in the cloud and applies it (so a second device/reinstall on the same
 * linked account starts from existing settings, not defaults); if nothing's been saved yet, this
 * device's current local settings are pushed up instead, so whichever device links first doesn't
 * lose what it already had. From then on every settings-screen close pushes the latest, and every
 * future launch/device pulls whatever's newest - there's no "local file already exists, don't
 * touch it" safety net anymore, since account-linking already means the user asked for this.
 */
public final class CloudSyncManager {
	// Color fields are serialized as a plain RGB int via a custom adapter instead of Gson's default
	// reflection - reflecting into java.awt.Color's private fields can throw InaccessibleObjectException
	// under the JDK module system unless java.desktop/java.awt is explicitly opened, which we can't rely on.
	private static final Gson GSON = new GsonBuilder()
			.registerTypeAdapter(Color.class, (com.google.gson.JsonSerializer<Color>) (src, type, ctx) ->
					new com.google.gson.JsonPrimitive(src.getRGB()))
			.registerTypeAdapter(Color.class, (com.google.gson.JsonDeserializer<Color>) (json, type, ctx) ->
					new Color(json.getAsInt(), true))
			.create();

	// Attempted once per game launch, same reasoning as the whitelist/permissions checks this mirrors -
	// the JOIN event this would otherwise reset on ALSO fires on Hypixel's own internal server-hops
	// (dungeon floor entry, "/server X"), so resetting there would mean re-running this on every hop.
	private static volatile boolean syncAttempted = false;

	private CloudSyncManager() {
	}

	/** Called once per join - resolves link status itself (a fresh fetch, not PermissionsManager's cached copy, to avoid a startup race where that cache hasn't resolved yet) rather than gating on it directly. */
	public static void pullIfNeeded(Minecraft client) {
		if (syncAttempted || client.player == null) {
			return;
		}
		syncAttempted = true;
		DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: checking account-link status...");
		ModAuthManager.getIdentity(client).thenCompose(identity ->
				SkyMellooApiClient.fetchPermissions(identity).thenCompose(perms -> {
					if (!Boolean.TRUE.equals(perms.get("accountLinked"))) {
						DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: account not linked, skipping.");
						return java.util.concurrent.CompletableFuture.<Void>completedFuture(null);
					}
					return SkyMellooApiClient.fetchCloudSettings(identity).thenAccept(settings ->
							Minecraft.getInstance().execute(() -> {
								if (settings != null) {
									applySettings(settings);
									DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: pulled and applied cloud settings.");
								} else {
									DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: nothing saved yet, pushing this device's current settings.");
									pushWithIdentity(identity);
								}
							}));
				})
		).exceptionally(error -> {
			DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: startup check failed (" + error.getMessage() + ").");
			return null;
		});
	}

	/** "Pull Now" button in the Cloud tab - always overwrites local settings with whatever's in the cloud, regardless of link-status caching, since the user explicitly asked for it right now. */
	public static void forcePull(Minecraft client, Runnable onApplied) {
		if (client.player == null) {
			return;
		}
		DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: manual pull requested...");
		ModAuthManager.getIdentity(client).thenCompose(SkyMellooApiClient::fetchCloudSettings).whenComplete((settings, error) ->
				Minecraft.getInstance().execute(() -> {
					if (settings != null) {
						applySettings(settings);
						onApplied.run();
						DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: manual pull applied.");
					} else {
						DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: manual pull found nothing to apply.");
					}
				})
		);
	}

	/** Uploads the current settings - called when the settings screen closes, so most changes sync near-immediately. Gated on the account being linked (PermissionsManager's own cache, already kept fresh while the settings menu is open). */
	public static void push(Minecraft client) {
		if (!PermissionsManager.isAccountLinked() || client.player == null) {
			return;
		}
		ModAuthManager.getIdentity(client).thenAccept(CloudSyncManager::pushWithIdentity);
	}

	private static void pushWithIdentity(ModAuthManager.ModIdentity identity) {
		JsonObject json = GSON.toJsonTree(SkyMellooConfig.HANDLER.instance(), SkyMellooConfig.class).getAsJsonObject();
		SkyMellooApiClient.pushCloudSettings(identity, json).whenComplete((success, error) ->
				Minecraft.getInstance().execute(() ->
						DebugLog.log(DebugLog.Category.CLOUD_SYNC, Boolean.TRUE.equals(success)
								? "Cloud sync: settings pushed."
								: "Cloud sync: push failed" + (error != null ? " (" + error.getMessage() + ")" : "") + ".")
				)
		);
	}

	/** Copies every matching public field from the parsed cloud blob onto the live config via reflection - avoids hand-listing every one of the ~100 settings fields. */
	private static void applySettings(JsonObject settingsJson) {
		SkyMellooConfig parsed;
		try {
			parsed = GSON.fromJson(settingsJson, SkyMellooConfig.class);
		} catch (Exception e) {
			return;
		}
		if (parsed == null) {
			return;
		}
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		for (Field field : SkyMellooConfig.class.getFields()) {
			int mods = field.getModifiers();
			if (Modifier.isStatic(mods) || Modifier.isFinal(mods)) {
				continue;
			}
			try {
				field.set(config, field.get(parsed));
			} catch (IllegalAccessException ignored) {
				// all matched fields here are public instance fields - shouldn't happen
			}
		}
		SkyMellooConfig.HANDLER.save();
	}
}
