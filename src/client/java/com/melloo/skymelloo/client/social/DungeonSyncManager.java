package com.melloo.skymelloo.client.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.party.PartyHudManager;
import com.melloo.skymelloo.client.util.DebugLog;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Party-wide dungeon-run sync, relayed over sky.melloo.me's existing presence rendezvous.
// Run-wide state comes from DungeonRunTracker; room name/per-secret detail need Skyblocker installed.
public final class DungeonSyncManager {
	private record TeammateProgress(String room, int found, int max, List<SkyblockerBridge.SecretRow> details, String floor, boolean runActive, int score, double clearedPercent, double secretsPercentage, int deaths, List<String> roster, boolean hasTimeLimit, int timeRemainingSeconds, int witherKeysObtained, int witherDoorsOpened, boolean bloodRoomEntered, boolean bloodRoomCleared, boolean bossRoomEntered, boolean bossRoomCleared) {
	}

	private static final Map<String, TeammateProgress> teammateProgress = new LinkedHashMap<>();

	// Diagnostic-only bandwidth tracking for compactStandStillRuns; reset per run, reported via DebugLog.
	private static boolean wasRunActive = false;
	private static long runRawHistoryBytes = 0;
	private static long runSavedHistoryBytes = 0;

	private record PositionSample(double mapX, double mapY, float yaw, long atMillis) {
	}

	// Sampled every tick, not once per report, for a dense path instead of one sparse point.
	private static final List<PositionSample> selfHistory = new ArrayList<>();
	private static final Map<UUID, List<PositionSample>> otherHistory = new LinkedHashMap<>();
	// Every report resends this whole window (overlapping the previous one), so a dropped report
	// can't create a gap; the server/website dedupe incoming samples by atMillis.
	private static final long HISTORY_SEND_WINDOW_MS = 2000;
	private static final long HISTORY_RETAIN_MS = 3000;

