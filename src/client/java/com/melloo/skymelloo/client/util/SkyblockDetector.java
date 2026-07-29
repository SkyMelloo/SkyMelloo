package com.melloo.skymelloo.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Whether the local player is actually in Hypixel SkyBlock right now, as opposed to the main
 * Hypixel lobby, another minigame, or a different server entirely - checked via the sidebar
 * scoreboard title containing "SKYBLOCK", the same well-established signal every other third-party
 * SkyBlock mod (Skyblocker, NEU, Firmament) uses, rather than just "connected to Hypixel" (this
 * mod's existing Hypixel-IP checks, e.g. {@code PartyTracker#isHypixel}, don't distinguish SkyBlock
 * from any other Hypixel gamemode - several HUD elements, e.g. the health/mana bars and the party
 * HUD, were showing on ANY Hypixel gamemode, not just SkyBlock, until this existed).
 * <p>
 * Re-checked once a second rather than every tick - the sidebar title doesn't change fast enough to
 * need tighter polling, and this can get called from HUD render code every frame.
 */
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
