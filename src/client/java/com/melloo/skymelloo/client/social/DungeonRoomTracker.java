package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.util.DebugLog;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

// Ported from Skyblocker's dungeon room detection.
// Converts world position to dungeon map pixels using fixed entrance anchors.
public final class DungeonRoomTracker {

	public enum RoomType {
		ENTRANCE, ROOM, PUZZLE, TRAP, MINIBOSS, FAIRY, BLOOD, UNKNOWN
	}

	private static byte colorFor(RoomType type) {
		return switch (type) {
			case ENTRANCE -> MapColor.PLANT.getPackedId(MapColor.Brightness.HIGH);
			case ROOM -> MapColor.COLOR_ORANGE.getPackedId(MapColor.Brightness.LOWEST);
			case PUZZLE -> MapColor.COLOR_MAGENTA.getPackedId(MapColor.Brightness.HIGH);
			case TRAP -> MapColor.COLOR_ORANGE.getPackedId(MapColor.Brightness.HIGH);
			case MINIBOSS -> MapColor.COLOR_YELLOW.getPackedId(MapColor.Brightness.HIGH);
			case FAIRY -> MapColor.COLOR_PINK.getPackedId(MapColor.Brightness.HIGH);
			case BLOOD -> MapColor.FIRE.getPackedId(MapColor.Brightness.HIGH);
			case UNKNOWN -> MapColor.COLOR_GRAY.getPackedId(MapColor.Brightness.NORMAL);
		};
	}

	// {x, z}, northwest corner of the entrance room
	private static int[] physicalEntrancePos = null;
	// {x, y}, top-left of the entrance's color block on the map
	private static int[] mapEntrancePos = null;
	private static int mapRoomSize = 0;

	// Ticks a room reading must persist before being acted on - avoids grid-boundary jitter.
	private static final int ROOM_CONFIRM_TICKS = 6;

	private static int[] currentPhysicalRoomPos = null;
	private static List<int[]> currentRoomConnectedCells = null;
	// Largest known dungeon room shape (2x2) - safety cap on the flood-fill below.
	private static final int MAX_CONNECTED_ROOM_CELLS = 4;
	private static int[] pendingPhysicalRoomPos = null;
	private static int pendingTicks = 0;
	private static boolean skyblockerConfirmedLogged = false;
	private static boolean skyblockerSegmentsApplied = false;
	private static int[] bloodRoomPhysicalPos = null;

	// "dx,dy" grid key -> {found, max}, for rooms actually visited this run.
	private static final Map<String, int[]> secretsPerRoom = new LinkedHashMap<>();
	private static final Map<String, String> roomNamesPerRoom = new LinkedHashMap<>();
	private static final Map<String, List<SkyblockerBridge.SecretRow>> secretDetailsPerRoom = new LinkedHashMap<>();

	private DungeonRoomTracker() {
	}

	public record MapGridMeta(int entranceMapX, int entranceMapY, int roomSize) {
	}

	/** Null until the anchors are found. */
	public static MapGridMeta getMapGridMeta() {
		if (mapEntrancePos == null || mapRoomSize == 0) {
			return null;
		}
		return new MapGridMeta(mapEntrancePos[0], mapEntrancePos[1], mapRoomSize);
	}

	/** Null if not yet located on the map this run. */
	public static int[] getBloodRoomPhysicalPos() {
		return bloodRoomPhysicalPos;
	}

	public record RoomInfo(int dx, int dy, RoomType type) {
	}

