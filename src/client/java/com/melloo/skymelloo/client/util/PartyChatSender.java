package com.melloo.skymelloo.client.util;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;

import java.util.regex.Pattern;

// Central choke point for every /pc send, so a Hypixel rate-limit rejection can retry the dropped
// message. Only remembers the one most recently sent command, within a short retry window.
public final class PartyChatSender {
	private static final Pattern RATE_LIMITED = Pattern.compile("(?i)woah slow down, you're doing that too fast!");
	private static final int RETRY_DELAY_TICKS = 100; // ~5s - clear of whatever window triggered the limit
	private static final long RETRY_ELIGIBLE_WINDOW_MILLIS = 3000;
	private static final int MAX_RETRIES = 1;

	private static boolean initialized = false;
	private static String lastSentCommand = null;
	private static long lastSentMillis = 0;
	private static int lastSentRetries = 0;

	private PartyChatSender() {
	}

	public static void init() {
		if (initialized) {
			return;
		}
		initialized = true;
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (lastSentCommand == null || !RATE_LIMITED.matcher(message.getString()).find()) {
				return;
			}
			long now = System.currentTimeMillis();
			if (now - lastSentMillis > RETRY_ELIGIBLE_WINDOW_MILLIS || lastSentRetries >= MAX_RETRIES) {
				return;
			}
			lastSentRetries++;
			String toRetry = lastSentCommand;
			DebugLog.log(DebugLog.Category.PARTY, "Party chat rate-limited - retrying in " + (RETRY_DELAY_TICKS / 20) + "s: " + toRetry);
			TickDelay.schedule(RETRY_DELAY_TICKS, () -> {
				Minecraft client = Minecraft.getInstance();
				if (client.player != null && client.player.connection != null) {
					client.player.connection.sendCommand(toRetry);
				}
			});
		});
	}

	public static void send(Minecraft client, String rawText) {
		if (client.player == null || client.player.connection == null) {
			return;
		}
		String command = "pc " + rawText;
		lastSentCommand = command;
		lastSentMillis = System.currentTimeMillis();
		lastSentRetries = 0;
		client.player.connection.sendCommand(command);
	}
}
