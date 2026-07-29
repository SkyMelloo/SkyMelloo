package com.melloo.skymelloo.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.server.network.EventLoopGroupHolder;

/**
 * A real ping measurement using Minecraft's OWN server-list-ping mechanism - literally the same
 * thing the vanilla multiplayer server list uses to show its ping bars next to each server entry
 * ({@link ServerStatusPinger}), reused here rather than reinventing it. A real protocol-level status
 * request/response round trip to the exact address the game is actually connected to - independent
 * of Hypixel's own server-side keep-alive latency number
 * ({@link net.minecraft.client.multiplayer.PlayerInfo#getLatency()}), which on their Bungee/Velocity
 * proxy setup can reflect an internal backend-to-proxy hop rather than your real connection - the
 * "1ms" that didn't add up.
 * <p>
 * Was reading {@code ServerData.ping} in the wrong callback and always got 0 - confirmed via
 * bytecode inspection of {@code ServerStatusPinger$1}: {@link ServerStatusPinger#pingServer}'s two
 * Runnable parameters are NOT "onSuccess"/"onFailure". The first fires from
 * {@code handleStatusResponse} (MOTD/player-count/version updated - not ping yet), the second fires
 * from {@code handlePongResponse}, which is the actual round-trip measurement that sets
 * {@code ping}. Reading it in the first callback meant reading it before it was ever computed.
 * There's also no failure callback at all (a failed/unreachable ping calls a private method
 * internally that we never see), so a safety timeout resets {@link #pingInFlight} if neither
 * callback ever fires, or a stuck ping would silently block every future attempt forever.
 * <p>
 * A scratch {@link ServerData} (never added to the real server list, just used as the ping target
 * carrier the way this API expects) is re-pinged on a fixed interval. {@link #tick} must be called
 * every client tick - the underlying connection is asynchronous and needs pumping.
 */
public final class ServerPingMonitor {
	private static final int PING_INTERVAL_TICKS = 20; // 1s at 20 ticks/s
	private static final int PING_TIMEOUT_TICKS = 60; // 3s safety - see class doc, there's no failure callback to rely on

	private static final ServerStatusPinger PINGER = new ServerStatusPinger();
	private static volatile Long lastPingMillis = null;
	private static int tickCounter = 0;
	private static boolean pingInFlight = false;
	private static int inFlightTicks = 0;
	// 10 second window for the average/worst-case readout - "1% letzte 10 sekunden".
	private static final RollingStats HISTORY = new RollingStats(10);

	private ServerPingMonitor() {
	}

	public static void tick(Minecraft client) {
		PINGER.tick();
		if (pingInFlight) {
			inFlightTicks++;
			if (inFlightTicks > PING_TIMEOUT_TICKS) {
				pingInFlight = false; // neither callback ever fired - give up, let the next cycle retry
			}
			return;
		}
		ServerData current = client.getCurrentServer();
		if (current == null || current.ip == null || current.ip.isBlank()) {
			return;
		}
		tickCounter++;
		if (tickCounter < PING_INTERVAL_TICKS) {
			return;
		}
		tickCounter = 0;
		pingOnce(current.ip);
	}

	private static void pingOnce(String ip) {
		ServerData scratch = new ServerData("SkyMelloo Ping", ip, ServerData.Type.OTHER);
		pingInFlight = true;
		inFlightTicks = 0;
		try {
			PINGER.pingServer(scratch,
					() -> {
						// Status response - MOTD/player-count/version, not the ping measurement itself.
					},
					() -> {
						// Pong response - THIS is when ping is actually computed and set.
						lastPingMillis = scratch.ping;
						HISTORY.addSample(scratch.ping);
						pingInFlight = false;
					},
					EventLoopGroupHolder.remote(false));
		} catch (Exception e) {
			pingInFlight = false;
		}
	}

	/** {@code null} until the first successful ping (or if the last one failed). */
	public static Long getPingMillis() {
		return lastPingMillis;
	}

	/** Average ping over the last 10 seconds, or null if nothing's been sampled yet. */
	public static Double getAveragePingMillis() {
		return HISTORY.hasSamples() ? HISTORY.average() : null;
	}

	/** Worst-case ping over the last 10 seconds - average of the worst (highest, since spikes are what's bad for ping) 1% of samples, or null if nothing's been sampled yet. */
	public static Double getWorstPingMillis() {
		return HISTORY.hasSamples() ? HISTORY.worstAverage(false) : null;
	}
}
