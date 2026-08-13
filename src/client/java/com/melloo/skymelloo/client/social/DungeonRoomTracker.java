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

/**
 * Faithful port of SkyblockerMod/Skyblocker's dungeon room-type detection (GitHub, LGPL-3.0,
 * {@code src/main/java/de/hysky/skyblocker/skyblock/dungeon/secrets/DungeonManager.java}'s
 * {@code update()} method plus the {@code DungeonMapUtils} helpers it calls - fetched and ported
 * directly from their source, not reconstructed from memory, after an earlier version of this file
 * used a much cruder "sample the pixel under the player's map marker" shortcut).
 * <p>
 * The real algorithm anchors everything to two fixed reference points found once per run, then does
 * pure integer math from the player's own (exact, non-jittery) world position every tick after that -
 * no per-tick pixel-sampling/debounce hacks needed at all:
 * <ol>
 *     <li>{@link #physicalEntrancePos} - the entrance room's northwest corner in the world, derived
 *     from the "Mort" NPC's (an {@link ArmorStand}) position via the room-grid math in
 *     {@link #getPhysicalRoomPos}. Dungeons are aligned to a 32x32-block grid, so this is exact.</li>
 *     <li>{@link #mapEntrancePos} / {@link #mapRoomSize} - the same entrance room's position and size
 *     in dungeon-MAP pixels, found by an outward search from the player's map marker for the
 *     entrance's distinct green color ({@link #getMapEntrancePosAndRoomSize}).</li>
 * </ol>
 * From there, every tick: the player's current physical room corner ({@link #getPhysicalRoomPos}) is
 * converted straight to a map position via {@link #getMapPosFromPhysical} using the two anchors above,
 * and the pixel color there is looked up against each {@link RoomType}'s known map color
 * ({@link #getRoomType}) - Puzzle rooms specifically render as {@link MapColor#COLOR_MAGENTA} at
 * {@link MapColor.Brightness#HIGH}. A "new room" is simply the physical room corner changing, which is
 * block-precise and has no edge-pixel ambiguity.
 * <p>
 * Not ported: Skyblocker's room NAME/secret-count matching (their {@code Room}/skeleton-database
 * system) - that's a separate, much larger subsystem this project has no need for; only room TYPE
 * (specifically: is it a Puzzle room) is used here. All Minecraft APIs below (MapItem.getSavedData,
 * MapItemSavedData.colors/getDecorations, MapDecoration, MapDecorationTypes.FRAME, MapColor field/
 * getPackedId, ClientLevel.entitiesForRendering, Entity.getCustomName/position) are confirmed via
 * javap against this exact game version.
 */
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

	// Anchors, found once per run and cached (recomputing every tick would be wasteful and pointless
	// - both are fixed for the whole dungeon instance).
	private static int[] physicalEntrancePos = null; // {x, z}, northwest corner of the entrance room
	private static int[] mapEntrancePos = null; // {x, y}, top-left of the entrance's color block on the map
	private static int mapRoomSize = 0; // 0 = not yet found

	// A room reading has to persist for this many consecutive ticks before being acted on - confirmed
	// directly from a real log: standing near an EXACT 32-block room-grid boundary can make the
	// computed room corner flip back and forth between two adjacent rooms from normal movement/knockback
	// jitter (two different rooms logged within the same rendered second, then the same two again
	// ~12s later). Physical-position math is still exact, this just waits for it to settle.
	private static final int ROOM_CONFIRM_TICKS = 6;

	private static int[] currentPhysicalRoomPos = null; // northwest corner of the room we last reported
	// Every grid cell belonging to the same physical room as currentPhysicalRoomPos - most rooms are
	// one cell, multi-cell shapes (1x2, 2x1, 2x2, L-shaped) span several. Recomputed only in tick().
	private static List<int[]> currentRoomConnectedCells = null;
	// Largest known real dungeon room shape (2x2) - a safety cap so the flood-fill below can never
	// chain arbitrarily far even in a pathological case.
	private static final int MAX_CONNECTED_ROOM_CELLS = 4;
	private static int[] pendingPhysicalRoomPos = null;
	private static int pendingTicks = 0;
	// Whether the Skyblocker-confirmed type for the CURRENT room has already been logged - reset
	// whenever currentPhysicalRoomPos changes. Skyblocker's own shape/door match against its room
	// database usually takes a little longer than our own instant map-color read, so this keeps
	// checking (cheaply, once already back in a confirmed room - see tick()) until it lands.
	private static boolean skyblockerConfirmedLogged = false;
	// Same reset/recheck pattern as skyblockerConfirmedLogged, for currentRoomConnectedCells.
	private static boolean skyblockerSegmentsApplied = false;
	// Cached once found by scanning the MAP itself (not by the local player having to physically
	// visit it) - see findBloodRoomMapPos(). Exposed to DungeonRunTracker so its boss-room-portal
	// scan can center on the actual Blood Room instead of wherever the local player happens to be,
	// since the portal only appears once THAT room's mobs are cleared, which can happen while the
	// local player is elsewhere on the floor.
	private static int[] bloodRoomPhysicalPos = null;

	// "dx,dy" grid key -> {found, max} - the last-known secrets progress for every room actually
	// visited this run (not the full layout below, which includes rooms revealed on the map but
	// never physically entered - Skyblocker only ever has secrets data for a room the local player
	// has actually been in). LinkedHashMap so getSecretsLog() naturally lists rooms in visit order.
	private static final Map<String, int[]> secretsPerRoom = new LinkedHashMap<>();
	// Same keying/visited-only constraint as secretsPerRoom, populated in the same place - see
	// getRoomNamesLog().
	private static final Map<String, String> roomNamesPerRoom = new LinkedHashMap<>();
	// Same again, but per-secret found/missing detail instead of just a found/max count - "man kann
	// sehen welche secrets genau fehlen" (not just current room, every visited room). See
	// getSecretDetailsLog().
	private static final Map<String, List<SkyblockerBridge.SecretRow>> secretDetailsPerRoom = new LinkedHashMap<>();

	private DungeonRoomTracker() {
	}

	public record MapGridMeta(int entranceMapX, int entranceMapY, int roomSize) {
	}

	/** The map-pixel anchor + per-room pixel size needed to convert any {@link RoomInfo}'s {@code dx,dy} grid offset into actual pixel bounds on the map - lets the website draw real room boundaries/labels instead of just a coarse grid, and know exactly where to place hover targets. {@code null} until the anchors are found. */
	public static MapGridMeta getMapGridMeta() {
		if (mapEntrancePos == null || mapRoomSize == 0) {
			return null;
		}
		return new MapGridMeta(mapEntrancePos[0], mapEntrancePos[1], mapRoomSize);
	}

	/** Physical (northwest-corner) position of the Blood Room, or {@code null} if not yet located on the map this run. */
	public static int[] getBloodRoomPhysicalPos() {
		return bloodRoomPhysicalPos;
	}

	public record RoomInfo(int dx, int dy, RoomType type) {
	}

	/**
	 * Every room revealed on the map so far this run, as {@code {dx, dy}} grid offsets from the
	 * entrance (room units, not raw map pixels) plus its {@link RoomType} - a real floor-plan, not
	 * just "which room am I in". Generalizes {@link #findBloodRoomMapPos}'s single-type scan to every
	 * type at once. A room appears here once ANY party member has revealed it on the shared dungeon
	 * map (Hypixel reveals map colors account-wide per party, not per-player), so this can include
	 * rooms the local player has never personally set foot in.
	 */
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

	/** Local player's current confirmed room as a {@code {dx, dy}} grid offset from the entrance (room units), or {@code null} if no room is confirmed yet (still in the entrance/corridor, or anchors not found yet). */
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

	/** Every room the local player has actually visited this run with known secrets progress, in first-visit order - see {@link #secretsPerRoom}. */
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

	/**
	 * Skyblocker's real catalogued room name (e.g. "Precision Mining", "Bowler") for every room the
	 * local player has actually visited this run, not just its generic room type. Only
	 * available for VISITED rooms with Skyblocker installed and matched, same constraint as
	 * {@link #secretsPerRoom} - Skyblocker's room database is huge and separately maintained (see
	 * SkyblockerBridge's own doc comment on why that's not reimplemented here), so a room revealed on
	 * the map but never entered only ever gets the coarse {@link RoomType} from the pixel color, never
	 * a real name.
	 */
	public static List<RoomNameEntry> getRoomNamesLog() {
		List<RoomNameEntry> list = new ArrayList<>();
		roomNamesPerRoom.forEach((key, name) -> {
			String[] parts = key.split(",");
			list.add(new RoomNameEntry(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), name));
		});
		return list;
	}

	/**
	 * Snapshots Skyblocker's current-room secrets progress AND real room name (one reflection call
	 * covers both) into {@link #secretsPerRoom}/{@link #roomNamesPerRoom}, keyed by the current
	 * confirmed room's grid position - called every tick a room is confirmed so the log always
	 * reflects the latest state, not just a one-time snapshot from the moment the room was entered.
	 * <p>
	 * Cross-checked against Skyblocker's own CONFIRMED type for "current room"
	 * ({@link SkyblockerBridge#getCurrentRoomTypeName}) before trusting its name/secrets at all -
	 * Skyblocker tracks "current room" via its own independent shape-match, on its own timing, and
	 * can briefly still be describing the PREVIOUS room right after our own (ROOM_CONFIRM_TICKS-
	 * gated) physical-grid tracking has already moved to a new one. Confirmed as a real, reproducible
	 * bug from a live report/screenshot: one room's name ("draw-bridge") kept getting
	 * written onto several unrelated rooms (including an actual Puzzle room) as the player walked
	 * around, because Skyblocker just hadn't caught up internally yet. If Skyblocker's confirmed type
	 * disagrees with our own independent map-color read for the SAME physical cell, it's still
	 * describing a different (stale) room, so the write is skipped entirely - a room with no name yet
	 * just falls back to its generic type label on the map (correct and harmless), unlike a
	 * confidently WRONG one.
	 */
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
				// Same PUZZLE-color-but-really-MINIBOSS correction tick() applies (see its own comment) -
				// without this, a real Miniboss room that happens to render with Hypixel's Puzzle color
				// would permanently disagree with Skyblocker's (correct) MINIBOSS answer and never get a
				// name at all.
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

	/** Per-secret found/missing breakdown (not just a found/max count) for every room the local player has actually visited this run, not just the current room. {@code null}/absent for a room if Skyblocker's deeper per-secret reflection (see SkyblockerBridge#getCurrentRoomSecretDetails) wasn't available while that room was current. */
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

	/**
	 * The dungeon map's raw 128x128 pixel data (Minecraft's own {@code MapColor} byte format,
	 * base64-encoded) plus the coordinate metadata needed to convert any world (x, z) to a pixel
	 * position, one pixel per block, matching the in-game map exactly. Sending the actual pixel grid instead
	 * of a hand-reconstructed room-by-room summary means rooms, doors, AND unexplored (black) areas
	 * all render correctly for free, exactly matching what the real in-game map looks like, with no
	 * separate door/unexplored-tracking logic needed on either end. {@code null} if no dungeon map is
	 * currently held.
	 */
	public static MapPixelData getRawMapData(Minecraft client) {
		MapItemSavedData map = findDungeonMap(client);
		if (map == null) {
			return null;
		}
		return new MapPixelData(Base64.getEncoder().encodeToString(map.colors), map.centerX, map.centerZ, map.scale);
	}

	public record ExactPosition(double mapX, double mapY, float yaw) {
	}

	/**
	 * The local player's EXACT position converted to continuous map-pixel space (not the room-
	 * quantized grid used elsewhere in this class) plus their look direction - "genau koordinaten und
	 * richtung in die die gucken wie ingame".
	 * <p>
	 * Previously used {@code MapItemSavedData}'s own {@code centerX}/{@code centerZ}/{@code scale}
	 * fields directly (the formula itself is correct - verified three separate ways against this
	 * exact game version's bytecode) - but reported wrong regardless ("bin im grünen [Entrance] Raum
	 * aber auf website werde ich in braunen [normal ROOM] Raum angezeigt"), meaning centerX/centerZ
	 * don't actually correspond to the dungeon's own layout the way assumed (most likely: wherever the
	 * map happened to be centered when first created/received, not anchored to the dungeon grid at
	 * all). Rebuilt on the SAME anchor system {@link #getMapPosFromPhysical} already uses for room
	 * TYPE detection - which reports correctly - just extended to continuous (non-room-quantized)
	 * precision instead of only whole room corners, so standing exactly at a known point (like the
	 * Entrance) is now guaranteed to land on that same point's real pixel position by construction.
	 * {@code null} until the anchors are found (same requirement every other anchor-based method here
	 * already has) or no dungeon map is currently held.
	 */
	public static ExactPosition getExactPlayerMapPosition(Minecraft client) {
		if (client.player == null) {
			return null;
		}
		return getExactMapPositionFor(client, client.player.getX(), client.player.getZ(), client.player.getYRot());
	}

	/**
	 * Same math as {@link #getExactPlayerMapPosition}, generalized to an arbitrary world (x, z, yaw)
	 * instead of always the local player's own - the client already knows every visible player's real
	 * position (that's how their entity renders at all, same technique {@link DungeonRunTracker}'s own
	 * boss-room-entry detection already relies on), so this lets ONE SkyMelloo client report exact
	 * positions for its WHOLE visible party, not just itself: a teammate
	 * doesn't need their OWN SkyMelloo install for their position to show up on the map, as long as at
	 * least one other party member's client can see them.
	 */
	public static ExactPosition getExactMapPositionFor(Minecraft client, double x, double z, float yaw) {
		if (physicalEntrancePos == null || mapEntrancePos == null || mapRoomSize == 0) {
			return null;
		}
		if (findDungeonMap(client) == null) {
			return null;
		}
		double roomSizeWithGap = mapRoomSize + 4;
		// physicalEntrancePos is getPhysicalRoomPos(mortX)'s OUTPUT, i.e. 32*floor((mortX+8.5)/32) - 8
		// - not mortX itself. The first fix here (subtracting a flat 8) mismodeled that: it treated
		// the "+8.5" as pure rounding noise to discard, but algebraically it survives the floor
		// removal - continuous-relaxing 32*floor((x+8.5)/32) - 8 by dropping just the floor() gives
		// (x+8.5) - 8 = x + 0.5, not x - 8. That 8.5-block error (~0.27 of a room) is exactly the
		// small residual offset seen after the first fix attempt - real progress
		// (no longer a different room entirely), just the wrong constant.
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

		// Checked every tick (cheap - no-ops immediately unless a puzzle is PENDING/FAILED) rather than
		// only on room entry, since the whole point is catching a retry-solve that happens well after the
		// room was first entered and may never get a fresh chat line at all.
		DungeonRunTracker.checkPuzzleClearedViaSkyblocker();

		if (physicalEntrancePos == null) {
			Vec3 mortPos = findMortPos(client);
			if (mortPos == null) {
				return; // retry next tick - Mort may not be loaded/rendered yet
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
			pendingTicks = 0; // back to the confirmed room - whatever was pending is moot
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
			// Corridors/connectors and not-yet-revealed map tiles read this way - not a real room, not
			// worth logging as if it were one.
			return;
		}
		// Raw color byte included so a mislabeled room (e.g. ROOM vs TRAP, which share the same base
		// MapColor and differ only by brightness) can be diagnosed directly from the log instead of guessed.
		// The specific catalogued room name (e.g. "Round Room") is Skyblocker's own room-database match,
		// if it's also installed - our own map-color reading only ever knows the coarse RoomType.
		SkyblockerBridge.RoomSecrets roomSecrets = SkyblockerBridge.getCurrentRoomSecrets();
		String nameSuffix = roomSecrets != null && roomSecrets.roomName() != null ? " name=" + roomSecrets.roomName() : "";
		// Skyblocker might already have this room matched by the time our own (instant, single-pixel)
		// map-color guess above settles - if so, use/log it as confirmed right away instead of waiting
		// for the next tick's logSkyblockerConfirmationIfNewlyAvailable() to catch it.
		String confirmedTypeName = SkyblockerBridge.getCurrentRoomTypeName();
		RoomType effectiveType = type;
		String confirmSuffix = " (unconfirmed - instant map-color estimate)";
		if (confirmedTypeName != null) {
			skyblockerConfirmedLogged = true;
			try {
				effectiveType = RoomType.valueOf(confirmedTypeName);
				confirmSuffix = effectiveType == type ? " (confirmed via Skyblocker)" : " (CORRECTED via Skyblocker, map-color guessed " + type + ")";
			} catch (IllegalArgumentException ignored) {
				// Skyblocker added a room type our own enum doesn't know about - keep the map-color guess.
			}
		}
		// A known miniboss mob actually standing here is stronger evidence than either read above - if
		// either one says PUZZLE while a real miniboss is physically present, that's a misclassification
		// (confirmed as the real root cause of a reported "puzzle stuck pending forever" bug: a Miniboss
		// room got flagged as a Puzzle room, so the PENDING entry it created could never resolve - no
		// PUZZLE SOLVED/FAIL line was ever going to arrive for a miniboss kill). Corrected here instead
		// of guessed at, since the exact names below are read straight out of Skyblocker's own installed
		// jar (DungeonGlowAdder.computeColour, decompiled via javap) - "Lost Adventurer", "Shadow
		// Assassin", "Diamond Guy" - not assumed from memory.
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

	// Verified directly from the installed Skyblocker jar's own DungeonGlowAdder.computeColour bytecode
	// (javap decompile, not guessed/remembered) - the exact entity custom-name strings it matches via
	// String#equals for its own miniboss-glow feature.
	private static final Set<String> KNOWN_MINIBOSS_NAMES = Set.of("Lost Adventurer", "Shadow Assassin", "Diamond Guy");

	/** The matched name if a known Catacombs miniboss entity is currently rendered nearby, else {@code null} - see {@link #KNOWN_MINIBOSS_NAMES}. */
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

	/**
	 * Once already settled in a confirmed room (see the early-return in {@link #tick}), keep cheaply
	 * checking whether Skyblocker's shape/door match for THIS room newly completes - its own matching
	 * usually lands a little after our instant map-color guess, so this is what actually delivers the
	 * "authoritative, not just instant" recognition once it's ready, without re-running the whole
	 * room-detection pipeline above.
	 */
	private static void logSkyblockerConfirmationIfNewlyAvailable() {
		if (currentPhysicalRoomPos == null) {
			return;
		}
		// Skyblocker's real shape match (correct even for a not-yet-fully-explored multi-cell room,
		// unlike our own map-color flood-fill) can land on a different tick than the type confirmation
		// below - checked independently so it's picked up the moment it's ready either way. Verified
		// to actually contain currentPhysicalRoomPos before trusting it - a real bug: right after
		// switching rooms, Skyblocker's own match can briefly still point at the PREVIOUS room, which
		// would otherwise silently overwrite the already-correct new room with stale old segments.
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

	/**
	 * Room type at an arbitrary physical (x, z) - used by {@link com.melloo.skymelloo.client.party.PartyHud}
	 * to show OTHER party members' current room, not just the local player's. Unlike {@link #tick},
	 * this doesn't require the anchors to be freshly computed this tick (they're cached, fixed for
	 * the whole run) or gate on a room having "changed" - it's a plain lookup, safe to call every
	 * frame for every visible member.
	 */
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

		// Skyblocker's confirmed match (if installed) is only ever for the LOCAL player's OWN current
		// room - only trust it here when the queried position actually IS that same room, otherwise
		// it'd silently apply Skyblocker's local-room answer to some other party member's position.
		if (currentPhysicalRoomPos != null && currentPhysicalRoomPos[0] == roomPos[0] && currentPhysicalRoomPos[1] == roomPos[1]) {
			String confirmedTypeName = SkyblockerBridge.getCurrentRoomTypeName();
			if (confirmedTypeName != null) {
				try {
					return RoomType.valueOf(confirmedTypeName);
				} catch (IllegalArgumentException ignored) {
					// Unknown-to-us type name - fall through to the map-color guess below.
				}
			}
		}
		return mapColorType;
	}

	/**
	 * Rough physical bounding box of the LOCAL player's current room - the X/Z bounds are exact (every
	 * dungeon room is a 32x32-block grid cell, see {@link #getPhysicalRoomPos}), but the Y range is
	 * only an approximation ({@code referenceY} +/- {@code verticalMargin}), since neither our own
	 * map-color reading nor Skyblocker's room database expose a room's actual vertical extent (room
	 * ceiling heights vary by template) - good enough for a cosmetic "which mobs are in this room"
	 * highlight distinction, not meant to be pixel-exact. {@code null} if no room is currently confirmed.
	 */
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

	/**
	 * Flood-fills through adjacent grid cells sharing {@code startPos}'s exact room type, so a
	 * multi-cell room (1x2/2x1/2x2/L-shaped) counts as one room. Capped at
	 * {@link #MAX_CONNECTED_ROOM_CELLS}; computed once per room entry (see tick()), not per-mob.
	 */
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

	/**
	 * Scans the map's own room grid (stepping by {@code mapRoomSize + 4} from the northwest-most
	 * possible room, per {@code DungeonMapUtils.getMapPosForNWMostRoom}) for a Blood-Room-colored
	 * pixel, rather than requiring the local player to have physically walked into it - the local
	 * player might never personally visit the Blood Room while a teammate clears it.
	 */
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

	/** Ported verbatim from {@code DungeonMapUtils.getPhysicalPosFromMap} - the exact inverse of {@link #getMapPosFromPhysical}. */
	private static int[] getPhysicalPosFromMap(int[] mapEntrancePos, int mapRoomSize, int[] physicalEntrancePos, int[] mapPos) {
		int dx = (mapPos[0] - mapEntrancePos[0]) / (mapRoomSize + 4);
		int dy = (mapPos[1] - mapEntrancePos[1]) / (mapRoomSize + 4);
		return new int[]{dx * 32 + physicalEntrancePos[0], dy * 32 + physicalEntrancePos[1]};
	}

	/**
	 * Physical northwest corner of the 32x32-block room grid cell containing (x, z). Ported verbatim
	 * from {@code DungeonMapUtils.getPhysicalRoomPos(double, double)} - the +8.5 shift centers room
	 * borders correctly, and the extra +/-8 accounts for Hypixel's own dungeon grid offset.
	 */
	private static int[] getPhysicalRoomPos(double x, double z) {
		int px = (int) (x + 8.5); // truncating cast matches JOML's RoundingMode.TRUNCATE used upstream
		int pz = (int) (z + 8.5);
		px -= Math.floorMod(px, 32) + 8;
		pz -= Math.floorMod(pz, 32) + 8;
		return new int[]{px, pz};
	}

	/** Ported verbatim from {@code DungeonMapUtils.getMapPosFromPhysical} - both positions are room-corner-aligned so the division is always exact. */
	private static int[] getMapPosFromPhysical(int[] physicalEntrancePos, int[] mapEntrancePos, int mapRoomSize, int[] physicalPos) {
		int dx = (physicalPos[0] - physicalEntrancePos[0]) / 32;
		int dz = (physicalPos[1] - physicalEntrancePos[1]) / 32;
		int mapRoomSizeWithGap = mapRoomSize + 4;
		return new int[]{dx * mapRoomSizeWithGap + mapEntrancePos[0], dz * mapRoomSizeWithGap + mapEntrancePos[1]};
	}

	/** Ported verbatim from {@code DungeonMapUtils.getMapEntrancePosAndRoomSize} - BFS outward from the player's map marker in steps of 10 pixels until an entrance-colored pixel is found and confirmed. */
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

	/** Ported verbatim from {@code DungeonMapUtils.getMapEntrancePosAndRoomSizeAt} - walks to the entrance color block's top-left corner, then measures its width. */
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

	/** Ported verbatim from {@code DungeonMapUtils.getMapRoomSize} - counts the contiguous entrance-colored run starting at the corner; below 6 pixels wide is treated as noise, not a real room. */
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

	/** Ported verbatim from {@code DungeonMapUtils.getRoomType} - the {@link RoomType} whose known color matches the pixel at (x, y), or {@code null} if it's not a recognized room color (e.g. still black/unexplored). */
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

	/** Ported verbatim from {@code DungeonMapUtils.getMapPlayerPos} - the player marker is the map's FRAME decoration, at half-resolution offset from map center. */
	private static int[] getMapPlayerPos(MapItemSavedData map) {
		for (MapDecoration decoration : map.getDecorations()) {
			if (decoration.type().value().equals(MapDecorationTypes.FRAME.value())) {
				return new int[]{(decoration.x() >> 1) + 64, (decoration.y() >> 1) + 64};
			}
		}
		return null;
	}

	/** Ported from {@code DungeonManager.getMortArmorStandPos} - the dungeon-start NPC, used to anchor the entrance room's exact physical position once per run. */
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
