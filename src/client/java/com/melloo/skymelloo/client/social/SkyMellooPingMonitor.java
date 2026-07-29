package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.api.SkyMellooApiClient;
import net.minecraft.client.Minecraft;

/**
 * Tracks round-trip latency to sky.melloo.me itself (distinct from the Minecraft server ping) for
 * display on {@link WhitelistStatusHud} - purely informational, a slow/failed ping here doesn't
 * gate or disable anything.
 */
public final class SkyMellooPingMonitor {
	private static final int PING_INTERVAL_TICKS = 20; // 1s at 20 ticks/s - was 15s, far slower than useful for a live reading

	private static volatile int lastPingMs = -1;
	private static volatile boolean pingInFlight = false;
	private static int tickCounter = PING_INTERVAL_TICKS; // ping once immediately on first tick

	private SkyMellooPingMonitor() {
	}

	/** @return last measured round-trip ms, or -1 if never measured/failed. */
	public static int getLastPingMs() {
		return lastPingMs;
	}

	public static void tick(Minecraft client) {
		tickCounter++;
		if (tickCounter < PING_INTERVAL_TICKS || pingInFlight) {
			return;
		}
		tickCounter = 0;
		pingInFlight = true;
		long start = System.currentTimeMillis();
		SkyMellooApiClient.ping().whenComplete((v, error) -> {
			pingInFlight = false;
			lastPingMs = error == null ? (int) (System.currentTimeMillis() - start) : -1;
		});
	}
}
