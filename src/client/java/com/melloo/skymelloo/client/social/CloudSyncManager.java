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
 * Syncs SkyMelloo settings to sky.melloo.me: pulls once per join if this device has no local
 * config yet (so a new device/reinstall on the same account starts from existing settings
 * instead of all defaults), and pushes whenever the settings screen is closed.
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

	private static boolean pullAttemptedThisSession = false;

	private CloudSyncManager() {
	}

	/** Called once per join - only actually pulls if cloud sync is on and this device has never saved its own config. */
	public static void pullIfNeeded(Minecraft client) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (pullAttemptedThisSession || !config.cloudSyncEnabled || client.player == null) {
			return;
		}
		pullAttemptedThisSession = true;
		if (!SkyMellooConfig.hasNoLocalConfigFile()) {
			// This device already has its own settings - never silently overwrite them with the cloud copy.
			DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: local config already exists, skipping auto-pull.");
			return;
		}
		DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: no local config found, requesting cloud settings...");
		ModAuthManager.getIdentity(client).thenCompose(SkyMellooApiClient::fetchCloudSettings).whenComplete((settings, error) ->
				Minecraft.getInstance().execute(() -> {
					if (settings != null) {
						applySettings(settings);
						DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: pulled and applied cloud settings.");
					} else {
						DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: no cloud settings available yet.");
					}
				})
		);
	}

	/** "Pull Now" button in the Cloud tab - unlike {@link #pullIfNeeded}, this always overwrites local settings regardless of whether a local config already exists, since the user explicitly asked for it. */
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

	/** Uploads the current settings - called when the settings screen closes, so most changes sync near-immediately. */
	public static void push(Minecraft client) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.cloudSyncEnabled || client.player == null) {
			return;
		}
		JsonObject json = GSON.toJsonTree(config, SkyMellooConfig.class).getAsJsonObject();
		ModAuthManager.getIdentity(client).thenCompose(identity -> SkyMellooApiClient.pushCloudSettings(identity, json)).whenComplete((success, error) ->
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
