package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.util.ChatUtil;
import com.melloo.mellooessentials.client.util.HypixelDetector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

// Samples ping every tick for the first 5s after connecting to Hypixel, then chat-reports
// stability plus packet-rate averages. Started from INIT (fires before the player/world exist).
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

	public static void start(Minecraft client) {
		if (!SkyMellooConfig.HANDLER.instance().connectionQualityCheckEnabled || !HypixelDetector.isHypixel(client)) {
			return;
		}
		// Hypixel moves you between internal sub-servers via the same transfer mechanism as a real
		// reconnect, re-firing INIT without you leaving the network - only measure on an address change.
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
			// Zero samples across the whole window is itself a bad sign.
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

	private static void report(Minecraft client, boolean stable, int avgPing, int spread, float sent, float received) {
		if (client.player == null) {
			return;
		}
		String pingText = avgPing >= 0 ? Component.translatable("skymelloo.chat.connection.ping_value", avgPing).getString() : Component.translatable("skymelloo.chat.connection.ping_unknown").getString();
		String packetText = Component.translatable("skymelloo.chat.connection.packet_rate", String.format("%.1f", sent), String.format("%.1f", received)).getString();
		if (stable) {
			client.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.chat.connection.stable", pingText, packetText)));
		} else {
			client.player.sendSystemMessage(ChatUtil.prefixed(
					Component.translatable("skymelloo.chat.connection.unstable", pingText, spread, packetText)
			));
		}
	}
}
