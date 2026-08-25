package com.melloo.skymelloo.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Rebuilds a SkyBlock item as a real {@link ItemStack} from the raw NBT the API passes through. */
public final class SkyblockItemIcons {
	private static final Map<String, ItemStack> CACHE = new HashMap<>();
	// Toggled by /sm debug items - off by default, since a debug lore line has no business showing to a normal user.
	private static volatile boolean debugMode = false;

	/** Flips the debug lore on player heads and clears the cache, so already-resolved items pick up the new state immediately. */
	public static boolean toggleDebug() {
		debugMode = !debugMode;
		CACHE.clear();
		return debugMode;
	}
	private static final Map<Integer, Item> LEGACY = buildLegacy();

	// Legacy ids whose damage value picks a different item entirely (colour variants and skulls).
	private static final Item[] WOOL = {
			Items.WHITE_WOOL, Items.ORANGE_WOOL, Items.MAGENTA_WOOL, Items.LIGHT_BLUE_WOOL,
			Items.YELLOW_WOOL, Items.LIME_WOOL, Items.PINK_WOOL, Items.GRAY_WOOL,
			Items.LIGHT_GRAY_WOOL, Items.CYAN_WOOL, Items.PURPLE_WOOL, Items.BLUE_WOOL,
			Items.BROWN_WOOL, Items.GREEN_WOOL, Items.RED_WOOL, Items.BLACK_WOOL};
	// 1.8 dye damage runs the opposite way round to the wool order above.
	private static final Item[] DYE = {
			Items.INK_SAC, Items.RED_DYE, Items.GREEN_DYE, Items.COCOA_BEANS,
			Items.LAPIS_LAZULI, Items.PURPLE_DYE, Items.CYAN_DYE, Items.LIGHT_GRAY_DYE,
			Items.GRAY_DYE, Items.PINK_DYE, Items.LIME_DYE, Items.YELLOW_DYE,
			Items.LIGHT_BLUE_DYE, Items.MAGENTA_DYE, Items.ORANGE_DYE, Items.WHITE_DYE};
	private static final Item[] STAINED_GLASS = {
			Items.WHITE_STAINED_GLASS, Items.ORANGE_STAINED_GLASS, Items.MAGENTA_STAINED_GLASS, Items.LIGHT_BLUE_STAINED_GLASS,
			Items.YELLOW_STAINED_GLASS, Items.LIME_STAINED_GLASS, Items.PINK_STAINED_GLASS, Items.GRAY_STAINED_GLASS,
			Items.LIGHT_GRAY_STAINED_GLASS, Items.CYAN_STAINED_GLASS, Items.PURPLE_STAINED_GLASS, Items.BLUE_STAINED_GLASS,
			Items.BROWN_STAINED_GLASS, Items.GREEN_STAINED_GLASS, Items.RED_STAINED_GLASS, Items.BLACK_STAINED_GLASS};
	private static final Item[] STAINED_PANE = {
			Items.WHITE_STAINED_GLASS_PANE, Items.ORANGE_STAINED_GLASS_PANE, Items.MAGENTA_STAINED_GLASS_PANE, Items.LIGHT_BLUE_STAINED_GLASS_PANE,
			Items.YELLOW_STAINED_GLASS_PANE, Items.LIME_STAINED_GLASS_PANE, Items.PINK_STAINED_GLASS_PANE, Items.GRAY_STAINED_GLASS_PANE,
			Items.LIGHT_GRAY_STAINED_GLASS_PANE, Items.CYAN_STAINED_GLASS_PANE, Items.PURPLE_STAINED_GLASS_PANE, Items.BLUE_STAINED_GLASS_PANE,
			Items.BROWN_STAINED_GLASS_PANE, Items.GREEN_STAINED_GLASS_PANE, Items.RED_STAINED_GLASS_PANE, Items.BLACK_STAINED_GLASS_PANE};
	private static final Item[] SKULL = {
			Items.SKELETON_SKULL, Items.WITHER_SKELETON_SKULL, Items.ZOMBIE_HEAD, Items.PLAYER_HEAD,
			Items.CREEPER_HEAD, Items.DRAGON_HEAD};
	private static final Item[] LOG = {Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG, Items.JUNGLE_LOG};
	private static final Item[] LOG2 = {Items.ACACIA_LOG, Items.DARK_OAK_LOG};
	private static final Item[] PLANKS = {
			Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS,
			Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS};
	private static final Item[] SANDSTONE = {Items.SANDSTONE, Items.CHISELED_SANDSTONE, Items.CUT_SANDSTONE};
	private static final Item[] STONE = {
			Items.STONE, Items.GRANITE, Items.POLISHED_GRANITE, Items.DIORITE,
			Items.POLISHED_DIORITE, Items.ANDESITE, Items.POLISHED_ANDESITE};
	private static final Item[] QUARTZ_BLOCK = {Items.QUARTZ_BLOCK, Items.CHISELED_QUARTZ_BLOCK, Items.QUARTZ_PILLAR};
	private static final Item[] PRISMARINE = {Items.PRISMARINE, Items.PRISMARINE_BRICKS, Items.DARK_PRISMARINE};
	private static final Item[] COAL = {Items.COAL, Items.CHARCOAL};
	private static final Item[] FISH = {Items.COD, Items.SALMON, Items.TROPICAL_FISH, Items.PUFFERFISH};
	private static final Item[] COOKED_FISH = {Items.COOKED_COD, Items.COOKED_SALMON};
	private static final Item[] GOLDEN_APPLE = {Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE};
	private static final Item[] SPONGE = {Items.SPONGE, Items.WET_SPONGE};
	private static final Item[] DIRT = {Items.DIRT, Items.COARSE_DIRT, Items.PODZOL};

