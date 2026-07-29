package com.melloo.skymelloo.client.gui;

import com.melloo.skymelloo.client.util.RollingStats;

/**
 * A rough server TPS estimate, since Hypixel exposes no real TPS value to the client through any
 * signal this mod has found. Instead of a made-up number, this measures the ACTUAL rate the server's
 * {@code gameTime} advances relative to real wall-clock time between consecutive
 * {@code ClientboundSetTimePacket}s (see {@link com.melloo.skymelloo.client.mixin.SetTimePacketMixin}) -
 * if the server is genuinely running behind 20 ticks/second, gameTime falls behind real time by
 * exactly that much, the same principle every "TPS estimator" utility mod uses.
 * <p>
 * Was a flat rolling average over the last 10 samples - since the server only sends one of these
 * packets per second, that meant a real TPS change took up to ~10 seconds to fully show, diluted
 * evenly the whole way. This is an exponential moving average instead: every new sample immediately
 * pulls the shown value most of the way toward what just happened, and a reading shows up after the
 * very first sample rather than waiting for 3, for a much faster, always-current reading. One sample per
 * second is still the hard ceiling on update rate (that's how often the server actually sends this
 * packet, nothing this mod can speed up), but each one now counts for a lot more immediately.
 */
public final class TpsEstimator {
	private static final double EMA_ALPHA = 0.5;

	private static Double smoothedTps = null;
	private static long lastRealTime = -1;
	private static long lastGameTime = -1;
	// 10 second window for the average/1%-low readout - "1% letzte 10 sekunden".
	private static final RollingStats HISTORY = new RollingStats(10);

	private TpsEstimator() {
	}

	public static void onSetTimePacket(long gameTime) {
		long now = System.currentTimeMillis();
		if (lastRealTime < 0) {
			lastRealTime = now;
			lastGameTime = gameTime;
			return;
		}
		long gameDelta = gameTime - lastGameTime;
		long realDeltaMillis = now - lastRealTime;
		lastRealTime = now;
		lastGameTime = gameTime;
		// A huge gameTime jump (day/time change command, /time set, rejoining) isn't a tick-rate
		// signal - skip it rather than let it yank the estimate toward a nonsense reading.
		if (gameDelta <= 0 || gameDelta >= 200 || realDeltaMillis <= 0) {
			return;
		}
		double instantTps = Math.min(20.0, gameDelta / (realDeltaMillis / 1000.0));
		smoothedTps = smoothedTps == null ? instantTps : smoothedTps * (1 - EMA_ALPHA) + instantTps * EMA_ALPHA;
		HISTORY.addSample(instantTps);
	}

	/** {@code null} until the very first valid sample has come in. */
	public static Integer getEstimatedTps() {
		return smoothedTps == null ? null : (int) Math.round(smoothedTps);
	}

	/** Average TPS over the last 10 seconds, or null if nothing's been sampled yet. */
	public static Double getAverageTps() {
		return HISTORY.hasSamples() ? HISTORY.average() : null;
	}

	/** "1% lows" over the last 10 seconds - the average of the worst (lowest) 1% of samples, or null if nothing's been sampled yet. */
	public static Double getOnePercentLowTps() {
		return HISTORY.hasSamples() ? HISTORY.worstAverage(true) : null;
	}
}
