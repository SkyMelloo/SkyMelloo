package com.melloo.skymelloo.client.social;

import com.google.gson.JsonObject;
import com.melloo.skymelloo.client.api.SkyMellooApiClient;
import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.util.DebugLog;
import net.minecraft.client.Minecraft;

import java.awt.Color;
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
	// UUIDs currently reporting a linked sky.melloo.me account (see PermissionsManager#isAccountLinked)
	// - shown as a small nametag marker (see StatusTextDisplayManager) purely so it's visible to
	// other SkyMelloo users, same spirit as the mod-user highlight color itself.
	private static final java.util.Set<UUID> otherAccountLinked = java.util.concurrent.ConcurrentHashMap.newKeySet();
	// "owner"/"admin"/"developer"/null, as resolved server-side by roleForUuid (server.js) from that
	// player's actual linked account - never self-reported. SkyMelloo admins/developers/the owner
	// get gold - see HighlightManager#classifyPlayer for how this picks the color.
	private static final Map<UUID, String> otherRoles = new ConcurrentHashMap<>();

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

	/** @return true if that player reported this cosmetic effect as currently enabled. */
	public static boolean hasCosmetic(UUID uuid, String effectKey) {
		Map<String, Integer> cosmetics = otherCosmetics.get(uuid);
		return cosmetics != null && cosmetics.containsKey(effectKey);
	}

	/** @return the ARGB color that player reported for this effect, or -1 if it's enabled with no custom color. 0 (transparent) if not enabled at all. */
	public static int getCosmeticColor(UUID uuid, String effectKey) {
		Map<String, Integer> cosmetics = otherCosmetics.get(uuid);
		if (cosmetics == null) {
			return 0;
		}
		Integer color = cosmetics.get(effectKey);
		return color != null ? color : 0;
	}

	/** @return that player's custom status text, or "" if they haven't set one (or aren't a known mod user). */
	public static String getStatusText(UUID uuid) {
		return otherStatusText.getOrDefault(uuid, "");
	}

	/** @return whether that nearby player currently reports a linked sky.melloo.me account (nametag marker, see EntityDisplayNameMixin). */
	public static boolean isAccountLinked(UUID uuid) {
		return otherAccountLinked.contains(uuid);
	}

	/** @return true if that player's linked account is the SkyMelloo owner, an admin, or a developer - server-resolved, see roleForUuid. */
	public static boolean isAdminOrOwner(UUID uuid) {
		String role = otherRoles.get(uuid);
		return "owner".equals(role) || "admin".equals(role) || "developer".equals(role);
	}

	private static void reportSelf(Minecraft client) {
		if (reportInFlight) {
			return;
		}
		// Privacy master switch - if off, we send NOTHING about ourselves at all (not even a
		// stripped-down report), so we simply never appear in anyone else's mod-user Highlighting, the Credits
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
			java.util.Set<UUID> updatedAccountLinked = new java.util.HashSet<>();
			Map<UUID, String> updatedRoles = new HashMap<>();
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
				if (entry.accountLinked()) {
					updatedAccountLinked.add(uuid);
				}
				if (entry.role() != null) {
					updatedRoles.put(uuid, entry.role());
				}
				if (entry.dungeonSync() != null) {
					DungeonSyncManager.onReceivedPayload(uuid.toString(), entry.username(), entry.dungeonSync());
				}
			}
			otherCosmetics.clear();
			otherCosmetics.putAll(updated);
			otherStatusText.clear();
			otherStatusText.putAll(updatedStatus);
			otherAccountLinked.clear();
			otherAccountLinked.addAll(updatedAccountLinked);
			otherRoles.clear();
			otherRoles.putAll(updatedRoles);
		});
	}

	private static List<String> collectEnabledCosmetics() {
		List<String> list = new ArrayList<>();
		if (!PermissionsManager.has("cosmetics")) {
			// Don't report cosmetics as active if this account isn't permitted to use them - a
			// permission-denied user's own client already shows none of these, so other clients
			// shouldn't render fake ones for them either.
			return list;
		}
		SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
		if (c.haloEnabled) {
			list.add("halo:" + hex(c.haloColor));
		}
		if (c.cherryBlossomEnabled) {
			list.add("cherryBlossom");
		}
		if (c.rainbowHelixEnabled) {
			list.add("rainbowHelix:" + hex(c.rainbowHelixColor));
		}
		if (c.auraEnabled) {
			list.add("aura:" + hex(c.auraColor));
		}
		if (c.magicMissileEnabled) {
			list.add("magicMissile:" + hex(c.magicMissileColor));
		}
		if (c.waveEnabled) {
			list.add("wave:" + hex(c.waveColor));
		}
		if (c.rainCloudEnabled) {
			list.add("rainCloud");
		}
		if (c.fireRingEnabled) {
			list.add("fireRing");
		}
		if (c.starRainEnabled) {
			list.add("starRain");
		}
		if (c.sparkAuraEnabled) {
			list.add("sparkAura");
		}
		if (c.lissajousEnabled) {
			list.add("lissajous:" + hex(c.lissajousColor));
		}
		if (c.roseCurveEnabled) {
			list.add("roseCurve:" + hex(c.roseCurveColor));
		}
		if (c.landingShockwaveEnabled) {
			list.add("landingShockwave:" + hex(c.landingShockwaveColor));
		}
		if (c.fireworkBurstEnabled) {
			list.add("fireworkBurst");
		}
		if (c.frostAuraEnabled) {
			list.add("frostAura:" + hex(c.frostAuraColor));
		}
		if (c.noteMelodyEnabled) {
			list.add("noteMelody");
		}
		if (c.portalVortexEnabled) {
			list.add("portalVortex:" + hex(c.portalVortexColor));
		}
		if (c.heartTrailEnabled) {
			list.add("heartTrail");
		}
		if (c.spiralGalaxyEnabled) {
			list.add("spiralGalaxy:" + hex(c.spiralGalaxyColor));
		}
		if (c.jumpTrailEnabled) {
			list.add("jumpTrail:" + hex(c.jumpTrailColor));
		}
		if (c.totemFlashEnabled) {
			list.add("totemFlash");
		}
		if (c.sculkPulseEnabled) {
			list.add("sculkPulse");
		}
		if (c.omenAuraEnabled) {
			list.add("omenAura");
		}
		if (c.gustAuraEnabled) {
			list.add("gustAura:" + hex(c.gustAuraColor));
		}
		if (c.ashFallEnabled) {
			list.add("ashFall:" + hex(c.ashFallColor));
		}
		if (c.campfireSmokeEnabled) {
			list.add("campfireSmoke");
		}
		if (c.tornadoEnabled) {
			list.add("tornado:" + hex(c.tornadoColor));
		}
		if (c.blackHoleEnabled) {
			list.add("blackHole:" + hex(c.blackHoleColor));
		}
		if (c.twinVortexEnabled) {
			list.add("twinVortex:" + hex(c.twinVortexColor));
		}
		if (c.enchantedCritSparkleEnabled) {
			list.add("enchantedCritSparkle");
		}
		if (c.dustPlumeTrailEnabled) {
			list.add("dustPlumeTrail");
		}
		if (c.chargeUpEnabled) {
			list.add("chargeUp:" + hex(c.chargeUpColor));
		}
		if (c.orbitRingsEnabled) {
			list.add("orbitRings:" + hex(c.orbitRingsColor));
		}
		if (c.lightningAuraEnabled) {
			list.add("lightningAura");
		}
		if (c.confettiBurstEnabled) {
			list.add("confettiBurst");
		}
		if (c.phoenixWingsEnabled) {
			list.add("phoenixWings:" + hex(c.phoenixWingsColor));
		}
		if (c.voidRiftEnabled) {
			list.add("voidRift");
		}
		if (c.starWeaveEnabled) {
			list.add("starWeave");
		}
		if (c.ascendingSparklesEnabled) {
			list.add("ascendingSparkles");
		}
		if (c.cometTrailEnabled) {
			list.add("cometTrail");
		}
		if (c.starVeilEnabled) {
			list.add("starVeil");
		}
		if (c.radiantPulseEnabled) {
			list.add("radiantPulse");
		}
		return list;
	}

	private static String hex(Color color) {
		return String.format("%06X", color.getRGB() & 0xFFFFFF);
	}
}
