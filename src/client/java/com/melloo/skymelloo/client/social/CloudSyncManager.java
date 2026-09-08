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
import java.util.concurrent.CompletableFuture;

// Syncs SkyMelloo settings to sky.melloo.me - requires cloudSyncEnabled and a linked account.
// Cloud is unconditionally authoritative on join, no timestamp/content-diff comparison.
public final class CloudSyncManager {
	// Color serialized as a plain RGB int - reflecting into its private fields can throw under the JDK module system.
	private static final Gson GSON = new GsonBuilder()
			.registerTypeAdapter(Color.class, (com.google.gson.JsonSerializer<Color>) (src, type, ctx) ->
					new com.google.gson.JsonPrimitive(src.getRGB()))
			.registerTypeAdapter(Color.class, (com.google.gson.JsonDeserializer<Color>) (json, type, ctx) ->
					new Color(json.getAsInt(), true))
			.create();

	// Once per launch - the JOIN event also fires on Hypixel's own internal server-hops (floor entry, "/server X").
	private static volatile boolean syncAttempted = false;

	private CloudSyncManager() {
	}

	// Fetches link status fresh rather than gating on PermissionsManager's cache, to avoid a startup race.
	public static void pullIfNeeded(Minecraft client) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (syncAttempted || !config.cloudSyncEnabled || client.player == null) {
			return;
		}
		syncAttempted = true;
		DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: checking account-link status...");
		ModAuthManager.getIdentity(client).thenCompose(identity ->
				SkyMellooApiClient.fetchPermissions(identity).thenCompose(perms -> {
					if (!Boolean.TRUE.equals(perms.get("accountLinked"))) {
						DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: account not linked, skipping.");
						return CompletableFuture.<Void>completedFuture(null);
					}
					return reconcile(identity);
				})
		).exceptionally(error -> {
			DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: startup check failed (" + error.getMessage() + ").");
			return null;
		});
	}

	// If nothing's been pushed yet, bootstraps the cloud from this device's current settings instead.
	private static CompletableFuture<Void> reconcile(ModAuthManager.ModIdentity identity) {
		return SkyMellooApiClient.fetchCloudSettings(identity).thenAccept(result ->
				Minecraft.getInstance().execute(() -> {
					if (result == null) {
						DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: nothing saved yet, pushing this device's current settings.");
						pushWithIdentity(identity);
						return;
					}
					applySettings(result.settings());
					DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: pulled and applied cloud settings.");
				})
		);
	}

	// "Pull Now" button in the Cloud tab - same pull as the automatic join-time one, triggered on demand.
	public static void forcePull(Minecraft client, Runnable onApplied) {
		if (client.player == null) {
			return;
		}
		DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: manual pull requested...");
		ModAuthManager.getIdentity(client).thenCompose(SkyMellooApiClient::fetchCloudSettings).whenComplete((result, error) ->
				Minecraft.getInstance().execute(() -> {
					if (result != null) {
						applySettings(result.settings());
						onApplied.run();
						DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: manual pull applied.");
					} else {
						DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: manual pull found nothing to apply.");
					}
				})
		);
	}

	// Called when the settings screen closes, so most changes sync near-immediately.
	public static void push(Minecraft client) {
		if (!SkyMellooConfig.HANDLER.instance().cloudSyncEnabled || !PermissionsManager.isAccountLinked() || client.player == null) {
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

	// Copies every matching public field via reflection, avoiding hand-listing every settings field.
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
