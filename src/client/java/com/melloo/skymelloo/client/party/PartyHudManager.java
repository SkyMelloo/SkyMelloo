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

// Resolves the current roster into displayable info for PartyHud - usernames from the tab list
// (or Mojang's session server for an off-instance member), Accessory Power via a cached API fetch.
public final class PartyHudManager {
	// highestFloor (ever completed) and qualifyingFloor (currently level-eligible) answer different
	// questions and are both -1 until resolved, same as readinessScore (a rough estimate, not authoritative).
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
	// Seeded with the full roster on the first tick after a reset (haveRosterBaseline), so pre-existing members never look like a new join.
	private static final Set<UUID> announcedJoins = new java.util.HashSet<>();
	private static boolean haveRosterBaseline = false;
	private static int tickCounter = 0;
	// Only logs the "tracking N member(s)" line when N actually changes, not every tick.
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

	// Called by DungeonRunTracker as a run starts - readiness needs current gear/AP, not stale data.
	// Also fires requestRefresh per member since the backend has its own separate cache too.
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

	// Read-only snapshot for PartyHud - iteration order is stable between calls but isn't join order.
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
		// First non-empty roster after a reset is a baseline, not a batch of "joined your party" notifications.
		if (!haveRosterBaseline && !currentMembers.isEmpty()) {
			announcedJoins.addAll(currentMembers);
			haveRosterBaseline = true;
		}
		// Only the membership list itself needs this slow cadence; per-member resolution below runs
		// every tick (cheap, cache-gated) so a new member resolves immediately instead of waiting 5s.
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
				// Checks the actual stored value every tick, not just whether something is stored -
				// otherwise the AP/floor fetches below would fire against this fake placeholder name.
				Long lastAttempt = mojangRetryAt.get(uuid);
				if ((lastAttempt == null || System.currentTimeMillis() - lastAttempt > MOJANG_RETRY_MS) && !Boolean.TRUE.equals(mojangLookupInFlight.get(uuid))) {
					mojangRetryAt.put(uuid, System.currentTimeMillis());
					DebugLog.log(DebugLog.Category.PARTY, "Party HUD: " + uuid + " not in tab list, resolving via Mojang...");
					resolveUsernameViaMojang(uuid);
				}
				continue;
			}
			// First real username resolved since the last roster baseline - a genuine new join.
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

			// Highest-completed-floor + level-qualifying-floor - shown pre-run and used for the floor Auto-Kick checks.
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
