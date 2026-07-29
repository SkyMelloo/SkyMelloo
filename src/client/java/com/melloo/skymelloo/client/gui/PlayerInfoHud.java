package com.melloo.skymelloo.client.gui;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.social.HypixelLocationTracker;
import com.melloo.skymelloo.client.util.ServerPingMonitor;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ServerData;

import java.util.ArrayList;
import java.util.List;

/**
 * FPS, ping, TPS, server address, coordinates, area (from {@link HypixelLocationTracker}'s real
 * Hypixel Mod API data, not a guess), and facing direction - all real, directly-readable/measured
 * client values. FPS, TPS, and the real (non-Hypixel-reported) ping each show as
 * "current average (1%low)" - a 1-minute rolling average plus the "1% lows" stutter-sensitive
 * benchmark metric, not just an instantaneous number - and are color-coded green/orange/red by how
 * good the CURRENT reading is. Numbers are fixed-width padded (a leading space instead of shrinking)
 * so the line doesn't visually jump around as digit counts change.
 */
public final class PlayerInfoHud implements HudElement {
	public static final PlayerInfoHud INSTANCE = new PlayerInfoHud();

	private static final String[] COMPASS_DIRECTIONS = { "S", "SW", "W", "NW", "N", "NE", "E", "SE" };

	private PlayerInfoHud() {
	}

	private static String compassDirection(float yaw) {
		float normalized = yaw % 360F;
		if (normalized < 0) {
			normalized += 360F;
		}
		int index = Math.round(normalized / 45F) % 8;
		return COMPASS_DIRECTIONS[index];
	}

	/** Green/orange/red by how good {@code current} is - {@code higherIsBetter} true for FPS/TPS, false for ping. */
	private static String colorFor(double current, double goodThreshold, double badThreshold, boolean higherIsBetter) {
		boolean good = higherIsBetter ? current >= goodThreshold : current <= goodThreshold;
		boolean bad = higherIsBetter ? current <= badThreshold : current >= badThreshold;
		if (good) {
			return "§a";
		}
		if (bad) {
			return "§c";
		}
		return "§6";
	}

	/** "current average (1%low)", padded to {@code padWidth} so the line doesn't shift as digit counts change, colored by how good the current reading is. */
	private static String formatStat(String label, int current, Double average, Double worst, int padWidth, double goodThreshold, double badThreshold, boolean higherIsBetter, String unit) {
		String color = colorFor(current, goodThreshold, badThreshold, higherIsBetter);
		String currentStr = String.format("%" + padWidth + "d", current);
		String avgStr = average != null ? String.format("%" + padWidth + "d", Math.round(average)) : "--";
		String worstStr = worst != null ? String.valueOf(Math.round(worst)) : "--";
		return "§r" + label + ": " + color + currentStr + unit + " §7" + avgStr + unit + " §8(" + worstStr + unit + ")";
	}

	/**
	 * Builds the exact same lines the real HUD renders, given the current live client state - shared
	 * by {@link #extractRenderState} and by {@link com.melloo.skymelloo.client.gui.HudLayoutEditorScreen}'s
	 * placeholder box, so the editor's preview size can never drift from what actually renders (it
	 * used to guess from two hardcoded sample lines, which was wider than reality whenever the
	 * player's actual FPS/ping/server/area text was shorter than those samples - centering the wide
	 * editor box then left the real, narrower box off-center once the editor closed). Safe to reuse
	 * for sizing (unlike Fishing Combo/Dungeon Score/Debug, this HUD has no "hidden right now" state
	 * to preserve a placeholder for - it's on whenever a player exists and the toggle is enabled).
	 */
	public static List<String> buildLines(Minecraft client) {
		List<String> lines = new ArrayList<>();
		lines.add(formatStat("FPS", client.getFps(), FpsMonitor.getAverageFps(), FpsMonitor.getOnePercentLowFps(), 3, 60, 30, true, ""));
		// Only the independently-measured reading (the same server-list-ping mechanism vanilla's own
		// multiplayer screen uses for its ping bars) - not Hypixel's own server-reported tab latency,
		// which is what didn't make sense in the first place ("1ms").
		Long realPing = ServerPingMonitor.getPingMillis();
		if (realPing != null) {
			Double avgPing = ServerPingMonitor.getAveragePingMillis();
			Double worstPing = ServerPingMonitor.getWorstPingMillis();
			lines.add(formatStat("Ping", realPing.intValue(), avgPing, worstPing, 3, 80, 200, false, "ms"));
		}
		Integer tps = TpsEstimator.getEstimatedTps();
		if (tps != null) {
			Double avgTps = TpsEstimator.getAverageTps();
			Double worstTps = TpsEstimator.getOnePercentLowTps();
			lines.add(formatStat("TPS", tps, avgTps, worstTps, 2, 18, 10, true, ""));
		}
		ServerData server = client.getCurrentServer();
		if (server != null && server.ip != null) {
			lines.add("§rServer: §f" + server.ip);
		}
		String map = HypixelLocationTracker.getMap();
		if (map != null) {
			lines.add("§rArea: §f" + map);
		}
		if (client.player != null) {
			lines.add("§rXYZ: §f" + (int) client.player.getX() + " " + (int) client.player.getY() + " " + (int) client.player.getZ());
			lines.add("§rFacing: §f" + compassDirection(client.player.getYRot()));
		}
		return lines;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gg, DeltaTracker deltaTracker) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.playerInfoHudEnabled) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}

		List<String> lines = buildLines(client);

		int x = config.hudPlayerInfoX;
		int y = config.hudPlayerInfoY;
		int width = 8;
		for (String line : lines) {
			width = Math.max(width, client.font.width(line) + 8);
		}
		int height = 4 + lines.size() * 10 + 2;

		gg.fill(x - 4, y - 3, x + width, y + height, 0x99101018);
		int lineY = y;
		for (String line : lines) {
			gg.text(client.font, line, x, lineY, 0xFFFFFFFF);
			lineY += 10;
		}
	}
}
