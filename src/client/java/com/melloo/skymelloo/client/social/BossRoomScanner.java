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

// Scans real world blocks into a 3D boss-room model (the map item doesn't cover boss rooms).
// Blocks reduce to a MapColor id, not real textures. Positions are relative to origin, not world coords.
public final class BossRoomScanner {
	// Max distance from the player's current position a candidate block is scanned at.
	private static final int SCAN_RADIUS = 50;
	// How many new positions get read per pass - bounds per-tick cost regardless of frontier size.
	private static final int MAX_PER_PASS = 400;
	private static final int SCAN_INTERVAL_TICKS = 10;
	// Nearest-to-player entries are kept; farther ones are dropped and can be rediscovered later.
	private static final int MAX_FRONTIER_SIZE = 20_000;
	private static final int MAX_TOTAL_BLOCKS = 40_000;
	// Anything past this stays queued for the next report instead of spiking one request's size.
	private static final int MAX_PENDING_PER_DRAIN = 4_000;

	private static boolean active = false;
	private static long scanId = 0;
	private static BlockPos origin = null;
	private static int tickCounter = 0;
	private static final Set<Long> seenKeys = new HashSet<>(); // every position ever read (air or not) this encounter
	private static final Set<Long> frontierKeys = new HashSet<>(); // mirrors frontier, for O(1) duplicate checks
	private static final List<BlockPos> frontier = new ArrayList<>(); // discovered, not-yet-read neighbors of already-read non-air blocks
	private static final List<int[]> pending = new ArrayList<>(); // {relX, relY, relZ, colorId}
	// Running min-corner of every non-air block found, relative to origin - see getAnchorOffset.
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
		// Re-sorted every pass so the scan follows the player outward instead of finishing one direction first.
		frontier.sort((a, b) -> Double.compare(a.distSqr(playerPos), b.distSqr(playerPos)));
		int newlyFound = 0;
		int budget = Math.min(MAX_PER_PASS, MAX_TOTAL_BLOCKS - seenKeys.size());
		// Sorted nearest-first, so once one candidate is out of range every one after it is too.
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
			frontier.subList(0, consumed).clear(); // bulk removal - one-by-one from the front is quadratic here
		}
		if (newlyFound > 0) {
			DebugLog.log(DebugLog.Category.DUNGEON, "BossRoomScanner: +" + newlyFound + " new blocks this pass (seen=" + seenKeys.size() + ", pending=" + pending.size() + ", frontier=" + frontier.size() + ")");
		}
	}

	// Keeps the player's immediate surroundings in the frontier even after a jump/teleport in the room.
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

	// Reads a position, records it, and queues its neighbors if it's a real block. Returns true if non-air.
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

	// How many distinct positions have been checked (air or not) so far this encounter - see /sm debug bossroom.
	public static int getSeenCount() {
		return seenKeys.size();
	}

	// How many non-air blocks are queued but not yet drained into a report - see /sm debug bossroom.
	public static int getPendingCount() {
		return pending.size();
	}

	// A healthy scan keeps this moving, not stuck at 0 while seen keeps climbing - see /sm debug bossroom.
	public static int getFrontierSize() {
		return frontier.size();
	}

	public static BlockPos getOrigin() {
		return origin;
	}

	// The server resets its accumulated model whenever this changes, so encounters never mix.
	public static long getScanId() {
		return scanId;
	}

	// Min-corner relative to origin, null until a block is found; the website should always use the
	// latest value for a given scanId, since this gets more accurate as the scan progresses.
	public static int[] getAnchorOffset() {
		return haveAnchor ? new int[]{anchorMinX, anchorMinY, anchorMinZ} : null;
	}

	// Drains (and clears) every block newly discovered since the last report.
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