	private SkyblockItemIcons() {
	}

	private static Map<Integer, Item> buildLegacy() {
		Map<Integer, Item> m = new HashMap<>();
		m.put(1, Items.STONE);
		m.put(2, Items.GRASS_BLOCK);
		m.put(3, Items.DIRT);
		m.put(4, Items.COBBLESTONE);
		m.put(5, Items.OAK_PLANKS);
		m.put(6, Items.OAK_SAPLING);
		m.put(7, Items.BEDROCK);
		m.put(12, Items.SAND);
		m.put(13, Items.GRAVEL);
		m.put(14, Items.GOLD_ORE);
		m.put(15, Items.IRON_ORE);
		m.put(16, Items.COAL_ORE);
		m.put(17, Items.OAK_LOG);
		m.put(18, Items.OAK_LEAVES);
		m.put(19, Items.SPONGE);
		m.put(20, Items.GLASS);
		m.put(21, Items.LAPIS_ORE);
		m.put(22, Items.LAPIS_BLOCK);
		m.put(23, Items.DISPENSER);
		m.put(24, Items.SANDSTONE);
		m.put(35, Items.WHITE_WOOL);
		m.put(41, Items.GOLD_BLOCK);
		m.put(42, Items.IRON_BLOCK);
		m.put(45, Items.BRICKS);
		m.put(46, Items.TNT);
		m.put(47, Items.BOOKSHELF);
		m.put(48, Items.MOSSY_COBBLESTONE);
		m.put(49, Items.OBSIDIAN);
		m.put(50, Items.TORCH);
		m.put(52, Items.SPAWNER);
		m.put(54, Items.CHEST);
		m.put(56, Items.DIAMOND_ORE);
		m.put(57, Items.DIAMOND_BLOCK);
		m.put(58, Items.CRAFTING_TABLE);
		m.put(61, Items.FURNACE);
		m.put(73, Items.REDSTONE_ORE);
		m.put(79, Items.ICE);
		m.put(80, Items.SNOW_BLOCK);
		m.put(81, Items.CACTUS);
		m.put(82, Items.CLAY);
		m.put(84, Items.JUKEBOX);
		m.put(86, Items.CARVED_PUMPKIN);
		m.put(87, Items.NETHERRACK);
		m.put(88, Items.SOUL_SAND);
		m.put(89, Items.GLOWSTONE);
		m.put(91, Items.JACK_O_LANTERN);
		m.put(95, Items.WHITE_STAINED_GLASS);
		m.put(97, Items.INFESTED_STONE);
		m.put(98, Items.STONE_BRICKS);
		m.put(103, Items.MELON);
		m.put(110, Items.MYCELIUM);
		m.put(112, Items.NETHER_BRICKS);
		m.put(116, Items.ENCHANTING_TABLE);
		m.put(120, Items.END_PORTAL_FRAME);
		m.put(121, Items.END_STONE);
		m.put(129, Items.EMERALD_ORE);
		m.put(130, Items.ENDER_CHEST);
		m.put(133, Items.EMERALD_BLOCK);
		m.put(152, Items.REDSTONE_BLOCK);
		m.put(153, Items.NETHER_QUARTZ_ORE);
		m.put(155, Items.QUARTZ_BLOCK);
		m.put(158, Items.DROPPER);
		m.put(159, Items.WHITE_TERRACOTTA);
		m.put(160, Items.WHITE_STAINED_GLASS_PANE);
		m.put(162, Items.ACACIA_LOG);
		m.put(165, Items.SLIME_BLOCK);
		m.put(168, Items.PRISMARINE);
		m.put(169, Items.SEA_LANTERN);
		m.put(170, Items.HAY_BLOCK);
		m.put(172, Items.TERRACOTTA);
		m.put(173, Items.COAL_BLOCK);
		m.put(174, Items.PACKED_ICE);
		m.put(175, Items.SUNFLOWER);
		m.put(179, Items.RED_SANDSTONE);
		m.put(256, Items.IRON_SHOVEL);
		m.put(257, Items.IRON_PICKAXE);
		m.put(258, Items.IRON_AXE);
		m.put(259, Items.FLINT_AND_STEEL);
		m.put(260, Items.APPLE);
		m.put(261, Items.BOW);
		m.put(262, Items.ARROW);
		m.put(263, Items.COAL);
		m.put(264, Items.DIAMOND);
		m.put(265, Items.IRON_INGOT);
		m.put(266, Items.GOLD_INGOT);
		m.put(267, Items.IRON_SWORD);
		m.put(268, Items.WOODEN_SWORD);
		m.put(269, Items.WOODEN_SHOVEL);
		m.put(270, Items.WOODEN_PICKAXE);
		m.put(271, Items.WOODEN_AXE);
		m.put(272, Items.STONE_SWORD);
		m.put(273, Items.STONE_SHOVEL);
		m.put(274, Items.STONE_PICKAXE);
		m.put(275, Items.STONE_AXE);
		m.put(276, Items.DIAMOND_SWORD);
		m.put(277, Items.DIAMOND_SHOVEL);
		m.put(278, Items.DIAMOND_PICKAXE);
		m.put(279, Items.DIAMOND_AXE);
		m.put(280, Items.STICK);
		m.put(281, Items.BOWL);
		m.put(282, Items.MUSHROOM_STEW);
		m.put(283, Items.GOLDEN_SWORD);
		m.put(284, Items.GOLDEN_SHOVEL);
		m.put(285, Items.GOLDEN_PICKAXE);
		m.put(286, Items.GOLDEN_AXE);
		m.put(287, Items.STRING);
		m.put(288, Items.FEATHER);
		m.put(289, Items.GUNPOWDER);
		m.put(290, Items.WOODEN_HOE);
		m.put(291, Items.STONE_HOE);
		m.put(292, Items.IRON_HOE);
		m.put(293, Items.DIAMOND_HOE);
		m.put(294, Items.GOLDEN_HOE);
		m.put(295, Items.WHEAT_SEEDS);
		m.put(296, Items.WHEAT);
		m.put(297, Items.BREAD);
		m.put(298, Items.LEATHER_HELMET);
		m.put(299, Items.LEATHER_CHESTPLATE);
		m.put(300, Items.LEATHER_LEGGINGS);
		m.put(301, Items.LEATHER_BOOTS);
		m.put(302, Items.CHAINMAIL_HELMET);
		m.put(303, Items.CHAINMAIL_CHESTPLATE);
		m.put(304, Items.CHAINMAIL_LEGGINGS);
		m.put(305, Items.CHAINMAIL_BOOTS);
		m.put(306, Items.IRON_HELMET);
		m.put(307, Items.IRON_CHESTPLATE);
		m.put(308, Items.IRON_LEGGINGS);
		m.put(309, Items.IRON_BOOTS);
		m.put(310, Items.DIAMOND_HELMET);
		m.put(311, Items.DIAMOND_CHESTPLATE);
		m.put(312, Items.DIAMOND_LEGGINGS);
		m.put(313, Items.DIAMOND_BOOTS);
		m.put(314, Items.GOLDEN_HELMET);
		m.put(315, Items.GOLDEN_CHESTPLATE);
		m.put(316, Items.GOLDEN_LEGGINGS);
		m.put(317, Items.GOLDEN_BOOTS);
		m.put(318, Items.FLINT);
		m.put(319, Items.PORKCHOP);
		m.put(320, Items.COOKED_PORKCHOP);
		m.put(321, Items.PAINTING);
		m.put(322, Items.GOLDEN_APPLE);
		m.put(323, Items.OAK_SIGN);
		m.put(324, Items.OAK_DOOR);
		m.put(325, Items.BUCKET);
		m.put(326, Items.WATER_BUCKET);
		m.put(327, Items.LAVA_BUCKET);
		m.put(328, Items.MINECART);
		m.put(329, Items.SADDLE);
		m.put(331, Items.REDSTONE);
		m.put(332, Items.SNOWBALL);
		m.put(333, Items.OAK_BOAT);
		m.put(334, Items.LEATHER);
		m.put(336, Items.BRICK);
		m.put(337, Items.CLAY_BALL);
		m.put(338, Items.SUGAR_CANE);
		m.put(339, Items.PAPER);
		m.put(340, Items.BOOK);
		m.put(341, Items.SLIME_BALL);
		m.put(344, Items.EGG);
		m.put(345, Items.COMPASS);
		m.put(346, Items.FISHING_ROD);
		m.put(347, Items.CLOCK);
		m.put(348, Items.GLOWSTONE_DUST);
		m.put(349, Items.COD);
		m.put(350, Items.COOKED_COD);
		m.put(351, Items.INK_SAC);
		m.put(352, Items.BONE);
		m.put(353, Items.SUGAR);
		m.put(354, Items.CAKE);
		m.put(355, Items.WHITE_BED);
		m.put(357, Items.COOKIE);
		m.put(358, Items.FILLED_MAP);
		m.put(359, Items.SHEARS);
		m.put(360, Items.MELON_SLICE);
		m.put(361, Items.PUMPKIN_SEEDS);
		m.put(362, Items.MELON_SEEDS);
		m.put(363, Items.BEEF);
		m.put(364, Items.COOKED_BEEF);
		m.put(365, Items.CHICKEN);
		m.put(366, Items.COOKED_CHICKEN);
		m.put(367, Items.ROTTEN_FLESH);
		m.put(368, Items.ENDER_PEARL);
		m.put(369, Items.BLAZE_ROD);
		m.put(370, Items.GHAST_TEAR);
		m.put(371, Items.GOLD_NUGGET);
		m.put(372, Items.NETHER_WART);
		m.put(373, Items.POTION);
		m.put(374, Items.GLASS_BOTTLE);
		m.put(375, Items.SPIDER_EYE);
		m.put(376, Items.FERMENTED_SPIDER_EYE);
		m.put(377, Items.BLAZE_POWDER);
		m.put(378, Items.MAGMA_CREAM);
		m.put(379, Items.BREWING_STAND);
		m.put(380, Items.CAULDRON);
		m.put(381, Items.ENDER_EYE);
		m.put(382, Items.GLISTERING_MELON_SLICE);
		m.put(383, Items.PIG_SPAWN_EGG);
		m.put(384, Items.EXPERIENCE_BOTTLE);
		m.put(385, Items.FIRE_CHARGE);
		m.put(386, Items.WRITABLE_BOOK);
		m.put(387, Items.WRITTEN_BOOK);
		m.put(388, Items.EMERALD);
		m.put(389, Items.ITEM_FRAME);
		m.put(390, Items.FLOWER_POT);
		m.put(391, Items.CARROT);
		m.put(392, Items.POTATO);
		m.put(393, Items.BAKED_POTATO);
		m.put(394, Items.POISONOUS_POTATO);
		m.put(395, Items.MAP);
		m.put(396, Items.GOLDEN_CARROT);
		m.put(397, Items.PLAYER_HEAD);
		m.put(398, Items.CARROT_ON_A_STICK);
		m.put(399, Items.NETHER_STAR);
		m.put(400, Items.PUMPKIN_PIE);
		m.put(401, Items.FIREWORK_ROCKET);
		m.put(402, Items.FIREWORK_STAR);
		m.put(403, Items.ENCHANTED_BOOK);
		m.put(405, Items.NETHER_BRICK);
		m.put(406, Items.QUARTZ);
		m.put(407, Items.TNT_MINECART);
		m.put(408, Items.HOPPER_MINECART);
		m.put(409, Items.PRISMARINE_SHARD);
		m.put(410, Items.PRISMARINE_CRYSTALS);
		m.put(411, Items.RABBIT);
		m.put(412, Items.COOKED_RABBIT);
		m.put(413, Items.RABBIT_STEW);
		m.put(414, Items.RABBIT_FOOT);
		m.put(415, Items.RABBIT_HIDE);
		m.put(416, Items.ARMOR_STAND);
		m.put(417, Items.IRON_HORSE_ARMOR);
		m.put(418, Items.GOLDEN_HORSE_ARMOR);
		m.put(419, Items.DIAMOND_HORSE_ARMOR);
		m.put(420, Items.LEAD);
		m.put(421, Items.NAME_TAG);
		m.put(422, Items.COMMAND_BLOCK_MINECART);
		m.put(423, Items.MUTTON);
		m.put(424, Items.COOKED_MUTTON);
		m.put(425, Items.WHITE_BANNER);
		m.put(426, Items.END_CRYSTAL);
		m.put(427, Items.SPRUCE_DOOR);
		m.put(434, Items.BEETROOT);
		m.put(435, Items.BEETROOT_SEEDS);
		m.put(436, Items.BEETROOT_SOUP);
		return m;
	}

