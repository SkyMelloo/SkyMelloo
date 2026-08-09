package com.melloo.skymelloo.client.social;

import com.google.gson.JsonObject;
import com.melloo.skymelloo.client.api.SkyMellooApiClient;
import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.util.DebugLog;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects other players also running SkyMelloo, via sky.melloo.me acting as a rendezvous point -
 * Hypixel itself never relays custom packets between two third-party clients, so this is the only
 * way two independent SkyMelloo installs can find each other. Each client reports its own UUID +
 * currently-enabled cosmetic effect keys every few seconds, and periodically asks which of the
 * players currently in the tab list have reported in recently. Purely additive/cosmetic: nothing
 * here is required for the mod to work solo, and a network hiccup just means nobody looks aqua for
 * a bit rather than anything breaking.
 */
public final class ModPresenceManager {
	// Reduced 5s -> 2s -> 1s, briefly made user-configurable then reverted back to a
	// fixed interval: a per-player interval meant the website's render delay had to
	// adapt per player too, and any mismatch made the live map (always current) and the player
	// marker (rendered from a delay buffer) visibly drift out of sync with each other. One fixed
	// interval for everyone keeps the two in lockstep - see app.js's own matching fixed delay.
	// Reduced again 1s -> 0.5s - this is the original working
	// cadence, paired with DungeonSyncManager now resending the last 1s of samples (overlapping,
	// not just the delta) on every report - see its own HISTORY_SEND_WINDOW_MS comment.
	private static final int REPORT_INTERVAL_TICKS = 10; // 0.5s
	private static final int QUERY_INTERVAL_TICKS = 100; // 5s

	private static int tickCounter = 0;
	private static volatile boolean reportInFlight = false;
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
	private static volatile boolean queryInFlight = false;

	// effect key -> ARGB color (dust-style effects) or -1 for a bool-only effect with no color.
	private static final Map<UUID, Map<String, Integer>> otherCosmetics = new ConcurrentHashMap<>();
	private static final Map<UUID, String> otherStatusText = new ConcurrentHashMap<>();

	private ModPresenceManager() {
	}

	public static void tick(Minecraft client) {
		if (client.player == null || client.getConnection() == null) {
			return;
		}
		tickCounter++;
		if (tickCounter % REPORT_INTERVAL_TICKS == 0) {
			reportSelf(client);
		}
		if (tickCounter % QUERY_INTERVAL_TICKS == 0) {
			queryNearby(client);
		}
	}

	/** Whether this UUID has reported SkyMelloo presence recently (i.e. is also running the mod right now). */
	public static boolean isModUser(UUID uuid) {
		return otherCosmetics.containsKey(uuid);
	}

	/** @return true if that player reported this cosmetic effect as currently enabled - only ever "magicMissile" now, see {@link #collectEnabledCosmetics}. */
	public static boolean hasCosmetic(UUID uuid, String effectKey) {
		Map<String, Integer> cosmetics = otherCosmetics.get(uuid);
		return cosmetics != null && cosmetics.containsKey(effectKey);
	}

	/** @return that player's custom status text, or "" if they haven't set one (or aren't a known mod user). */
	public static String getStatusText(UUID uuid) {
		return otherStatusText.getOrDefault(uuid, "");
	}

