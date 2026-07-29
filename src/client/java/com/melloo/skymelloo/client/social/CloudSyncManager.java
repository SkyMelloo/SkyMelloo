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
 * Direction is timestamp-based, not "cloud always wins": once per game launch, this device's own
 * settings-file mtime is compared against the cloud copy's own updatedAt (both real wall-clock
 * timestamps of the last actual save on either side) - whichever is genuinely newer wins, so a
 * device that was just configured offline doesn't get silently overwritten by a stale cloud copy,
 * and a stale local copy doesn't overwrite a newer cloud one either. If nothing's been saved to the
 * cloud at all yet, this device's current settings are pushed up to bootstrap it.
 * <p>
 * The one genuine ambiguity - THIS device already has its own settings AND the cloud already has a
 * different saved copy, so neither timestamp can be trusted as "the" answer - only ever gets asked
 * once (see CloudSyncChoiceScreen and cloudSyncConflictResolved below). Once resolved, both sides
 * are in sync from that point on, so every later launch is back to plain timestamp comparison with
 * no prompt - there's no scenario where a resolved sync can un-resolve itself into needing to ask
 * again.
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
	// the cloud, never overwritten by a pull. cloudSyncConflictResolved specifically: if this were
	// synced like everything else, pulling a cloud copy that predates this device's own resolution
	// would reset the flag and bring the conflict-choice screen back, which defeats the entire point
	// of it only ever asking once.
	private static final Set<String> LOCAL_ONLY_FIELDS = Set.of("cloudSyncConflictResolved");

	// Small tolerance so a push immediately followed by that same device reading its own just-synced
	// state back doesn't register as "the other side is newer" purely from a few seconds of clock/
	// request latency - both timestamps land within a couple seconds of each other right after any
	// successful sync, and that's still "in sync", not a real divergence to act on.
	private static final long SYNC_TOLERANCE_MS = 5_000;

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

	/** Pulls the cloud copy's timestamp, compares against this device's own, and pushes/pulls/asks depending on what's actually needed. */
	private static CompletableFuture<Void> reconcile(ModAuthManager.ModIdentity identity) {
		return SkyMellooApiClient.fetchCloudSettings(identity).thenAccept(result ->
				Minecraft.getInstance().execute(() -> {
					if (result == null) {
						DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: nothing saved yet, pushing this device's current settings.");
						pushWithIdentity(identity);
						return;
					}
					long localMtime = SkyMellooConfig.localSettingsLastModifiedMillis();
					if (localMtime == 0) {
						// This device has never saved a settings file at all - nothing local to weigh
						// against, so just take the cloud copy, no need to ask.
						applySettings(result.settings());
						DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: no local settings yet, pulled and applied cloud settings.");
						return;
					}
					long diff = localMtime - result.updatedAt();
					SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
					if (!config.cloudSyncConflictResolved && Math.abs(diff) > SYNC_TOLERANCE_MS) {
						// Both sides genuinely have their own independent history and this device has never
						// resolved that before - never guess, ask once, showing exactly when each was last
						// saved. Whichever button gets picked also marks this resolved so future launches
						// never ask again (see the class doc comment).
						DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: both local and cloud settings exist and diverge, asking which to keep.");
						Minecraft.getInstance().setScreen(new com.melloo.skymelloo.client.gui.CloudSyncChoiceScreen(
								localMtime, result.updatedAt(),
								() -> {
									markConflictResolved();
									pushWithIdentity(identity);
								},
								() -> {
									markConflictResolved();
									applySettings(result.settings());
								}));
						return;
					}
					if (diff > SYNC_TOLERANCE_MS) {
						DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: this device's settings are newer, pushing.");
						pushWithIdentity(identity);
					} else if (diff < -SYNC_TOLERANCE_MS) {
						applySettings(result.settings());
						DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: cloud settings are newer, pulled and applied.");
					} else {
						DebugLog.log(DebugLog.Category.CLOUD_SYNC, "Cloud sync: already in sync, nothing to do.");
					}
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

	/** "Pull Now" button in the Cloud tab - always overwrites local settings with whatever's in the cloud, regardless of the usual timestamp comparison, since the user explicitly asked for it right now. */
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
		for (String field : LOCAL_ONLY_FIELDS) {
			json.remove(field);
		}
		SkyMellooApiClient.pushCloudSettings(identity, json).whenComplete((success, error) ->
				Minecraft.getInstance().execute(() ->
						DebugLog.log(DebugLog.Category.CLOUD_SYNC, Boolean.TRUE.equals(success)
								? "Cloud sync: settings pushed."
								: "Cloud sync: push failed" + (error != null ? " (" + error.getMessage() + ")" : "") + ".")
				)
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
