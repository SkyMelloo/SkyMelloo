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
 * FIRST PROTOTYPE (2026-07-27) of a real 3D boss-room viewer - "kannst du für den boss room einen
 * ersten prototyp machen ... daten zu den blöcken ... dann damit so 3d umgebung bauen die man moven
 * kann". The dungeon map item doesn't cover boss rooms at all (unlike the rest of the floor) - Hypixel
 * literally swaps the map out of the player's inventory for a Nether Star on boss-room entry ("im boss
 * room wird die map gegen den netherstar getauscht", 2026-07-27), so there's no map pixel data to read
 * even in principle here. This reads real world blocks directly instead - scans a limited box around
 * the player while inside one and delta-encodes them (a given block position is only ever sent ONCE per boss-room
 * encounter, never resent) into the regular dungeonSync report, so the website can accumulate a real
 * voxel model over time as the player walks around, rather than one huge payload up front.
 * <p>
 * Colour, not texture: each block is reduced to its {@link MapColor#id} (0-61) - the exact same
 * lookup Hypixel's own in-game map item rendering uses internally, decompiled straight from the
 * game's own {@code MapColor} class (real RGB int VALUES, not a copyrighted image/texture asset).
 * Deliberately NOT real block textures - bundling Mojang's actual texture files into this project and
 * serving them to any website visitor would be a real redistribution risk this prototype avoids
 * entirely. Flat-coloured cubes are the safe, zero-dependency choice for a first pass.
 */
public final class BossRoomScanner {
	private static final int SCAN_RADIUS_XZ = 10;
	private static final int SCAN_RADIUS_UP = 8;
	private static final int SCAN_RADIUS_DOWN = 4;
	// A full-radius scan is real work (up to ~4000 block reads) - twice a second is plenty to keep up
	// with walking speed without doing it every tick.
	private static final int SCAN_INTERVAL_TICKS = 10;
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
	private static final Set<Long> seenKeys = new HashSet<>();
	private static final List<int[]> pending = new ArrayList<>(); // {relX, relY, relZ, colorId}

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
			pending.clear();
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
		int newlyFound = 0;
		BlockPos center = client.player.blockPosition();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = -SCAN_RADIUS_XZ; dx <= SCAN_RADIUS_XZ && seenKeys.size() < MAX_TOTAL_BLOCKS; dx++) {
			for (int dz = -SCAN_RADIUS_XZ; dz <= SCAN_RADIUS_XZ && seenKeys.size() < MAX_TOTAL_BLOCKS; dz++) {
				for (int dy = -SCAN_RADIUS_DOWN; dy <= SCAN_RADIUS_UP && seenKeys.size() < MAX_TOTAL_BLOCKS; dy++) {
					cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					long key = cursor.asLong();
					if (seenKeys.contains(key)) {
						continue;
					}
					BlockState state = client.level.getBlockState(cursor);
					if (state.isAir()) {
						seenKeys.add(key); // air stays air for the rest of this encounter - never re-check this spot
						continue;
					}
					seenKeys.add(key);
					MapColor color = state.getMapColor(client.level, cursor);
					pending.add(new int[]{cursor.getX() - origin.getX(), cursor.getY() - origin.getY(), cursor.getZ() - origin.getZ(), color.id});
					newlyFound++;
				}
			}
		}
		if (newlyFound > 0) {
			DebugLog.log(DebugLog.Category.DUNGEON, "BossRoomScanner: +" + newlyFound + " new blocks this pass (seen=" + seenKeys.size() + ", pending=" + pending.size() + ")");
		}
	}

	private static void reset() {
		if (active) {
			DebugLog.log(DebugLog.Category.DUNGEON, "BossRoomScanner: stopped (seen=" + seenKeys.size() + " positions this encounter)");
		}
		active = false;
		origin = null;
		seenKeys.clear();
		pending.clear();
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

	public static BlockPos getOrigin() {
		return origin;
	}

	/** Identifies which boss-room ENCOUNTER a batch of blocks belongs to - the server resets its accumulated model whenever a new value arrives, so a fresh boss room never gets mixed with stale blocks from a previous one. */
	public static long getScanId() {
		return scanId;
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
