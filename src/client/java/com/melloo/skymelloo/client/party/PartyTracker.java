package com.melloo.skymelloo.client.party;

import com.melloo.skymelloo.client.util.DebugLog;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.hypixel.modapi.HypixelModAPI;
import net.hypixel.modapi.error.BuiltinErrorReason;
import net.hypixel.modapi.packet.impl.clientbound.ClientboundPartyInfoPacket;
import net.hypixel.modapi.packet.impl.serverbound.ServerboundPartyInfoPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tracks the local player's current Hypixel party via the official HypixelModAPI
 * (net.hypixel:mod-api) - it's a request/response packet, not a push event, so Hypixel never tells
 * us proactively when party membership changes; something has to actually ask.
 * <p>
 * Used to ask every ~5 seconds forever, permanently, even with no party at all and even after
 * disconnecting - confirmed directly from a real log ("Failed to send a packet as the client is not
 * connected to a server", repeating every ~5s post-disconnect) that this was pure waste. Now: one
 * check shortly after joining a server, then only re-checks when a chat line plausibly related to
 * party membership shows up (broad "party" substring match, debounced) - covers being invited,
 * accepting, someone joining/leaving, etc. without needing to guess Hypixel's exact wording for each
 * of those (this session has been burned more than once assuming an exact chat string, so a loose
 * heuristic here is deliberate, not an oversight).
 */
public final class PartyTracker {
	private static final int JOIN_DELAY_TICKS = 40;
	private static final int REFRESH_DEBOUNCE_TICKS = 40; // ~2s - avoid re-asking repeatedly if several party-related lines land close together
	// Confirmed directly from a real log: repeatedly hitting Hypixel's own rate limit during a busy
	// stretch (e.g. switching parties - several "party"-containing chat lines landing within a couple
	// seconds) never lets a fresh, successful packet through, which is exactly when the cached state is
	// most likely stale/wrong. The normal 2s debounce just re-triggers into the SAME rate limit again -
	// this backs off much harder specifically on RATE_LIMITED so the next attempt has a real chance.
	private static final int RATE_LIMIT_BACKOFF_TICKS = 400; // ~20s

	// Verified directly from real screenshots, both explicitly matched rather than a
	// loose "disbanded" keyword (which risked a false positive on unrelated chat, e.g. someone typing
	// "lol my other party disbanded" casually):
	//  1. "You left the party." - self-leave.
	//  2. "The party was disbanded because all invites expired and the party was empty." - auto-disband.
	//  3. "<rank/icon><Name> has disbanded the party!" - the leader manually disbanding it, a DIFFERENT
	//     word order than #2 (confirmed as a real gap: the original pattern only matched "the party was
	//     disbanded", not "has disbanded the party", so this specific real wording silently fell
	//     through to the slow ~1-minute fallback instead of clearing immediately). Matched by suffix,
	//     not anchored to the start, since a name can have a rank/cosmetic icon glued directly to it
	//     with no space (same reasoning as PartyJoinWatcher's JOINED_DUNGEON_GROUP pattern).
	// A confirmed leave/disband like this is cleared IMMEDIATELY rather than waiting on a fresh info
	// request, since that request can itself fail once you're no longer in a party (see the onError
	// comment below) - which used to leave the Party HUD stuck showing the old, now-stale roster
	// instead of updating.
	private static final Pattern CONFIRMED_LEFT_PARTY = Pattern.compile("(?i)you left the party\\.|the party was disbanded because|has disbanded the party!");

	private static volatile Set<UUID> members = Collections.emptySet();
	// Tracked separately from members.isEmpty() - an empty member set is ambiguous (genuinely no
	// party vs. no response received yet), but the packet's own isInParty() flag isn't.
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
			// A failed/errored response means "we don't know right now" (a transient network hiccup,
			// timeout, etc.), NOT "confirmed no party" - wiping the cached members here used to make
			// the Party HUD suddenly collapse to just the local player mid-party on a single blip,
			// since requestRefreshNow() fires often (every ~2s after any "party"-containing chat line,
			// see the GAME listener below). A genuine leave/disband is still a "party"-containing chat
			// line either way, so it already triggers its own fresh, hopefully-successful refresh -
			// this fix only stops a FAILED one from prematurely clearing good data.
			if (error == BuiltinErrorReason.RATE_LIMITED) {
				refreshCooldownTicks = Math.max(refreshCooldownTicks, RATE_LIMIT_BACKOFF_TICKS);
			}
			DebugLog.log(DebugLog.Category.PARTY, "Party info request failed (" + error + ") - keeping last known party state.");
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (isHypixel(client)) {
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

	/** Call once per client tick - just counts down the join-delay/debounce timers above, no per-tick network work anymore. */
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

	/** Sends an immediate out-of-cycle party info request (e.g. right after detecting you just joined one) - no-op if not actually connected to a server. */
	public static void requestRefreshNow() {
		if (Minecraft.getInstance().getConnection() == null) {
			return;
		}
		HypixelModAPI.getInstance().sendPacket(new ServerboundPartyInfoPacket());
	}

	public static boolean isMember(UUID uuid) {
		return members.contains(uuid);
	}

	/** Whether Hypixel actually reports the local player as being in a party right now - see {@link #members} for why this isn't just inferred from an empty member set. */
	public static boolean isInParty() {
		return inParty;
	}

	public static Set<UUID> getMembers() {
		return members;
	}

	/** Whether the LOCAL player is the current party leader - only the leader can actually /party kick someone, so kick buttons/auto-kick are meaningless (and just silently fail server-side) for anyone else. */
	public static boolean isLocalPlayerLeader() {
		Minecraft client = Minecraft.getInstance();
		return client.player != null && client.player.getUUID().equals(leaderUuid);
	}

	private static boolean isHypixel(Minecraft client) {
		ServerData server = client.getCurrentServer();
		return server != null && server.ip != null && server.ip.toLowerCase(Locale.ROOT).contains("hypixel");
	}
}
