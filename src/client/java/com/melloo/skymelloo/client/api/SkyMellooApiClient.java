package com.melloo.skymelloo.client.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Thin client for sky.melloo.me's read-only SkyBlock API (no key/auth required).
 * All requests run on the JDK HttpClient's own async executor, never the render/tick thread;
 * callers must marshal results back via {@code Minecraft.getInstance().execute(...)} before
 * touching any game state.
 */
public final class SkyMellooApiClient {
	private static final String BASE_URL = "https://sky.melloo.me/api";
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	private SkyMellooApiClient() {
	}

	public record MagicalPowerResult(int magicalPower, String selectedPower) {
	}

	public record SummaryResult(
			int skyblockLevel, double averageSkillLevel, int catacombsLevel, String selectedClass,
			double purse, double bank, double netWorth, int fairySouls, String guildName,
			String rankLabel, int highestFloor, int minionUniqueCount, int minionUpgrades,
			long bestiaryKills, long firstJoin,
			Map<String, Integer> skillLevels, Map<String, Integer> slayerLevels, Map<String, Integer> classLevels,
			int minionSlots, int petCount, String bestPetLabel, int collectionsStarted,
			String profilesLabel, int dungeonCompletions, String guildTag, int guildMemberCount,
			long dataFetchedAt
	) {
	}

	private static final String[] SKILL_KEYS = {"farming", "mining", "combat", "foraging", "fishing", "enchanting", "alchemy", "taming"};
	private static final String[] SLAYER_KEYS = {"zombie", "spider", "wolf", "enderman", "blaze", "vampire"};
	private static final String[] CLASS_KEYS = {"healer", "mage", "berserk", "archer", "tank"};

