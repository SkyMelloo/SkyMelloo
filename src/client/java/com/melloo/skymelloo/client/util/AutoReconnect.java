package com.melloo.skymelloo.client.util;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.util.Locale;
import java.util.Map;

/**
 * Automatically rejoins Hypixel after an unexpected disconnect - {@link ConnectScreen#startConnecting}
 * is the same real API vanilla's own "Join Server" button uses (confirmed via javap against this
 * game version, not guessed), so this isn't a hack, just calling it ourselves after a short delay
 * instead of waiting for a manual click.
 * <p>
 * Deliberately Hypixel-only (never auto-rejoins some other server) and capped at
 * {@link #MAX_CONSECUTIVE_ATTEMPTS} attempts in a row - a real ban/kick would otherwise mean silently
 * hammering reconnect attempts forever, which is exactly the kind of automated-looking behavior this
 * should never do. The streak resets once a connection actually holds for a while
 * ({@link #RESET_STREAK_TICKS}), so a single bad patch of instability doesn't permanently use up the
 * budget for a real problem later in the session.
 */
public final class AutoReconnect {
	private static final int MAX_CONSECUTIVE_ATTEMPTS = 3;
	private static final int RECONNECT_DELAY_TICKS = 5 * 20;
	private static final int RESET_STREAK_TICKS = 30 * 20;

	private static ServerData lastServer = null;
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
			ServerData server = client.getCurrentServer();
			if (server == null || server.ip == null || !server.ip.toLowerCase(Locale.ROOT).contains("hypixel")) {
				return;
			}
			if (consecutiveAttempts >= MAX_CONSECUTIVE_ATTEMPTS) {
				return;
			}
			lastServer = server;
			reconnectDelayTicks = RECONNECT_DELAY_TICKS;
			pendingReconnect = true;
		});
	}

	/** Call once per client tick. */
	public static void tick(Minecraft client) {
		if (client.getConnection() != null) {
			// Currently connected - once it's held for a while, the attempt streak resets, so a single
			// rough patch earlier in the session doesn't permanently eat into the budget for later.
			ticksConnected++;
			if (ticksConnected >= RESET_STREAK_TICKS) {
				consecutiveAttempts = 0;
			}
			return;
		}
		ticksConnected = 0;
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
