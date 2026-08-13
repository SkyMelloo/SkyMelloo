package com.melloo.skymelloo.client.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.melloo.skymelloo.client.util.ChatUtil;

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
	private static final String BASE_URL = "https://sky.melloo.me/api/public/mod/v1";
	// /credits has no v1 equivalent (shared with the website's own credits page, not mod-specific) -
	// the one remaining call still against the old base URL, see getJsonLegacy/fetchCredits.
	private static final String LEGACY_BASE_URL = "https://sky.melloo.me/api";
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

	/** Only fetchCredits uses this - see LEGACY_BASE_URL. Never signed, same as the v1 unauthenticated calls. */
	private static CompletableFuture<JsonObject> getJsonLegacy(String path) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(LEGACY_BASE_URL + path))
				.timeout(Duration.ofSeconds(8))
				.header("X-SkyMelloo-Client", "mod")
				.GET();
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
	 * deliberate simplification, not an oversight. Prepends "/api/public/mod/v1" to match the full
	 * signed path the server expects (see DEVELOPER_API.md section 3.1).
	 */
	private static String requestPath(String pathWithQuery) {
		int queryStart = pathWithQuery.indexOf('?');
		String pathOnly = queryStart < 0 ? pathWithQuery : pathWithQuery.substring(0, queryStart);
		return "/api/public/mod/v1" + pathOnly;
	}

	/** Round-trip latency to sky.melloo.me (see SkyMellooPingMonitor) - v1's /health requires mod auth, unlike the old internal route. */
	public static CompletableFuture<Void> ping(ModAuthManager.ModIdentity identity) {
		return getJson("/health", identity).thenApply(root -> null);
	}

	/** Whether this build is still compatible with the current backend API - see ModVersionManager, checked once per join right alongside the whitelist check. */
	/** {@code buildKind} - "release" (published, validly signed), "dev-build" (validly signed but unpublished, only within the most-recent-2 trust window), "dev-whitelist" (admin-whitelisted, unsigned), "unverified" (a reported hash that's none of those), or "unknown" (no hash reported at all) - see server.js's own comment. */
	/** {@code latestVersion} is the real latest PUBLISHED release's internal dev version string (see server.js's own latestRelease()) - always fetched fresh from the server, never guessed/cached client-side. {@code latestPublicVersion} is the separate user-facing release number for that same release (set by the admin at publish time, see server.js's publish route) - null if that release predates public-version tracking. {@code maintainerUsername} is the real connected owner account's name, fetched live rather than hardcoded client-side - null if the owner hasn't linked one. */
	public record VersionCheckResult(boolean compatible, String minVersion, String message, boolean upToDate, String updateAvailableMessage, boolean integrityOk, String buildKind, String latestVersion, String latestPublicVersion, String maintainerUsername) {
	}

	/** {@code jarHash} (lowercase hex SHA-256 of this build's own jar, see ModVersionManager) is optional - null when running from a dev/exploded classpath rather than a real packaged jar. */
	public static CompletableFuture<VersionCheckResult> checkVersion(String version, String jarHash) {
		String url = "/version-check?version=" + encode(version) + (jarHash != null ? "&hash=" + encode(jarHash) : "");
		return getJson(url).thenApply(root -> new VersionCheckResult(
				root.has("compatible") && root.get("compatible").getAsBoolean(),
				root.has("minVersion") && !root.get("minVersion").isJsonNull() ? root.get("minVersion").getAsString() : null,
				root.has("message") && !root.get("message").isJsonNull() ? root.get("message").getAsString() : null,
				!root.has("upToDate") || root.get("upToDate").getAsBoolean(),
				root.has("updateAvailableMessage") && !root.get("updateAvailableMessage").isJsonNull() ? root.get("updateAvailableMessage").getAsString() : null,
				!root.has("integrityOk") || root.get("integrityOk").getAsBoolean(),
				root.has("buildKind") && !root.get("buildKind").isJsonNull() ? root.get("buildKind").getAsString() : "unknown",
				root.has("latestVersion") && !root.get("latestVersion").isJsonNull() ? root.get("latestVersion").getAsString() : null,
				root.has("latestPublicVersion") && !root.get("latestPublicVersion").isJsonNull() ? root.get("latestPublicVersion").getAsString() : null,
				root.has("maintainerUsername") && !root.get("maintainerUsername").isJsonNull() ? root.get("maintainerUsername").getAsString() : null
		));
	}

	public record LegalInfo(String imprint, String privacy, String terms) {
	}

	/**
	 * "/sm legal" - moved server-side entirely, out of the (now public/open-source) mod
	 * source, and gated by the same build-verification check the integrity system already does: a
	 * build that can't be verified as an official/dev SkyMelloo release doesn't get to show itself as
	 * legally covered by the maintainer's own imprint/privacy/terms, since it genuinely isn't -
	 * anyone could have changed anything in an unverified build. The future completes exceptionally
	 * (see server.js's 403) for an unverified build - the caller shows a "not available" message.
	 */
	public static CompletableFuture<LegalInfo> fetchLegalInfo(String jarHash) {
		String url = "/legal" + (jarHash != null ? "?hash=" + encode(jarHash) : "");
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
		return getJson("/auth/challenge").thenApply(root ->
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
		return postJson("/auth/verify", body)
				.thenApply(root -> new SessionResult(root.get("expiresAt").getAsLong()));
	}

	/** Resolved per-feature permissions (cosmetics, etc.) - admin-configurable defaults + per-user overrides, resolved server-side from the verified signed request. */
	public static CompletableFuture<Map<String, Boolean>> fetchPermissions(ModAuthManager.ModIdentity identity) {
		return getJson("/permissions", identity).thenApply(root -> {
			Map<String, Boolean> result = new HashMap<>();
			for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
				if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isBoolean()) {
					result.put(entry.getKey(), entry.getValue().getAsBoolean());
				}
			}
			return result;
		});
	}

	/** Whether the account is admin-linked, and its actual highest role label (e.g. "Owner") if so - {@code roleLabel} is {@code null} against an older server that doesn't send it yet. */
	public record AdminStatus(boolean isAdmin, String roleLabel) {
	}

	/** Whether the account behind this identity is verified-linked to the admin website account (via /skymelloo verify). */
	public static CompletableFuture<AdminStatus> checkIsAdmin(ModAuthManager.ModIdentity identity) {
		return getJson("/is-admin", identity).thenApply(root -> {
			boolean isAdmin = root.has("isAdmin") && !root.get("isAdmin").isJsonNull() && root.get("isAdmin").getAsBoolean();
			String roleLabel = root.has("roleLabel") && !root.get("roleLabel").isJsonNull() ? root.get("roleLabel").getAsString() : null;
			return new AdminStatus(isAdmin, roleLabel);
		});
	}

	/** Result of completing the "/skymelloo unlink" account flow (account verification itself moved to MellooEssentials' "/mes verify"). */
	public record VerifyResult(boolean ok, String error) {
	}

	/** Result of starting the "/sm link" browser-based linking flow - {@code token} is opened as {@code https://sky.melloo.me/link/<token>} in the system browser, see SkyMellooClient's "link" command. */
	public record LinkStartResult(boolean ok, String token, String error) {
	}

	/** Starts the mirror-image of "/skymelloo verify <code>" - instead of typing a website-generated code in-game, this generates a token in-game (via the signed request, so it's tied to a proven identity) that the website consumes once opened, using whatever Discord session is already there. */
	public static CompletableFuture<LinkStartResult> startAccountLink(ModAuthManager.ModIdentity identity) {
		return postJson("/link/start", new JsonObject(), identity)
				.thenApply(root -> new LinkStartResult(true, root.get("token").getAsString(), null))
				.exceptionally(error -> new LinkStartResult(false, null, ChatUtil.friendlyError(error)));
	}

	public record CloudSettingsResult(JsonObject settings) {
	}

	/** The cloud-synced settings blob for the account behind this identity, or null if nothing's been saved yet (or the request failed). */
	public static CompletableFuture<CloudSettingsResult> fetchCloudSettings(ModAuthManager.ModIdentity identity) {
		return getJson("/settings", identity)
				.thenApply(root -> root.has("settings") && root.get("settings").isJsonObject()
						? new CloudSettingsResult(root.getAsJsonObject("settings"))
						: null)
				.exceptionally(error -> null);
	}

	/** Saves the current settings for cloud sync - a failure here just means the next sync attempt tries again. Returns whether it actually succeeded, for debug logging. */
	public static CompletableFuture<Boolean> pushCloudSettings(ModAuthManager.ModIdentity identity, JsonObject settings) {
		JsonObject body = new JsonObject();
		body.add("settings", settings);
		return postJson("/settings", body, identity)
				.thenApply(root -> true)
				.exceptionally(error -> false);
	}

	/** Undoes "/skymelloo verify" - only ever affects whichever account the signed request proves you are. */
	public static CompletableFuture<VerifyResult> unlinkAccount(ModAuthManager.ModIdentity identity) {
		return postJson("/unlink", new JsonObject(), identity)
				.thenApply(root -> new VerifyResult(true, null))
				.exceptionally(error -> new VerifyResult(false, ChatUtil.friendlyError(error)));
	}

	/** One credited contributor - shown on the menu's Credits page as a player head. {@code online} is live (same presence system behind the mod-user highlight/badge), not baked into the cached list - see SkyMellooMenuScreen's CreditsPage, which re-fetches on a timer to keep it current while the page is open. */
	public record CreditEntry(String username, String role, boolean online) {
	}

	/** Who's credited for the mod/website - pulled live from sky.melloo.me rather than hardcoded in the mod, so it stays current without a mod update. See the website's own home page for the same data. */
	public static CompletableFuture<List<CreditEntry>> fetchCredits() {
		return getJsonLegacy("/credits").thenApply(root -> {
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
