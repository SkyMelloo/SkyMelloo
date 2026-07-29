package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.util.DebugLog;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional soft integration with the Skyblocker mod (LGPL-3.0-or-later, github.com/SkyblockerMod/
 * Skyblocker), read via reflection only if it happens to also be installed - Skyblocker maintains
 * its own large database of catalogued dungeon room layouts (hundreds of variants) with the exact
 * position of every secret in each one, matched live against the room the player is standing in.
 * That database is a huge, separately-maintained dataset well beyond anything reasonable to port or
 * rebuild here, so this reads Skyblocker's own already-computed result instead of reimplementing it.
 * <p>
 * No compile-time dependency on Skyblocker at all - SkyMelloo builds and runs identically whether or
 * not Skyblocker is present, this class just reports "unavailable" if it isn't. Only ever reads
 * Skyblocker's state (current room's matched secret count), never calls anything that could affect
 * Skyblocker's own behavior. Like the local player's own {@link DungeonRoomTracker}, this only ever
 * reflects the LOCAL player's current room - Skyblocker doesn't know a teammate's room either unless
 * that teammate is also running Skyblocker AND its (separate, opt-in) websocket sync is connected.
 */
public final class SkyblockerBridge {
	private static final String DUNGEON_MANAGER_CLASS = "de.hysky.skyblocker.skyblock.dungeon.secrets.DungeonManager";
	private static final String ROOM_CLASS = "de.hysky.skyblocker.skyblock.dungeon.secrets.Room";
	private static final String DUNGEON_SCORE_CLASS = "de.hysky.skyblocker.skyblock.dungeon.DungeonScore";

	private static boolean checked = false;
	private static boolean available = false;
	private static Method getCurrentRoom;
	private static Method isMatched;
	private static Method getName;
	private static Method getType;
	private static Method getMaxSecretCount;
	private static Method getFoundSecretCount;
	private static Method getScore;
	private static Method isDungeonStarted;
	private static Field clearStateField;

	// Deeper, more fragile reflection than the rest of this class - secretWaypoints is a PROTECTED
	// field (a Guava Table<Integer, BlockPos, SecretWaypoint>), not part of Room's public API, so
	// this needs Field#setAccessible rather than a plain getMethod lookup. Kept optional/best-effort:
	// if this specific piece breaks on a future Skyblocker update, per-secret rows just report
	// unavailable while everything else in this class (score, room name/type, aggregate counts,
	// which only use public methods) keeps working.
	private static boolean secretDetailChecked = false;
	private static boolean secretDetailAvailable = false;
	private static Field secretWaypointsField;
	private static Field secretIndexField;
	private static Method isEnabled;

	private SkyblockerBridge() {
	}

	private static void ensureChecked() {
		if (checked) {
			return;
		}
		checked = true;
		try {
			if (!FabricLoader.getInstance().isModLoaded("skyblocker")) {
				return;
			}
			Class<?> dungeonManagerClass = Class.forName(DUNGEON_MANAGER_CLASS);
			Class<?> roomClass = Class.forName(ROOM_CLASS);
			Class<?> dungeonScoreClass = Class.forName(DUNGEON_SCORE_CLASS);
			getCurrentRoom = dungeonManagerClass.getMethod("getCurrentRoom");
			isMatched = roomClass.getMethod("isMatched");
			getName = roomClass.getMethod("getName");
			getType = roomClass.getMethod("getType");
			getMaxSecretCount = roomClass.getMethod("getMaxSecretCount");
			getFoundSecretCount = roomClass.getMethod("getFoundSecretCount");
			getScore = dungeonScoreClass.getMethod("getScore");
			isDungeonStarted = dungeonScoreClass.getMethod("isDungeonStarted");
			// Public field, not a method - Room#clearState directly mirrors the real in-game dungeon-map
			// checkmark icon colour (GREEN_CHECKED/WHITE_CHECKED/FAILED/UNCLEARED, see Room$ClearState,
			// javap-confirmed), which Hypixel keeps accurate the moment a room truly completes regardless
			// of whether it also sends a chat line for it - used as a fallback signal for puzzle rooms that
			// get solved on a retry, since Hypixel doesn't always re-send "PUZZLE SOLVED!" for those.
			clearStateField = roomClass.getField("clearState");
			available = true;
			DebugLog.log(DebugLog.Category.DUNGEON, "Skyblocker detected - reading its room-secrets/score data");
		} catch (ReflectiveOperationException | LinkageError e) {
			// Skyblocker isn't installed, or a future version renamed/removed one of these methods -
			// either way, just report unavailable rather than crashing anything. Previously logged
			// NOTHING on this path at all - a real "score frozen at 120 the whole run" bug report
			// (2026-07-27) turned out to trace back to currentDisplayedScore() silently falling
			// through to our OWN (also broken, see DungeonTabList's doc comment) tab-list-based
			// calculation, and there was zero evidence anywhere to tell whether that was because
			// Skyblocker genuinely wasn't detected, or because it WAS detected and its own score just
			// wasn't preferred/available at the time - completely unverifiable after the fact.
			available = false;
			DebugLog.log(DebugLog.Category.DUNGEON, "Skyblocker NOT available (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ") - falling back to our own tab-list-based score/room tracking");
		}
	}

