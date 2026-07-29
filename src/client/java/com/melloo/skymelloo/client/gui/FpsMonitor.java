package com.melloo.skymelloo.client.gui;

import com.melloo.skymelloo.client.util.RollingStats;
import net.minecraft.client.Minecraft;

/** Tracks FPS over a 10-second rolling window so the HUD can show current/average/1%-lows instead of just the instantaneous frame count. */
public final class FpsMonitor {
	private static final RollingStats HISTORY = new RollingStats(10);

	private FpsMonitor() {
	}

	public static void tick(Minecraft client) {
		HISTORY.addSample(client.getFps());
	}

	/** Average FPS over the last 10 seconds, or null if nothing's been sampled yet. */
	public static Double getAverageFps() {
		return HISTORY.hasSamples() ? HISTORY.average() : null;
	}

	/** "1% lows" over the last 10 seconds - the average of the worst (lowest) 1% of frame-rate samples, or null if nothing's been sampled yet. */
	public static Double getOnePercentLowFps() {
		return HISTORY.hasSamples() ? HISTORY.worstAverage(true) : null;
	}
}