	private static Item variant(Item[] table, int damage) {
		return damage >= 0 && damage < table.length ? table[damage] : table[0];
	}

	/** Hypixel still sends 1.8 ids, where the damage value selects the colour/wood/skull variant. */
	private static Item itemFor(int legacyId, int damage) {
		Item byDamage = switch (legacyId) {
			case 35 -> variant(WOOL, damage);
			case 351 -> variant(DYE, damage);
			case 95 -> variant(STAINED_GLASS, damage);
			case 160 -> variant(STAINED_PANE, damage);
			case 397 -> variant(SKULL, damage);
			case 17 -> variant(LOG, damage % 4);
			case 162 -> variant(LOG2, damage % 2);
			case 5 -> variant(PLANKS, damage);
			case 24 -> variant(SANDSTONE, damage);
			case 1 -> variant(STONE, damage);
			case 155 -> variant(QUARTZ_BLOCK, damage);
			case 168 -> variant(PRISMARINE, damage);
			case 263 -> variant(COAL, damage);
			case 349 -> variant(FISH, damage);
			case 350 -> variant(COOKED_FISH, damage);
			case 322 -> variant(GOLDEN_APPLE, damage);
			case 19 -> variant(SPONGE, damage);
			case 3 -> variant(DIRT, damage);
			default -> null;
		};
		if (byDamage != null) {
			return byDamage;
		}
		Item mapped = LEGACY.get(legacyId);
		return mapped != null ? mapped : Items.PAPER;
	}