	private DungeonSyncManager() {
	}

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
		JsonArray raw = sampleListToJson(history);
		JsonArray compacted = sampleListToJson(compactStandStillRuns(history));
		if (DungeonRunTracker.isRunActive()) {
			int rawBytes = raw.toString().getBytes(StandardCharsets.UTF_8).length;
			int compactedBytes = compacted.toString().getBytes(StandardCharsets.UTF_8).length;
			runRawHistoryBytes += rawBytes;
			runSavedHistoryBytes += rawBytes - compactedBytes;
		}
		return compacted;
	}

	private static JsonArray sampleListToJson(List<PositionSample> samples) {
		JsonArray arr = new JsonArray();
		for (PositionSample sample : samples) {
			JsonObject obj = new JsonObject();
			obj.addProperty("mapX", sample.mapX());
			obj.addProperty("mapY", sample.mapY());
			obj.addProperty("yaw", sample.yaw());
			obj.addProperty("atMillis", sample.atMillis());
			arr.add(obj);
		}
		return arr;
	}

	/** Collapses a run of identical mapX/mapY/yaw samples to its first and last tick, so a stand-still doesn't cost bandwidth. */
	private static List<PositionSample> compactStandStillRuns(List<PositionSample> samples) {
		if (samples.size() <= 2) {
			return samples;
		}
		List<PositionSample> result = new ArrayList<>();
		int i = 0;
		while (i < samples.size()) {
			PositionSample runStart = samples.get(i);
			int runEnd = i;
			while (runEnd + 1 < samples.size() && isSamePosition(samples.get(runEnd + 1), runStart)) {
				runEnd++;
			}
			result.add(runStart);
			if (runEnd > i) {
				result.add(samples.get(runEnd));
			}
			i = runEnd + 1;
		}
		return result;
	}

	private static boolean isSamePosition(PositionSample a, PositionSample b) {
		return a.mapX() == b.mapX() && a.mapY() == b.mapY() && a.yaw() == b.yaw();
	}

	private static void reportBandwidthSavings() {
		if (runRawHistoryBytes <= 0) {
			return;
		}
		double percent = 100.0 * runSavedHistoryBytes / runRawHistoryBytes;
		DebugLog.log(DebugLog.Category.DUNGEON, String.format(
				"Position history dedup saved %s of %s this run (%.1f%%).",
				formatBytes(runSavedHistoryBytes), formatBytes(runRawHistoryBytes), percent));
	}

	private static String formatBytes(long bytes) {
		if (bytes < 1_000) {
			return bytes + " B";
		}
		if (bytes < 1_000_000) {
			return String.format("%.1f KB", bytes / 1_000.0);
		}
		if (bytes < 1_000_000_000) {
			return String.format("%.1f MB", bytes / 1_000_000.0);
		}
		return String.format("%.1f GB", bytes / 1_000_000_000.0);
	}

	/** Null if there's nothing worth sharing (feature off, or neither an active run nor a Skyblocker room match). */
	public static JsonObject buildOutgoingPayload() {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.dungeonSyncEnabled) {
			return null;
		}
		SkyblockerBridge.RoomSecrets current = SkyblockerBridge.getCurrentRoomSecrets();
		boolean runActive = DungeonRunTracker.isRunActive();
		if (runActive && !wasRunActive) {
			runRawHistoryBytes = 0;
			runSavedHistoryBytes = 0;
		} else if (!runActive && wasRunActive) {
			reportBandwidthSavings();
		}
		wasRunActive = runActive;
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
		String runEndReason = DungeonRunTracker.getRunEndReason();
		if (!runActive && runEndReason != null) {
			payload.addProperty("runEndReason", runEndReason);
		}
		// Lets the backend tell "still the same run" apart from a fast requeue into a new one.
		payload.addProperty("runStartedAtMillis", DungeonRunTracker.getRunStartedAtMillis());
		payload.addProperty("score", DungeonRunTracker.currentDisplayedScore());
		payload.addProperty("grade", DungeonRunTracker.gradeForTotal(DungeonRunTracker.currentDisplayedScore()));
		payload.addProperty("clearedPercent", DungeonRunTracker.getClearedPercent());
		payload.addProperty("secretsPercentage", DungeonRunTracker.getSecretsPercentage());
		payload.addProperty("deaths", DungeonRunTracker.getTotalDeaths());
		// Full list every report (not a delta), for the website's death markers.
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
		payload.addProperty("hasTimeLimit", DungeonRunTracker.hasTimeLimit());
		payload.addProperty("timeRemainingSeconds", DungeonRunTracker.getTimeRemainingSeconds());
		List<Boolean> witherDoors = DungeonRunTracker.getWitherDoors();
		payload.addProperty("witherKeysObtained", witherDoors.size());
		payload.addProperty("witherDoorsOpened", (int) witherDoors.stream().filter(Boolean::booleanValue).count());
		JsonArray witherDoorsArr = new JsonArray();
		for (boolean opened : witherDoors) {
			witherDoorsArr.add(opened);
		}
		payload.add("witherDoors", witherDoorsArr);
		payload.addProperty("bloodRoomEntered", DungeonRunTracker.isBloodRoomEntered());
		payload.addProperty("bloodRoomCleared", DungeonRunTracker.isBloodRoomCleared());
		payload.addProperty("bossRoomEntered", DungeonRunTracker.isBossRoomEntered());
		payload.addProperty("bossRoomCleared", DungeonRunTracker.isBossRoomCleared());
		payload.addProperty("localPlayerDied", DungeonRunTracker.hasLocalPlayerDied());
		payload.addProperty("partyWiped", DungeonRunTracker.isEntirePartyDead());
		// Server TPS isn't sent - Hypixel doesn't expose it to the client the way it does latency.
		Minecraft mcInstance = Minecraft.getInstance();
		payload.addProperty("fps", mcInstance.getFps());
		if (mcInstance.player != null && mcInstance.getConnection() != null) {
			var playerInfo = mcInstance.getConnection().getPlayerInfo(mcInstance.player.getUUID());
			if (playerInfo != null) {
				payload.addProperty("pingMs", playerInfo.getLatency());
			}
		}
		// 3D boss-room viewer: only while actively scanning, only newly discovered blocks since the last report.
		if (BossRoomScanner.isActive()) {
			payload.addProperty("bossRoomScanId", BossRoomScanner.getScanId());
			payload.add("bossRoomBlocks", BossRoomScanner.drainPendingJson());
			// Resent every report since it gets more accurate as more of the room is scanned.
			int[] anchor = BossRoomScanner.getAnchorOffset();
			if (anchor != null) {
				JsonObject anchorObj = new JsonObject();
				anchorObj.addProperty("dx", anchor[0]);
				anchorObj.addProperty("dy", anchor[1]);
				anchorObj.addProperty("dz", anchor[2]);
				payload.add("bossRoomAnchor", anchorObj);
			}
			Minecraft bossRoomClient = Minecraft.getInstance();
			BlockPos scanOrigin = BossRoomScanner.getOrigin();
			if (bossRoomClient.level != null && bossRoomClient.player != null && scanOrigin != null) {
				JsonArray bossRoomPlayersArr = new JsonArray();
				UUID selfId = bossRoomClient.player.getUUID();
				addBossRoomPlayer(bossRoomPlayersArr, bossRoomClient.player.getGameProfile().name(), bossRoomClient.player, scanOrigin);
				for (net.minecraft.client.player.AbstractClientPlayer other : bossRoomClient.level.players()) {
					UUID id = other.getUUID();
					if (id.equals(selfId) || !DungeonRunTracker.getEffectiveRoster().contains(id)) {
						continue;
					}
					PartyHudManager.MemberInfo info = PartyHudManager.getMembers().get(id);
					if (info == null) {
						continue;
					}
					addBossRoomPlayer(bossRoomPlayersArr, info.username(), other, scanOrigin);
				}
				payload.add("bossRoomPlayers", bossRoomPlayersArr);
			}
		}
		// Grid position is room-units relative to the entrance, not raw map pixels or world coordinates.
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
		DungeonRoomTracker.MapGridMeta gridMeta = DungeonRoomTracker.getMapGridMeta();
		if (gridMeta != null) {
			payload.addProperty("mapEntranceX", gridMeta.entranceMapX());
			payload.addProperty("mapEntranceY", gridMeta.entranceMapY());
			payload.addProperty("mapRoomSize", gridMeta.roomSize());
		}
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
		// Mutual attestation: the backend only trusts a teammate's live data if both sides confirm seeing each other.
		JsonArray visibleArr = new JsonArray();
		for (UUID visible : DungeonRunTracker.getVisibleTeammates()) {
			visibleArr.add(visible.toString());
		}
		payload.add("visibleTeammates", visibleArr);
		// Reports positions for visible teammates who don't have SkyMelloo installed themselves.
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
		}
		return payload;
	}

	private static void addBossRoomPlayer(JsonArray array, String username, Player player, BlockPos origin) {
		JsonObject obj = new JsonObject();
		obj.addProperty("username", username);
		obj.addProperty("x", player.getX() - origin.getX());
		obj.addProperty("y", player.getY() - origin.getY());
		obj.addProperty("z", player.getZ() - origin.getZ());
		obj.addProperty("yaw", player.getYRot());
		obj.addProperty("pitch", player.getXRot());
		array.add(obj);
	}

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

	/** Ages out naturally as presence entries expire (~20s) once a teammate stops reporting. */
	public static Map<String, TeammateProgressView> getTeammateProgress() {
		Map<String, TeammateProgressView> result = new LinkedHashMap<>();
		teammateProgress.forEach((name, p) -> result.put(name, new TeammateProgressView(p.room(), p.found(), p.max(), p.details(), p.floor(), p.runActive(), p.score(), p.clearedPercent(), p.secretsPercentage(), p.deaths(), p.roster(), p.hasTimeLimit(), p.timeRemainingSeconds(), p.witherKeysObtained(), p.witherDoorsOpened(), p.bloodRoomEntered(), p.bloodRoomCleared(), p.bossRoomEntered(), p.bossRoomCleared())));
		return result;
	}

	public record TeammateProgressView(String room, int found, int max, List<SkyblockerBridge.SecretRow> details, String floor, boolean runActive, int score, double clearedPercent, double secretsPercentage, int deaths, List<String> roster, boolean hasTimeLimit, int timeRemainingSeconds, int witherKeysObtained, int witherDoorsOpened, boolean bloodRoomEntered, boolean bloodRoomCleared, boolean bossRoomEntered, boolean bossRoomCleared) {
	}
}
