package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.api.ModAuthManager;
import com.melloo.skymelloo.client.api.SkyMellooApiClient;
import com.melloo.skymelloo.client.util.DebugLog;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Scans the tab list for real SkyMelloo staff/owner members and reports every sighting to
 * sky.melloo.me, which keeps its own per-account log of who you've ever been seen alongside (see
 * "/sm hitstaff"). Deliberately NOT gated to Hypixel/SkyBlock - unlike every other feature in the
 * mod, this is meant to keep working on any server, since the whole point is recognizing a staff
 * member wherever you happen to run into them.
 * <p>
 * The server itself is the source of truth for who's actually staff (via roleForUuid, an
 * account's linked-Minecraft-account + team-role record) - this only ever sends candidate
 * uuid/username pairs from the tab list, never a role guess of its own.
 */
public final class StaffEncounterTracker {
	private static final int SCAN_INTERVAL_TICKS = 200; // 10s - tab list doesn't change fast enough to need tighter polling
	private static int tickCounter = 0;
	private static volatile boolean reportInFlight = false;

	private StaffEncounterTracker() {
	}

	public static void tick(Minecraft client) {
		if (client.getConnection() == null || client.player == null) {
			return;
		}
		tickCounter++;
		if (tickCounter % SCAN_INTERVAL_TICKS != 0) {
			return;
		}
		scanAndReport(client);
	}

	private static void scanAndReport(Minecraft client) {
		if (reportInFlight) {
			return;
		}
		UUID self = client.player.getUUID();
		List<SkyMellooApiClient.StaffCheckEntry> players = new ArrayList<>();
		for (var info : client.getConnection().getOnlinePlayers()) {
			UUID id = info.getProfile().id();
			String name = info.getProfile().name();
			// Hypixel NPCs commonly show up in the tab list with names starting with "!" (e.g.
			// "!Auctioneer") - not real players, never worth a lookup.
			if (id.equals(self) || name.startsWith("!")) {
				continue;
			}
			players.add(new SkyMellooApiClient.StaffCheckEntry(id.toString(), name));
		}
		if (players.isEmpty()) {
			return;
		}
		reportInFlight = true;
		ModAuthManager.getIdentity(client)
				.exceptionally(error -> null)
				.thenCompose(identity -> SkyMellooApiClient.reportStaffEncounters(players, identity))
				.whenComplete((ignored, error) -> {
					reportInFlight = false;
					if (error != null) {
						DebugLog.log(DebugLog.Category.STAFF, "Staff-encounter scan failed (" + error.getMessage() + ").");
					} else {
						DebugLog.log(DebugLog.Category.STAFF, "Staff-encounter scan sent for " + players.size() + " nearby player(s).");
					}
				});
	}
}