	/** Cached per item uuid - a tab would otherwise re-parse every lore line on every frame. */
	public static ItemStack resolve(String cacheKey, JsonObject raw, String skyblockId, int legacyId, String tier, String fallbackName) {
		String key = cacheKey != null ? cacheKey : fallbackKey(raw, skyblockId, legacyId);
		ItemStack cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		try {
			ItemStack stack = build(raw, skyblockId, legacyId, tier, fallbackName);
			CACHE.put(key, stack);
			return stack;
		} catch (Throwable t) {
			// Not cached - a transient failure shouldn't permanently poison this key for every other item that falls back to it.
			ItemStack stack = new ItemStack(Items.PAPER);
			stack.set(DataComponents.CUSTOM_NAME, Component.literal((fallbackName != null ? fallbackName : "?") + " (" + t + ")"));
			return stack;
		}
	}

	/** Without a uuid, two different unnamed heads can share id+tier - fold in the skull texture itself so they don't collide in the cache. */
	private static String fallbackKey(JsonObject raw, String skyblockId, int legacyId) {
		JsonObject skullOwner = obj(obj(raw, "tag"), "SkullOwner");
		JsonObject properties = obj(skullOwner, "Properties");
		if (properties != null && properties.has("textures") && properties.get("textures").isJsonArray() && !properties.getAsJsonArray("textures").isEmpty()) {
			JsonElement first = properties.getAsJsonArray("textures").get(0);
			String value = first.isJsonObject() ? string(first.getAsJsonObject(), "Value") : null;
			if (value != null) {
				return "tex/" + value;
			}
		}
		return (skyblockId != null ? skyblockId : "?") + "/" + legacyId;
	}

