package com.melloo.skymelloo.client.party;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.melloo.skymelloo.client.api.ModAuthManager;
import com.melloo.skymelloo.client.api.SkyMellooApiClient;
import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.social.DungeonReadiness;
import com.melloo.skymelloo.client.social.DungeonRunTracker;
import com.melloo.skymelloo.client.social.PartyJoinWatcher;
import com.melloo.skymelloo.client.util.DebugLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the current roster ({@link DungeonRunTracker#getEffectiveRoster()} - live party
 * membership normally, but locked to who was in the party when a dungeon run started while one is
 * active) into displayable info for {@link PartyHud} - usernames via the tab list, Accessory Power
 * via a periodic API fetch cached per player, so the HUD's own render never has to do any network
 * work.
 * <p>
 * A party member isn't always in your own tab list - Hypixel parties can span different
 * lobbies/instances on the network, so someone can be in your party without being in the same
 * server instance as you at all. When that happens, the tab list lookup returns nothing (that's
 * not a bug in it, they're just genuinely not part of your connection) - fall back to resolving
 * their username directly from Mojang's session server by UUID instead of showing a raw UUID
 * fragment, since that's needed anyway for the Accessory Power lookup to work for them too.
 */
public final class PartyHudManager {
	/**
	 * {@code highestFloor} (highest ever COMPLETED) and {@code qualifyingFloor} (highest they're
	 * currently level-ELIGIBLE for, per {@link PartyJoinWatcher#qualifyingFloor}) are both -1 until
	 * resolved - see {@link #FLOOR_CACHE_MS}. Two different questions: "have they done this" vs.
	 * "could they queue for this right now." {@code readinessScore} is {@link DungeonReadiness#combinedScore}
	 * (-1 until resolved) - a rough 0-1000 gear/level/experience estimate, NOT an authoritative score.
	 */
	public record MemberInfo(String username, int accessoryPower, int highestFloor, int qualifyingFloor, int readinessScore) {
	}

	private static final int REFRESH_INTERVAL_TICKS = 100; // 5s, matches PartyTracker's own cadence
	private static final long AP_CACHE_MS = 60_000;
	private static final long FLOOR_CACHE_MS = 300_000; // highest-floor barely changes mid-session, no need to refetch as often as AP
	private static final long MOJANG_RETRY_MS = 10_000; // cap retry rate for a persistently-failing/absent lookup
	private static final HttpClient MOJANG_HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private static final Map<UUID, MemberInfo> members = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> apFetchedAt = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> floorFetchedAt = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> mojangRetryAt = new ConcurrentHashMap<>();
	private static final Map<UUID, Boolean> mojangLookupInFlight = new ConcurrentHashMap<>();
	// Every UUID a "party member joined" notification has already fired for (see
	// com.melloo.mellooessentials.client.party.PartyKickQueue#handleMemberJoined) - seeded with the FULL roster the first time it's
	// seen after a reset (see haveRosterBaseline below), so members who were already in the party
	// before this session started tracking never get mistaken for a brand-new join.
	private static final Set<UUID> announcedJoins = new java.util.HashSet<>();
	private static boolean haveRosterBaseline = false;
	private static int tickCounter = 0;
	// So the "tracking N member(s)" debug line only fires when N actually changes, not every 5s
	// forever regardless - confirmed directly from a real log: this was spamming "tracking 0
	// member(s)" continuously for minutes on end while not even in a party.
	private static int lastLoggedMemberCount = -1;

	private PartyHudManager() {
	}

	public static void reset() {
		members.clear();
		apFetchedAt.clear();
		floorFetchedAt.clear();
		mojangRetryAt.clear();
		mojangLookupInFlight.clear();
		lastLoggedMemberCount = -1;
		announcedJoins.clear();
		haveRosterBaseline = false;
	}

	/**
	 * Forces every tracked member's AP/summary data to be re-fetched on the very next tick, bypassing
	 * our OWN client-side {@link #AP_CACHE_MS}/{@link #FLOOR_CACHE_MS} - called by
	 * {@link DungeonRunTracker} right as a run actually starts, since the readiness score (see
	 * {@link DungeonReadiness}) needs CURRENT gear/AP, not up-to-5-minutes-stale data - a player can
	 * freely swap armor/accessories right up until the run begins.
	 * <p>
	 * Clearing our own cache alone isn't enough though - the sky.melloo.me BACKEND has its own
	 * separate 3-5 minute profile cache, so our very next fetch could still just get served the same
	 * stale backend data. This also fires {@link SkyMellooApiClient#requestRefresh} for every
	 * currently-known member first, which forces the backend to bypass ITS cache and pull straight
	 * from Hypixel - fire-and-forget, since a 429 (someone already refreshed that account in the last
	 * 10 minutes server-side) is a perfectly fine outcome, not something to handle specially. There's
	 * an inherent small race (our own re-fetch could beat the backend's refresh completing over the
	 * network), but that just means falling back to however fresh the last normal cache cycle was -
	 * never worse than before this existed.
	 */
	public static void forceRefreshAll() {
		apFetchedAt.clear();
		floorFetchedAt.clear();

		Minecraft client = Minecraft.getInstance();
		ModAuthManager.getIdentity(client).thenAccept(identity -> {
			for (MemberInfo member : members.values()) {
				SkyMellooApiClient.requestRefresh(member.username(), identity).exceptionally(error -> null);
			}
		});
	}

	/** Read-only snapshot for {@link PartyHud} - iteration order is stable between calls but isn't join order. */
	public static Map<UUID, MemberInfo> getMembers() {
		return members;
	}

	public static void tick(Minecraft client) {
		if (client.player == null || client.getConnection() == null) {
			return;
		}
		tickCounter++;

		// Uses the run-locked roster while a dungeon run is active (see DungeonRunTracker), not live
		// party membership directly - someone leaving the party mid-run should still show up here.
		Set<UUID> currentMembers = DungeonRunTracker.getEffectiveRoster();
		// The very first time a real (non-empty) roster shows up after a reset is a BASELINE, not a
		// batch of joins - without this, everyone already in the party before this session started
		// tracking would incorrectly trigger a "joined your party" notification (see
		// com.melloo.mellooessentials.client.party.PartyKickQueue#handleMemberJoined) the moment their username resolves.
		if (!haveRosterBaseline && !currentMembers.isEmpty()) {
			announcedJoins.addAll(currentMembers);
			haveRosterBaseline = true;
		}
		// Only the membership LIST itself needs the slow 5s cadence (matches PartyTracker's own
		// poll interval - no point checking who's in the party more often than that data changes).
		// Per-member username/MP resolution below runs every tick regardless - it's cache-gated
		// (mpFetchedAt/mojangLookupInFlight) so that's cheap, and it means a brand-new member gets
		// resolved on the very next tick after they appear instead of waiting up to 5s just to
		// START the fetch, which was making "others" visibly slower than a self-entry that may
		// already have been resolved from an earlier cycle.
		if (tickCounter % REFRESH_INTERVAL_TICKS == 0) {
			members.keySet().retainAll(currentMembers);
			apFetchedAt.keySet().retainAll(currentMembers);
			floorFetchedAt.keySet().retainAll(currentMembers);
			mojangRetryAt.keySet().retainAll(currentMembers);
			mojangLookupInFlight.keySet().retainAll(currentMembers);
			// A member who leaves and later rejoins should be able to trigger the join notification
			// again, not be silently treated as "already announced" forever.
			announcedJoins.retainAll(currentMembers);
			if (currentMembers.size() != lastLoggedMemberCount) {
				lastLoggedMemberCount = currentMembers.size();
				DebugLog.log(DebugLog.Category.PARTY, "Party HUD: tracking " + currentMembers.size() + " member(s).");
			}
		}

		for (UUID uuid : currentMembers) {
			PlayerInfo info = client.getConnection().getPlayerInfo(uuid);
			MemberInfo existing = members.get(uuid);
			String placeholder = uuid.toString().substring(0, 8);

			if (info != null) {
				members.put(uuid, new MemberInfo(info.getProfile().name(), existing != null ? existing.accessoryPower() : -1, existing != null ? existing.highestFloor() : -1, existing != null ? existing.qualifyingFloor() : -1, existing != null ? existing.readinessScore() : -1));
			} else if (existing == null) {
				members.put(uuid, new MemberInfo(placeholder, -1, -1, -1, -1));
			}
			// else: already resolved previously (either via tab list earlier, or via Mojang) - keep
			// using that name even if the tab-list lookup is empty again this tick.

			String username = members.get(uuid).username();
			if (username.equals(placeholder)) {
				// Still just the UUID-fragment placeholder, not a real resolved username - a PREVIOUS
				// version only skipped the AP/floor fetches below on the exact tick the placeholder was
				// FIRST created, but kept using it as a real username on every tick after that (since
				// `existing` was no longer null), still firing both API calls against a fake name and
				// spamming "Minecraft account not found" forever. Check the actual VALUE every tick
				// instead of just whether something was stored at all.
				Long lastAttempt = mojangRetryAt.get(uuid);
				if ((lastAttempt == null || System.currentTimeMillis() - lastAttempt > MOJANG_RETRY_MS) && !Boolean.TRUE.equals(mojangLookupInFlight.get(uuid))) {
					mojangRetryAt.put(uuid, System.currentTimeMillis());
					DebugLog.log(DebugLog.Category.PARTY, "Party HUD: " + uuid + " not in tab list, resolving via Mojang...");
					resolveUsernameViaMojang(uuid);
				}
				continue;
			}
			// First time this uuid's username has resolved to something real since the last roster
			// baseline (see haveRosterBaseline above) - a genuine new party join, not someone who was
			// already there. Never fires for the local player's own name.
			if (haveRosterBaseline && announcedJoins.add(uuid) && !username.equalsIgnoreCase(client.player.getGameProfile().name())) {
				com.melloo.mellooessentials.client.party.PartyKickQueue.handleMemberJoined(client, username);
			}
			Long lastApFetch = apFetchedAt.get(uuid);
			if (lastApFetch == null || System.currentTimeMillis() - lastApFetch > AP_CACHE_MS) {
				apFetchedAt.put(uuid, System.currentTimeMillis());
				DebugLog.log(DebugLog.Category.PARTY, "Party HUD: fetching AP for " + username + "...");
				ModAuthManager.getIdentity(client).thenCompose(identity -> SkyMellooApiClient.fetchAccessoryPower(username, identity)).whenComplete((result, error) -> {
					if (error != null) {
						DebugLog.log(DebugLog.Category.PARTY, "Party HUD: AP fetch for " + username + " failed (" + error.getMessage() + ").");
						return;
					}
					if (result.accessoryPower() >= 0) {
						members.computeIfPresent(uuid, (id, old) -> new MemberInfo(old.username(), result.accessoryPower(), old.highestFloor(), old.qualifyingFloor(), old.readinessScore()));
					} else {
						DebugLog.log(DebugLog.Category.PARTY, "Party HUD: AP fetch for " + username + " returned no data.");
					}
				});
			}

			// Highest-completed-floor + level-qualifying-floor - shown pre-run (see PartyHud) and used
			// for the floor Auto-Kick (see PartyJoinWatcher#maybeAutoKickForFloor), covering members
			// who were already in the party before this session started tracking, not just brand-new
			// joiners.
			Long lastFloorFetch = floorFetchedAt.get(uuid);
			if (lastFloorFetch == null || System.currentTimeMillis() - lastFloorFetch > FLOOR_CACHE_MS) {
				floorFetchedAt.put(uuid, System.currentTimeMillis());
				ModAuthManager.getIdentity(client).thenCompose(identity -> SkyMellooApiClient.fetchSummary(username, identity)).whenComplete((summary, error) -> {
					if (error != null) {
						DebugLog.log(DebugLog.Category.PARTY, "Party HUD: floor fetch for " + username + " failed (" + error.getMessage() + ").");
						return;
					}
					int qualifying = PartyJoinWatcher.qualifyingFloor(summary.catacombsLevel(), summary.skillLevels().getOrDefault("combat", 0));
					int targetFloor = SkyMellooConfig.HANDLER.instance().dungeonTargetFloor;
					DebugLog.log(DebugLog.Category.PARTY, "Party HUD: " + username + " -> cata=" + summary.catacombsLevel()
							+ ", combat=" + summary.skillLevels().getOrDefault("combat", -1) + ", qualifies=F" + qualifying + ", highestFloor=F" + summary.highestFloor());
					members.computeIfPresent(uuid, (id, old) -> new MemberInfo(old.username(), old.accessoryPower(), summary.highestFloor(), qualifying, DungeonReadiness.combinedScore(summary, old.accessoryPower(), targetFloor)));
					Minecraft mcClient = Minecraft.getInstance();
					if (mcClient.player != null && !username.equalsIgnoreCase(mcClient.player.getGameProfile().name())) {
						PartyJoinWatcher.maybeAutoKickForFloor(mcClient, username, summary, SkyMellooConfig.HANDLER.instance());
						PartyJoinWatcher.maybeAutoKickForFloorMax(mcClient, username, summary, SkyMellooConfig.HANDLER.instance());
						PartyJoinWatcher.maybeAutoKickForFloorCompletion(mcClient, username, summary, SkyMellooConfig.HANDLER.instance());
						PartyJoinWatcher.maybeAutoKickForFloorCompletionMax(mcClient, username, summary, SkyMellooConfig.HANDLER.instance());
					}
				});
			}
		}
	}

	private static void resolveUsernameViaMojang(UUID uuid) {
		mojangLookupInFlight.put(uuid, true);
		String url = "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid.toString().replace("-", "");
		HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(6)).GET().build();
		MOJANG_HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenAccept(response -> {
					if (response.statusCode() != 200) {
						return;
					}
					JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
					if (root.has("name")) {
						String username = root.get("name").getAsString();
						members.computeIfPresent(uuid, (id, old) -> new MemberInfo(username, old.accessoryPower(), old.highestFloor(), old.qualifyingFloor(), old.readinessScore()));
					}
				})
				.exceptionally(error -> null)
				.thenRun(() -> mojangLookupInFlight.remove(uuid));
	}
}