	/** Every room revealed on the shared dungeon map so far, as grid offsets from the entrance. */
	public static List<RoomInfo> getFullRoomLayout(Minecraft client) {
		List<RoomInfo> rooms = new ArrayList<>();
		if (mapEntrancePos == null || mapRoomSize == 0) {
			return rooms;
		}
		MapItemSavedData map = findDungeonMap(client);
		if (map == null) {
			return rooms;
		}
		int step = mapRoomSize + 4;
		int startX = Math.floorMod(mapEntrancePos[0], step);
		int startY = Math.floorMod(mapEntrancePos[1], step);
		for (int x = startX; x < 128; x += step) {
			int dx = (x - mapEntrancePos[0]) / step;
			for (int y = startY; y < 128; y += step) {
				int dy = (y - mapEntrancePos[1]) / step;
				RoomType type = getRoomType(map, x, y);
				if (type != null) {
					rooms.add(new RoomInfo(dx, dy, type));
				}
			}
		}
		return rooms;
	}

	public static int[] getCurrentRoomGridPos() {
		if (currentPhysicalRoomPos == null || physicalEntrancePos == null) {
			return null;
		}
		return new int[]{
				(currentPhysicalRoomPos[0] - physicalEntrancePos[0]) / 32,
				(currentPhysicalRoomPos[1] - physicalEntrancePos[1]) / 32,
		};
	}

	public record RoomSecretsEntry(int dx, int dy, int found, int max) {
	}

