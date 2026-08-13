package com.melloo.skymelloo.client.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.melloo.skymelloo.client.util.DebugLog;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * FIRST PROTOTYPE of a real 3D boss-room viewer. The dungeon map item doesn't cover boss rooms at
 * all (unlike the rest of the floor) - Hypixel literally swaps the map out of the player's
 * inventory for a Nether Star on boss-room entry, so there's no map pixel data to read
 * even in principle here. This reads real world blocks directly instead - scans outward from the
 * player as they move (see {@link #tick}) and delta-encodes newly found blocks (a given position is
 * only ever sent ONCE per boss-room encounter, never resent) into the regular dungeonSync report, so
 * the website can accumulate a real voxel model over time rather than one huge payload up front.
 * <p>
 * Colour, not texture: each block is reduced to its {@link MapColor#id} (0-61) - the exact same
 * lookup Hypixel's own in-game map item rendering uses internally, decompiled straight from the
 * game's own {@code MapColor} class (real RGB int VALUES, not a copyrighted image/texture asset).
 * Deliberately NOT real block textures - bundling Mojang's actual texture files into this project and
 * serving them to any website visitor would be a real redistribution risk this prototype avoids
 * entirely. Flat-coloured cubes are the safe, zero-dependency choice for a first pass.
 * <p>
 * All positions (in {@link #drainPendingJson} and {@link #getAnchorOffset}) are relative to
 * {@link #origin} (the player's position the moment scanning started this encounter) - never raw
 * world coordinates, since those differ every run (the dungeon instance generates at a different
 * world position each time). {@code origin} itself isn't a stable reference ACROSS different
 * encounters of the same boss though - it's just wherever the player happened to be standing when
 * they walked in, which varies run to run - so {@link #getAnchorOffset} additionally tracks the
 * min-corner of everything scanned so far, letting the website re-align this run's blocks onto the
 * same frame as a previous run of the same boss room (whose static geometry is identical every
 * time, only the player's own starting position within it differs).
 */
public final class BossRoomScanner {
	// Max distance (Euclidean, from the player's CURRENT position) a candidate block is scanned at -
	// "bis zu fünfzig Blöcke um einen rum" - replaces the old fixed box around the player with a
	// genuine outward search that follows the player as they walk through the room.
	private static final int SCAN_RADIUS = 50;
	// How many NEW positions get actually read (block state lookups) per pass - bounds per-tick cost
	// regardless of how big the current frontier is.
	private static final int MAX_PER_PASS = 400;
	// A full-radius scan is real work - twice a second is plenty to keep up with walking speed
	// without doing it every tick.
	private static final int SCAN_INTERVAL_TICKS = 10;
	// Safety cap so a stalled/huge frontier can't grow memory unbounded - the nearest-to-player
	// entries are always kept (see refillFrontier), farther ones are simply dropped and can be
	// rediscovered later via neighbor expansion if still relevant.
	private static final int MAX_FRONTIER_SIZE = 20_000;
	// Hard cap so a long/messy boss fight can't grow the payload/server-side storage unbounded - a
	// prototype doesn't need to capture every last corner, just enough to be a real 3D impression.
	private static final int MAX_TOTAL_BLOCKS = 40_000;
	// Safety cap on a single report's payload size - anything past this stays queued for the NEXT
	// report instead of spiking one request's size (e.g. right after first entering a big room).
	private static final int MAX_PENDING_PER_DRAIN = 4_000;

	private static boolean active = false;
	private static long scanId = 0;
	private static BlockPos origin = null;
	private static int tickCounter = 0;
	private static final Set<Long> seenKeys = new HashSet<>(); // every position ever read (air or not) this encounter
	private static final Set<Long> frontierKeys = new HashSet<>(); // mirrors frontier, for O(1) duplicate checks
	private static final List<BlockPos> frontier = new ArrayList<>(); // discovered, not-yet-read neighbors of already-read non-air blocks
	private static final List<int[]> pending = new ArrayList<>(); // {relX, relY, relZ, colorId}
	// Running min-corner of every non-air block found so far, relative to origin - see class doc
	// comment on why this (not origin itself) is the anchor the website should align different
	// encounters of the same boss room by.
	private static boolean haveAnchor = false;
	private static int anchorMinX, anchorMinY, anchorMinZ;

	private BossRoomScanner() {
	}

	public static void tick(Minecraft client) {
		boolean shouldBeActive = DungeonRunTracker.isBossRoomEntered() && !DungeonRunTracker.isBossRoomCleared();
		if (!shouldBeActive) {
			if (active) {
				reset(); // left the boss room (cleared, died, or run ended) - the next entry starts fresh
			}
			return;
		}
		if (!active) {
			active = true;
			scanId = System.currentTimeMillis();
			origin = client.player != null ? client.player.blockPosition() : BlockPos.ZERO;
			seenKeys.clear();
			frontier.clear();
			frontierKeys.clear();
			pending.clear();
			haveAnchor = false;
			DebugLog.log(DebugLog.Category.DUNGEON, "BossRoomScanner: started scanning at " + origin + " (scanId=" + scanId + ")");
		}
		if (client.player == null || client.level == null || seenKeys.size() >= MAX_TOTAL_BLOCKS) {
			return;
		}
		tickCounter++;
		if (tickCounter < SCAN_INTERVAL_TICKS) {
			return;
		}
		tickCounter = 0;
		BlockPos playerPos = client.player.blockPosition();
		refillFrontier(playerPos);
		if (frontier.isEmpty()) {
			return;
		}
		// Always work through the CURRENT nearest-to-player candidates first - re-sorted every pass
		// since the player keeps moving, so the scan genuinely follows them outward instead of
		// finishing one fixed direction before considering another.
		frontier.sort((a, b) -> Double.compare(a.distSqr(playerPos), b.distSqr(playerPos)));
		int newlyFound = 0;
		int budget = Math.min(MAX_PER_PASS, MAX_TOTAL_BLOCKS - seenKeys.size());
		// frontier is sorted nearest-first, so the moment one candidate is out of range every
		// candidate after it is too - safe to stop rather than keep scanning past it. Consumed
		// entries are removed in one bulk operation below rather than one-by-one, since removing
		// from the front of an ArrayList repeatedly is quadratic for a frontier this size.
		// readAndExpand may itself append new candidates to the END of frontier - safe to do while
		// iterating the front by index, since appends don't shift already-visited indices.
		int consumed = 0;
		while (consumed < frontier.size() && consumed < budget) {
			BlockPos pos = frontier.get(consumed);
			if (pos.distSqr(playerPos) > (double) SCAN_RADIUS * SCAN_RADIUS) {
				break; // out of range for now - stays in the frontier, may come back into range once the player walks closer
			}
			consumed++;
			if (readAndExpand(client, pos)) {
				newlyFound++;
			}
		}
		if (consumed > 0) {
			for (int k = 0; k < consumed; k++) {
				frontierKeys.remove(frontier.get(k).asLong());
			}
			frontier.subList(0, consumed).clear();
		}
		if (newlyFound > 0) {
			DebugLog.log(DebugLog.Category.DUNGEON, "BossRoomScanner: +" + newlyFound + " new blocks this pass (seen=" + seenKeys.size() + ", pending=" + pending.size() + ", frontier=" + frontier.size() + ")");
		}
	}

	/** Makes sure the player's immediate surroundings are always in the frontier, even if BFS growth from elsewhere hasn't reached there yet (e.g. right after entering, or after a big jump/teleport within the room). */
	private static void refillFrontier(BlockPos playerPos) {
		int seedRadius = 3;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = -seedRadius; dx <= seedRadius; dx++) {
			for (int dy = -seedRadius; dy <= seedRadius; dy++) {
				for (int dz = -seedRadius; dz <= seedRadius; dz++) {
					cursor.set(playerPos.getX() + dx, playerPos.getY() + dy, playerPos.getZ() + dz);
					addToFrontier(cursor.immutable());
				}
			}
		}
		if (frontier.size() > MAX_FRONTIER_SIZE) {
			// Keep only the nearest-to-player entries - farther ones are simply dropped, they can be
			// rediscovered later via neighbor expansion once the scan actually reaches that area.
			frontier.sort((a, b) -> Double.compare(a.distSqr(playerPos), b.distSqr(playerPos)));
			for (int i = frontier.size() - 1; i >= MAX_FRONTIER_SIZE; i--) {
				frontierKeys.remove(frontier.remove(i).asLong());
			}
		}
	}

	private static void addToFrontier(BlockPos pos) {
		long key = pos.asLong();
		if (seenKeys.contains(key) || frontierKeys.contains(key)) {
			return;
		}
		frontier.add(pos);
		frontierKeys.add(key);
	}

	/** Reads one position, records it (air or not), and - if it's a real block - queues its 6 orthogonal neighbors for future passes. @return true if a new NON-AIR block was found. */
	private static boolean readAndExpand(Minecraft client, BlockPos pos) {
		long key = pos.asLong();
		seenKeys.add(key);
		BlockState state = client.level.getBlockState(pos);
		if (state.isAir()) {
			return false; // air stays air for the rest of this encounter - never re-check this spot, and never expands the frontier from here
		}
		MapColor color = state.getMapColor(client.level, pos);
		int relX = pos.getX() - origin.getX();
		int relY = pos.getY() - origin.getY();
		int relZ = pos.getZ() - origin.getZ();
		pending.add(new int[]{relX, relY, relZ, color.id});
		if (!haveAnchor) {
			anchorMinX = relX;
			anchorMinY = relY;
			anchorMinZ = relZ;
			haveAnchor = true;
		} else {
			anchorMinX = Math.min(anchorMinX, relX);
			anchorMinY = Math.min(anchorMinY, relY);
			anchorMinZ = Math.min(anchorMinZ, relZ);
		}
		addToFrontier(pos.north());
		addToFrontier(pos.south());
		addToFrontier(pos.east());
		addToFrontier(pos.west());
		addToFrontier(pos.above());
		addToFrontier(pos.below());
		return true;
	}

	private static void reset() {
		if (active) {
			DebugLog.log(DebugLog.Category.DUNGEON, "BossRoomScanner: stopped (seen=" + seenKeys.size() + " positions this encounter)");
		}
		active = false;
		origin = null;
		seenKeys.clear();
		frontier.clear();
		frontierKeys.clear();
		pending.clear();
		haveAnchor = false;
	}

	public static boolean isActive() {
		return active;
	}

	/** How many distinct positions have been checked (air or not) so far this encounter - see {@code /sm debug bossroom}. */
	public static int getSeenCount() {
		return seenKeys.size();
	}

	/** How many non-air blocks are queued but not yet drained into a report - see {@code /sm debug bossroom}. */
	public static int getPendingCount() {
		return pending.size();
	}

	/** How many discovered-but-not-yet-read positions are waiting in the frontier - see {@code /sm debug bossroom}. A healthy scan keeps this moving, not stuck at 0 while seen keeps climbing. */
	public static int getFrontierSize() {
		return frontier.size();
	}

	public static BlockPos getOrigin() {
		return origin;
	}

	/** Identifies which boss-room ENCOUNTER a batch of blocks belongs to - the server resets its accumulated model whenever a new value arrives, so a fresh boss room never gets mixed with stale blocks from a previous one. */
	public static long getScanId() {
		return scanId;
	}

	/**
	 * Min-corner of every non-air block scanned so far this encounter, relative to {@link #origin} -
	 * see class doc comment. {@code null} until at least one non-air block has actually been found.
	 * Recomputed as the scan progresses, so later reports carry a more complete (and more accurate)
	 * offset than earlier ones - the website should always use the LATEST value it has received for
	 * a given {@link #getScanId}, not the first.
	 */
	public static int[] getAnchorOffset() {
		return haveAnchor ? new int[]{anchorMinX, anchorMinY, anchorMinZ} : null;
	}

	/** Drains (and clears) every block newly discovered since the last report - same "drain, don't just read" pattern the position-history buffers already use elsewhere in this file's sibling classes. */
	public static JsonArray drainPendingJson() {
		JsonArray array = new JsonArray();
		int count = Math.min(pending.size(), MAX_PENDING_PER_DRAIN);
		for (int i = 0; i < count; i++) {
			int[] b = pending.get(i);
			JsonObject obj = new JsonObject();
			obj.addProperty("x", b[0]);
			obj.addProperty("y", b[1]);
			obj.addProperty("z", b[2]);
			obj.addProperty("c", b[3]);
			array.add(obj);
		}
		// Only remove what was actually drained - if MAX_PENDING_PER_DRAIN capped this call, the rest
		// stays queued for the NEXT report rather than being silently dropped.
		if (count > 0) {
			pending.subList(0, count).clear();
		}
		return array;
	}
}