	private static ItemStack build(JsonObject raw, String skyblockId, int legacyId, String tier, String fallbackName) {
		int id = raw != null && raw.has("id") && raw.get("id").isJsonPrimitive() ? raw.get("id").getAsInt() : legacyId;
		int damage = raw != null && raw.has("Damage") && raw.get("Damage").isJsonPrimitive() ? raw.get("Damage").getAsInt() : 0;
		Item item = id > 0 ? itemFor(id, damage) : vanillaOrPlaceholder(skyblockId);
		ItemStack stack = new ItemStack(item);

		JsonObject tag = obj(raw, "tag");
		JsonObject display = obj(tag, "display");

		String name = display != null ? string(display, "Name") : null;
		stack.set(DataComponents.CUSTOM_NAME, name != null ? LegacyText.parse(name)
				: Component.literal(fallbackName != null ? fallbackName : "?"));

		if (display != null && display.has("Lore") && display.get("Lore").isJsonArray()) {
			List<Component> lore = new ArrayList<>();
			for (JsonElement line : display.getAsJsonArray("Lore")) {
				if (!line.isJsonNull()) {
					lore.add(LegacyText.parse(line.getAsString()));
				}
			}
			stack.set(DataComponents.LORE, new ItemLore(lore));
		}

		if (display != null && display.has("color") && display.get("color").isJsonPrimitive()) {
			stack.set(DataComponents.DYED_COLOR, new DyedItemColor(display.get("color").getAsInt()));
		}

		if (item == Items.PLAYER_HEAD) {
			String outcome;
			try {
				ResolvableProfile profile = skullProfile(obj(tag, "SkullOwner"));
				if (profile != null) {
					stack.set(DataComponents.PROFILE, profile);
					outcome = "profile set";
				} else {
					outcome = "skullProfile() returned null";
				}
			} catch (Throwable t) {
				outcome = "threw: " + t;
			}
			if (debugMode) {
				appendDebugLore(stack, "[debug] " + outcome);
			}
		}
		return stack;
	}

