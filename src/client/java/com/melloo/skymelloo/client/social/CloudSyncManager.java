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

/**
 * Syncs SkyMelloo settings to sky.melloo.me - requires BOTH an explicit opt-in toggle
 * ({@code cloudSyncEnabled}, on by default) AND a linked Minecraft account; account-linking alone
 * isn't treated as consent to sync settings data, only as the technical prerequisite for there being
 * a persistent identity to sync against at all.
 * <p>
 * Cloud is unconditionally authoritative on join - no timestamp comparison, no content-diff
 * comparison, no "keep local or keep cloud" prompt. Earlier versions of this tried both a
 * timestamp-based "whichever side is newer wins" model and then a content-diff "ask only on a real
 * conflict" model - both looked correct for one device, but fell apart for the actual real-world use
 * case that prompted this rewrite: the same account played across SEVERAL separate installs (e.g.
 * multiple Lunar Client profiles), each one its own independent "device" with its own local settings
 * file. Every one of those installs kept hitting its own genuine first-time divergence from
 * whatever's in the cloud, so "ask once per device" in practice meant "ask constantly, on every
 * install, forever" - indistinguishable from a bug even though each individual prompt was itself
 * correct. Simplest fix: stop trying to protect against losing an unsynced local edit at all. On
 * every join, whatever's in the cloud is pulled and applied, full stop; local edits still get pushed
 * up the moment the settings screen closes (see {@link #push}), so the LAST install you actually
 * changed settings on is what every other install converges to the next time it joins.
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

	/** Pulls whatever's in the cloud and applies it unconditionally - or, if nothing's been pushed there yet at all, bootstraps the cloud from this device's current settings instead. */
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

	/** "Pull Now" button in the Cloud tab - same unconditional pull as the automatic join-time one, just triggered on demand. */
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

	/** Uploads the current settings - called when the settings screen closes, so most changes sync near-immediately. Requires both the toggle and a linked account (PermissionsManager's own cache, already kept fresh while the settings menu is open). */
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
