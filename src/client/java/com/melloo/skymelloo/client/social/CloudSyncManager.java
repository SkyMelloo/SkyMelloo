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
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Syncs SkyMelloo settings to sky.melloo.me - requires BOTH an explicit opt-in toggle
 * ({@code cloudSyncEnabled}, off by default) AND a linked Minecraft account; account-linking alone
 * isn't treated as consent to sync settings data, only as the technical prerequisite for there
 * being a persistent identity to sync against at all.
 * <p>
 * Cloud is authoritative, not timestamp-based: once this device has ever resolved a Local-vs-Cloud
 * choice (or never needed to, because one side was empty), every later launch just silently pulls
 * whatever's in the cloud and applies it - this device's own settings are still saved locally too
 * (both as the thing actually pushed back up after local edits, and as an offline-usable cache), but
 * the cloud copy is what's trusted on join.
 * <p>
 * The one genuine ambiguity - THIS device has its own settings AND the cloud has a genuinely
 * different copy, compared by actual content, not by timestamp - only ever gets asked about once
 * per divergence (see CloudSyncChoiceScreen and cloudSyncConflictResolved below). A silent "cloud
 * changed on another device" pull never counts as a divergence worth asking about, since this
 * device's own settings haven't actually changed since the last time it was in sync - only a case
 * where THIS device also has real unsynced local edits that conflict with the cloud triggers another
 * prompt, and even that always resolves to a fresh silent-pull baseline afterward.
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

	// Fields that describe THIS device's own local state rather than a real setting - never sent to
	// the cloud, never overwritten by a pull. If either of these were synced like everything else,
	// pulling a cloud copy that predates this device's own resolution/snapshot would reset it and
	// bring the conflict-choice screen back on every launch, which defeats the entire point of both.
	private static final Set<String> LOCAL_ONLY_FIELDS = Set.of("cloudSyncConflictResolved", "cloudSyncLastSyncedSnapshot");

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

	/** Fetches the cloud copy and, by actual content rather than a timestamp, pushes/pulls/asks depending on what's genuinely needed. */
	private static CompletableFuture<Void> reconcile(ModAuthManager.ModIdentity identity) {
		return SkyMellooApiClient.fetchCloudSettings(identity).thenAccept(result ->
				Minecraft.getInstance().execute(() -> {
					if (result == null) {
						DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: nothing saved yet, pushing this device's current settings.");
						pushWithIdentity(identity);
						return;
					}
					JsonObject cloudJson = result.settings();
					JsonObject localJson = currentSettingsJson();
					SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
					if (localJson.equals(cloudJson)) {
						recordSynced(cloudJson);
						markConflictResolved();
						DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: already in sync, nothing to do.");
						return;
					}
					if ((config.cloudSyncConflictResolved && matchesLastSyncedSnapshot(localJson)) || isUntouchedDefaults(localJson)) {
						// Either this device hasn't touched its own settings since the last time it was in
						// sync (the cloud copy changed from some OTHER device, nothing of this device's own
						// at stake), or this device has never been configured at all (nothing but untouched
						// defaults, so there's no real local choice to protect either way) - in both cases
						// just silently adopt the cloud copy. No need to ask.
						applySettings(cloudJson);
						recordSynced(cloudJson);
						markConflictResolved();
						DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: cloud settings adopted, no local changes to protect.");
						return;
					}
					// Either this is the very first time both sides genuinely exist, or this device has
					// its own real unsynced edits that conflict with what's in the cloud - never guess,
					// ask. Whichever button gets picked marks this resolved and records the new synced
					// baseline, so a plain "cloud changed elsewhere" pull is all that's left afterward.
					DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: local and cloud settings differ, asking which to keep.");
					Minecraft.getInstance().setScreen(new com.melloo.skymelloo.client.gui.CloudSyncChoiceScreen(
							() -> {
								markConflictResolved();
								pushWithIdentity(identity);
							},
							() -> {
								markConflictResolved();
								applySettings(cloudJson);
								recordSynced(cloudJson);
							}));
				})
		);
	}

	private static void markConflictResolved() {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.cloudSyncConflictResolved) {
			config.cloudSyncConflictResolved = true;
			SkyMellooConfig.HANDLER.save();
		}
	}

	/** Remembers the settings blob now known to be identical on both sides, so a later launch can tell "cloud moved on its own" apart from "this device has its own unsynced edits". */
	private static void recordSynced(JsonObject settingsJson) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		config.cloudSyncLastSyncedSnapshot = GSON.toJson(settingsJson);
		SkyMellooConfig.HANDLER.save();
	}

	private static boolean matchesLastSyncedSnapshot(JsonObject localJson) {
		String snapshot = SkyMellooConfig.HANDLER.instance().cloudSyncLastSyncedSnapshot;
		if (snapshot == null) {
			return false;
		}
		try {
			return com.google.gson.JsonParser.parseString(snapshot).getAsJsonObject().equals(localJson);
		} catch (Exception e) {
			return false;
		}
	}

	/** Whether this device's local settings are still exactly what a fresh install starts with - if so, there's no real local customization to weigh against the cloud, and a first sync can just silently adopt the cloud copy instead of asking. */
	private static boolean isUntouchedDefaults(JsonObject localJson) {
		JsonObject defaults = GSON.toJsonTree(new SkyMellooConfig(), SkyMellooConfig.class).getAsJsonObject();
		for (String field : LOCAL_ONLY_FIELDS) {
			defaults.remove(field);
		}
		return defaults.equals(localJson);
	}

	/** This device's own current settings, minus the fields that describe local-only sync bookkeeping rather than a real setting - the same shape a push sends and a fetched cloud copy already has. */
	private static JsonObject currentSettingsJson() {
		JsonObject json = GSON.toJsonTree(SkyMellooConfig.HANDLER.instance(), SkyMellooConfig.class).getAsJsonObject();
		for (String field : LOCAL_ONLY_FIELDS) {
			json.remove(field);
		}
		return json;
	}

	/** "Pull Now" button in the Cloud tab - always overwrites local settings with whatever's in the cloud, regardless of the usual reconcile logic, since the user explicitly asked for it right now. */
	public static void forcePull(Minecraft client, Runnable onApplied) {
		if (client.player == null) {
			return;
		}
		DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: manual pull requested...");
		ModAuthManager.getIdentity(client).thenCompose(SkyMellooApiClient::fetchCloudSettings).whenComplete((result, error) ->
				Minecraft.getInstance().execute(() -> {
					if (result != null) {
						applySettings(result.settings());
						recordSynced(result.settings());
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
		JsonObject json = currentSettingsJson();
		SkyMellooApiClient.pushCloudSettings(identity, json).whenComplete((success, error) ->
				Minecraft.getInstance().execute(() -> {
					if (Boolean.TRUE.equals(success)) {
						// A successful push means this device's current settings ARE the cloud's current
						// settings now - recorded so a later launch can tell "cloud moved on its own"
						// (worth a silent pull) apart from "this device has unsynced edits" (worth asking).
						recordSynced(json);
						DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: settings pushed.");
					} else {
						DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: push failed" + (error != null ? " (" + error.getMessage() + ")" : "") + ".");
					}
				})
		);
	}

	/** Copies every matching public field from the parsed cloud blob onto the live config via reflection - avoids hand-listing every one of the ~100 settings fields. Skips LOCAL_ONLY_FIELDS, which describe this device's own state rather than a real synced setting. */
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
			if (Modifier.isStatic(mods) || Modifier.isFinal(mods) || LOCAL_ONLY_FIELDS.contains(field.getName())) {
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
