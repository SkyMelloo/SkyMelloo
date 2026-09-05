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
 * <p>
 * Only ever triggers for a REAL drop (kick, timeout, connection loss) - never for the player
 * deliberately leaving. Verified directly against this game version's own decompiled source
 * (Minecraft#disconnectFromWorld, the pause menu's "Disconnect" button target, vs.
 * ClientCommonPacketListenerImpl#onDisconnect, the real-drop path): a voluntary disconnect closes
 * the connection and goes straight to TitleScreen/JoinMultiplayerScreen, while a real kick/timeout/
 * connection loss is the ONLY path that ever shows {@link DisconnectedScreen}. See
 * {@link #checkInvoluntaryDisconnect} for how that's used here - checked one tick after the
 * disconnect event rather than inside the event itself, since the event's own firing point relative
 * to vanilla's screen assignment isn't something this mod's code controls or can rely on.
 */
public final class AutoReconnect {
	private static final int MAX_CONSECUTIVE_ATTEMPTS = 3;
	// Was 5s - too short to reliably tell a real disconnect apart from Hypixel's own internal
	// server-to-server transfers (island/dungeon/lobby hops), which can briefly look like a
	// disconnect at the vanilla client level and complete on their own within a few seconds. Racing
	// our own reconnect attempt against Hypixel's still-in-progress one caused real disconnect/
	// reconnect cycling on ordinary server hops, not just genuine drops.
	private static final int RECONNECT_DELAY_TICKS = 12 * 20;
	private static final int RESET_STREAK_TICKS = 30 * 20;

	private static ServerData lastServer = null;
	private static ServerData lastKnownHypixelServer = null;
	// Set by the DISCONNECT event, consumed one tick later by checkInvoluntaryDisconnect - see the
	// class doc comment for why this can't just be decided inside the event handler itself.
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
			// Deliberately NOT client.getCurrentServer() here - by the time this event fires (any
			// disconnect, manual or not), the client has often already cleared it, which silently
			// skipped reconnecting no matter how the disconnect actually happened. lastKnownHypixelServer
			// is kept fresh every tick instead (see tick() below), so it still reflects the real server
			// from the moment before the disconnect regardless of what's already been torn down.
			if (lastKnownHypixelServer == null) {
				return;
			}
			if (consecutiveAttempts >= MAX_CONSECUTIVE_ATTEMPTS) {
				return;
			}
			pendingInvoluntaryCheck = lastKnownHypixelServer;
		});
	}

	/** Real host check, not a substring match - "definitely-not-hypixel.net.evil.com" or a server named "myHypixelProxy" must never pass this. */
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

	/**
	 * Runs exactly once per disconnect, on the first tick after it happened - by then vanilla has
	 * fully settled which screen it's showing (see the class doc comment), which is the one reliable
	 * signal for "was this actually a drop, not the player choosing to leave".
	 */
	private static void checkInvoluntaryDisconnect(Minecraft client) {
		ServerData server = pendingInvoluntaryCheck;
		pendingInvoluntaryCheck = null;
		if (server == null) {
			return;
		}
		if (!(client.screen instanceof DisconnectedScreen)) {
			// Voluntary (pause-menu "Disconnect") or the player has already navigated elsewhere on
			// their own - never auto-rejoin something nobody asked to go back to.
			return;
		}
		lastServer = server;
		reconnectDelayTicks = RECONNECT_DELAY_TICKS;
		pendingReconnect = true;
	}

	/** Call once per client tick. */
	public static void tick(Minecraft client) {
		if (client.getConnection() != null) {
			// Kept up to date every tick while actually connected, specifically so the DISCONNECT
			// handler above always has a valid server to reconnect to even if the client has already
			// cleared getCurrentServer() by the time that event actually fires.
			ServerData server = client.getCurrentServer();
			lastKnownHypixelServer = server != null && isHypixelHost(server.ip) ? server : null;
			// Currently connected - once it's held for a while, the attempt streak resets, so a single
			// rough patch earlier in the session doesn't permanently eat into the budget for later.
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
