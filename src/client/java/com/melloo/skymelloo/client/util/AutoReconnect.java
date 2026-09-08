package com.melloo.skymelloo.client.util;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.util.Locale;
import java.util.Map;

// Automatically rejoins Hypixel after an unexpected disconnect. Hypixel-only, capped at
// MAX_CONSECUTIVE_ATTEMPTS. Only triggers on a real drop - a voluntary disconnect never shows DisconnectedScreen.
public final class AutoReconnect {
	private static final int MAX_CONSECUTIVE_ATTEMPTS = 3;
	// Long enough that Hypixel's own internal server-to-server transfers (island/dungeon/lobby hops)
	// complete on their own first, instead of racing them and causing a disconnect/reconnect cycle.
	private static final int RECONNECT_DELAY_TICKS = 12 * 20;
	private static final int RESET_STREAK_TICKS = 30 * 20;

	private static ServerData lastServer = null;
	private static ServerData lastKnownHypixelServer = null;
	// Set by the DISCONNECT event, consumed one tick later by checkInvoluntaryDisconnect.
	private static ServerData pendingInvoluntaryCheck = null;
	private static boolean pendingReconnect = false;
	private static int reconnectDelayTicks = 0;
	private static int consecutiveAttempts = 0;
	private static int ticksConnected = 0;

	private AutoReconnect() {
	}

	public static void init() {
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
			if (!config.autoReconnectEnabled) {
				return;
			}
			// Not client.getCurrentServer() - it's often already cleared by the time this fires.
			// lastKnownHypixelServer is kept fresh every tick instead (see tick() below).
			if (lastKnownHypixelServer == null) {
				return;
			}
			if (consecutiveAttempts >= MAX_CONSECUTIVE_ATTEMPTS) {
				return;
			}
			pendingInvoluntaryCheck = lastKnownHypixelServer;
		});
	}

	// Real host check, not a substring match - "hypixel.net.evil.com" must never pass this.
	private static boolean isHypixelHost(String ip) {
		if (ip == null) {
			return false;
		}
		String host = ServerAddress.parseString(ip).getHost();
		if (host == null) {
			return false;
		}
		host = host.toLowerCase(Locale.ROOT);
		return host.equals("hypixel.net") || host.endsWith(".hypixel.net");
	}

	// Runs once per disconnect, one tick later, once vanilla has settled which screen it's showing.
	private static void checkInvoluntaryDisconnect(Minecraft client) {
		ServerData server = pendingInvoluntaryCheck;
		pendingInvoluntaryCheck = null;
		if (server == null) {
			return;
		}
		if (!(client.screen instanceof DisconnectedScreen)) {
			return; // voluntary disconnect or already navigated elsewhere - never auto-rejoin
		}
		lastServer = server;
		reconnectDelayTicks = RECONNECT_DELAY_TICKS;
		pendingReconnect = true;
	}

	// Call once per client tick.
	public static void tick(Minecraft client) {
		if (client.getConnection() != null) {
			ServerData server = client.getCurrentServer();
			lastKnownHypixelServer = server != null && isHypixelHost(server.ip) ? server : null;
			// Once connected for a while, the attempt streak resets so an earlier rough patch doesn't eat into it.
			ticksConnected++;
			if (ticksConnected >= RESET_STREAK_TICKS) {
				consecutiveAttempts = 0;
			}
			return;
		}
		ticksConnected = 0;
		if (pendingInvoluntaryCheck != null) {
			checkInvoluntaryDisconnect(client);
		}
		if (!pendingReconnect) {
			return;
		}
		if (reconnectDelayTicks > 0) {
			reconnectDelayTicks--;
			return;
		}
		pendingReconnect = false;
		if (lastServer == null) {
			return;
		}
		consecutiveAttempts++;
		ServerAddress address = ServerAddress.parseString(lastServer.ip);
		ConnectScreen.startConnecting(client.screen, client, address, lastServer, false, new TransferState(Map.of(), Map.of(), false));
	}
}