	public static List<RoomSecretsEntry> getSecretsLog() {
		List<RoomSecretsEntry> list = new ArrayList<>();
		secretsPerRoom.forEach((key, val) -> {
			String[] parts = key.split(",");
			list.add(new RoomSecretsEntry(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), val[0], val[1]));
		});
		return list;
	}

	public record RoomNameEntry(int dx, int dy, String name) {
	}

	/** Only available for visited rooms with Skyblocker installed; a room seen only on the map never gets a real name. */
	public static List<RoomNameEntry> getRoomNamesLog() {
		List<RoomNameEntry> list = new ArrayList<>();
		roomNamesPerRoom.forEach((key, name) -> {
			String[] parts = key.split(",");
			list.add(new RoomNameEntry(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), name));
		});
		return list;
	}

	/** Skipped if Skyblocker's confirmed type disagrees with our map-color read - it can briefly still describe the previous room. */
	private static void updateSecretsForCurrentRoom(Minecraft client) {
		int[] gridPos = getCurrentRoomGridPos();
		if (gridPos == null) {
			return;
		}
		SkyblockerBridge.RoomSecrets secrets = SkyblockerBridge.getCurrentRoomSecrets();
		if (secrets == null) {
			return;
		}
		String skyblockerType = SkyblockerBridge.getCurrentRoomTypeName();
		if (skyblockerType != null) {
			MapItemSavedData map = findDungeonMap(client);
			if (map != null) {
				int[] mapPos = getMapPosFromPhysical(physicalEntrancePos, mapEntrancePos, mapRoomSize, currentPhysicalRoomPos);
				RoomType ourType = getRoomType(map, mapPos[0], mapPos[1]);
				if (ourType == RoomType.PUZZLE && nearbyKnownMinibossName(client) != null) {
					ourType = RoomType.MINIBOSS;
				}
				if (ourType != null && !skyblockerType.equals(ourType.name())) {
					return;
				}
			}
		}
		String key = gridPos[0] + "," + gridPos[1];
		secretsPerRoom.put(key, new int[]{secrets.found(), secrets.max()});
		if (secrets.roomName() != null) {
			roomNamesPerRoom.put(key, secrets.roomName());
		}
		List<SkyblockerBridge.SecretRow> details = SkyblockerBridge.getCurrentRoomSecretDetails();
		if (details != null) {
			secretDetailsPerRoom.put(key, details);
		}
	}

	public record SecretDetailEntry(int dx, int dy, List<SkyblockerBridge.SecretRow> secrets) {
	}

	public static List<SecretDetailEntry> getSecretDetailsLog() {
		List<SecretDetailEntry> list = new ArrayList<>();
		secretDetailsPerRoom.forEach((key, details) -> {
			String[] parts = key.split(",");
			list.add(new SecretDetailEntry(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), details));
		});
		return list;
	}

	public record MapPixelData(String colorsBase64, int centerX, int centerZ, byte scale) {
	}

	/** Raw 128x128 map pixel data, base64-encoded, so the website can render doors/unexplored areas without separate tracking logic. */
	public static MapPixelData getRawMapData(Minecraft client) {
		MapItemSavedData map = findDungeonMap(client);
		if (map == null) {
			return null;
		}
		return new MapPixelData(Base64.getEncoder().encodeToString(map.colors), map.centerX, map.centerZ, map.scale);
	}

	public record ExactPosition(double mapX, double mapY, float yaw) {
	}

	/** Continuous (non-room-quantized) map-pixel position, on the same anchor system as room detection. */
	public static ExactPosition getExactPlayerMapPosition(Minecraft client) {
		if (client.player == null) {
			return null;
		}
		return getExactMapPositionFor(client, client.player.getX(), client.player.getZ(), client.player.getYRot());
	}

	/** Generalizes {@link #getExactPlayerMapPosition} to any visible entity, so a teammate without SkyMelloo installed can still show up. */
	public static ExactPosition getExactMapPositionFor(Minecraft client, double x, double z, float yaw) {
		if (physicalEntrancePos == null || mapEntrancePos == null || mapRoomSize == 0) {
			return null;
		}
		if (findDungeonMap(client) == null) {
			return null;
		}
		double roomSizeWithGap = mapRoomSize + 4;
		// physicalEntrancePos = 32*floor((mortX+8.5)/32) - 8; relaxing the floor() gives x + 0.5, not x - 8.
		double fractionalX = (x + 0.5 - physicalEntrancePos[0]) / 32.0;
		double fractionalZ = (z + 0.5 - physicalEntrancePos[1]) / 32.0;
		double mapX = fractionalX * roomSizeWithGap + mapEntrancePos[0];
		double mapY = fractionalZ * roomSizeWithGap + mapEntrancePos[1];
		return new ExactPosition(mapX, mapY, yaw);
	}

	public static void tick(Minecraft client) {
		if (!DungeonRunTracker.isRunActive()) {
			if (physicalEntrancePos != null || mapEntrancePos != null || mapRoomSize != 0 || currentPhysicalRoomPos != null) {
				reset();
			}
			return;
		}
		if (client.player == null || client.level == null) {
			return;
		}

		DungeonRunTracker.checkPuzzleClearedViaSkyblocker();

		if (physicalEntrancePos == null) {
			Vec3 mortPos = findMortPos(client);
			if (mortPos == null) {
				return;
			}
			physicalEntrancePos = getPhysicalRoomPos(mortPos.x(), mortPos.z());
			DebugLog.log(DebugLog.Category.DUNGEON, "Dungeon entrance (physical) found at " + physicalEntrancePos[0] + "," + physicalEntrancePos[1]);
		}

		MapItemSavedData map = findDungeonMap(client);
		if (map == null) {
			return;
		}

		if (mapEntrancePos == null || mapRoomSize == 0) {
			int[] entranceAndSize = getMapEntrancePosAndRoomSize(map);
			if (entranceAndSize == null) {
				return;
			}
			mapEntrancePos = new int[]{entranceAndSize[0], entranceAndSize[1]};
			mapRoomSize = entranceAndSize[2];
			DebugLog.log(DebugLog.Category.DUNGEON, "Dungeon entrance (map) found at " + mapEntrancePos[0] + "," + mapEntrancePos[1] + ", room size " + mapRoomSize);
		}

		if (bloodRoomPhysicalPos == null) {
			int[] bloodMapPos = findBloodRoomMapPos(map);
			if (bloodMapPos != null) {
				bloodRoomPhysicalPos = getPhysicalPosFromMap(mapEntrancePos, mapRoomSize, physicalEntrancePos, bloodMapPos);
				DebugLog.log(DebugLog.Category.DUNGEON, "Blood Room located at physical " + bloodRoomPhysicalPos[0] + "," + bloodRoomPhysicalPos[1]);
			}
		}

		Vec3 playerPos = client.player.position();
		int[] physicalRoomPos = getPhysicalRoomPos(playerPos.x(), playerPos.z());
		if (currentPhysicalRoomPos != null && currentPhysicalRoomPos[0] == physicalRoomPos[0] && currentPhysicalRoomPos[1] == physicalRoomPos[1]) {
			pendingTicks = 0;
			logSkyblockerConfirmationIfNewlyAvailable();
			updateSecretsForCurrentRoom(client);
			return;
		}

		if (pendingPhysicalRoomPos != null && pendingPhysicalRoomPos[0] == physicalRoomPos[0] && pendingPhysicalRoomPos[1] == physicalRoomPos[1]) {
			pendingTicks++;
		} else {
			pendingPhysicalRoomPos = physicalRoomPos;
			pendingTicks = 1;
		}
		if (pendingTicks < ROOM_CONFIRM_TICKS) {
			return;
		}
		currentPhysicalRoomPos = physicalRoomPos;
		currentRoomConnectedCells = findConnectedRoomCells(map, physicalRoomPos);
		skyblockerConfirmedLogged = false;
		skyblockerSegmentsApplied = false;
		updateSecretsForCurrentRoom(client);

		int[] mapPos = getMapPosFromPhysical(physicalEntrancePos, mapEntrancePos, mapRoomSize, physicalRoomPos);
		byte rawColor = getColor(map, mapPos[0], mapPos[1]);
		RoomType type = getRoomType(map, mapPos[0], mapPos[1]);
		if (type == null || type == RoomType.UNKNOWN) {
			return;
		}
		SkyblockerBridge.RoomSecrets roomSecrets = SkyblockerBridge.getCurrentRoomSecrets();
		String nameSuffix = roomSecrets != null && roomSecrets.roomName() != null ? " name=" + roomSecrets.roomName() : "";
		String confirmedTypeName = SkyblockerBridge.getCurrentRoomTypeName();
		RoomType effectiveType = type;
		String confirmSuffix = " (unconfirmed - instant map-color estimate)";
		if (confirmedTypeName != null) {
			skyblockerConfirmedLogged = true;
			try {
				effectiveType = RoomType.valueOf(confirmedTypeName);
				confirmSuffix = effectiveType == type ? " (confirmed via Skyblocker)" : " (CORRECTED via Skyblocker, map-color guessed " + type + ")";
			} catch (IllegalArgumentException ignored) {
			}
		}
		// A miniboss physically present here overrides a PUZZLE read from either source - avoids a
		// PENDING puzzle entry that can never resolve (no SOLVED/FAIL line for a miniboss kill).
		if (effectiveType == RoomType.PUZZLE) {
			String miniboss = nearbyKnownMinibossName(client);
			if (miniboss != null) {
				DebugLog.log(DebugLog.Category.DUNGEON, "Room read as PUZZLE but \"" + miniboss + "\" is nearby - correcting to MINIBOSS, not marking a puzzle.");
				effectiveType = RoomType.MINIBOSS;
				confirmSuffix = " (CORRECTED - miniboss \"" + miniboss + "\" detected nearby)";
			}
		}
		DebugLog.log(DebugLog.Category.DUNGEON, "Entered room at " + physicalRoomPos[0] + "," + physicalRoomPos[1] + " -> type=" + effectiveType + " (color=" + rawColor + ")" + nameSuffix + confirmSuffix);

		if (effectiveType == RoomType.PUZZLE) {
			DungeonRunTracker.markPuzzleRoomFound();
		}
	}

	// Custom-name strings Skyblocker's own miniboss-glow feature matches on.
	private static final Set<String> KNOWN_MINIBOSS_NAMES = Set.of("Lost Adventurer", "Shadow Assassin", "Diamond Guy");

	private static String nearbyKnownMinibossName(Minecraft client) {
		for (Entity entity : client.level.entitiesForRendering()) {
			if (entity.getCustomName() == null) {
				continue;
			}
			String name = entity.getCustomName().getString();
			if (KNOWN_MINIBOSS_NAMES.contains(name)) {
				return name;
			}
		}
		return null;
	}

	/** Once settled in a confirmed room, keeps checking whether Skyblocker's own shape/type match newly completes. */
	private static void logSkyblockerConfirmationIfNewlyAvailable() {
		if (currentPhysicalRoomPos == null) {
			return;
		}
		// Verified to contain currentPhysicalRoomPos - right after switching rooms, Skyblocker's match
		// can briefly still point at the previous one.
		if (!skyblockerSegmentsApplied) {
			List<int[]> segments = SkyblockerBridge.getCurrentRoomSegments();
			if (segments != null && containsCell(segments, currentPhysicalRoomPos)) {
				currentRoomConnectedCells = segments;
				skyblockerSegmentsApplied = true;
			}
		}
		if (skyblockerConfirmedLogged) {
			return;
		}
		String confirmedTypeName = SkyblockerBridge.getCurrentRoomTypeName();
		if (confirmedTypeName == null) {
			return;
		}
		skyblockerConfirmedLogged = true;
		SkyblockerBridge.RoomSecrets roomSecrets = SkyblockerBridge.getCurrentRoomSecrets();
		String nameSuffix = roomSecrets != null && roomSecrets.roomName() != null ? " name=" + roomSecrets.roomName() : "";
		DebugLog.log(DebugLog.Category.DUNGEON, "Room at " + currentPhysicalRoomPos[0] + "," + currentPhysicalRoomPos[1]
				+ " confirmed via Skyblocker -> type=" + confirmedTypeName + nameSuffix);
	}

	private static void reset() {
		physicalEntrancePos = null;
		mapEntrancePos = null;
		mapRoomSize = 0;
		currentPhysicalRoomPos = null;
		currentRoomConnectedCells = null;
		skyblockerSegmentsApplied = false;
		pendingPhysicalRoomPos = null;
		pendingTicks = 0;
		bloodRoomPhysicalPos = null;
		secretsPerRoom.clear();
		roomNamesPerRoom.clear();
		secretDetailsPerRoom.clear();
	}

	/** Room type at an arbitrary position, so {@link com.melloo.skymelloo.client.party.PartyHud} can show other members' rooms too. */
	public static RoomType getRoomTypeAt(Minecraft client, double x, double z) {
		if (physicalEntrancePos == null || mapEntrancePos == null || mapRoomSize == 0) {
			return null;
		}
		MapItemSavedData map = findDungeonMap(client);
		if (map == null) {
			return null;
		}
		int[] roomPos = getPhysicalRoomPos(x, z);
		int[] mapPos = getMapPosFromPhysical(physicalEntrancePos, mapEntrancePos, mapRoomSize, roomPos);
		RoomType mapColorType = getRoomType(map, mapPos[0], mapPos[1]);

		// Skyblocker's confirmed match is only for the local player's own current room.
		if (currentPhysicalRoomPos != null && currentPhysicalRoomPos[0] == roomPos[0] && currentPhysicalRoomPos[1] == roomPos[1]) {
			String confirmedTypeName = SkyblockerBridge.getCurrentRoomTypeName();
			if (confirmedTypeName != null) {
				try {
					return RoomType.valueOf(confirmedTypeName);
				} catch (IllegalArgumentException ignored) {
				}
			}
		}
		return mapColorType;
	}

	/** X/Z bounds are exact; Y is only an approximation since room ceiling height isn't exposed anywhere. */
	public static AABB getCurrentRoomBounds(double referenceY, double verticalMargin) {
		if (currentPhysicalRoomPos == null) {
			return null;
		}
		List<int[]> cells = currentRoomConnectedCells != null ? currentRoomConnectedCells : List.of(currentPhysicalRoomPos);
		double minX = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
		for (int[] cell : cells) {
			minX = Math.min(minX, cell[0]);
			minZ = Math.min(minZ, cell[1]);
			maxX = Math.max(maxX, cell[0] + 32);
			maxZ = Math.max(maxZ, cell[1] + 32);
		}
		return new AABB(minX, referenceY - verticalMargin, minZ, maxX, referenceY + verticalMargin, maxZ);
	}

	/** Flood-fills adjacent cells sharing {@code startPos}'s room type, so a multi-cell room counts as one. */
	private static List<int[]> findConnectedRoomCells(MapItemSavedData map, int[] startPos) {
		List<int[]> result = new ArrayList<>();
		result.add(startPos);
		int[] startMapPos = getMapPosFromPhysical(physicalEntrancePos, mapEntrancePos, mapRoomSize, startPos);
		RoomType startType = getRoomType(map, startMapPos[0], startMapPos[1]);
		if (startType == null) {
			return result;
		}
		Set<Long> visited = new HashSet<>();
		visited.add(packKey(startPos));
		Queue<int[]> queue = new ArrayDeque<>();
		queue.add(startPos);
		while (!queue.isEmpty() && result.size() < MAX_CONNECTED_ROOM_CELLS) {
			int[] pos = queue.poll();
			int[][] neighbors = {
					{pos[0] - 32, pos[1]}, {pos[0] + 32, pos[1]},
					{pos[0], pos[1] - 32}, {pos[0], pos[1] + 32},
			};
			for (int[] neighbor : neighbors) {
				if (result.size() >= MAX_CONNECTED_ROOM_CELLS || !visited.add(packKey(neighbor))) {
					continue;
				}
				int[] neighborMapPos = getMapPosFromPhysical(physicalEntrancePos, mapEntrancePos, mapRoomSize, neighbor);
				if (getRoomType(map, neighborMapPos[0], neighborMapPos[1]) == startType) {
					result.add(neighbor);
					queue.add(neighbor);
				}
			}
		}
		return result;
	}

	/** Scans the map grid for a Blood-Room-colored pixel, since the local player may never visit it directly. */
	private static int[] findBloodRoomMapPos(MapItemSavedData map) {
		int step = mapRoomSize + 4;
		int startX = Math.floorMod(mapEntrancePos[0], step);
		int startY = Math.floorMod(mapEntrancePos[1], step);
		for (int x = startX; x < 128; x += step) {
			for (int y = startY; y < 128; y += step) {
				if (getRoomType(map, x, y) == RoomType.BLOOD) {
					return new int[]{x, y};
				}
			}
		}
		return null;
	}

	/** Inverse of {@link #getMapPosFromPhysical}. */
	private static int[] getPhysicalPosFromMap(int[] mapEntrancePos, int mapRoomSize, int[] physicalEntrancePos, int[] mapPos) {
		int dx = (mapPos[0] - mapEntrancePos[0]) / (mapRoomSize + 4);
		int dy = (mapPos[1] - mapEntrancePos[1]) / (mapRoomSize + 4);
		return new int[]{dx * 32 + physicalEntrancePos[0], dy * 32 + physicalEntrancePos[1]};
	}

	/** Northwest corner of the 32x32 room grid cell containing (x, z); +8.5 centers room borders, +/-8 is Hypixel's grid offset. */
	private static int[] getPhysicalRoomPos(double x, double z) {
		int px = (int) (x + 8.5);
		int pz = (int) (z + 8.5);
		px -= Math.floorMod(px, 32) + 8;
		pz -= Math.floorMod(pz, 32) + 8;
		return new int[]{px, pz};
	}

	private static int[] getMapPosFromPhysical(int[] physicalEntrancePos, int[] mapEntrancePos, int mapRoomSize, int[] physicalPos) {
		int dx = (physicalPos[0] - physicalEntrancePos[0]) / 32;
		int dz = (physicalPos[1] - physicalEntrancePos[1]) / 32;
		int mapRoomSizeWithGap = mapRoomSize + 4;
		return new int[]{dx * mapRoomSizeWithGap + mapEntrancePos[0], dz * mapRoomSizeWithGap + mapEntrancePos[1]};
	}

	/** BFS outward from the player's map marker in 10px steps until an entrance-colored pixel is found. */
	private static int[] getMapEntrancePosAndRoomSize(MapItemSavedData map) {
		int[] start = getMapPlayerPos(map);
		if (start == null) {
			return null;
		}
		Queue<int[]> posToCheck = new ArrayDeque<>();
		Set<Long> checked = new HashSet<>();
		posToCheck.add(start);
		checked.add(packKey(start));

		int[] pos;
		while ((pos = posToCheck.poll()) != null) {
			if (isEntranceColor(map, pos[0], pos[1])) {
				int[] entranceAndSize = getMapEntrancePosAndRoomSizeAt(map, pos);
				if (entranceAndSize[2] > 0) {
					return entranceAndSize;
				}
			}
			int[][] neighbors = {
					{pos[0] - 10, pos[1]},
					{pos[0], pos[1] - 10},
					{pos[0] + 10, pos[1]},
					{pos[0], pos[1] + 10},
			};
			for (int[] neighbor : neighbors) {
				if (checked.add(packKey(neighbor))) {
					posToCheck.add(neighbor);
				}
			}
		}
		return null;
	}

	private static long packKey(int[] pos) {
		return (((long) pos[0]) << 32) | (pos[1] & 0xFFFFFFFFL);
	}

	private static boolean containsCell(List<int[]> cells, int[] target) {
		for (int[] cell : cells) {
			if (cell[0] == target[0] && cell[1] == target[1]) {
				return true;
			}
		}
		return false;
	}

	private static int[] getMapEntrancePosAndRoomSizeAt(MapItemSavedData map, int[] mapPos) {
		int x = mapPos[0];
		int y = mapPos[1];
		while (isEntranceColor(map, x - 1, y)) {
			x -= 1;
		}
		while (isEntranceColor(map, x, y - 1)) {
			y -= 1;
		}
		return new int[]{x, y, getMapRoomSize(map, x, y)};
	}

	/** Below 6 pixels wide is treated as noise, not a real room. */
	private static int getMapRoomSize(MapItemSavedData map, int entranceX, int entranceY) {
		int size = 0;
		while (isEntranceColor(map, entranceX + size, entranceY)) {
			size++;
		}
		return size > 5 ? size : 0;
	}

	private static boolean isEntranceColor(MapItemSavedData map, int x, int y) {
		return getColor(map, x, y) == colorFor(RoomType.ENTRANCE);
	}

	private static RoomType getRoomType(MapItemSavedData map, int x, int y) {
		byte color = getColor(map, x, y);
		for (RoomType type : RoomType.values()) {
			if (colorFor(type) == color) {
				return type;
			}
		}
		return null;
	}

	private static byte getColor(MapItemSavedData map, int x, int y) {
		if (x < 0 || y < 0 || x >= 128 || y >= 128) {
			return -1;
		}
		return map.colors[x + (y << 7)];
	}

	/** The player marker is the map's FRAME decoration, at half-resolution offset from map center. */
	private static int[] getMapPlayerPos(MapItemSavedData map) {
		for (MapDecoration decoration : map.getDecorations()) {
			if (decoration.type().value().equals(MapDecorationTypes.FRAME.value())) {
				return new int[]{(decoration.x() >> 1) + 64, (decoration.y() >> 1) + 64};
			}
		}
		return null;
	}

	private static Vec3 findMortPos(Minecraft client) {
		for (Entity entity : client.level.entitiesForRendering()) {
			if (entity instanceof ArmorStand && entity.getCustomName() != null && entity.getCustomName().getString().contains("Mort")) {
				return entity.position();
			}
		}
		return null;
	}

	private static MapItemSavedData findDungeonMap(Minecraft client) {
		Inventory inventory = client.player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.getItem() instanceof MapItem) {
				MapItemSavedData data = MapItem.getSavedData(stack, client.level);
				if (data != null) {
					return data;
				}
			}
		}
		return null;
	}
}
