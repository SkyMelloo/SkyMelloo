package com.melloo.skymelloo.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.Locale;

/**
 * Whether the local player is connected to Hypixel at all, as opposed to any other server or
 * singleplayer - checked via the connected server's own IP address, same signal PartyTracker
 * already used internally before this was pulled out into a shared utility so the rest of the mod
 * could gate on it too.
 */
public final class HypixelDetector {
	private HypixelDetector() {
	}

	public static boolean isHypixel(Minecraft client) {
		ServerData server = client.getCurrentServer();
		return server != null && server.ip != null && server.ip.toLowerCase(Locale.ROOT).contains("hypixel");
	}
}
