package com.melloo.skymelloo.client.party;

import com.melloo.skymelloo.client.util.DebugLog;
import com.melloo.mellooessentials.client.util.HypixelDetector;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.hypixel.modapi.HypixelModAPI;
import net.hypixel.modapi.error.BuiltinErrorReason;
import net.hypixel.modapi.packet.impl.clientbound.ClientboundPartyInfoPacket;
import net.hypixel.modapi.packet.impl.serverbound.ServerboundPartyInfoPacket;
import net.minecraft.client.Minecraft;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

// Tracks the local player's current Hypixel party via HypixelModAPI's request/response packet.
// Refreshes shortly after join, then whenever a chat line loosely mentions "party" (debounced).
public final class PartyTracker {
	private static final int JOIN_DELAY_TICKS = 40;
	private static final int REFRESH_DEBOUNCE_TICKS = 40; // ~2s
	// Rate-limit errors need more breathing room than the normal debounce before retrying.
	private static final int RATE_LIMIT_BACKOFF_TICKS = 400; // ~20s

	// Matches self-leave/auto-disband/leader-disband chat lines by suffix (a name may have a
	// rank/cosmetic icon glued to it).
	private static final Pattern CONFIRMED_LEFT_PARTY = Pattern.compile("(?i)you left the party\\.|the party was disbanded because|has disbanded the party!");

	private static volatile Set<UUID> members = Collections.emptySet();
	// Separate from members.isEmpty(): an empty set is ambiguous (no party vs. no response yet).
	private static volatile boolean inParty = false;
	private static volatile UUID leaderUuid = null;
	private static boolean handlerRegistered = false;
	private static int joinDelayTicks = -1;
	private static int refreshCooldownTicks = 0;

	private PartyTracker() {
	}

	public static void init() {
		if (handlerRegistered) {
			return;
		}
		handlerRegistered = true;
		HypixelModAPI.getInstance().createHandler(ClientboundPartyInfoPacket.class, packet -> {
			inParty = packet.isInParty();
			members = inParty ? packet.getMembers() : Collections.emptySet();
			leaderUuid = inParty ? packet.getLeader().orElse(null) : null;
		}).onError(error -> {
			// A failed response means "unknown right now", not "confirmed no party" - keep the last
			// known state instead of collapsing the Party HUD on a transient blip.
			if (error == BuiltinErrorReason.RATE_LIMITED) {
				refreshCooldownTicks = Math.max(refreshCooldownTicks, RATE_LIMIT_BACKOFF_TICKS);
			}
			DebugLog.log(DebugLog.Category.PARTY, "Party info request failed (" + error + ") - keeping last known party state.");
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (HypixelDetector.isHypixel(client)) {
				joinDelayTicks = JOIN_DELAY_TICKS;
			}
		});

		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			String text = message.getString();
			if (CONFIRMED_LEFT_PARTY.matcher(text).find()) {
				inParty = false;
				members = Collections.emptySet();
				leaderUuid = null;
				DebugLog.log(DebugLog.Category.PARTY, "Confirmed party leave/disband via chat - clearing cached party state immediately.");
			}
			if (refreshCooldownTicks > 0) {
				return;
			}
			if (text.toLowerCase(Locale.ROOT).contains("party")) {
				refreshCooldownTicks = REFRESH_DEBOUNCE_TICKS;
				requestRefreshNow();
			}
		});
	}

	// Counts down the join-delay/debounce timers above; no per-tick network work.
	public static void tick() {
		if (refreshCooldownTicks > 0) {
			refreshCooldownTicks--;
		}
		if (joinDelayTicks > 0) {
			joinDelayTicks--;
			if (joinDelayTicks == 0) {
				requestRefreshNow();
			}
		}
	}

	// No-op if not connected to a server.
	public static void requestRefreshNow() {
		if (Minecraft.getInstance().getConnection() == null) {
			return;
		}
		HypixelModAPI.getInstance().sendPacket(new ServerboundPartyInfoPacket());
	}

	public static boolean isMember(UUID uuid) {
		return members.contains(uuid);
	}

	public static boolean isInParty() {
		return inParty;
	}

	public static Set<UUID> getMembers() {
		return members;
	}

	// Only the leader can actually /party kick; non-leaders would silently fail server-side.
	public static boolean isLocalPlayerLeader() {
		Minecraft client = Minecraft.getInstance();
		return client.player != null && client.player.getUUID().equals(leaderUuid);
	}

}