	private static void ensureSecretDetailChecked() {
		if (secretDetailChecked) {
			return;
		}
		secretDetailChecked = true;
		if (!isAvailable()) {
			return;
		}
		try {
			Class<?> roomClass = Class.forName(ROOM_CLASS);
			secretWaypointsField = roomClass.getDeclaredField("secretWaypoints");
			secretWaypointsField.setAccessible(true);
			Class<?> secretWaypointClass = Class.forName("de.hysky.skyblocker.skyblock.dungeon.secrets.SecretWaypoint");
			secretIndexField = secretWaypointClass.getField("secretIndex");
			isEnabled = secretWaypointClass.getMethod("isEnabled");
			secretDetailAvailable = true;
		} catch (ReflectiveOperationException | LinkageError | InaccessibleObjectException e) {
			secretDetailAvailable = false;
		}
	}

	/** Whether Skyblocker is installed and exposes the expected room/secrets API. */
	public static boolean isAvailable() {
		ensureChecked();
		return available;
	}

	public record RoomSecrets(String roomName, int found, int max) {
	}

	/**
	 * Secrets found/total for the room the LOCAL player is CURRENTLY in, per Skyblocker's own
	 * room-database match - {@code null} if Skyblocker isn't installed, or hasn't matched/identified
	 * the current room yet (e.g. just walked in, or an unrecognized/new room layout).
	 */
	public static RoomSecrets getCurrentRoomSecrets() {
		if (!isAvailable()) {
			return null;
		}
		try {
			Object room = getCurrentRoom.invoke(null);
			if (room == null || !(boolean) isMatched.invoke(room)) {
				return null;
			}
			String name = cleanRoomName((String) getName.invoke(room));
			int max = (int) getMaxSecretCount.invoke(room);
			int found = (int) getFoundSecretCount.invoke(room);
			return new RoomSecrets(name, found, max);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	/**
	 * The CONFIRMED room type for the LOCAL player's current room, per Skyblocker's own shape/door
	 * match against its room database - {@code null} if Skyblocker isn't installed or hasn't matched
	 * the room yet. Unlike our own {@link DungeonRoomTracker}'s single-pixel map-color read (available
	 * instantly, but can misfire right at a ROOM/TRAP color-brightness boundary), this only reports a
	 * type once Skyblocker is actually sure - it's the authoritative answer once present, just slower
	 * to arrive. The enum constant name (e.g. "PUZZLE") matches {@link DungeonRoomTracker.RoomType}'s
	 * own names exactly, since both enumerate the same real Hypixel room categories.
	 */
	public static String getCurrentRoomTypeName() {
		if (!isAvailable()) {
			return null;
		}
		try {
			Object room = getCurrentRoom.invoke(null);
			if (room == null || !(boolean) isMatched.invoke(room)) {
				return null;
			}
			Object type = getType.invoke(room);
			// .name() (the enum constant identifier, e.g. "PUZZLE"), not .toString() - Type doesn't
			// override toString() today so they'd currently agree, but .name() is guaranteed stable.
			return type instanceof Enum<?> enumType ? enumType.name() : null;
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	/**
	 * The LOCAL player's current room's real in-game dungeon-map checkmark state - one of
	 * "GREEN_CHECKED", "WHITE_CHECKED" (both mean the room actually completed; WHITE specifically means
	 * completed with a fail recorded somewhere along the way, e.g. a puzzle that failed once but was then
	 * solved on retry), "FAILED", or "UNCLEARED" - {@code null} if Skyblocker isn't installed or hasn't
	 * matched the room yet. See {@link #getCurrentRoomSecrets()} for the equivalent secrets-count read.
	 */
	public static String getCurrentRoomClearState() {
		if (!isAvailable()) {
			return null;
		}
		try {
			Object room = getCurrentRoom.invoke(null);
			if (room == null || !(boolean) isMatched.invoke(room)) {
				return null;
			}
			Object clearState = clearStateField.get(room);
			return clearState instanceof Enum<?> enumState ? enumState.name() : null;
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	/** Skyblocker's internal room names carry a "-N" catalog variant suffix (e.g. "Altar-6") - stripped for display, since a player has no use for which cataloged variant it is, only the room's real name. */
	private static String cleanRoomName(String rawName) {
		return rawName != null ? rawName.replaceFirst("-\\d+$", "") : null;
	}

	/**
	 * Skyblocker's own live dungeon score total, calculated from the SAME sidebar/tab-list data our
	 * own {@link DungeonRunTracker#calculateScore()} uses - preferred over our estimate when available
	 * since Skyblocker's read has been battle-tested far longer. {@code null} if Skyblocker isn't
	 * installed or hasn't detected an active dungeon run.
	 */
	public static Integer getScore() {
		if (!isAvailable()) {
			return null;
		}
		try {
			if (!(boolean) isDungeonStarted.invoke(null)) {
				return null;
			}
			return (int) getScore.invoke(null);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	public record SecretRow(int secretIndex, boolean found) {
	}

	/**
	 * Per-secret found/missing state for the LOCAL player's current room, one row per distinct secret
	 * index (a secret can have multiple possible waypoint spots sharing one index - e.g. a chest that
	 * can spawn in one of a few positions - they always share the same found state, so only one row
	 * per index is returned). {@code null} if unavailable for any reason: Skyblocker not installed,
	 * current room not matched yet, or {@code secretWaypoints} - a non-public field on Skyblocker's
	 * side - isn't reachable (e.g. renamed in a future Skyblocker version). This is deeper, more
	 * fragile reflection than the rest of this class (bypassing a protected field, not just calling a
	 * public method) - see {@link #ensureSecretDetailChecked}.
	 */
	public static List<SecretRow> getCurrentRoomSecretDetails() {
		ensureSecretDetailChecked();
		if (!secretDetailAvailable) {
			return null;
		}
		try {
			Object room = getCurrentRoom.invoke(null);
			if (room == null || !(boolean) isMatched.invoke(room)) {
				return null;
			}
			Object table = secretWaypointsField.get(room);
			if (table == null) {
				return null;
			}
			Collection<?> waypoints = (Collection<?>) table.getClass().getMethod("values").invoke(table);
			// LinkedHashMap so rows come out in a stable, first-seen order rather than jumping around
			// as the underlying table's iteration order shifts between calls.
			Map<Integer, Boolean> foundByIndex = new LinkedHashMap<>();
			for (Object waypoint : waypoints) {
				int index = secretIndexField.getInt(waypoint);
				boolean enabled = (boolean) isEnabled.invoke(waypoint);
				foundByIndex.putIfAbsent(index, !enabled); // isEnabled() true = still missing, see Waypoint#setFound/#setMissing
			}
			List<SecretRow> rows = new ArrayList<>(foundByIndex.size());
			foundByIndex.forEach((index, found) -> rows.add(new SecretRow(index, found)));
			return rows;
		} catch (ReflectiveOperationException | ClassCastException e) {
			return null;
		}
	}
}
