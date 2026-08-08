package com.melloo.skymelloo.client.util;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * Keeps a rolling window (by real wall-clock time, not sample count) of numeric samples and derives
 * the average plus "1% lows" - the average of the worst 1% of samples in the window, the same
 * stutter-sensitive metric FPS benchmarks use, since a plain average hides brief bad spikes. For a
 * metric where HIGHER is worse (ping), use {@link #worstAverage(boolean)} with {@code false} to get
 * the worst-1%-HIGH average instead - the equivalent "worst case" reading for that direction.
 * <p>
 * Every method is synchronized - real crash confirmed live: {@link ServerPingMonitor} adds samples
 * from the ping-out Netty event loop thread (see its own {@code EventLoopGroupHolder.remote} call),
 * while the HUD reads them from the render thread. Plain {@link ArrayDeque} has no thread-safety
 * guarantees at all, and its fail-fast iterator threw a real {@code ConcurrentModificationException}
 * (inside {@link #worstAverage}'s stream) the moment a sample got added mid-read. Contention here is
 * trivially low (one write roughly per second, occasional HUD-render reads), so plain synchronization
 * is simpler and just as fast in practice as a lock-free structure would be.
 */
public final class RollingStats {
	private record Sample(long timestampMillis, double value) {
	}

	private final long windowMillis;
	private final Deque<Sample> samples = new ArrayDeque<>();

	public RollingStats(int windowSeconds) {
		this.windowMillis = windowSeconds * 1000L;
	}

	public synchronized void addSample(double value) {
		long now = System.currentTimeMillis();
		samples.addLast(new Sample(now, value));
		long cutoff = now - windowMillis;
		while (!samples.isEmpty() && samples.peekFirst().timestampMillis() < cutoff) {
			samples.pollFirst();
		}
	}

	public synchronized boolean hasSamples() {
		return !samples.isEmpty();
	}

	public synchronized double average() {
		if (samples.isEmpty()) {
			return 0;
		}
		double sum = 0;
		for (Sample s : samples) {
			sum += s.value();
		}
		return sum / samples.size();
	}

	/** @param lowest true for "1% lows" (worst-case where LOWER is worse, e.g. FPS/TPS), false for the worst-case where HIGHER is worse (e.g. ping spikes). */
	public synchronized double worstAverage(boolean lowest) {
		if (samples.isEmpty()) {
			return 0;
		}
		List<Double> sorted = samples.stream()
				.map(Sample::value)
				.sorted(lowest ? Comparator.naturalOrder() : Comparator.reverseOrder())
				.toList();
		int count = Math.max(1, (int) Math.ceil(sorted.size() * 0.01));
		double sum = 0;
		for (int i = 0; i < count; i++) {
			sum += sorted.get(i);
		}
		return sum / count;
	}
}