	/**
	 * Null-safe replacement for {@link JsonObject#getAsJsonObject(String)} - the API sometimes
	 * sends a member as a literal JSON {@code null} (not just an absent key), and Gson's own
	 * getAsJsonObject blindly casts whatever it finds, throwing ClassCastException on JsonNull.
	 */
	private static JsonObject safeObject(JsonObject parent, String key) {
		if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) {
			return null;
		}
		return parent.getAsJsonObject(key);
	}

	private static Map<String, Integer> extractLevels(JsonObject parent, String[] keys) {
		Map<String, Integer> levels = new LinkedHashMap<>();
		if (parent == null) {
			return levels;
		}
		for (String key : keys) {
			JsonObject entry = safeObject(parent, key);
			if (entry != null && entry.has("level") && !entry.get("level").isJsonNull()) {
				levels.put(key, entry.get("level").getAsInt());
			}
		}
		return levels;
	}

	/** From /api/player/:username/inventory - the only endpoint that exposes Magical Power. */
	public static CompletableFuture<MagicalPowerResult> fetchMagicalPower(String username, ModAuthManager.ModIdentity identity) {
		return fetchMagicalPower(username, null, identity);
	}

	/** @param profile a specific SkyBlock profile name (see {@link #fetchProfileNames}), or null for the player's currently-selected profile. */
	public static CompletableFuture<MagicalPowerResult> fetchMagicalPower(String username, String profile, ModAuthManager.ModIdentity identity) {
		return getJson("/player/" + encode(username) + "/inventory" + profileQuery(profile), identity).thenApply(root -> {
			JsonObject mp = safeObject(root, "magicalPower");
			if (mp == null) {
				return new MagicalPowerResult(-1, null);
			}
			int current = mp.has("current") && !mp.get("current").isJsonNull() ? mp.get("current").getAsInt() : -1;
			String selected = mp.has("selectedPower") && !mp.get("selectedPower").isJsonNull() ? mp.get("selectedPower").getAsString() : null;
			return new MagicalPowerResult(current, selected);
		});
	}

	/**
	 * Forces the backend to bypass its own 3-5 minute profile cache (POST /player/:username/request-refresh)
	 * and pull straight from Hypixel - used right when a dungeon run starts, since a party member's
	 * gear/MP could have changed just before queueing and {@link DungeonReadiness} needs current data,
	 * not up-to-5-minutes-stale. Server-side cooldown-limited to once per 10 minutes per account
	 * (shared across every caller, not per-IP) - a 429 here just means someone already refreshed this
	 * account recently enough, which is a fine outcome too, not a real failure. Callers should treat
	 * any error from this as ignorable (fire-and-forget best effort).
	 */
	public static CompletableFuture<Void> requestRefresh(String username, ModAuthManager.ModIdentity identity) {
		return postJson("/player/" + encode(username) + "/request-refresh", new JsonObject(), identity).thenApply(root -> null);
	}

	/** The account's SkyBlock profile names (e.g. "Banana", "Orange") - for building a /player/:username?profile= call and for command autocomplete. */
	public static CompletableFuture<List<String>> fetchProfileNames(String username, ModAuthManager.ModIdentity identity) {
		return getJson("/player/" + encode(username), identity).thenApply(root -> {
			List<String> names = new ArrayList<>();
			if (root.has("profiles") && root.get("profiles").isJsonArray()) {
				for (JsonElement el : root.getAsJsonArray("profiles")) {
					if (!el.isJsonObject()) {
						continue;
					}
					JsonObject p = el.getAsJsonObject();
					if (p.has("name") && !p.get("name").isJsonNull()) {
						names.add(p.get("name").getAsString());
					}
				}
			}
			return names;
		});
	}

	private static String profileQuery(String profile) {
		return (profile != null && !profile.isBlank()) ? "?profile=" + encode(profile) : "";
	}

	/** From /api/player/:username - profile summary (skills, dungeons, etc). */
	public static CompletableFuture<SummaryResult> fetchSummary(String username, ModAuthManager.ModIdentity identity) {
		return fetchSummary(username, null, identity);
	}

	/** @param profile a specific SkyBlock profile name (see {@link #fetchProfileNames}), or null for the player's currently-selected profile. */
	public static CompletableFuture<SummaryResult> fetchSummary(String username, String profile, ModAuthManager.ModIdentity identity) {
		return getJson("/player/" + encode(username) + profileQuery(profile), identity).thenApply(root -> {
			int catacombs = 0;
			String selectedClass = null;
			JsonObject dungeons = safeObject(root, "dungeons");
			if (dungeons != null) {
				JsonObject catacombsObj = safeObject(dungeons, "catacombs");
				if (catacombsObj != null && catacombsObj.has("level") && !catacombsObj.get("level").isJsonNull()) {
					catacombs = catacombsObj.get("level").getAsInt();
				}
				if (dungeons.has("selectedClass") && !dungeons.get("selectedClass").isJsonNull()) {
					selectedClass = dungeons.get("selectedClass").getAsString();
				}
			}
			double avgSkill = root.has("averageSkillLevel") && !root.get("averageSkillLevel").isJsonNull() ? root.get("averageSkillLevel").getAsDouble() : 0;
			int sbLevel = root.has("skyblockLevel") && !root.get("skyblockLevel").isJsonNull() ? root.get("skyblockLevel").getAsInt() : 0;
			double purse = root.has("purse") && !root.get("purse").isJsonNull() ? root.get("purse").getAsDouble() : 0;
			double bank = root.has("bank") && !root.get("bank").isJsonNull() ? root.get("bank").getAsDouble() : 0;
			int fairySouls = root.has("fairySouls") && !root.get("fairySouls").isJsonNull() ? root.get("fairySouls").getAsInt() : 0;

			double netWorth = 0;
			JsonObject netWorthObj = safeObject(root, "netWorth");
			if (netWorthObj != null && netWorthObj.has("total") && !netWorthObj.get("total").isJsonNull()) {
				netWorth = netWorthObj.get("total").getAsDouble();
			}

			String guildName = null;
			String guildTag = null;
			int guildMemberCount = 0;
			JsonObject guildObj = safeObject(root, "guild");
			if (guildObj != null) {
				if (guildObj.has("name") && !guildObj.get("name").isJsonNull()) {
					guildName = guildObj.get("name").getAsString();
				}
				if (guildObj.has("tag") && !guildObj.get("tag").isJsonNull()) {
					guildTag = guildObj.get("tag").getAsString();
				}
				if (guildObj.has("memberCount") && !guildObj.get("memberCount").isJsonNull()) {
					guildMemberCount = guildObj.get("memberCount").getAsInt();
				}
			}

			String rankLabel = null;
			JsonObject rankObj = safeObject(root, "rank");
			if (rankObj != null && rankObj.has("label") && !rankObj.get("label").isJsonNull()) {
				rankLabel = rankObj.get("label").getAsString();
			}

			int highestFloor = (dungeons != null && dungeons.has("highestFloor") && !dungeons.get("highestFloor").isJsonNull())
					? dungeons.get("highestFloor").getAsInt() : 0;

			int minionUniqueCount = 0;
			int minionUpgrades = 0;
			JsonObject minionsObj = safeObject(root, "minions");
			if (minionsObj != null) {
				if (minionsObj.has("uniqueCount") && !minionsObj.get("uniqueCount").isJsonNull()) {
					minionUniqueCount = minionsObj.get("uniqueCount").getAsInt();
				}
				if (minionsObj.has("totalUpgradesCrafted") && !minionsObj.get("totalUpgradesCrafted").isJsonNull()) {
					minionUpgrades = minionsObj.get("totalUpgradesCrafted").getAsInt();
				}
			}

			long bestiaryKills = root.has("bestiaryKills") && !root.get("bestiaryKills").isJsonNull() ? root.get("bestiaryKills").getAsLong() : 0;
			long firstJoin = root.has("firstJoin") && !root.get("firstJoin").isJsonNull() ? root.get("firstJoin").getAsLong() : 0;

			Map<String, Integer> skillLevels = extractLevels(safeObject(root, "skills"), SKILL_KEYS);
			Map<String, Integer> slayerLevels = extractLevels(safeObject(root, "slayers"), SLAYER_KEYS);
			Map<String, Integer> classLevels = extractLevels(dungeons != null ? safeObject(dungeons, "classes") : null, CLASS_KEYS);

			int minionSlots = root.has("minionSlots") && !root.get("minionSlots").isJsonNull() ? root.get("minionSlots").getAsInt() : 0;

			int petCount = 0;
			String bestPetLabel = null;
			if (root.has("pets") && root.get("pets").isJsonArray()) {
				JsonArray petsArr = root.getAsJsonArray("pets");
				petCount = petsArr.size();
				double bestXp = -1;
				for (JsonElement el : petsArr) {
					if (!el.isJsonObject()) {
						continue;
					}
					JsonObject pet = el.getAsJsonObject();
					String type = pet.has("type") && !pet.get("type").isJsonNull() ? pet.get("type").getAsString() : null;
					if (type == null) {
						continue;
					}
					String tier = pet.has("tier") && !pet.get("tier").isJsonNull() ? pet.get("tier").getAsString() : null;
					boolean active = pet.has("active") && !pet.get("active").isJsonNull() && pet.get("active").getAsBoolean();
					if (active) {
						bestPetLabel = type + (tier != null ? " (" + tier + ")" : "") + " [aktiv]";
						break;
					}
					double xp = pet.has("xp") && !pet.get("xp").isJsonNull() ? pet.get("xp").getAsDouble() : 0;
					if (xp > bestXp) {
						bestXp = xp;
						bestPetLabel = type + (tier != null ? " (" + tier + ")" : "");
					}
				}
			}

			int collectionsStarted = root.has("collections") && root.get("collections").isJsonArray()
					? root.getAsJsonArray("collections").size() : 0;

			String profilesLabel = "§7Keine Profile gefunden";
			if (root.has("profiles") && root.get("profiles").isJsonArray()) {
				StringBuilder sb = new StringBuilder();
				for (JsonElement el : root.getAsJsonArray("profiles")) {
					if (!el.isJsonObject()) {
						continue;
					}
					JsonObject p = el.getAsJsonObject();
					String pname = p.has("name") && !p.get("name").isJsonNull() ? p.get("name").getAsString() : "?";
					String mode = p.has("gameMode") && !p.get("gameMode").isJsonNull() ? p.get("gameMode").getAsString() : "classic";
					boolean selected = p.has("selected") && !p.get("selected").isJsonNull() && p.get("selected").getAsBoolean();
					if (sb.length() > 0) {
						sb.append("§r, ");
					}
					sb.append(pname).append(" §7(").append(mode).append(selected ? ", aktiv" : "").append(")§r");
				}
				if (sb.length() > 0) {
					profilesLabel = sb.toString();
				}
			}

			int dungeonCompletions = 0;
			if (dungeons != null) {
				dungeonCompletions += sumCompletions(dungeons, "floors");
				dungeonCompletions += sumCompletions(dungeons, "masterFloors");
			}

			long dataFetchedAt = root.has("dataFetchedAt") && !root.get("dataFetchedAt").isJsonNull() ? root.get("dataFetchedAt").getAsLong() : 0;

			return new SummaryResult(
					sbLevel, avgSkill, catacombs, selectedClass, purse, bank, netWorth, fairySouls, guildName,
					rankLabel, highestFloor, minionUniqueCount, minionUpgrades, bestiaryKills, firstJoin,
					skillLevels, slayerLevels, classLevels,
					minionSlots, petCount, bestPetLabel, collectionsStarted, profilesLabel, dungeonCompletions,
					guildTag, guildMemberCount, dataFetchedAt
			);
		});
	}

	private static int sumCompletions(JsonObject dungeons, String arrayKey) {
		if (!dungeons.has(arrayKey) || !dungeons.get(arrayKey).isJsonArray()) {
			return 0;
		}
		int total = 0;
		for (JsonElement el : dungeons.getAsJsonArray(arrayKey)) {
			if (!el.isJsonObject()) {
				continue;
			}
			JsonObject floor = el.getAsJsonObject();
			if (floor.has("completions") && !floor.get("completions").isJsonNull()) {
				total += floor.get("completions").getAsInt();
			}
		}
		return total;
	}

	private static CompletableFuture<JsonObject> getJson(String path) {
		return getJson(path, null);
	}

	/** @param identity a live identity from {@link ModAuthManager#getIdentity}, required by every /mod/* route except the auth handshake itself and /mod/check. Signs this specific request rather than attaching a single reusable token - see attachSignature. */
	private static CompletableFuture<JsonObject> getJson(String path, ModAuthManager.ModIdentity identity) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(BASE_URL + path))
				.timeout(Duration.ofSeconds(8))
				.header("X-SkyMelloo-Client", "mod")
				.GET();
		if (identity != null) {
			attachSignature(builder, identity, "GET", requestPath(path), new byte[0]);
		}
		return sendWithRetry(builder.build())
				.thenApply(response -> {
					if (response.statusCode() != 200) {
						throw new RuntimeException(extractErrorMessage(response.body(), response.statusCode()));
					}
					com.google.gson.JsonElement parsed = JsonParser.parseString(response.body());
					if (!parsed.isJsonObject()) {
						throw new RuntimeException("No data found");
					}
					return parsed.getAsJsonObject();
				});
	}

	/**
	 * A request timing out is very often just a one-off network hiccup (confirmed directly from a real
	 * screenshot: a single lookup failed with "request timed out" while every other identical request
	 * around it succeeded fine) - retried exactly ONCE, after a 1 second delay, rather than surfacing an
	 * error to chat immediately. Any other failure (a real HTTP error status, a malformed response) isn't
	 * retried - those aren't transient, retrying would just fail again the same way.
	 */
	private static CompletableFuture<HttpResponse<String>> sendWithRetry(HttpRequest request) {
		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.handle((response, error) -> {
					if (error == null || !isTimeout(error)) {
						if (error != null) {
							CompletableFuture<HttpResponse<String>> failed = new CompletableFuture<>();
							failed.completeExceptionally(error);
							return failed;
						}
						return CompletableFuture.completedFuture(response);
					}
					return CompletableFuture
							.supplyAsync(() -> null, CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS))
							.thenCompose(ignored -> HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
				})
				.thenCompose(future -> future);
	}

	private static boolean isTimeout(Throwable error) {
		Throwable cause = error;
		while (cause != null) {
			if (cause instanceof HttpTimeoutException) {
				return true;
			}
			cause = cause.getCause();
		}
		return false;
	}

	/** Every error response from the server is JSON {@code { "error": "message" }} - surface that instead of a bare HTTP status code. */
	private static String extractErrorMessage(String body, int statusCode) {
		try {
			JsonElement parsed = JsonParser.parseString(body);
			if (parsed.isJsonObject() && parsed.getAsJsonObject().has("error")) {
				return parsed.getAsJsonObject().get("error").getAsString();
			}
		} catch (Exception ignored) {
			// fall back to the generic status message below
		}
		return "HTTP " + statusCode;
	}

	private static CompletableFuture<JsonObject> postJson(String path, JsonObject body) {
		return postJson(path, body, null);
	}

	private static CompletableFuture<JsonObject> postJson(String path, JsonObject body, ModAuthManager.ModIdentity identity) {
		// Captured once as raw bytes and reused for BOTH transmission and the signature's body hash -
		// computing the hash from a second body.toString() call would risk it disagreeing (even in
		// theory) with what was actually sent, silently breaking every signed POST.
		byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(BASE_URL + path))
				.timeout(Duration.ofSeconds(8))
				.header("Content-Type", "application/json")
				.header("X-SkyMelloo-Client", "mod")
				.POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes));
		if (identity != null) {
			attachSignature(builder, identity, "POST", requestPath(path), bodyBytes);
		}
		return sendWithRetry(builder.build())
				.thenApply(response -> {
					if (response.statusCode() != 200) {
						throw new RuntimeException(extractErrorMessage(response.body(), response.statusCode()));
					}
					JsonElement parsed = JsonParser.parseString(response.body());
					if (!parsed.isJsonObject()) {
						throw new RuntimeException("No data found");
					}
					return parsed.getAsJsonObject();
				});
	}

	private static void attachSignature(HttpRequest.Builder builder, ModAuthManager.ModIdentity identity, String method, String path, byte[] bodyBytes) {
		ModAuthManager.ModIdentity.SignedHeaders headers = identity.sign(method, path, bodyBytes);
		builder.header("X-SkyMelloo-Uuid", headers.uuid())
				.header("X-SkyMelloo-Timestamp", headers.timestamp())
				.header("X-SkyMelloo-Nonce", headers.nonce())
				.header("X-SkyMelloo-Signature", headers.signature());
	}

	/**
	 * Only the path is signed, never the query string - none of the query params on these routes
	 * (e.g. checkVersion's ?version=/&hash=) are sensitive or mutate any state, so this is a
	 * deliberate simplification, not an oversight. Also prepends "/api" - the path strings the call
	 * sites above pass in (e.g. "/mod/friends") don't include it, but Express's req.path on the
	 * server DOES (see lib/modAuth.js#verifySignedRequest), so the signed message has to match that.
	 */
	private static String requestPath(String pathWithQuery) {
		int queryStart = pathWithQuery.indexOf('?');
		String pathOnly = queryStart < 0 ? pathWithQuery : pathWithQuery.substring(0, queryStart);
		return "/api" + pathOnly;
	}

	/** One other player currently reporting presence - their SkyMelloo cosmetic tokens (e.g. "halo:AA33FF" or "cherryBlossom"), custom status text, live dungeon-sync data (room/secrets/etc, see DungeonSyncManager), whether they have a linked sky.melloo.me account (nametag marker), and their server-resolved role ("owner"/"admin"/"developer", or null), if any. */
	public record PresenceEntry(String uuid, String username, List<String> cosmetics, String status, JsonObject dungeonSync, boolean accountLinked, String role) {
	}

	/** Tells sky.melloo.me "this UUID is online right now with these cosmetics enabled" - lets other SkyMelloo clients detect and mirror them. {@code dungeonSync} is an opaque JSON blob (may be null) forwarded as-is to other clients querying this UUID - see DungeonSyncManager. {@code afk} is a best-effort "no movement/camera look for 5+ min" read (see AfkDetector) - shown as a distinct yellow status on the website rather than folded into plain online/offline. {@code accountLinked} mirrors PermissionsManager#isAccountLinked so other nearby SkyMelloo clients can render the pink-dye nametag marker for us. {@code identity} (may be null if not yet acquired) proves this report really came from the mod, which exempts it from the server's generic per-IP abuse limiter - see server.js. */
	public static CompletableFuture<Void> reportPresence(String uuid, String username, List<String> cosmetics, String status, JsonObject dungeonSync, boolean afk, boolean accountLinked, String location, ModAuthManager.ModIdentity identity) {
		JsonObject body = new JsonObject();
		body.addProperty("uuid", uuid);
		body.addProperty("username", username);
		JsonArray cosmeticsArr = new JsonArray();
		for (String c : cosmetics) {
			cosmeticsArr.add(c);
		}
		body.add("cosmetics", cosmeticsArr);
		body.addProperty("status", status);
		if (dungeonSync != null) {
			body.add("dungeonSync", dungeonSync);
		}
		body.addProperty("afk", afk);
		body.addProperty("accountLinked", accountLinked);
		if (location != null) {
			body.addProperty("location", location);
		}
		return postJson("/presence", body, identity).thenApply(root -> null);
	}

	/** Of the given UUIDs, which ones reported SkyMelloo presence in the last ~20s, and with what cosmetics. {@code identity} (may be null) exempts this query from the generic per-IP rate limiter the same way reportPresence's does. */
	public static CompletableFuture<List<PresenceEntry>> queryPresence(List<String> uuids, ModAuthManager.ModIdentity identity) {
		JsonObject body = new JsonObject();
		JsonArray uuidsArr = new JsonArray();
		for (String uuid : uuids) {
			uuidsArr.add(uuid);
		}
		body.add("uuids", uuidsArr);
		return postJson("/presence/query", body, identity).thenApply(root -> {
			List<PresenceEntry> result = new ArrayList<>();
			if (root.has("present") && root.get("present").isJsonArray()) {
				for (JsonElement el : root.getAsJsonArray("present")) {
					if (!el.isJsonObject()) {
						continue;
					}
					JsonObject entry = el.getAsJsonObject();
					if (!entry.has("uuid") || entry.get("uuid").isJsonNull()) {
						continue;
					}
					String uuid = entry.get("uuid").getAsString();
					String username = entry.has("username") && !entry.get("username").isJsonNull() ? entry.get("username").getAsString() : "";
					List<String> cosmetics = new ArrayList<>();
					if (entry.has("cosmetics") && entry.get("cosmetics").isJsonArray()) {
						for (JsonElement c : entry.getAsJsonArray("cosmetics")) {
							cosmetics.add(c.getAsString());
						}
					}
					String status = entry.has("status") && !entry.get("status").isJsonNull() ? entry.get("status").getAsString() : "";
					JsonObject dungeonSync = entry.has("dungeonSync") && entry.get("dungeonSync").isJsonObject() ? entry.getAsJsonObject("dungeonSync") : null;
					boolean accountLinked = entry.has("accountLinked") && entry.get("accountLinked").getAsBoolean();
					String role = entry.has("role") && !entry.get("role").isJsonNull() ? entry.get("role").getAsString() : null;
					result.add(new PresenceEntry(uuid, username, cosmetics, status, dungeonSync, accountLinked, role));
				}
			}
			return result;
		});
	}

	/** Whether this UUID is on the admin-managed whitelist (sky.melloo.me/set) - the mod refuses to run any feature otherwise. */
	/** Lightweight, cheap endpoint used purely to measure round-trip latency to sky.melloo.me itself (see SkyMellooPingMonitor) - not for anything else. */
	public static CompletableFuture<Void> ping() {
		return getJson("/health").thenApply(root -> null);
	}

	/** Whether this build is still compatible with the current backend API - see ModVersionManager, checked once per join right alongside the whitelist check. */
	/** {@code buildKind} - "release" (published, validly signed), "dev-build" (validly signed but unpublished, only within the most-recent-2 trust window), "dev-whitelist" (admin-whitelisted, unsigned), "unverified" (a reported hash that's none of those), or "unknown" (no hash reported at all) - see server.js's own comment. */
	public record VersionCheckResult(boolean compatible, String minVersion, String message, boolean upToDate, String updateAvailableMessage, boolean integrityOk, String buildKind) {
	}

	/** {@code jarHash} (lowercase hex SHA-256 of this build's own jar, see ModVersionManager) is optional - null when running from a dev/exploded classpath rather than a real packaged jar. */
	public static CompletableFuture<VersionCheckResult> checkVersion(String version, String jarHash) {
		String url = "/mod/version-check?version=" + encode(version) + (jarHash != null ? "&hash=" + encode(jarHash) : "");
		return getJson(url).thenApply(root -> new VersionCheckResult(
				root.has("compatible") && root.get("compatible").getAsBoolean(),
				root.has("minVersion") && !root.get("minVersion").isJsonNull() ? root.get("minVersion").getAsString() : null,
				root.has("message") && !root.get("message").isJsonNull() ? root.get("message").getAsString() : null,
				!root.has("upToDate") || root.get("upToDate").getAsBoolean(),
				root.has("updateAvailableMessage") && !root.get("updateAvailableMessage").isJsonNull() ? root.get("updateAvailableMessage").getAsString() : null,
				!root.has("integrityOk") || root.get("integrityOk").getAsBoolean(),
				root.has("buildKind") && !root.get("buildKind").isJsonNull() ? root.get("buildKind").getAsString() : "unknown"
		));
	}

	public record LegalInfo(String imprint, String privacy, String terms) {
	}

	/**
	 * "/sm legal" (2026-07-28) - moved server-side entirely, out of the (now public/open-source) mod
	 * source, and gated by the same build-verification check the integrity system already does: a
	 * build that can't be verified as an official/dev SkyMelloo release doesn't get to show itself as
	 * legally covered by the maintainer's own imprint/privacy/terms, since it genuinely isn't -
	 * anyone could have changed anything in an unverified build. The future completes exceptionally
	 * (see server.js's 403) for an unverified build - the caller shows a "not available" message.
	 */
	public static CompletableFuture<LegalInfo> fetchLegalInfo(String jarHash) {
		String url = "/mod/legal" + (jarHash != null ? "?hash=" + encode(jarHash) : "");
		return getJson(url).thenApply(root -> new LegalInfo(
				root.get("imprint").getAsString(),
				root.get("privacy").getAsString(),
				root.get("terms").getAsString()
		));
	}

	/** A one-time serverId to hand to Mojang's own joinServer call, plus the server's own clock reading so the mod can correct for its own clock drift once per session - see {@link ModAuthManager}. */
	public record ChallengeResult(String serverId, long serverTime) {
	}

	public static CompletableFuture<ChallengeResult> requestAuthChallenge() {
		return getJson("/mod/auth/challenge").thenApply(root ->
				new ChallengeResult(root.get("serverId").getAsString(), root.get("serverTime").getAsLong()));
	}

	public record SessionResult(long expiresAt) {
	}

	/** Redeems a completed joinServer call, registering {@code publicKeyBase64} (this launch's ephemeral Ed25519 public key, SPKI DER) as this account's active session - Mojang's hasJoined has to actually confirm the joinServer call server-side first. */
	public static CompletableFuture<SessionResult> verifyAuthChallenge(String serverId, String username, String uuid, String publicKeyBase64) {
		JsonObject body = new JsonObject();
		body.addProperty("serverId", serverId);
		body.addProperty("username", username);
		body.addProperty("uuid", uuid);
		body.addProperty("publicKey", publicKeyBase64);
		return postJson("/mod/auth/verify", body)
				.thenApply(root -> new SessionResult(root.get("expiresAt").getAsLong()));
	}

	/** Resolved per-feature permissions (highlight, cosmetics, etc.) - admin-configurable defaults + per-user overrides, resolved server-side from the verified signed request. */
	public static CompletableFuture<Map<String, Boolean>> fetchPermissions(ModAuthManager.ModIdentity identity) {
		return getJson("/mod/permissions", identity).thenApply(root -> {
			Map<String, Boolean> result = new HashMap<>();
			for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
				if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isBoolean()) {
					result.put(entry.getKey(), entry.getValue().getAsBoolean());
				}
			}
			return result;
		});
	}

	/** Whether the account behind this identity is verified-linked to the admin website account (via /skymelloo verify). */
	public static CompletableFuture<Boolean> checkIsAdmin(ModAuthManager.ModIdentity identity) {
		return getJson("/mod/is-admin", identity).thenApply(root ->
				root.has("isAdmin") && !root.get("isAdmin").isJsonNull() && root.get("isAdmin").getAsBoolean()
		);
	}

	/** Result of completing the "/skymelloo verify" account-linking flow. */
	public record VerifyResult(boolean ok, String error) {
	}

	/** Completes the account-linking flow: the admin generated {@code code} on sky.melloo.me/set, this proves (via the signed request) the in-game account owns it. */
	public static CompletableFuture<VerifyResult> verifyAccount(String code, ModAuthManager.ModIdentity identity) {
		JsonObject body = new JsonObject();
		body.addProperty("code", code);
		return postJson("/mod/verify", body, identity)
				.thenApply(root -> new VerifyResult(true, null))
				.exceptionally(error -> new VerifyResult(false, error.getMessage()));
	}

	/** The cloud-synced settings blob for the account behind this identity, or null if nothing's been saved yet (or the request failed). */
	public static CompletableFuture<JsonObject> fetchCloudSettings(ModAuthManager.ModIdentity identity) {
		return getJson("/mod/settings", identity)
				.thenApply(root -> root.has("settings") && root.get("settings").isJsonObject() ? root.getAsJsonObject("settings") : null)
				.exceptionally(error -> null);
	}

	/** Saves the current settings for cloud sync - a failure here just means the next sync attempt tries again. Returns whether it actually succeeded, for debug logging. */
	public static CompletableFuture<Boolean> pushCloudSettings(ModAuthManager.ModIdentity identity, JsonObject settings) {
		JsonObject body = new JsonObject();
		body.add("settings", settings);
		return postJson("/mod/settings", body, identity)
				.thenApply(root -> true)
				.exceptionally(error -> false);
	}

	/** Undoes "/skymelloo verify" - only ever affects whichever account the signed request proves you are. */
	public static CompletableFuture<VerifyResult> unlinkAccount(ModAuthManager.ModIdentity identity) {
		return postJson("/mod/unlink", new JsonObject(), identity)
				.thenApply(root -> new VerifyResult(true, null))
				.exceptionally(error -> new VerifyResult(false, error.getMessage()));
	}

	// -------------------------------------------------------------------------------------------
	// SkyMelloo friends + relay chat - a friends list separate from Hypixel's real one, and a short
	// message relay (DM to a friend, or broadcast to the current party) that never touches real
	// Hypixel chat at all. See FriendsManager/RelayChatManager for the client-side state this feeds.
	// -------------------------------------------------------------------------------------------
	public record FriendEntry(String uuid, String username) {
	}

	public record FriendRequestEntry(String uuid, String username, long at) {
	}

	public record FriendsList(List<FriendEntry> friends, List<FriendRequestEntry> requests) {
	}

	public static CompletableFuture<FriendsList> fetchFriends(ModAuthManager.ModIdentity identity) {
		return getJson("/mod/friends", identity).thenApply(root -> {
			List<FriendEntry> friendsList = new ArrayList<>();
			if (root.has("friends") && root.get("friends").isJsonArray()) {
				for (JsonElement el : root.getAsJsonArray("friends")) {
					JsonObject o = el.getAsJsonObject();
					friendsList.add(new FriendEntry(o.get("uuid").getAsString(), o.get("username").getAsString()));
				}
			}
			List<FriendRequestEntry> requestsList = new ArrayList<>();
			if (root.has("requests") && root.get("requests").isJsonArray()) {
				for (JsonElement el : root.getAsJsonArray("requests")) {
					JsonObject o = el.getAsJsonObject();
					requestsList.add(new FriendRequestEntry(o.get("uuid").getAsString(), o.get("username").getAsString(), o.get("at").getAsLong()));
				}
			}
			return new FriendsList(friendsList, requestsList);
		});
	}

	/** {@code status} is one of "self", "already_friends", "accepted" (they'd already requested you back), "pending", or "limit". */
	public record FriendRequestResult(String username, String status) {
	}

	private static CompletableFuture<FriendRequestResult> friendAction(String path, String username, ModAuthManager.ModIdentity identity) {
		JsonObject body = new JsonObject();
		body.addProperty("username", username);
		return postJson(path, body, identity)
				.thenApply(root -> new FriendRequestResult(
						root.has("username") && !root.get("username").isJsonNull() ? root.get("username").getAsString() : username,
						root.has("status") && !root.get("status").isJsonNull() ? root.get("status").getAsString() : null));
	}

	public static CompletableFuture<FriendRequestResult> sendFriendRequest(String username, ModAuthManager.ModIdentity identity) {
		return friendAction("/mod/friends/request", username, identity);
	}

	public static CompletableFuture<FriendRequestResult> acceptFriendRequest(String username, ModAuthManager.ModIdentity identity) {
		return friendAction("/mod/friends/accept", username, identity);
	}

	public static CompletableFuture<FriendRequestResult> declineFriendRequest(String username, ModAuthManager.ModIdentity identity) {
		return friendAction("/mod/friends/decline", username, identity);
	}

	public static CompletableFuture<FriendRequestResult> removeFriend(String username, ModAuthManager.ModIdentity identity) {
		return friendAction("/mod/friends/remove", username, identity);
	}

	/** Sends a DM to a friend by username - the server rejects it (403) unless the two accounts are already confirmed SkyMelloo friends. */
	public static CompletableFuture<Boolean> sendRelayMessage(String toUsername, String text, ModAuthManager.ModIdentity identity) {
		JsonObject body = new JsonObject();
		body.addProperty("toUsername", toUsername);
		body.addProperty("text", text);
		return postJson("/mod/relay/message", body, identity)
				.thenApply(root -> true)
				.exceptionally(error -> false);
	}

	/** Broadcasts to a caller-resolved list of party-member UUIDs (the server has no visibility into real Hypixel parties, so this trusts whichever roster the mod itself resolved). */
	public static CompletableFuture<Boolean> sendRelayPartyMessage(List<String> toUuids, String text, ModAuthManager.ModIdentity identity) {
		JsonObject body = new JsonObject();
		JsonArray uuidsArr = new JsonArray();
		for (String uuid : toUuids) {
			uuidsArr.add(uuid);
		}
		body.add("toUuids", uuidsArr);
		body.addProperty("text", text);
		return postJson("/mod/relay/party", body, identity)
				.thenApply(root -> true)
				.exceptionally(error -> false);
	}

	/** One relayed message waiting in the inbox - {@code scope} is "dm" or "party". */
	public record RelayMessage(String fromUuid, String fromUsername, String text, String scope) {
	}

	/** Drains (not peeks) everything currently queued for this account - polled every few seconds by RelayChatManager. */
	public static CompletableFuture<List<RelayMessage>> fetchRelayInbox(ModAuthManager.ModIdentity identity) {
		return getJson("/mod/relay/inbox", identity).thenApply(root -> {
			List<RelayMessage> result = new ArrayList<>();
			if (root.has("messages") && root.get("messages").isJsonArray()) {
				for (JsonElement el : root.getAsJsonArray("messages")) {
					JsonObject o = el.getAsJsonObject();
					result.add(new RelayMessage(
							o.get("from").getAsString(),
							o.has("fromUsername") && !o.get("fromUsername").isJsonNull() ? o.get("fromUsername").getAsString() : "?",
							o.get("text").getAsString(),
							o.has("scope") && !o.get("scope").isJsonNull() ? o.get("scope").getAsString() : "dm"));
				}
			}
			return result;
		}).exceptionally(error -> List.of());
	}

	/** One credited contributor - shown on the menu's Credits page as a player head. {@code online} is live (same presence system behind the mod-user Highlighting/badge), not baked into the cached list - see SkyMellooMenuScreen's CreditsPage, which re-fetches on a timer to keep it current while the page is open. */
	public record CreditEntry(String username, String role, boolean online) {
	}

	/** Who's credited for the mod/website - pulled live from sky.melloo.me rather than hardcoded in the mod, so it stays current without a mod update. See the website's own home page for the same data. */
	public static CompletableFuture<List<CreditEntry>> fetchCredits() {
		return getJson("/credits").thenApply(root -> {
			List<CreditEntry> result = new ArrayList<>();
			if (root.has("credits") && root.get("credits").isJsonArray()) {
				for (JsonElement el : root.getAsJsonArray("credits")) {
					if (!el.isJsonObject()) {
						continue;
					}
					JsonObject entry = el.getAsJsonObject();
					if (!entry.has("username") || entry.get("username").isJsonNull()) {
						continue;
					}
					String username = entry.get("username").getAsString();
					String role = entry.has("role") && !entry.get("role").isJsonNull() ? entry.get("role").getAsString() : "";
					boolean online = entry.has("online") && entry.get("online").getAsBoolean();
					result.add(new CreditEntry(username, role, online));
				}
			}
			return result;
		});
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
