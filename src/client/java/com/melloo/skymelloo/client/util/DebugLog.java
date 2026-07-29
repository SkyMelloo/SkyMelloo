package com.melloo.skymelloo.client.util;

import com.melloo.skymelloo.client.SkyMellooClient;
import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.social.WhitelistManager;
import net.minecraft.client.Minecraft;

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
 */
public final class DebugLog {
	public enum Category {
		SYNC, PERMISSIONS, CLOUD_SYNC, PRESENCE, PARTY, DUNGEON, STAFF
	}

	private static final long CHAT_THROTTLE_MILLIS = 250;
	private static long lastChatMillis = 0;

	private DebugLog() {
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
		// Always written to the actual game log file (latest.log), completely independent of the
		// toggles below - those only ever gated the in-game CHAT echo. Without this, a bug that only
		// shows up with a category's debug toggle off (the normal case - nobody plays with debug chat
		// spam on) left literally zero evidence anywhere to diagnose it from afterward.
		SkyMellooClient.LOGGER.info("[{}] {}", category, message);
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
			com.melloo.skymelloo.client.social.RelayChatManager.sendPartyAnnouncement(client, "§8[Debug] §7" + message);
		} else if ("PARTY".equalsIgnoreCase(delivery) && com.melloo.skymelloo.client.party.PartyTracker.isInParty()) {
			// § codes don't survive /pc as a command string - Hypixel strips the § itself but leaves
			// its format-code letter behind as literal text, which is worse than no color at all.
			client.player.connection.sendCommand("pc " + ChatUtil.partyPrefixed("[Debug] " + message));
		} else {
			client.player.sendSystemMessage(ChatUtil.prefixed("§8[Debug] §7" + message));
		}
	}
}
