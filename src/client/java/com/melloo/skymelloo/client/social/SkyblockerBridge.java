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

/** Optional soft integration with Skyblocker, read via reflection only if it's installed - no compile-time dependency, read-only. */
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
	private static Method getSegments;
	private static Method vector2icX;
	private static Method vector2icY;

	// secretWaypoints is a protected field, not public API - needs Field#setAccessible, kept optional/best-effort.
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
			// Room#clearState mirrors the in-game checkmark icon - fallback for puzzle rooms Hypixel doesn't re-announce on retry.
			clearStateField = roomClass.getField("clearState");
			getSegments = roomClass.getMethod("getSegments");
			Class<?> vector2icClass = Class.forName("org.joml.Vector2ic");
			vector2icX = vector2icClass.getMethod("x");
			vector2icY = vector2icClass.getMethod("y");
			available = true;
			DebugLog.log(DebugLog.Category.DUNGEON, "Skyblocker detected - reading its room-secrets/score data");
		} catch (ReflectiveOperationException | LinkageError e) {
			// Not installed, or a future version renamed/removed a method - report unavailable, don't crash.
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

	/** Secrets found/total for the local player's current room. {@code null} if unmatched or Skyblocker isn't installed. */
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

	/** Grid-cell corners for the current room's full shape - unlike our map-color read, correct even for cells not yet revealed. */
	public static List<int[]> getCurrentRoomSegments() {
		if (!isAvailable()) {
			return null;
		}
		try {
			Object room = getCurrentRoom.invoke(null);
			if (room == null || !(boolean) isMatched.invoke(room)) {
				return null;
			}
			Collection<?> segments = (Collection<?>) getSegments.invoke(room);
			List<int[]> result = new ArrayList<>();
			for (Object segment : segments) {
				result.add(new int[]{(int) vector2icX.invoke(segment), (int) vector2icY.invoke(segment)});
			}
			return result;
		} catch (ReflectiveOperationException | ClassCastException e) {
			return null;
		}
	}

	/** Confirmed room type, slower but more authoritative than {@link DungeonRoomTracker}'s map-color read. Names match {@link DungeonRoomTracker.RoomType}. */
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
			// .name() not .toString() - the enum constant identifier is guaranteed stable.
			return type instanceof Enum<?> enumType ? enumType.name() : null;
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	/** Real in-game checkmark state: GREEN_CHECKED/WHITE_CHECKED (completed, WHITE = with a recorded fail)/FAILED/UNCLEARED. */
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

	/** Skyblocker's own live score - preferred over {@link DungeonRunTracker#calculateScore()} when available. */
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

	/** One row per distinct secret index (multiple waypoint spots sharing an index collapse to one row). See {@link #ensureSecretDetailChecked}. */
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
