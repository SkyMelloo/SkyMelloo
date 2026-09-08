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

// Thin client for sky.melloo.me's read-only SkyBlock API. Requests run on HttpClient's own async
// executor, never the render/tick thread - callers must marshal results back via Minecraft.getInstance().execute(...).
public final class SkyMellooApiClient {
	private static final String BASE_URL = SiteConfig.url("/api/public/mod/v1");
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	private SkyMellooApiClient() {
	}

	public record AccessoryPowerResult(int accessoryPower, String selectedPower) {
	}

	public record SummaryResult(
			int skyblockLevel, double averageSkillLevel, int catacombsLevel, String selectedClass,
			double purse, double bank, double netWorth, int fairySouls, String guildName,
			String rankLabel, int highestFloor, int minionUniqueCount, int minionUpgrades,
			long bestiaryKills, long firstJoin,
			Map<String, Integer> skillLevels, Map<String, Integer> slayerLevels, Map<String, Integer> classLevels,
			int minionSlots, int petCount, String bestPetLabel, int collectionsStarted,
			String profilesLabel, int dungeonCompletions, String guildTag, int guildMemberCount,
			long dataFetchedAt,
			String gameMode, double netWorthNonCosmetic, Map<String, Double> netWorthCategories,
			List<PetEntry> pets, List<FloorEntry> floors, List<FloorEntry> masterFloors,
			List<CollectionGroup> collections, Map<String, Double> combatStats,
			String uuid, int rankColor, boolean modUser, boolean modUserAfk, boolean everModUser,
			String role, List<String> badges, int accessoryPower,
			List<MinionEntry> minions, List<BestiaryEntry> bestiary,
			List<LevelEntry> skills, List<LevelEntry> slayers
	) {
	}

	// One SkyBlock item out of any inventory section; raw is the item's own NBT, kept so the GUI can rebuild the real stack.
	public record SkyblockItem(int slot, String skyblockId, String uuid, String name, List<String> lore, String tier,
			int count, Double value, int legacyId, JsonObject raw, boolean inactive) {
	}

	// An accessory the account doesn't own yet - never has NBT to render from, just an id/name/tier.
	public record MissingAccessory(String skyblockId, String name, String tier) {
	}

	public record RarityCount(String rarity, int count, int accessoryPower) {
	}

	// A skill or slayer with the server's own level curve, so a bar shows real progress rather than level/max.
	public record LevelEntry(String name, int level, int maxLevel, double progress, double xp) {
	}

	public record MinionEntry(String type, String displayName, int tier, int maxTier) {
	}

	public record BestiaryEntry(String type, long kills, String zone) {
	}

	public record PetEntry(String type, String tier, int level, int maxLevel, boolean active, String heldItem) {
	}

	public record FloorEntry(String floor, int completions, int timesPlayed, Integer bestScore, Long fastestTimeMs) {
	}

	public record CollectionEntry(String name, int tier, int maxTier, long amount) {
	}

	public record CollectionGroup(String category, List<CollectionEntry> items) {
	}

	public record SackEntry(String id, long amount, String category) {
	}

	public record InventoryResult(
			List<SkyblockItem> armor, List<SkyblockItem> equipment, List<SkyblockItem> accessories,
			List<SkyblockItem> inventory, List<SkyblockItem> enderChest, List<SkyblockItem> vault,
			List<SackEntry> sacks, int accessoryPower, String selectedPower, List<BackpackEntry> backpacks,
			List<MissingAccessory> missingAccessories, List<RarityCount> rarityBreakdown
	) {
	}

	public record BackpackEntry(int size, SkyblockItem icon, List<SkyblockItem> items) {
	}

	private static final String[] SKILL_KEYS = {"farming", "mining", "combat", "foraging", "fishing", "enchanting", "alchemy", "taming"};
	private static final String[] SLAYER_KEYS = {"zombie", "spider", "wolf", "enderman", "blaze", "vampire"};
	private static final String[] CLASS_KEYS = {"healer", "mage", "berserk", "archer", "tank"};