	// Only reached when debugMode is on - reveals why a head has no custom skin instead of failing silently.
	private static void appendDebugLore(ItemStack stack, String line) {
		ItemLore existing = stack.get(DataComponents.LORE);
		ItemLore updated = (existing != null ? existing : ItemLore.EMPTY).withLineAdded(Component.literal(line));
		stack.set(DataComponents.LORE, updated);
	}

	/** Rebuilds the head's own texture from its SkullOwner tag - without it every SkyBlock head draws as Steve. */
	private static ResolvableProfile skullProfile(JsonObject skullOwner) {
		if (skullOwner == null) {
			return null;
		}
		JsonObject properties = obj(skullOwner, "Properties");
		if (properties == null || !properties.has("textures") || !properties.get("textures").isJsonArray()) {
			return null;
		}
		JsonArray textures = properties.getAsJsonArray("textures");
		if (textures.isEmpty() || !textures.get(0).isJsonObject()) {
			return null;
		}
		JsonObject texture = textures.get(0).getAsJsonObject();
		String value = string(texture, "Value");
		if (value == null) {
			return null;
		}
		UUID id = parseUuid(string(skullOwner, "Id"));
		// PropertyMap's own constructor always copies its argument into an ImmutableMultimap, so the
		// map has to be fully populated BEFORE construction - putting into the PropertyMap afterward throws.
		Multimap<String, Property> multimap = ArrayListMultimap.create();
		multimap.put("textures", new Property("textures", value, string(texture, "Signature")));
		PropertyMap profileProperties = new PropertyMap(multimap);
		return ResolvableProfile.createResolved(
				new GameProfile(id != null ? id : UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)), "", profileProperties));
	}

	private static UUID parseUuid(String raw) {
		if (raw == null) {
			return null;
		}
		try {
			return raw.contains("-") ? UUID.fromString(raw)
					: UUID.fromString(raw.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Icon for something known only by its SkyBlock id - sacks and minions carry no NBT to rebuild from. */
	public static ItemStack byId(String skyblockId, String displayName) {
		String key = "id/" + skyblockId;
		ItemStack cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		ItemStack stack = new ItemStack(itemForId(skyblockId));
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(displayName != null ? displayName : String.valueOf(skyblockId)));
		CACHE.put(key, stack);
		return stack;
	}

	private static Item itemForId(String rawId) {
		if (rawId == null || rawId.isBlank()) {
			return Items.PAPER;
		}
		String id = rawId.replace(':', '_');
		for (String candidate : new String[]{id, id + "_LOG", id + "_WOOD", id.replace("_ITEM", "")}) {
			Item found = vanillaById(candidate);
			if (found != null) {
				return found;
			}
		}
		// Sack and minion ids are often a plain material with a tier prefix on the front.
		for (String prefix : new String[]{"ENCHANTED_HUGE_", "ENCHANTED_", "SUPER_ENCHANTED_", "REFINED_"}) {
			if (id.startsWith(prefix)) {
				return itemForId(id.substring(prefix.length()));
			}
		}
		if (ID_ALIAS.containsKey(id)) {
			return ID_ALIAS.get(id);
		}
		// A mob-based minion (ZOMBIE, WOLF, ...) has no matching item at all - its own spawn egg reads far better than a blank slot.
		Item egg = vanillaById(id + "_SPAWN_EGG");
		return egg != null ? egg : Items.PAPER;
	}

	// Minion/sack ids with no matching vanilla item - a mob's own spawn egg, or the closest-looking material.
	private static final Map<String, Item> ID_ALIAS = Map.ofEntries(
			Map.entry("CAVESPIDER", Items.CAVE_SPIDER_SPAWN_EGG),
			Map.entry("MUSHROOM_COW", Items.MOOSHROOM_SPAWN_EGG),
			Map.entry("VOIDLING", Items.ENDERMITE_SPAWN_EGG),
			Map.entry("TARANTULA", Items.SPIDER_SPAWN_EGG),
			Map.entry("REVENANT", Items.ZOMBIE_SPAWN_EGG),
			Map.entry("VAMPIRE", Items.ZOMBIE_VILLAGER_SPAWN_EGG),
			Map.entry("SVEN", Items.WOLF_SPAWN_EGG),
			Map.entry("BROODMOTHER", Items.SPIDER_SPAWN_EGG),
			Map.entry("COCOA", Items.COCOA_BEANS),
			Map.entry("ENDER_STONE", Items.END_STONE),
			Map.entry("FISHING", Items.FISHING_ROD),
			Map.entry("GOLD", Items.GOLD_INGOT),
			Map.entry("IRON", Items.IRON_INGOT),
			Map.entry("HARD_STONE", Items.STONE),
			Map.entry("LAPIS", Items.LAPIS_LAZULI),
			Map.entry("MITHRIL", Items.PRISMARINE_CRYSTALS),
			Map.entry("MUSHROOM", Items.RED_MUSHROOM),
			Map.entry("NETHER_WARTS", Items.NETHER_WART),
			Map.entry("FLOWER", Items.POPPY),
			Map.entry("DOUBLE_PLANT", Items.SUNFLOWER),
			Map.entry("INFERNO", Items.MAGMA_BLOCK),
			Map.entry("RED_SAND", Items.RED_SANDSTONE)
	);

	private static Item vanillaById(String id) {
		Identifier location = Identifier.tryParse("minecraft:" + id.toLowerCase(Locale.ROOT));
		if (location == null) {
			return null;
		}
		return BuiltInRegistries.ITEM.getOptional(location).filter(i -> i != Items.AIR).orElse(null);
	}

	private static Item vanillaOrPlaceholder(String skyblockId) {
		return skyblockId == null ? Items.PAPER : itemForId(skyblockId);
	}

	private static JsonObject obj(JsonObject parent, String key) {
		if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
			return null;
		}
		return parent.getAsJsonObject(key);
	}

	private static String string(JsonObject parent, String key) {
		if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) {
			return null;
		}
		return parent.get(key).getAsString();
	}

	/** The in-game rarity colour, as an ARGB value for text and slot tinting. */
	public static int tierColor(String tier) {
		if (tier == null) {
			return 0xFFAAAAAA;
		}
		return switch (tier.toUpperCase(Locale.ROOT)) {
			case "COMMON" -> 0xFFFFFFFF;
			case "UNCOMMON" -> 0xFF55FF55;
			case "RARE" -> 0xFF5555FF;
			case "EPIC" -> 0xFFAA00AA;
			case "LEGENDARY" -> 0xFFFFAA00;
			case "MYTHIC" -> 0xFFFF55FF;
			case "DIVINE" -> 0xFF55FFFF;
			case "SPECIAL", "VERY_SPECIAL" -> 0xFFFF5555;
			default -> 0xFFAAAAAA;
		};
	}
}
