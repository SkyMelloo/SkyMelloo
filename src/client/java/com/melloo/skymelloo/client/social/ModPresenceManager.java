package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.mellooessentials.client.social.PresenceManager;

import java.util.List;
import java.util.UUID;

/**
 * SkyMelloo's own contribution to MellooEssentials' single presence report/query loop - registers
 * extension points once at startup rather than running a second, independent report/query cycle
 * against the same endpoint. Two uncoordinated loops for the same account used to race each other,
 * each overwriting the other's data server-side (MellooEssentials' report always sent empty
 * status/afk/accountLinked/no location, silently stomping over SkyMelloo's richer data on its own
 * next tick).
 */
public final class ModPresenceManager {
	// Boss-room-block send tracking - distinguishes "drained locally" from "actually reached the
	// server", since BossRoomScanner's own "queued, not yet sent: 0" only proves the data was DRAINED
	// out of its local pending list into a report payload, never that the HTTP request carrying it
	// actually reached the server successfully. Once drained it's gone either way (drainPendingJson
	// doesn't put anything back on failure) - this is what actually answers "did it arrive", counting
	// only reports that genuinely had ≥1 boss-room block in them, not every presence report.
	private static volatile long bossRoomSendAttempts = 0;
	private static volatile long bossRoomSendSuccesses = 0;
	private static volatile long bossRoomSendFailures = 0;
	private static volatile String lastBossRoomSendError = null;
	// Set synchronously right before MellooEssentials' report fires, read back in the completion
	// listener - safe because only one report is ever in flight at a time (see PresenceManager's own
	// reportInFlight guard), so this can't race a second concurrent report.
	private static volatile boolean lastReportHadBossRoomBlocks = false;
	private static boolean registered = false;

	private ModPresenceManager() {
	}

	public static void init() {
		if (registered) {
			return;
		}
		registered = true;
		PresenceManager.setStatusTextSupplier(() -> SkyMellooConfig.HANDLER.instance().customStatusText);
		PresenceManager.setExtraCosmeticsSupplier(ModPresenceManager::collectEnabledCosmetics);
		PresenceManager.setDungeonSyncSupplier(ModPresenceManager::buildDungeonSyncPayload);
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

	/**
	 * Only "magicMissile" (bare, no color) survives here - every other particle cosmetic moved to
	 * MellooEssentials, which has its own separate presence/broadcast mechanism entirely. Magic
	 * Missile itself stays a SkyMelloo feature (see MagicMissileManager), and {@link
	 * com.melloo.skymelloo.client.mixin.RemoteMissileTriggerMixin} still needs {@link #hasCosmetic}
	 * to know whether a nearby SkyMelloo user actually has it enabled before mirroring their cast.
	 */
	private static List<String> collectEnabledCosmetics() {
		if (PermissionsManager.has("spell") && SkyMellooConfig.HANDLER.instance().magicMissileEnabled) {
			return List.of("magicMissile");
		}
		return List.of();
	}
}
