package com.melloo.skymelloo.client.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a callback a fixed number of client ticks from now - used to stagger a sequence of actions
 * (e.g. one /pc announcement per party member) rather than firing them back-to-back, which risks
 * tripping Hypixel's own chat rate limit. Not a general-purpose scheduler: just a flat list checked
 * once per tick, fine for the handful of concurrent delays this mod ever actually needs.
 */
public final class TickDelay {
	private record Pending(int[] ticksRemaining, Runnable task) {
	}

	private static final List<Pending> pending = new ArrayList<>();

	private TickDelay() {
	}

	public static void schedule(int delayTicks, Runnable task) {
		pending.add(new Pending(new int[]{delayTicks}, task));
	}

	/**
	 * Call once per client tick. Iterates a snapshot rather than the live list directly - confirmed
	 * directly from a real crash report: a task run from here (e.g. a delayed run-report) can itself
	 * call {@link #schedule} again (a long party announcement splitting into several staggered chunks -
	 * see DungeonRunTracker#sendDungeonMessage), which used to throw ConcurrentModificationException by
	 * mutating {@code pending} while the old removeIf() was still iterating it. A newly-scheduled entry
	 * added mid-tick this way just isn't in this tick's snapshot, so it's picked up starting next tick -
	 * correct anyway, since it was scheduled partway through the current one.
	 */
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