	// Null-safe getAsJsonObject - the API can send a member as a literal JSON null, which Gson's own version throws on.
	private static JsonObject safeObject(JsonObject parent, String key) {
		if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) {
			return null;
		}
		return parent.getAsJsonObject(key);
	}

	private static String str(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	private static double dbl(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : 0;
	}

	private static long lng(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : 0;
	}

	private static int integer(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : 0;
	}

	private static boolean bool(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
	}

	private static JsonArray safeArray(JsonObject parent, String key) {
		if (parent == null || !parent.has(key) || !parent.get(key).isJsonArray()) {
			return new JsonArray();
		}
		return parent.getAsJsonArray(key);
	}

	// Reads an item-list section ({size, items}) or a bare array into the mod's own item model.
	private static List<SkyblockItem> parseItems(JsonObject parent, String key) {
		List<SkyblockItem> out = new ArrayList<>();
		if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) {
			return out;
		}
		JsonElement raw = parent.get(key);
		JsonArray arr = raw.isJsonArray() ? raw.getAsJsonArray() : safeArray(raw.getAsJsonObject(), "items");
		for (JsonElement el : arr) {
			if (el.isJsonObject()) {
				out.add(parseItem(el.getAsJsonObject()));
			}
		}
		return out;
	}

	private static SkyblockItem parseItem(JsonObject it) {
		List<String> lore = new ArrayList<>();
		for (JsonElement line : safeArray(it, "lorePlain")) {
			if (!line.isJsonNull()) {
				lore.add(line.getAsString());
			}
		}
		Double value = it.has("value") && !it.get("value").isJsonNull() ? it.get("value").getAsDouble() : null;
		return new SkyblockItem(
				integer(it, "slot"), str(it, "skyblockId"), str(it, "uuid"), str(it, "namePlain"), lore,
				str(it, "tier"), Math.max(1, integer(it, "count")), value, integer(it, "legacyId"),
				safeObject(it, "raw"), bool(it, "inactive"));
	}

	// From /player/:username/inventory - gear, accessories, storage and sacks in one call.
	public static CompletableFuture<InventoryResult> fetchInventory(String username, String profile, ModAuthManager.ModIdentity identity) {
		return getJson("/player/" + encode(username) + "/inventory" + profileQuery(profile), identity).thenApply(root -> {
			List<SackEntry> sacks = new ArrayList<>();
			for (JsonElement el : safeArray(root, "sacks")) {
				if (!el.isJsonObject()) {
					continue;
				}
				JsonObject s = el.getAsJsonObject();
				sacks.add(new SackEntry(str(s, "id"), lng(s, "amount"), str(s, "category")));
			}
			JsonObject ap = safeObject(root, "accessoryPower");
			if (ap == null) {
				ap = safeObject(root, "magicalPower");
			}

			List<BackpackEntry> backpacks = new ArrayList<>();
			for (JsonElement el : safeArray(root, "backpacks")) {
				if (!el.isJsonObject()) {
					continue;
				}
				JsonObject bp = el.getAsJsonObject();
				List<SkyblockItem> items = new ArrayList<>();
				for (JsonElement itemEl : safeArray(bp, "items")) {
					if (itemEl.isJsonObject()) {
						items.add(parseItem(itemEl.getAsJsonObject()));
					}
				}
				JsonObject iconObj = safeObject(bp, "icon");
				backpacks.add(new BackpackEntry(integer(bp, "size"), iconObj != null ? parseItem(iconObj) : null, items));
			}

			List<MissingAccessory> missing = new ArrayList<>();
			for (JsonElement el : safeArray(root, "missingAccessories")) {
				if (el.isJsonObject()) {
					JsonObject m = el.getAsJsonObject();
					missing.add(new MissingAccessory(str(m, "skyblockId"), str(m, "namePlain"), str(m, "tier")));
				}
			}

			List<RarityCount> rarityBreakdown = new ArrayList<>();
			JsonObject rarityObj = ap != null ? safeObject(ap, "rarityBreakdown") : null;
			if (rarityObj != null) {
				for (String rarity : rarityObj.keySet()) {
					JsonObject r = safeObject(rarityObj, rarity);
					if (r != null && integer(r, "count") > 0) {
						rarityBreakdown.add(new RarityCount(rarity, integer(r, "count"), integer(r, "accessoryPower")));
					}
				}
			}

			return new InventoryResult(
					parseItems(root, "armor"), parseItems(root, "equipment"), parseItems(root, "accessories"),
					parseItems(root, "contents"), parseItems(root, "enderChest"), parseItems(root, "vault"),
					sacks, ap != null ? integer(ap, "current") : -1, ap != null ? str(ap, "selectedPower") : null, backpacks,
					missing, rarityBreakdown);
		});
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

	// From /api/player/:username/inventory - the only endpoint that exposes Accessory Power.
	public static CompletableFuture<AccessoryPowerResult> fetchAccessoryPower(String username, ModAuthManager.ModIdentity identity) {
		return fetchAccessoryPower(username, null, identity);
	}

	// profile is a specific SkyBlock profile name, or null for the player's currently-selected profile.
	public static CompletableFuture<AccessoryPowerResult> fetchAccessoryPower(String username, String profile, ModAuthManager.ModIdentity identity) {
		return getJson("/player/" + encode(username) + "/inventory" + profileQuery(profile), identity).thenApply(root -> {
			// Prefers the website's new "accessoryPower" field, falls back to the legacy
			// "magicalPower" name (same shape) so this keeps working during the website's own rollout.
			JsonObject ap = safeObject(root, "accessoryPower");
			if (ap == null) {
				ap = safeObject(root, "magicalPower");
			}
			if (ap == null) {
				return new AccessoryPowerResult(-1, null);
			}
			int current = ap.has("current") && !ap.get("current").isJsonNull() ? ap.get("current").getAsInt() : -1;
			String selected = ap.has("selectedPower") && !ap.get("selectedPower").isJsonNull() ? ap.get("selectedPower").getAsString() : null;
			return new AccessoryPowerResult(current, selected);
		});
	}

	// Forces the backend past its own profile cache to pull straight from Hypixel. Server-side
	// cooldown-limited to once per 10 minutes/account - a 429 is a fine outcome, treat errors as ignorable.
	public static CompletableFuture<Void> requestRefresh(String username, ModAuthManager.ModIdentity identity) {
		return postJson("/player/" + encode(username) + "/request-refresh", new JsonObject(), identity).thenApply(root -> null);
	}

	// The account's SkyBlock profile names, for a ?profile= call and command autocomplete.
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

	// From /api/player/:username - profile summary (skills, dungeons, etc).
	public static CompletableFuture<SummaryResult> fetchSummary(String username, ModAuthManager.ModIdentity identity) {
		return fetchSummary(username, null, identity);
	}

	// profile is a specific SkyBlock profile name, or null for the player's currently-selected profile.
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

			List<PetEntry> pets = new ArrayList<>();
			int petCount = 0;
			String bestPetLabel = null;
			if (root.has("pets") && root.get("pets").isJsonArray()) {
				JsonArray petsArr = root.getAsJsonArray("pets");
				petCount = petsArr.size();
				double bestXp = -1;
				boolean activeFound = false;
				for (JsonElement el : petsArr) {
					if (!el.isJsonObject()) {
						continue;
					}
					JsonObject pet = el.getAsJsonObject();
					String type = pet.has("type") && !pet.get("type").isJsonNull() ? pet.get("type").getAsString() : null;
					if (type == null) {
						continue;
					}
					String tier = str(pet, "tier");
					boolean active = bool(pet, "active");
					pets.add(new PetEntry(type, tier, integer(pet, "level"), integer(pet, "maxLevel"), active, str(pet, "heldItem")));
					if (active) {
						bestPetLabel = type + (tier != null ? " (" + tier + ")" : "") + " [active]";
						activeFound = true;
						continue;
					}
					double xp = dbl(pet, "xp");
					if (!activeFound && xp > bestXp) {
						bestXp = xp;
						bestPetLabel = type + (tier != null ? " (" + tier + ")" : "");
					}
				}
			}

			int collectionsStarted = root.has("collections") && root.get("collections").isJsonArray()
					? root.getAsJsonArray("collections").size() : 0;

			String profilesLabel = "§7No profiles found";
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
					sb.append(pname).append(" §7(").append(mode).append(selected ? ", active" : "").append(")§r");
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

			double netWorthNonCosmetic = netWorthObj != null ? dbl(netWorthObj, "nonCosmetic") : 0;
			Map<String, Double> netWorthCategories = new LinkedHashMap<>();
			JsonObject catsObj = netWorthObj != null ? safeObject(netWorthObj, "categories") : null;
			if (catsObj != null) {
				for (String key : catsObj.keySet()) {
					if (!catsObj.get(key).isJsonNull()) {
						netWorthCategories.put(key, catsObj.get(key).getAsDouble());
					}
				}
			}

			List<FloorEntry> floors = parseFloors(dungeons, "floors");
			List<FloorEntry> masterFloors = parseFloors(dungeons, "masterFloors");

			List<CollectionGroup> collections = new ArrayList<>();
			for (JsonElement el : safeArray(root, "collections")) {
				if (!el.isJsonObject()) {
					continue;
				}
				JsonObject group = el.getAsJsonObject();
				List<CollectionEntry> entries = new ArrayList<>();
				for (JsonElement itemEl : safeArray(group, "items")) {
					if (!itemEl.isJsonObject()) {
						continue;
					}
					JsonObject item = itemEl.getAsJsonObject();
					entries.add(new CollectionEntry(str(item, "name"), integer(item, "tier"), integer(item, "maxTier"), lng(item, "amount")));
				}
				collections.add(new CollectionGroup(str(group, "category"), entries));
			}
			// The raw array is one entry per category, so its size is a category count, not a collection count.
			collectionsStarted = collections.stream().mapToInt(g -> g.items().size()).sum();

			// The real numbers sit one level down in .stats; the wrapper itself only adds accessoryPower.
			JsonObject combatObj = safeObject(root, "combatStats");
			JsonObject statsObj = safeObject(combatObj, "stats");
			Map<String, Double> combatStats = new LinkedHashMap<>();
			if (statsObj != null) {
				for (String key : statsObj.keySet()) {
					if (statsObj.get(key).isJsonPrimitive() && statsObj.get(key).getAsJsonPrimitive().isNumber()) {
						combatStats.put(key, statsObj.get(key).getAsDouble());
					}
				}
			}
			int accessoryPower = combatObj != null ? integer(combatObj, "accessoryPower") : 0;

			int rankColor = rankObj != null ? parseHexColor(str(rankObj, "color")) : 0xFFAAAAAA;

			List<String> badges = new ArrayList<>();
			for (JsonElement el : safeArray(root, "badges")) {
				if (!el.isJsonNull()) {
					badges.add(el.getAsString());
				}
			}

			List<MinionEntry> minions = new ArrayList<>();
			if (minionsObj != null) {
				for (JsonElement el : safeArray(minionsObj, "minions")) {
					if (!el.isJsonObject()) {
						continue;
					}
					JsonObject minion = el.getAsJsonObject();
					minions.add(new MinionEntry(str(minion, "type"), str(minion, "displayName"),
							integer(minion, "tier"), integer(minion, "maxTier")));
				}
			}

			List<BestiaryEntry> bestiary = new ArrayList<>();
			for (JsonElement el : safeArray(root, "bestiary")) {
				if (!el.isJsonObject()) {
					continue;
				}
				JsonObject mob = el.getAsJsonObject();
				bestiary.add(new BestiaryEntry(str(mob, "type"), lng(mob, "kills"), str(mob, "zone")));
			}

			List<LevelEntry> skills = parseLevels(safeObject(root, "skills"));
			List<LevelEntry> slayers = parseLevels(safeObject(root, "slayers"));

			return new SummaryResult(
					sbLevel, avgSkill, catacombs, selectedClass, purse, bank, netWorth, fairySouls, guildName,
					rankLabel, highestFloor, minionUniqueCount, minionUpgrades, bestiaryKills, firstJoin,
					skillLevels, slayerLevels, classLevels,
					minionSlots, petCount, bestPetLabel, collectionsStarted, profilesLabel, dungeonCompletions,
					guildTag, guildMemberCount, dataFetchedAt,
					str(root, "gameMode"), netWorthNonCosmetic, netWorthCategories,
					pets, floors, masterFloors, collections, combatStats,
					str(root, "uuid"), rankColor, bool(root, "modUser"), bool(root, "modUserAfk"),
					bool(root, "isModUser"), str(root, "role"), badges, accessoryPower,
					minions, bestiary, skills, slayers
			);
		});
	}

	// Reads a {name: {level, maxLevel, progress, xp}} map, keeping the server's own curve.
	private static List<LevelEntry> parseLevels(JsonObject parent) {
		List<LevelEntry> out = new ArrayList<>();
		if (parent == null) {
			return out;
		}
		for (String key : parent.keySet()) {
			JsonObject entry = safeObject(parent, key);
			if (entry == null || !entry.has("level") || entry.get("level").isJsonNull()) {
				continue;
			}
			out.add(new LevelEntry(key, integer(entry, "level"), integer(entry, "maxLevel"),
					dbl(entry, "progress"), dbl(entry, "xp")));
		}
		return out;
	}

	// The API sends a rank colour as a CSS hex string; the GUI needs it as an ARGB int.
	private static int parseHexColor(String hex) {
		if (hex == null || !hex.startsWith("#") || hex.length() != 7) {
			return 0xFFAAAAAA;
		}
		try {
			return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
		} catch (NumberFormatException e) {
			return 0xFFAAAAAA;
		}
	}

	private static List<FloorEntry> parseFloors(JsonObject dungeons, String key) {
		List<FloorEntry> out = new ArrayList<>();
		for (JsonElement el : safeArray(dungeons, key)) {
			if (!el.isJsonObject()) {
				continue;
			}
			JsonObject f = el.getAsJsonObject();
			Integer bestScore = f.has("bestScore") && !f.get("bestScore").isJsonNull() ? f.get("bestScore").getAsInt() : null;
			Long fastest = f.has("fastestTimeMs") && !f.get("fastestTimeMs").isJsonNull() ? f.get("fastestTimeMs").getAsLong() : null;
			out.add(new FloorEntry(str(f, "floor"), integer(f, "completions"), integer(f, "timesPlayed"), bestScore, fastest));
		}
		return out;
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

	// Signs this specific request rather than reusing a token - required by every /mod/* route except the auth handshake.
	private static CompletableFuture<JsonObject> getJson(String path, ModAuthManager.ModIdentity identity) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(BASE_URL + path))
				.timeout(Duration.ofSeconds(8))
				.header("X-SkyMelloo-Client", "mod")
				.GET();
		if (identity != null) {
			attachSignature(builder, identity, "GET", requestPath(path), new byte[0]);
		}
		return sendJson(builder.build());
	}

	private static CompletableFuture<JsonObject> sendJson(HttpRequest request) {
		return sendWithRetry(request)
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

	// A timeout is often a one-off hiccup - retried exactly once after 1s. Other failures aren't retried.
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

	// Every error response is JSON { "error": "message" } - surface that instead of a bare status code.
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
		// Reused as raw bytes for both transmission and the signature hash - a second toString() call could disagree with what was sent.
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

	// Only the path is signed, never the query string - deliberate, these routes' query params never mutate state.
	private static String requestPath(String pathWithQuery) {
		int queryStart = pathWithQuery.indexOf('?');
		String pathOnly = queryStart < 0 ? pathWithQuery : pathWithQuery.substring(0, queryStart);
		return "/api/public/mod/v1" + pathOnly;
	}

	// Round-trip latency to sky.melloo.me - v1's /health requires mod auth, unlike the old internal route.
	public static CompletableFuture<Void> ping(ModAuthManager.ModIdentity identity) {
		return getJson("/health", identity).thenApply(root -> null);
	}

	public record LegalInfo(String imprint, String privacy, String terms) {
	}

	// "/sm legal" - server-gated by build verification; an unverified build can't claim to be legally covered, future completes exceptionally.
	public static CompletableFuture<LegalInfo> fetchLegalInfo(String jarHash) {
		String url = "/legal" + (jarHash != null ? "?hash=" + encode(jarHash) : "");
		return getJson(url).thenApply(root -> new LegalInfo(
				root.get("imprint").getAsString(),
				root.get("privacy").getAsString(),
				root.get("terms").getAsString()
		));
	}

	// A one-time serverId for Mojang's joinServer call, plus a clock reading to correct for drift.
	public record ChallengeResult(String serverId, long serverTime) {
	}

	public static CompletableFuture<ChallengeResult> requestAuthChallenge() {
		return getJson("/auth/challenge").thenApply(root ->
				new ChallengeResult(root.get("serverId").getAsString(), root.get("serverTime").getAsLong()));
	}

	public record SessionResult(long expiresAt) {
	}

	// Redeems a completed joinServer call, registering this launch's ephemeral public key as the active session.
	public static CompletableFuture<SessionResult> verifyAuthChallenge(String serverId, String username, String uuid, String publicKeyBase64) {
		JsonObject body = new JsonObject();
		body.addProperty("serverId", serverId);
		body.addProperty("username", username);
		body.addProperty("uuid", uuid);
		body.addProperty("publicKey", publicKeyBase64);
		return postJson("/auth/verify", body)
				.thenApply(root -> new SessionResult(root.get("expiresAt").getAsLong()));
	}

	// Resolved per-feature permissions - admin defaults + per-user overrides, resolved server-side.
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

	// roleLabel is null against an older server that doesn't send it yet.
	public record AdminStatus(boolean isAdmin, String roleLabel) {
	}

	// Whether the account behind this identity is verified-linked to the admin website account (via /skymelloo verify).
	public static CompletableFuture<AdminStatus> checkIsAdmin(ModAuthManager.ModIdentity identity) {
		return getJson("/is-admin", identity).thenApply(root -> {
			boolean isAdmin = root.has("isAdmin") && !root.get("isAdmin").isJsonNull() && root.get("isAdmin").getAsBoolean();
			String roleLabel = root.has("roleLabel") && !root.get("roleLabel").isJsonNull() ? root.get("roleLabel").getAsString() : null;
			return new AdminStatus(isAdmin, roleLabel);
		});
	}

	// Result of completing the "/skymelloo unlink" account flow (account verification itself moved to MellooEssentials' "/mes verify").
	public record VerifyResult(boolean ok, String error) {
	}

	// Result of starting "/sm link" - token is opened as sky.melloo.me/link/<token> in the system browser.
	public record LinkStartResult(boolean ok, String token, String error) {
	}

	// Mirror of "/skymelloo verify <code>" - generates a token in-game the website consumes once opened.
	public static CompletableFuture<LinkStartResult> startAccountLink(ModAuthManager.ModIdentity identity) {
		return postJson("/link/start", new JsonObject(), identity)
				.thenApply(root -> new LinkStartResult(true, root.get("token").getAsString(), null))
				.exceptionally(error -> new LinkStartResult(false, null, ChatUtil.friendlyError(error)));
	}

	public record CloudSettingsResult(JsonObject settings) {
	}

	// The cloud-synced settings blob for the account behind this identity, or null if nothing's been saved yet (or the request failed).
	public static CompletableFuture<CloudSettingsResult> fetchCloudSettings(ModAuthManager.ModIdentity identity) {
		return getJson("/settings", identity)
				.thenApply(root -> root.has("settings") && root.get("settings").isJsonObject()
						? new CloudSettingsResult(root.getAsJsonObject("settings"))
						: null)
				.exceptionally(error -> null);
	}

	// A failure here just means the next sync attempt tries again; returns success for debug logging.
	public static CompletableFuture<Boolean> pushCloudSettings(ModAuthManager.ModIdentity identity, JsonObject settings) {
		JsonObject body = new JsonObject();
		body.add("settings", settings);
		return postJson("/settings", body, identity)
				.thenApply(root -> true)
				.exceptionally(error -> false);
	}

	// Undoes "/skymelloo verify" - only ever affects whichever account the signed request proves you are.
	public static CompletableFuture<VerifyResult> unlinkAccount(ModAuthManager.ModIdentity identity) {
		return postJson("/unlink", new JsonObject(), identity)
				.thenApply(root -> new VerifyResult(true, null))
				.exceptionally(error -> new VerifyResult(false, ChatUtil.friendlyError(error)));
	}

	// One credited contributor shown on the Credits page - online is live, not baked into the cached list.
	public record CreditEntry(String username, String role, boolean online) {
	}

	// Who's credited for the mod/website - pulled live rather than hardcoded, so it stays current without a mod update.
	public static CompletableFuture<List<CreditEntry>> fetchCredits(ModAuthManager.ModIdentity identity) {
		return getJson("/credits", identity).thenApply(root -> {
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
