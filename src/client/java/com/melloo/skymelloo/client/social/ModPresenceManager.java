package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.mellooessentials.client.social.PresenceManager;

import java.util.List;
import java.util.UUID;

/** SkyMelloo's contribution to MellooEssentials' presence report/query loop - registers extension points instead of running a second competing loop. */
public final class ModPresenceManager {
	// Counts only reports that actually had ≥1 boss-room block, confirming the HTTP send succeeded (not just drained locally).
	private static volatile long bossRoomSendAttempts = 0;
	private static volatile long bossRoomSendSuccesses = 0;
	private static volatile long bossRoomSendFailures = 0;
	private static volatile String lastBossRoomSendError = null;
	// Set right before the report fires, read back in the completion listener - safe since only one report is ever in flight.
	private static volatile boolean lastReportHadBossRoomBlocks = false;
	private static boolean registered = false;

	private ModPresenceManager() {
	}

	public static void init() {
		if (registered) {
			return;
		}
		registered = true;
		PresenceManager.setSkyMellooInstalled(true);
		// Status text is no longer set here - MellooEssentials' own presence.js on the server now
		// derives it from the linked website account's statusText directly (see T-website task filed
		// for that), not from anything the mod self-reports.
		PresenceManager.setExtraCosmeticsSupplier(ModPresenceManager::collectEnabledCosmetics);
		PresenceManager.setDungeonSyncSupplier(ModPresenceManager::buildDungeonSyncPayload);
		PresenceManager.setDungeonSyncEnabledSupplier(() -> SkyMellooConfig.HANDLER.instance().dungeonSyncEnabled);
		PresenceManager.setDungeonSyncListener((uuid, username, payload) -> DungeonSyncManager.onReceivedPayload(uuid, username, payload));
		PresenceManager.setReportCompletionListener(ModPresenceManager::onReportCompleted);
	}

	private static com.google.gson.JsonObject buildDungeonSyncPayload() {
		com.google.gson.JsonObject payload = DungeonSyncManager.buildOutgoingPayload();
		lastReportHadBossRoomBlocks = payload != null && payload.has("bossRoomBlocks") && payload.getAsJsonArray("bossRoomBlocks").size() > 0;
		return payload;
	}

	private static void onReportCompleted(Throwable error) {
		if (!lastReportHadBossRoomBlocks) {
			return;
		}
		bossRoomSendAttempts++;
		if (error == null) {
			bossRoomSendSuccesses++;
			lastBossRoomSendError = null;
		} else {
			bossRoomSendFailures++;
			lastBossRoomSendError = error.getMessage();
		}
	}

	public static long getBossRoomSendAttempts() {
		return bossRoomSendAttempts;
	}

	public static long getBossRoomSendSuccesses() {
		return bossRoomSendSuccesses;
	}

	public static long getBossRoomSendFailures() {
		return bossRoomSendFailures;
	}

	public static String getLastBossRoomSendError() {
		return lastBossRoomSendError;
	}

	/** Whether this UUID has reported presence recently (i.e. is running SkyMelloo or MellooEssentials right now). */
	public static boolean isModUser(UUID uuid) {
		return PresenceManager.isModUser(uuid);
	}

	/** @return true if that player reported the "magicMissile" cosmetic as currently enabled. */
	public static boolean hasCosmetic(UUID uuid, String effectKey) {
		return PresenceManager.hasCosmetic(uuid, effectKey);
	}

	/** @return that player's custom status text, or "" if they haven't set one (or aren't a known mod user). */
	public static String getStatusText(UUID uuid) {
		return PresenceManager.getStatusText(uuid);
	}

	/** Only "magicMissile" - every other cosmetic is MellooEssentials' own. */
	private static List<String> collectEnabledCosmetics() {
		if (PermissionsManager.has("spell") && SkyMellooConfig.HANDLER.instance().magicMissileEnabled) {
			return List.of("magicMissile");
		}
		return List.of();
	}
}
