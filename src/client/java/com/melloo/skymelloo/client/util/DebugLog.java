package com.melloo.skymelloo.client.util;

import com.melloo.skymelloo.client.SkyMellooClient;
import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.social.WhitelistManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// Shared debug logging, gated by a master switch + per-category toggle. Writes to a dedicated
// skymelloo-debug.log, since SLF4J output doesn't reach latest.log under Lunar Client.
public final class DebugLog {
	public enum Category {
		SYNC, PERMISSIONS, CLOUD_SYNC, PRESENCE, PARTY, DUNGEON, STAFF
	}

	// Avoids tripping a null-pointer bug in Lunar Client's Enhanced Chat mod during a chat burst.
	private static final long CHAT_THROTTLE_MILLIS = 250;
	private static long lastChatMillis = 0;
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
	// Opened once for the whole JVM lifetime; null if it couldn't be opened, in which case writes silently no-op.
	private static final int FLUSH_INTERVAL_SECONDS = 5;
	private static final Writer FILE_WRITER = openFile();
	private static final AtomicBoolean DIRTY = new AtomicBoolean(false);
	private static final ScheduledExecutorService FLUSH_EXECUTOR = startFlushScheduler();

	private DebugLog() {
	}

	private static Writer openFile() {
		try {
			return Files.newBufferedWriter(
					FabricLoader.getInstance().getGameDir().resolve("skymelloo-debug.log"),
					StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		} catch (IOException e) {
			return null;
		}
	}

	private static ScheduledExecutorService startFlushScheduler() {
		if (FILE_WRITER == null) {
			return null;
		}
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "skymelloo-debuglog-flush");
			thread.setDaemon(true);
			return thread;
		});
		executor.scheduleWithFixedDelay(DebugLog::flushIfDirty, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
		// Flushes anything written since the last periodic flush on a normal game close.
		Runtime.getRuntime().addShutdownHook(new Thread(DebugLog::flushIfDirty, "skymelloo-debuglog-shutdown-flush"));
		return executor;
	}

	private static synchronized void flushIfDirty() {
		if (FILE_WRITER == null || !DIRTY.compareAndSet(true, false)) {
			return;
		}
		try {
			FILE_WRITER.flush();
		} catch (IOException ignored) {
			// Best-effort - losing buffered debug lines to a transient IO error isn't worth handling further.
		}
	}

	private static synchronized void writeToFile(Category category, String message) {
		if (FILE_WRITER == null) {
			return;
		}
		try {
			FILE_WRITER.write("[" + TIME_FORMAT.format(LocalTime.now()) + "] [" + category + "] " + message + System.lineSeparator());
			DIRTY.set(true);
		} catch (IOException ignored) {
			// Best-effort - losing one debug line to a transient IO error isn't worth handling further.
		}
	}

	private static boolean categoryEnabled(Category category, SkyMellooConfig config) {
		return switch (category) {
			case SYNC -> config.debugSync;
			case PERMISSIONS -> config.debugPermissions;
			case CLOUD_SYNC -> config.debugCloudSync;
			case PRESENCE -> config.debugPresence;
			case PARTY -> config.debugParty;
			case DUNGEON -> config.debugDungeon;
			case STAFF -> config.debugStaff;
		};
	}

	// Per-category LOCAL/PARTY delivery - e.g. Dungeon debug can go to the party while others stay local.
	private static String deliveryFor(Category category, SkyMellooConfig config) {
		return switch (category) {
			case SYNC -> config.debugSyncDelivery;
			case PERMISSIONS -> config.debugPermissionsDelivery;
			case CLOUD_SYNC -> config.debugCloudSyncDelivery;
			case PRESENCE -> config.debugPresenceDelivery;
			case PARTY -> config.debugPartyDelivery;
			case DUNGEON -> config.debugDungeonDelivery;
			case STAFF -> config.debugStaffDelivery;
		};
	}

	public static void log(Category category, String message) {
		// Always written to the file and SLF4J logger, independent of the toggles below - those only gate the chat echo.
		SkyMellooClient.LOGGER.info("[{}] {}", category, message);
		writeToFile(category, message);
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.debugMessagesEnabled || !categoryEnabled(category, config)) {
			return;
		}
		// Permission internals aren't something a normal user should see, even with debug messages on.
		if (category == Category.PERMISSIONS && !WhitelistManager.isAdmin()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}
		// The file already has this line regardless - skipping the chat echo during a burst loses nothing.
		long now = System.currentTimeMillis();
		if (now - lastChatMillis < CHAT_THROTTLE_MILLIS) {
			return;
		}
		lastChatMillis = now;
		String delivery = deliveryFor(category, config);
		if ("PARTY SM".equalsIgnoreCase(delivery)) {
			// Debug messages are per-client, not a shared fact - unlike DungeonRunTracker's "PARTY SM", never leader-gated.
			client.player.sendSystemMessage(ChatUtil.prefixed("§8[Debug] §7" + message));
			com.melloo.mellooessentials.client.social.RelayChatManager.sendPartyAnnouncement(client, "§8[Debug] §7" + message);
		} else if ("PARTY".equalsIgnoreCase(delivery) && com.melloo.skymelloo.client.party.PartyTracker.isInParty()) {
			// § codes don't survive /pc as a command string - Hypixel strips the § itself but leaves
			// its format-code letter behind as literal text, which is worse than no color at all.
			client.player.connection.sendCommand("pc " + ChatUtil.partyPrefixed("[Debug] " + message));
		} else {
			client.player.sendSystemMessage(ChatUtil.prefixed("§8[Debug] §7" + message));
		}
	}
}
