package com.melloo.skymelloo.client.util;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

// Best-effort wipe of Lunar Client's resource-pack download cache after a load-failure disconnect.
// Sweeps once immediately, then again a few seconds later once a still-open file handle has closed.
public final class LunarPackCacheCleaner {
	private static final int RETRY_DELAY_TICKS = 3 * 20;

	private static boolean pendingRetry = false;
	private static int retryTicks = 0;

	private LunarPackCacheCleaner() {
	}

	public static void clearNowAndRetry() {
		sweep();
		pendingRetry = true;
		retryTicks = RETRY_DELAY_TICKS;
	}

	public static void tick(Minecraft client) {
		if (!pendingRetry) {
			return;
		}
		if (retryTicks > 0) {
			retryTicks--;
			return;
		}
		pendingRetry = false;
		sweep();
	}

	private static void sweep() {
		try {
			Path downloads = FabricLoader.getInstance().getGameDir().resolve("downloads");
			if (!Files.isDirectory(downloads)) {
				return;
			}
			try (var stream = Files.walk(downloads)) {
				stream.sorted(Comparator.reverseOrder()).forEach(path -> {
					try {
						Files.deleteIfExists(path);
					} catch (IOException ignored) {
						// Still locked - the retry pass a few seconds later gets another shot at it.
					}
				});
			}
		} catch (Exception ignored) {
		}
	}
}
