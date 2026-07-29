package com.melloo.skymelloo.client.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.party.PartyHudManager;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Party-wide dungeon-run sync (room/secrets, floor, run-active), in
 * the spirit of Skyblocker's own "Dungeon Secret Sync" (which relays over a separate opt-in websocket
 * - see {@link SkyblockerBridge}'s own doc comment). Relayed over sky.melloo.me's existing presence
 * rendezvous ({@link ModPresenceManager}) rather than a new connection of its own - that system
 * already has every SkyMelloo client reporting in and querying nearby players every few seconds for
 * mod-user detection, so this just rides along as an extra opaque payload on the same report/query
 * cycle instead of standing up a second channel. Unlike an earlier version of this that went over
 * {@code /pc} party chat, this is genuinely invisible to everyone except other SkyMelloo clients - the
 * backend only ever forwards it, never renders it anywhere a human would see it. Renamed from
 * "SecretSyncManager" - it grew beyond just secrets (floor, run-active) and the name should reflect
 * that it's the general dungeon sync channel, not add a second one alongside it.
 * <p>
 * Run-wide state (floor/runActive/score/cleared%/secrets%/deaths/roster) comes straight from
 * {@link DungeonRunTracker}'s own scoreboard reading and needs no Skyblocker at all. Only the current
 * room's NAME and per-secret found/not-found detail specifically come from {@link SkyblockerBridge},
 * which itself only works if Skyblocker is installed and has matched the room - this never invents
 * its own room database, matching the constraint documented there - so those two fields are simply
 * absent from the payload rather than blocking everything else whenever Skyblocker hasn't resolved a
 * room yet (or isn't installed at all).
 */
public final class DungeonSyncManager {
	private record TeammateProgress(String room, int found, int max, List<SkyblockerBridge.SecretRow> details, String floor, boolean runActive, int score, double clearedPercent, double secretsPercentage, int deaths, List<String> roster, boolean hasTimeLimit, int timeRemainingSeconds, int witherKeysObtained, int witherDoorsOpened, boolean bloodRoomEntered, boolean bloodRoomCleared, boolean bossRoomEntered, boolean bossRoomCleared) {
	}

	private static final Map<String, TeammateProgress> teammateProgress = new LinkedHashMap<>();

	/** One timestamped position - the building block for a real, dense path on the website instead of one sparse point per report cycle. */
	private record PositionSample(double mapX, double mapY, float yaw, long atMillis) {
	}

	// Sampled every client tick (see sampleTick, called from SkyMellooClient's tick handler) rather
	// than once per presence-report cycle (fixed at 1s, see ModPresenceManager#REPORT_INTERVAL_TICKS)
	// - "der mod sammelt ganz viele positionen ... dann jede sekunde schickt der die gebündelten
	// informationen ... kann dadurch nen extrem smoothen path machen" (2026-07-27): a single position
	// per report is too sparse for the website to draw anything smoother than a rough guess between
	// two far-apart points - sampling every tick (20/s) and bundling the whole buffer into the NEXT
	// report instead gives it a real, dense recorded path to draw through. Cleared every time
	// buildOutgoingPayload() actually drains it into a report.
	private static final List<PositionSample> selfHistory = new ArrayList<>();
	private static final Map<UUID, List<PositionSample>> otherHistory = new LinkedHashMap<>();
	// Reworked (2026-07-27, "damals hat es funktioniert mit jeden tick nen snap alle daten jede
	// halbe sekunde der letzten sekunde schicken website smoothed es dann") back to the original
	// working design after the "drain only the newly-added samples" version looked broken in
	// practice: every report now resends the last HISTORY_SEND_WINDOW_MS of samples (overlapping
	// with the previous report, not just the delta since then), and the buffer itself is never
	// fully drained - only time-trimmed - so a single delayed/dropped report can't create a gap.
	// The server (lib/presence.js#mergePositionHistory) and website (app.js#mergeMarkerHistory)
	// both already dedupe incoming samples by their exact atMillis, so resending overlapping data
	// is free/harmless on that end.
	private static final long HISTORY_SEND_WINDOW_MS = 1000;
	// A bit more than the send window so two consecutive reports' windows always overlap even with
	// some jitter in the report timer, rather than bounding memory as tightly as possible.
	private static final long HISTORY_RETAIN_MS = 2000;

	private DungeonSyncManager() {
	}

	/**
	 * Called every client tick (not just once per report cycle) - appends this instant's exact
	 * position for self and every visible roster member, if available right now. Cheap: the same
	 * map-projection math buildOutgoingPayload already did once per report, just done every tick
	 * instead of once every several seconds.
	 */
	public static void sampleTick(Minecraft client) {
		if (client.level == null || client.player == null) {
			return;
		}
		long now = System.currentTimeMillis();
		DungeonRoomTracker.ExactPosition selfPos = DungeonRoomTracker.getExactPlayerMapPosition(client);
		if (selfPos != null) {
			appendSample(selfHistory, selfPos.mapX(), selfPos.mapY(), selfPos.yaw(), now);
		}
		UUID self = client.player.getUUID();
		for (net.minecraft.client.player.AbstractClientPlayer other : client.level.players()) {
			UUID id = other.getUUID();
			if (id.equals(self) || !DungeonRunTracker.getEffectiveRoster().contains(id)) {
				continue;
			}
			DungeonRoomTracker.ExactPosition otherPos = DungeonRoomTracker.getExactMapPositionFor(client, other.getX(), other.getZ(), other.getYRot());
			if (otherPos == null) {
				continue;
			}
			appendSample(otherHistory.computeIfAbsent(id, k -> new ArrayList<>()), otherPos.mapX(), otherPos.mapY(), otherPos.yaw(), now);
		}
	}

	private static void appendSample(List<PositionSample> list, double mapX, double mapY, float yaw, long atMillis) {
		list.add(new PositionSample(mapX, mapY, yaw, atMillis));
		long cutoff = atMillis - HISTORY_RETAIN_MS;
		while (!list.isEmpty() && list.get(0).atMillis() < cutoff) {
			list.remove(0);
		}
	}

	/** Just the samples from the last {@link #HISTORY_SEND_WINDOW_MS} - the buffer itself (see appendSample) keeps a bit more than this so reports can overlap. */
	private static List<PositionSample> recentWindow(List<PositionSample> history) {
		long cutoff = System.currentTimeMillis() - HISTORY_SEND_WINDOW_MS;
		List<PositionSample> recent = new ArrayList<>();
		for (PositionSample sample : history) {
			if (sample.atMillis() >= cutoff) {
				recent.add(sample);
			}
		}
		return recent;
	}

	private static JsonArray historyToJson(List<PositionSample> history) {
		JsonArray arr = new JsonArray();
		for (PositionSample sample : history) {
			JsonObject obj = new JsonObject();
			obj.addProperty("mapX", sample.mapX());
			obj.addProperty("mapY", sample.mapY());
			obj.addProperty("yaw", sample.yaw());
			obj.addProperty("atMillis", sample.atMillis());
			arr.add(obj);
		}
		return arr;
	}

	/**
	 * Called from {@link ModPresenceManager#reportSelf} every ~5s (the existing presence report
	 * cycle) - {@code null} if there's nothing worth sharing right now (feature off, no permission, or
	 * neither an active run nor a Skyblocker room match).
	 */
	public static JsonObject buildOutgoingPayload() {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.dungeonSyncEnabled || !PermissionsManager.has("dungeonInfo")) {
			return null;
		}
		SkyblockerBridge.RoomSecrets current = SkyblockerBridge.getCurrentRoomSecrets();
		boolean runActive = DungeonRunTracker.isRunActive();
		// Run-wide state (floor/runActive/score/roster/etc., all below) comes straight from
		// DungeonRunTracker's own scoreboard reading, NOT from Skyblocker at all - only the room
		// NAME/secrets fields actually need Skyblocker's room match. This used to bail out on the
		// WHOLE payload whenever Skyblocker simply hadn't matched a room yet (no Skyblocker installed,
		// or just a few seconds into a fresh run before the first room resolves) - meaning the website
		// showed nothing but "online" for however long that took, even though "run active, floor X"
		// was already known and ready to share. Now only skips entirely if there's truly nothing to
		// say at all (not in a run AND no Skyblocker room match either - e.g. standing in the hub).
		if (!runActive && current == null) {
			return null;
		}
		JsonObject payload = new JsonObject();
		if (current != null) {
			payload.addProperty("room", current.roomName());
			payload.addProperty("found", current.found());
			payload.addProperty("max", current.max());
			List<SkyblockerBridge.SecretRow> details = SkyblockerBridge.getCurrentRoomSecretDetails();
			if (details != null) {
				JsonArray detailsArr = new JsonArray();
				for (SkyblockerBridge.SecretRow row : details) {
					JsonObject rowObj = new JsonObject();
					rowObj.addProperty("index", row.secretIndex());
					rowObj.addProperty("found", row.found());
					detailsArr.add(rowObj);
				}
				payload.add("details", detailsArr);
			}
		}
		payload.addProperty("floor", DungeonRunTracker.getFloor());
		payload.addProperty("runActive", runActive);
		// Real wall-clock start time of THIS specific run - added (2026-07-26) so the run-recording
		// backend can tell "still the same run" from "a genuinely new run started" independent of
		// runActive's own timing, which no longer flips false right away (see DungeonRunTracker's
		// change to only end a run after leaving the dungeon and waiting 20s, made earlier the same day).
		// Root-caused as a real bug from a live report: with runActive staying true through that whole
		// wait, a fast requeue into a brand new run could start being recorded as a continuation of the
		// PREVIOUS run's still-open recording, making a replay look like it "starts mid-run" - the new
		// run's real start was actually somewhere in the middle of the merged snapshot list. See
		// lib/dungeonRuns.js's own use of this field.
		payload.addProperty("runStartedAtMillis", DungeonRunTracker.getRunStartedAtMillis());
		// Broader run-wide info ("alles an infos durchgeben was skymelloo ehh sammelt") so the website
		// and teammates get the whole picture, not just this one room - lets missing/failed data from
		// one player be filled in by whichever other party member's own report has it (see
		// getTeammateProgress()'s doc comment on how these merge).
		payload.addProperty("score", DungeonRunTracker.currentDisplayedScore());
		payload.addProperty("grade", DungeonRunTracker.gradeForTotal(DungeonRunTracker.currentDisplayedScore()));
		payload.addProperty("clearedPercent", DungeonRunTracker.getClearedPercent());
		payload.addProperty("secretsPercentage", DungeonRunTracker.getSecretsPercentage());
		payload.addProperty("deaths", DungeonRunTracker.getTotalDeaths());
		// Every death this run so far, with position/death-number - "wenn wer died da wo der
		// gestorben ist ein x machen ... mit namen drüber und zahl der wie vielte tod das ist"
		// (2026-07-27). The FULL list every report (not a delta) - same pattern as roomNames/
		// secretsLog below, small enough that this isn't a real storage concern.
		JsonArray deathMarkersArr = new JsonArray();
		for (DungeonRunTracker.DeathMarker marker : DungeonRunTracker.getDeathMarkers()) {
			JsonObject markerObj = new JsonObject();
			markerObj.addProperty("username", marker.username());
			if (marker.mapX() != null && marker.mapY() != null) {
				markerObj.addProperty("mapX", marker.mapX());
				markerObj.addProperty("mapY", marker.mapY());
			}
			markerObj.addProperty("deathNumber", marker.deathNumber());
			markerObj.addProperty("atMillis", marker.atMillis());
			deathMarkersArr.add(markerObj);
		}
		payload.add("deathMarkers", deathMarkersArr);
		// Score breakdown (same skill/explore/speed/bonus split DungeonScoreHud shows locally),
		// S+ pace, and the puzzle-by-puzzle log - all previously computed but website-invisible.
		DungeonRunTracker.ScoreEstimate estimate = DungeonRunTracker.calculateScore();
		JsonObject scoreBreakdown = new JsonObject();
		scoreBreakdown.addProperty("skill", estimate.skill());
		scoreBreakdown.addProperty("explore", estimate.explore());
		scoreBreakdown.addProperty("speed", estimate.speed());
		scoreBreakdown.addProperty("bonus", estimate.bonus());
		payload.add("scoreBreakdown", scoreBreakdown);
		payload.addProperty("scoreTrendDelta", DungeonRunTracker.getScoreTrendDelta());
		payload.addProperty("bestPossibleScore", DungeonRunTracker.getBestPossibleScore());
		payload.addProperty("splusStillPossible", DungeonRunTracker.isSPlusStillPossible());
		Integer extraSecondsForSplus = DungeonRunTracker.getExtraSecondsForSPlus();
		if (extraSecondsForSplus != null) {
			payload.addProperty("extraSecondsForSplus", extraSecondsForSplus);
		}
		DungeonRunTracker.ScorePenalties penalties = DungeonRunTracker.currentPenalties();
		payload.addProperty("puzzleFailPenalty", penalties.puzzleFailPenalty());
		payload.addProperty("deathPenalty", penalties.deathPenalty());
		JsonArray puzzlesArr = new JsonArray();
		for (DungeonRunTracker.PuzzleResult puzzle : DungeonRunTracker.getPuzzleOutcomes()) {
			JsonObject puzzleObj = new JsonObject();
			puzzleObj.addProperty("outcome", puzzle.outcome().name());
			puzzleObj.addProperty("player", puzzle.player());
			puzzleObj.addProperty("detail", puzzle.detail());
			puzzlesArr.add(puzzleObj);
		}
		payload.add("puzzles", puzzlesArr);
		// Previously tracked (DungeonRunTracker/DungeonDebugHud) but never actually sent anywhere -
		// only visible locally on the Dungeon Debug HUD. Added so the website's /dungeon page can show
		// more than the run-wide score numbers - "generelle daten wie zeit time until time left ...
		// bloodkeys etc".
		payload.addProperty("hasTimeLimit", DungeonRunTracker.hasTimeLimit());
		payload.addProperty("timeRemainingSeconds", DungeonRunTracker.getTimeRemainingSeconds());
		List<Boolean> witherDoors = DungeonRunTracker.getWitherDoors();
		payload.addProperty("witherKeysObtained", witherDoors.size());
		payload.addProperty("witherDoorsOpened", (int) witherDoors.stream().filter(Boolean::booleanValue).count());
		// Per-door state, not just the aggregate count above - "wither door 1 etc" (2026-07-27), the
		// same individual breakdown the local Dungeon Debug HUD already shows, now also on the website.
		JsonArray witherDoorsArr = new JsonArray();
		for (boolean opened : witherDoors) {
			witherDoorsArr.add(opened);
		}
		payload.add("witherDoors", witherDoorsArr);
		payload.addProperty("bloodRoomEntered", DungeonRunTracker.isBloodRoomEntered());
		payload.addProperty("bloodRoomCleared", DungeonRunTracker.isBloodRoomCleared());
		payload.addProperty("bossRoomEntered", DungeonRunTracker.isBossRoomEntered());
		payload.addProperty("bossRoomCleared", DungeonRunTracker.isBossRoomCleared());
		// "Run failed" state - same local-death/party-wipe distinction the Dungeon Debug HUD already
		// shows (2026-07-27).
		payload.addProperty("localPlayerDied", DungeonRunTracker.hasLocalPlayerDied());
		payload.addProperty("partyWiped", DungeonRunTracker.isEntirePartyDead());
		// Client performance stats - fps and ping (2026-07-27). Real Hypixel
		// server TPS is NOT included - unlike FPS/ping, there's no vanilla-client-visible source for it
		// (Hypixel doesn't expose it to the client the way it does latency), so sending a made-up number
		// would just be wrong; leaving it out rather than guessing.
		Minecraft mcInstance = Minecraft.getInstance();
		payload.addProperty("fps", mcInstance.getFps());
		if (mcInstance.player != null && mcInstance.getConnection() != null) {
			var playerInfo = mcInstance.getConnection().getPlayerInfo(mcInstance.player.getUUID());
			if (playerInfo != null) {
				payload.addProperty("pingMs", playerInfo.getLatency());
			}
		}
		// First 3D boss-room viewer prototype (2026-07-27, see BossRoomScanner's own doc comment) -
		// only present while actively scanning, and only the NEWLY discovered blocks since the last
		// report (delta-encoded, drained), not the whole accumulated set every time.
		if (BossRoomScanner.isActive()) {
			payload.addProperty("bossRoomScanId", BossRoomScanner.getScanId());
			payload.add("bossRoomBlocks", BossRoomScanner.drainPendingJson());
		}
		// Real floor-plan data (see DungeonRoomTracker's own doc comment - a faithful port of
		// Skyblocker's map-pixel room detection) - "die map wo ich die map sehe... map von oben wo
		// räume sind... standort der leute". Grid position is room-units relative to the entrance
		// (0,0), not raw map pixels or world coordinates, so the website can lay it out as a simple
		// grid without needing to know anything about Hypixel's actual map pixel format.
		int[] gridPos = DungeonRoomTracker.getCurrentRoomGridPos();
		if (gridPos != null) {
			payload.addProperty("roomGridX", gridPos[0]);
			payload.addProperty("roomGridY", gridPos[1]);
		}
		JsonArray layoutArr = new JsonArray();
		for (DungeonRoomTracker.RoomInfo room : DungeonRoomTracker.getFullRoomLayout(Minecraft.getInstance())) {
			JsonObject roomObj = new JsonObject();
			roomObj.addProperty("dx", room.dx());
			roomObj.addProperty("dy", room.dy());
			roomObj.addProperty("type", room.type().name());
			layoutArr.add(roomObj);
		}
		payload.add("layout", layoutArr);
		JsonArray secretsLogArr = new JsonArray();
		for (DungeonRoomTracker.RoomSecretsEntry entry : DungeonRoomTracker.getSecretsLog()) {
			JsonObject entryObj = new JsonObject();
			entryObj.addProperty("dx", entry.dx());
			entryObj.addProperty("dy", entry.dy());
			entryObj.addProperty("found", entry.found());
			entryObj.addProperty("max", entry.max());
			secretsLogArr.add(entryObj);
		}
		payload.add("secretsLog", secretsLogArr);
		JsonArray roomNamesArr = new JsonArray();
		for (DungeonRoomTracker.RoomNameEntry entry : DungeonRoomTracker.getRoomNamesLog()) {
			JsonObject entryObj = new JsonObject();
			entryObj.addProperty("dx", entry.dx());
			entryObj.addProperty("dy", entry.dy());
			entryObj.addProperty("name", entry.name());
			roomNamesArr.add(entryObj);
		}
		payload.add("roomNames", roomNamesArr);
		// Per-secret found/missing breakdown, same visited-only constraint as roomNames/secretsLog
		// above.
		JsonArray secretDetailsArr = new JsonArray();
		for (DungeonRoomTracker.SecretDetailEntry entry : DungeonRoomTracker.getSecretDetailsLog()) {
			JsonObject entryObj = new JsonObject();
			entryObj.addProperty("dx", entry.dx());
			entryObj.addProperty("dy", entry.dy());
			JsonArray secretsArr = new JsonArray();
			for (SkyblockerBridge.SecretRow row : entry.secrets()) {
				JsonObject rowObj = new JsonObject();
				rowObj.addProperty("index", row.secretIndex());
				rowObj.addProperty("found", row.found());
				secretsArr.add(rowObj);
			}
			entryObj.add("secrets", secretsArr);
			secretDetailsArr.add(entryObj);
		}
		payload.add("secretDetails", secretDetailsArr);
		// Map-pixel anchor + per-room pixel size - lets the website convert layout's dx,dy grid
		// offsets into real pixel bounds, for drawing room labels/hover targets directly on the map.
		DungeonRoomTracker.MapGridMeta gridMeta = DungeonRoomTracker.getMapGridMeta();
		if (gridMeta != null) {
			payload.addProperty("mapEntranceX", gridMeta.entranceMapX());
			payload.addProperty("mapEntranceY", gridMeta.entranceMapY());
			payload.addProperty("mapRoomSize", gridMeta.roomSize());
		}
		// Pixel-accurate map + exact live position ("wie ingame mit pixeln, ein pixel ein block...
		// genau koordinaten und richtung") - see DungeonRoomTracker#getRawMapData/
		// getExactPlayerMapPosition's own doc comments for why this supersedes the room-grid
		// approximation above (kept for the fallback path on older reports/clients).
		DungeonRoomTracker.MapPixelData mapData = DungeonRoomTracker.getRawMapData(Minecraft.getInstance());
		if (mapData != null) {
			payload.addProperty("mapColors", mapData.colorsBase64());
			payload.addProperty("mapCenterX", mapData.centerX());
			payload.addProperty("mapCenterZ", mapData.centerZ());
			payload.addProperty("mapScale", mapData.scale());
		}
		DungeonRoomTracker.ExactPosition exactPos = DungeonRoomTracker.getExactPlayerMapPosition(Minecraft.getInstance());
		if (exactPos != null) {
			payload.addProperty("exactMapX", exactPos.mapX());
			payload.addProperty("exactMapY", exactPos.mapY());
			payload.addProperty("yaw", exactPos.yaw());
		}
		// reportIntervalMs used to be sent here so the website could size its render delay per player
		// - removed 2026-07-27 alongside making the report interval itself fixed (not adjustable, see
		// ModPresenceManager#REPORT_INTERVAL_TICKS) - every client reports at the same fixed rate
		// now, so the website just uses one matching fixed delay instead (see app.js).
		// The dense per-tick history buffer (see sampleTick) - kept SEPARATE from exactMapX/Y/yaw
		// above rather than replacing them, so anything only reading the single latest position still
		// works unchanged. Sends the recent WINDOW, not a drain - see HISTORY_SEND_WINDOW_MS's own
		// comment on why overlapping reports are intentional now.
		List<PositionSample> recentSelf = recentWindow(selfHistory);
		if (!recentSelf.isEmpty()) {
			payload.add("positionHistory", historyToJson(recentSelf));
		}
		JsonArray rosterArr = new JsonArray();
		Map<UUID, PartyHudManager.MemberInfo> members = PartyHudManager.getMembers();
		for (UUID member : DungeonRunTracker.getEffectiveRoster()) {
			PartyHudManager.MemberInfo info = members.get(member);
			if (info != null) {
				rosterArr.add(info.username());
			}
		}
		payload.add("roster", rosterArr);
		// Mutual party attestation (2026-07-26, "ne party fixen indem... server matched diese spieler
		// dann und schaut ob die sich gegenseitig melden") - which of the roster above this client can
		// currently ALSO confirm seeing as a real, server-confirmed connected player (not just a
		// self-reported name) - see DungeonRunTracker#getVisibleTeammates. The backend only trusts a
		// teammate's live data if BOTH sides' independently-computed reports agree on seeing each other.
		JsonArray visibleArr = new JsonArray();
		for (UUID visible : DungeonRunTracker.getVisibleTeammates()) {
			visibleArr.add(visible.toString());
		}
		payload.add("visibleTeammates", visibleArr);
		// Exact map positions for OTHER roster members too, not just self - "wenn ich mit mehreren
		// spiele die keine mod nutzen deren standorte und alles trotzdem auch gesendet werden"
		// (2026-07-26): this client already knows every visible party member's real world position
		// (that's how their entity renders at all), so it can report positions for teammates who don't
		// have SkyMelloo installed themselves, as long as THIS client can see them. The backend merges
		// these in as a fallback wherever a teammate has no exactMapX/Y of their own to report (see
		// server.js's playerPositions merge).
		Minecraft mcClient = Minecraft.getInstance();
		if (mcClient.level != null && mcClient.player != null) {
			UUID self = mcClient.player.getUUID();
			JsonArray otherPositionsArr = new JsonArray();
			for (net.minecraft.client.player.AbstractClientPlayer other : mcClient.level.players()) {
				UUID id = other.getUUID();
				if (id.equals(self) || !DungeonRunTracker.getEffectiveRoster().contains(id)) {
					continue;
				}
				DungeonRoomTracker.ExactPosition otherPos = DungeonRoomTracker.getExactMapPositionFor(mcClient, other.getX(), other.getZ(), other.getYRot());
				if (otherPos == null) {
					continue;
				}
				PartyHudManager.MemberInfo info = members.get(id);
				if (info == null) {
					continue;
				}
				JsonObject posObj = new JsonObject();
				posObj.addProperty("username", info.username());
				posObj.addProperty("mapX", otherPos.mapX());
				posObj.addProperty("mapY", otherPos.mapY());
				posObj.addProperty("yaw", otherPos.yaw());
				// Same dense per-tick history as self above, so a teammate's dot can be drawn just as
				// smoothly on the website, not just the viewer's own.
				List<PositionSample> history = otherHistory.get(id);
				if (history != null) {
					List<PositionSample> recentOther = recentWindow(history);
					if (!recentOther.isEmpty()) {
						posObj.add("positionHistory", historyToJson(recentOther));
					}
				}
				otherPositionsArr.add(posObj);
			}
			payload.add("otherPlayerPositions", otherPositionsArr);
			// No longer cleared here (see HISTORY_SEND_WINDOW_MS's own comment) - each entry now
			// self-trims by age in appendSample instead of being fully drained every report.
		}
		return payload;
	}

	/** Called from {@link ModPresenceManager} for every queried player who reported a dungeonSync payload - stored by username, same as before. */
	public static void onReceivedPayload(String uuid, String username, JsonObject payload) {
		if (username == null || username.isBlank()) {
			return;
		}
		String room = payload.has("room") && !payload.get("room").isJsonNull() ? payload.get("room").getAsString() : null;
		int found = payload.has("found") ? payload.get("found").getAsInt() : 0;
		int max = payload.has("max") ? payload.get("max").getAsInt() : -1;
		String floor = payload.has("floor") && !payload.get("floor").isJsonNull() ? payload.get("floor").getAsString() : null;
		boolean runActive = payload.has("runActive") && payload.get("runActive").getAsBoolean();
		List<SkyblockerBridge.SecretRow> details = new ArrayList<>();
		if (payload.has("details") && payload.get("details").isJsonArray()) {
			payload.getAsJsonArray("details").forEach(el -> {
				if (!el.isJsonObject()) {
					return;
				}
				JsonObject rowObj = el.getAsJsonObject();
				if (rowObj.has("index") && rowObj.has("found")) {
					details.add(new SkyblockerBridge.SecretRow(rowObj.get("index").getAsInt(), rowObj.get("found").getAsBoolean()));
				}
			});
		}
		int score = payload.has("score") ? payload.get("score").getAsInt() : 0;
		double clearedPercent = payload.has("clearedPercent") ? payload.get("clearedPercent").getAsDouble() : 0;
		double secretsPercentage = payload.has("secretsPercentage") ? payload.get("secretsPercentage").getAsDouble() : 0;
		int deaths = payload.has("deaths") ? payload.get("deaths").getAsInt() : 0;
		List<String> roster = new ArrayList<>();
		if (payload.has("roster") && payload.get("roster").isJsonArray()) {
			payload.getAsJsonArray("roster").forEach(el -> {
				if (el.isJsonPrimitive()) {
					roster.add(el.getAsString());
				}
			});
		}
		boolean hasTimeLimit = payload.has("hasTimeLimit") && payload.get("hasTimeLimit").getAsBoolean();
		int timeRemainingSeconds = payload.has("timeRemainingSeconds") ? payload.get("timeRemainingSeconds").getAsInt() : 0;
		int witherKeysObtained = payload.has("witherKeysObtained") ? payload.get("witherKeysObtained").getAsInt() : 0;
		int witherDoorsOpened = payload.has("witherDoorsOpened") ? payload.get("witherDoorsOpened").getAsInt() : 0;
		boolean bloodRoomEntered = payload.has("bloodRoomEntered") && payload.get("bloodRoomEntered").getAsBoolean();
		boolean bloodRoomCleared = payload.has("bloodRoomCleared") && payload.get("bloodRoomCleared").getAsBoolean();
		boolean bossRoomEntered = payload.has("bossRoomEntered") && payload.get("bossRoomEntered").getAsBoolean();
		boolean bossRoomCleared = payload.has("bossRoomCleared") && payload.get("bossRoomCleared").getAsBoolean();
		teammateProgress.put(username, new TeammateProgress(room, found, max, details, floor, runActive, score, clearedPercent, secretsPercentage, deaths, roster, hasTimeLimit, timeRemainingSeconds, witherKeysObtained, witherDoorsOpened, bloodRoomEntered, bloodRoomCleared, bossRoomEntered, bossRoomCleared));
	}

	/**
	 * Every teammate's last-known room-secrets progress, freshest first-seen order - naturally ages
	 * out on its own since {@link ModPresenceManager}'s own presence entries expire (~20s) if a
	 * teammate stops reporting (run ended, they left, closed the game).
	 * <p>
	 * Each teammate's own {@code score}/{@code clearedPercent}/{@code secretsPercentage}/{@code deaths}
	 * is whatever THEIR client last calculated for the whole run (not just this room) - since these
	 * numbers are supposed to already agree across every SkyMelloo client in the same run, a reader
	 * that wants "the run's" number rather than one specific player's can just take the freshest
	 * non-zero value across everyone here, which naturally fills in from whoever's client still has
	 * good data if one player's own run tracking dropped out (disconnected, game closed) - filling in
	 * missing data from teammates rather than showing a gap.
	 */
	public static Map<String, TeammateProgressView> getTeammateProgress() {
		Map<String, TeammateProgressView> result = new LinkedHashMap<>();
		teammateProgress.forEach((name, p) -> result.put(name, new TeammateProgressView(p.room(), p.found(), p.max(), p.details(), p.floor(), p.runActive(), p.score(), p.clearedPercent(), p.secretsPercentage(), p.deaths(), p.roster(), p.hasTimeLimit(), p.timeRemainingSeconds(), p.witherKeysObtained(), p.witherDoorsOpened(), p.bloodRoomEntered(), p.bloodRoomCleared(), p.bossRoomEntered(), p.bossRoomCleared())));
		return result;
	}

	public record TeammateProgressView(String room, int found, int max, List<SkyblockerBridge.SecretRow> details, String floor, boolean runActive, int score, double clearedPercent, double secretsPercentage, int deaths, List<String> roster, boolean hasTimeLimit, int timeRemainingSeconds, int witherKeysObtained, int witherDoorsOpened, boolean bloodRoomEntered, boolean bloodRoomCleared, boolean bossRoomEntered, boolean bossRoomCleared) {
	}
}
