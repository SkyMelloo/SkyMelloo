package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.util.ChatUtil;
import com.melloo.mellooessentials.client.util.HypixelDetector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;

import java.util.ArrayList;
import java.util.List;

/**
 * On connecting to Hypixel specifically - gated behind {@link HypixelDetector#isHypixel} and
 * {@link SkyMellooConfig#connectionQualityCheckEnabled}.
 * <p>
 * Samples ping every tick (20/s) for the first 5 seconds to judge whether the connection looks
 * stable, then chat-reports the result plus the client's own packet-rate averages. The persistent
 * HUD is the only on-screen indicator - no title/subtitle.
 * <p>
 * Started from {@code ClientPlayConnectionEvents.INIT} rather than {@code JOIN}, since INIT fires
 * as soon as the play-protocol packet listener is set up, before the player entity/world exist.
 * <p>
 * {@link net.minecraft.network.Connection#getAverageSentPackets()}/{@code getAverageReceivedPackets()}
 * already track both directions on one object - "sent" is what this client is putting out,
 * "received" is what's coming back from the server - so there's no need for a second measurement.
 */
public final class ConnectionQualityMonitor {
	private static final int CHECK_DURATION_TICKS = 100; // 5 seconds at 20 ticks/s
	private static final int PING_SPIKE_THRESHOLD_MS = 250;
	private static final int PING_UNSTABLE_SPREAD_MS = 150;

	private static boolean active = false;
	private static int ticksElapsed = 0;
	private static final List<Integer> pingSamples = new ArrayList<>();
	private static String lastServerAddress = null;

	private ConnectionQualityMonitor() {
	}

	public static void reset() {
		active = false;
		ticksElapsed = 0;
		pingSamples.clear();
	}

	/** Called once per connection attempt (INIT) - starts the 5-second sampling window, but only on Hypixel and only with the toggle on (see class doc comment). */
	public static void start(Minecraft client) {
		if (!SkyMellooConfig.HANDLER.instance().connectionQualityCheckEnabled || !HypixelDetector.isHypixel(client)) {
			return;
		}
		// Networks like Hypixel move you between internal sub-servers (lobby <-> Skyblock island)
		// using the same vanilla transfer mechanism as a genuine reconnect, which re-fires INIT even
		// though you never actually left the network. Only measure this for an address change.
		ServerData server = client.getCurrentServer();
		String address = server != null ? server.ip : null;
		boolean sameNetwork = address != null && address.equalsIgnoreCase(lastServerAddress);
		lastServerAddress = address;
		if (sameNetwork) {
			return;
		}
		active = true;
		ticksElapsed = 0;
		pingSamples.clear();
	}

	public static void tick(Minecraft client) {
		if (!active) {
			return;
		}
		ticksElapsed++;
		// The player entity/connection may not exist for the first few ticks after INIT - just
		// skip sampling those ticks rather than delaying the whole 5-second window until they do.
		if (client.player != null && client.getConnection() != null) {
			PlayerInfo info = client.getConnection().getPlayerInfo(client.player.getUUID());
			if (info != null) {
				pingSamples.add(info.getLatency());
			}
		}
		if (ticksElapsed >= CHECK_DURATION_TICKS) {
			active = false;
			finish(client);
		}
	}

	private static void finish(Minecraft client) {
		net.minecraft.network.Connection connection = client.getConnection() != null ? client.getConnection().getConnection() : null;
		float sent = connection != null ? connection.getAverageSentPackets() : 0;
		float received = connection != null ? connection.getAverageReceivedPackets() : 0;

		if (pingSamples.isEmpty()) {
			// Zero samples across the whole 5-second window is itself a bad sign - either the
			// connection never settled, or something's wrong.
			report(client, false, -1, 0, sent, received);
			return;
		}
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		long total = 0;
		for (int ping : pingSamples) {
			min = Math.min(min, ping);
			max = Math.max(max, ping);
			total += ping;
		}
		int avg = (int) (total / pingSamples.size());
		int spread = max - min;
		boolean stable = spread <= PING_UNSTABLE_SPREAD_MS && max <= PING_SPIKE_THRESHOLD_MS;
		report(client, stable, avg, spread, sent, received);
	}

	/** Only reached at all when {@link SkyMellooConfig#connectionQualityCheckEnabled} was on back when {@link #start} kicked off this sampling window - the toggle is a hard gate now, not just a filter on which outcomes get reported. */
	private static void report(Minecraft client, boolean stable, int avgPing, int spread, float sent, float received) {
		if (client.player == null) {
			return;
		}
		String pingText = avgPing >= 0 ? avgPing + "ms" : "unknown";
		String packetText = "sent: " + String.format("%.1f", sent) + "/t, received: " + String.format("%.1f", received) + "/t";
		if (stable) {
			client.player.sendSystemMessage(ChatUtil.prefixed("§aConnected! §7Ping: " + pingText + ", " + packetText));
		} else {
			client.player.sendSystemMessage(ChatUtil.prefixed(
					"§aConnected§7, but your internet connection seems unstable. §7Ping: " + pingText + " (±" + spread + "ms), " + packetText
			));
		}
	}
}
