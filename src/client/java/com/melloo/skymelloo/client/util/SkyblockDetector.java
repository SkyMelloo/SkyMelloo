package com.melloo.skymelloo.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;

import java.util.Locale;
import java.util.regex.Pattern;

// Whether the local player is in Hypixel SkyBlock specifically, checked via the sidebar scoreboard
// title containing "SKYBLOCK", distinct from just "connected to Hypixel". Re-checked once a second.
public final class SkyblockDetector {
	private static final int CHECK_INTERVAL_TICKS = 20; // 1s
	private static final Pattern FORMAT_CODE = Pattern.compile("§[0-9A-FK-ORa-fk-or]");

	private static volatile boolean inSkyblock = false;
	private static int tickCounter = 0;

	private SkyblockDetector() {
	}

	public static void tick(Minecraft client) {
		if (client.player == null || client.level == null) {
			inSkyblock = false;
			return;
		}
		tickCounter++;
		if (tickCounter % CHECK_INTERVAL_TICKS != 0) {
			return;
		}
		Scoreboard scoreboard = client.level.getScoreboard();
		Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (objective == null) {
			inSkyblock = false;
			return;
		}
		String title = FORMAT_CODE.matcher(objective.getDisplayName().getString()).replaceAll("");
		inSkyblock = title.toUpperCase(Locale.ROOT).contains("SKYBLOCK");
	}

	public static boolean isInSkyblock() {
		return inSkyblock;
	}
}
