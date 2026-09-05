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

/**
 * Shared debug-message logging for background operations (syncs, permission/whitelist checks,
 * cloud sync, presence reporting, ...) that were previously silent even with debug messages on.
 * Gated by the master {@code debugMessagesEnabled} switch plus a per-category toggle, so a
 * player can narrow debug output down to just what they're actually trying to diagnose.
 * <p>
 * The IN-GAME CHAT echo specifically is throttled (see {@link #CHAT_THROTTLE_MILLIS}) - confirmed
 * directly from a real crash report: a burst of chat lines right as a dungeon starts (a lot happens
 * in Hypixel's own chat at that exact moment) tripped a null-pointer bug in Lunar Client's own
 * bundled "Enhanced Chat" mod ({@code ChatLineTracker.evictLine}) while it was evicting old lines -
 * our {@code sendSystemMessage} call was simply the one on the stack when it happened, not something
 * we can fix on our side directly. This USED to be deliberately unthrottled on the reasoning that
 * dropping a debug message meant losing real diagnostic info - but that's no longer true now that
 * {@link #log} always writes to the actual game log file first, completely unthrottled, regardless of
 * whether the chat echo below actually sends - so throttling the chat side costs nothing anymore.
 * <p>
 * "The actual game log file" turned out to mean a SECOND, dedicated file of our own
 * ({@code skymelloo-debug.log} in the game directory), not {@code SkyMellooClient.LOGGER.info(...)}
 * into {@code latest.log} like the rest of the mod's logging - confirmed live that under Lunar
 * Client, this mod's own SLF4J logger output never reaches {@code latest.log} at all (a whole
 * multi-hour session with this ticking constantly produced zero matching lines there, while other
 * mods' own loggers show up fine in the same file), for a reason not worth chasing further into
 * Lunar's closed-source logging setup. Writing our own file with plain {@code java.nio.file} I/O
 * sidesteps whatever's swallowing it, rather than depending on a routing path proven unreliable here.
 * Truncated fresh at the start of each launch (see {@link #FILE_WRITER}) so it only ever holds the
 * current session, not an ever-growing history.
 * <p>
 * Writes are buffered and flushed at most every {@link #FLUSH_INTERVAL_SECONDS} rather than after
 * every single line - presence reporting alone calls {@link #log} several times a second, and a
 * real {@code flush()} is a disk sync, not just an in-memory append. {@link Writer#write} still
 * happens immediately on every call (cheap, stays in the BufferedWriter's own in-memory buffer);
 * only the disk sync is debounced. A JVM shutdown hook does one final flush so a normal game close
 * doesn't lose whatever was written since the last periodic flush - this is a debug log, not
 * critical data, so losing the last couple seconds of it to a hard crash is an acceptable tradeoff
 * for not syncing to disk multiple times a second during normal play.
 */
public final class DebugLog {
	public enum Category {
		SYNC, PERMISSIONS, CLOUD_SYNC, PRESENCE, PARTY, DUNGEON, STAFF
	}

	private static final long CHAT_THROTTLE_MILLIS = 250;
	private static long lastChatMillis = 0;
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
	// Opened once, truncating any leftover file from a previous launch - kept open for the whole JVM
	// lifetime rather than reopened per line, since this can be called several times a second
	// (presence reporting) for a whole play session. null if it couldn't be opened at all (read-only
	// game dir, etc.) - every write silently no-ops rather than risking anything over a logging
	// nice-to-have.
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
		// Covers a normal game close (quit to title / process exit) between two periodic flushes -
		// without this, whatever was written in the last few seconds before shutdown is silently lost.
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

	/** Per-category LOCAL/PARTY delivery, same idea as every other dungeon/kill message this mod sends - lets e.g. Dungeon debug go to the party while everything else stays local. */
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
		// Always written to skymelloo-debug.log (see class doc comment for why that's a dedicated
		// file rather than latest.log), completely independent of the toggles below - those only ever
		// gated the in-game CHAT echo. Without this, a bug that only shows up with a category's debug
		// toggle off (the normal case - nobody plays with debug chat spam on) left literally zero
		// evidence anywhere to diagnose it from afterward. Also still sent to the normal SLF4J logger -
		// harmless, and still correct in whatever environment doesn't have Lunar's swallowing issue.
		SkyMellooClient.LOGGER.info("[{}] {}", category, message);
		writeToFile(category, message);
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.debugMessagesEnabled || !categoryEnabled(category, config)) {
			return;
		}
		// Permission internals (which feature keys exist, what this account is/isn't granted) are
		// only useful for diagnosing the permission system itself - not something a normal user
		// should see, even with debug messages on.
		if (category == Category.PERMISSIONS && !WhitelistManager.isAdmin()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}
		// Log file already has this line regardless (see above) - skipping the chat echo during a burst
		// loses nothing, it just avoids being the message that triggers Enhanced Chat's eviction bug.
		long now = System.currentTimeMillis();
		if (now - lastChatMillis < CHAT_THROTTLE_MILLIS) {
			return;
		}
		lastChatMillis = now;
		String delivery = deliveryFor(category, config);
		if ("PARTY SM".equalsIgnoreCase(delivery)) {
			// Debug messages are inherently personal (each client's OWN sync/permission/presence
			// activity), never a shared fact duplicated across every SM party member's client - unlike
			// DungeonRunTracker's "PARTY SM" option, this one is never leader-gated.
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