	private static void reportSelf(Minecraft client) {
		if (reportInFlight) {
			return;
		}
		// Privacy master switch - if off, we send NOTHING about ourselves at all (not even a
		// stripped-down report), so we simply never appear in anyone else's mod-user highlighting, the Credits
		// online dot, or the website's public online-user count. Querying OTHERS below is untouched -
		// this only controls what we report about ourselves, not what we can see.
		if (!SkyMellooConfig.HANDLER.instance().presenceSharingEnabled) {
			return;
		}
		reportInFlight = true;
		String uuid = client.player.getUUID().toString();
		String username = client.player.getGameProfile().name();
		List<String> cosmetics = collectEnabledCosmetics();
		String status = SkyMellooConfig.HANDLER.instance().customStatusText;
		// Rides along on the SAME report as cosmetics - own rendezvous mechanism, no reason to
		// duplicate the report/query cycle just for a different payload. Null unless DungeonSyncManager
		// actually has something to share right now (not in a dungeon, nothing changed, etc.).
		JsonObject dungeonSync = DungeonSyncManager.buildOutgoingPayload();
		boolean afk = com.melloo.skymelloo.client.util.AfkDetector.isAfk();
		boolean accountLinked = PermissionsManager.isAccountLinked();
		// "General sync" location - which
		// world/island/dungeon floor, as opposed to DungeonSyncManager's much heavier per-tick
		// dungeon-specific payload above. Straight from Hypixel's own Mod API location event, already
		// a short human-readable string - map falls back to mode since map is occasionally absent
		// right after a server transfer before Hypixel's sent the follow-up event.
		String location = HypixelLocationTracker.getMap();
		if (location == null) {
			location = HypixelLocationTracker.getMode();
		}
		// Riding along on a real mod-auth identity (when one's already available - never blocks the
		// report waiting on a fresh handshake) exempts this heartbeat from the server's generic
		// per-IP rate limiter, since it's trusted, self-controlled traffic rather than arbitrary
		// public API usage - see server.js's rate-limiter exemption.
		int bossRoomBlockCount = dungeonSync != null && dungeonSync.has("bossRoomBlocks") ? dungeonSync.getAsJsonArray("bossRoomBlocks").size() : 0;
		String finalLocation = location;
		com.melloo.skymelloo.client.api.ModAuthManager.getIdentity(client)
				.exceptionally(error -> null)
				.thenCompose(identity -> SkyMellooApiClient.reportPresence(uuid, username, cosmetics, status, dungeonSync, afk, accountLinked, finalLocation, identity))
				.whenComplete((v, error) -> {
					reportInFlight = false;
					if (bossRoomBlockCount > 0) {
						bossRoomSendAttempts++;
						if (error == null) {
							bossRoomSendSuccesses++;
							lastBossRoomSendError = null;
						} else {
							bossRoomSendFailures++;
							lastBossRoomSendError = error.getMessage();
						}
					}
					DebugLog.log(DebugLog.Category.PRESENCE, error == null
							? "Presence: reported self (" + cosmetics.size() + " cosmetic(s) active)."
							: "Presence: self-report failed (" + error.getMessage() + ").");
				});
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

	private static void queryNearby(Minecraft client) {
		if (queryInFlight) {
			return;
		}
		List<String> uuids = new ArrayList<>();
		for (var info : client.getConnection().getOnlinePlayers()) {
			UUID id = info.getProfile().id();
			if (!id.equals(client.player.getUUID())) {
				uuids.add(id.toString());
			}
		}
		if (uuids.isEmpty()) {
			return;
		}
		queryInFlight = true;
		com.melloo.skymelloo.client.api.ModAuthManager.getIdentity(client)
				.exceptionally(error -> null)
				.thenCompose(identity -> SkyMellooApiClient.queryPresence(uuids, identity))
				.whenComplete((present, error) -> {
			queryInFlight = false;
			if (error != null || present == null) {
				DebugLog.log(DebugLog.Category.PRESENCE, "Presence: nearby query failed" + (error != null ? " (" + error.getMessage() + ")" : "") + ".");
				return;
			}
			DebugLog.log(DebugLog.Category.PRESENCE, "Presence: " + present.size() + " of " + uuids.size() + " nearby player(s) also running SkyMelloo.");
			Map<UUID, Map<String, Integer>> updated = new HashMap<>();
			Map<UUID, String> updatedStatus = new HashMap<>();
			for (SkyMellooApiClient.PresenceEntry entry : present) {
				UUID uuid;
				try {
					uuid = UUID.fromString(entry.uuid());
				} catch (IllegalArgumentException e) {
					continue;
				}
				Map<String, Integer> parsed = new HashMap<>();
				for (String token : entry.cosmetics()) {
					int sep = token.indexOf(':');
					if (sep == -1) {
						parsed.put(token, -1);
						continue;
					}
					try {
						int rgb = Integer.parseInt(token.substring(sep + 1), 16) & 0xFFFFFF;
						parsed.put(token.substring(0, sep), rgb);
					} catch (NumberFormatException ignored) {
						// malformed color token from a mismatched/future mod version - skip it
					}
				}
				updated.put(uuid, parsed);
				if (entry.status() != null && !entry.status().isBlank()) {
					updatedStatus.put(uuid, entry.status());
				}
				if (entry.dungeonSync() != null) {
					DungeonSyncManager.onReceivedPayload(uuid.toString(), entry.username(), entry.dungeonSync());
				}
			}
			otherCosmetics.clear();
			otherCosmetics.putAll(updated);
			otherStatusText.clear();
			otherStatusText.putAll(updatedStatus);
		});
	}

	/**
	 * Only "magicMissile" (bare, no color) survives here - every other particle cosmetic moved to
	 * MellooEssentials, which has its own separate presence/broadcast mechanism entirely. Magic
	 * Missile itself stays a SkyMelloo feature (see MagicMissileManager), and {@link
	 * com.melloo.skymelloo.client.mixin.RemoteMissileTriggerMixin} still needs {@link #hasCosmetic}
	 * to know whether a nearby SkyMelloo user actually has it enabled before mirroring their cast.
	 */
	private static List<String> collectEnabledCosmetics() {
		List<String> list = new ArrayList<>();
		if (PermissionsManager.has("spell") && SkyMellooConfig.HANDLER.instance().magicMissileEnabled) {
			list.add("magicMissile");
		}
		return list;
	}
}
