package com.melloo.skymelloo.client.util;

import java.util.ArrayList;
import java.util.List;

// Runs a callback a fixed number of client ticks from now, to stagger actions and avoid tripping
// Hypixel's chat rate limit. Not a general-purpose scheduler, just a flat list checked per tick.
public final class TickDelay {
	private record Pending(int[] ticksRemaining, Runnable task) {
	}

	private static final List<Pending> pending = new ArrayList<>();

	private TickDelay() {
	}

	public static void schedule(int delayTicks, Runnable task) {
		pending.add(new Pending(new int[]{delayTicks}, task));
	}

	// Iterates a snapshot, not the live list - a task run here can itself call schedule() again,
	// which would otherwise throw ConcurrentModificationException while the loop is still iterating.
	public static void tick() {
		if (pending.isEmpty()) {
			return;
		}
		List<Pending> snapshot = new ArrayList<>(pending);
		List<Pending> completed = new ArrayList<>();
		for (Pending entry : snapshot) {
			if (--entry.ticksRemaining()[0] > 0) {
				continue;
			}
			completed.add(entry);
			entry.task().run();
		}
		pending.removeAll(completed);
	}
}
