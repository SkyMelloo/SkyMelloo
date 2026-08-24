package com.melloo.skymelloo.client.gui;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a SkyBlock item id into a vanilla {@link ItemStack} to draw as its icon. Resolution order:
 * an explicit override, then the id read as a vanilla item id, then the legacy numeric id Hypixel
 * still sends, then a rarity-coloured placeholder.
 */
public final class SkyblockItemIcons {
	private static final Map<String, ItemStack> CACHE = new HashMap<>();

	// SkyBlock items whose id doesn't name a vanilla item but which have an obvious vanilla stand-in.
	private static final Map<String, Item> OVERRIDES = Map.ofEntries(
			Map.entry("HYPERION", Items.NETHERITE_SWORD),
			Map.entry("VALKYRIE", Items.NETHERITE_SWORD),
			Map.entry("SCYLLA", Items.NETHERITE_SWORD),
			Map.entry("ASTRAEA", Items.NETHERITE_SWORD),
			Map.entry("TERMINATOR", Items.BOW),
			Map.entry("JUJU_SHORTBOW", Items.BOW),
			Map.entry("ENCHANTED_BOOK", Items.ENCHANTED_BOOK),
			Map.entry("RECOMBOBULATOR_3000", Items.PINK_DYE),
			Map.entry("SKYBLOCK_MENU", Items.NETHER_STAR),
			Map.entry("ASPECT_OF_THE_END", Items.DIAMOND_SWORD),
			Map.entry("ASPECT_OF_THE_DRAGON", Items.GOLDEN_SWORD),
			Map.entry("ROGUE_SWORD", Items.GOLDEN_SWORD),
			Map.entry("GRAPPLING_HOOK", Items.FISHING_ROD),
			Map.entry("TREECAPITATOR_AXE", Items.GOLDEN_AXE),
			Map.entry("DIAMOND_PICKAXE", Items.DIAMOND_PICKAXE),
			Map.entry("STONK_PICKAXE", Items.GOLDEN_PICKAXE),
			Map.entry("BONE_BOOMERANG", Items.BOW),
			Map.entry("SPIRIT_SCEPTRE", Items.BLAZE_ROD),
			Map.entry("MIDAS_STAFF", Items.BLAZE_ROD),
			Map.entry("MIDAS_SWORD", Items.GOLDEN_SWORD),
			Map.entry("GIANTS_SWORD", Items.DIAMOND_SWORD),
			Map.entry("LIVID_DAGGER", Items.IRON_SWORD),
			Map.entry("SHADOW_FURY", Items.IRON_SWORD),
			Map.entry("FLOWER_OF_TRUTH", Items.POPPY),
			Map.entry("PET", Items.BONE)
	);

	// The 1.8 numeric ids Hypixel's inventory NBT still uses, for ids that aren't vanilla names.
	private static final Map<Integer, Item> LEGACY = buildLegacy();

	private SkyblockItemIcons() {
	}

	private static Map<Integer, Item> buildLegacy() {
		Map<Integer, Item> m = new HashMap<>();
		m.put(1, Items.STONE);
		m.put(2, Items.GRASS_BLOCK);
		m.put(3, Items.DIRT);
		m.put(4, Items.COBBLESTONE);
		m.put(5, Items.OAK_PLANKS);
		m.put(12, Items.SAND);
		m.put(14, Items.GOLD_ORE);
		m.put(15, Items.IRON_ORE);
		m.put(16, Items.COAL_ORE);
		m.put(17, Items.OAK_LOG);
		m.put(18, Items.OAK_LEAVES);
		m.put(20, Items.GLASS);
		m.put(21, Items.LAPIS_ORE);
		m.put(24, Items.SANDSTONE);
		m.put(35, Items.WHITE_WOOL);
		m.put(41, Items.GOLD_BLOCK);
		m.put(42, Items.IRON_BLOCK);
		m.put(45, Items.BRICKS);
		m.put(46, Items.TNT);
		m.put(47, Items.BOOKSHELF);
		m.put(49, Items.OBSIDIAN);
		m.put(54, Items.CHEST);
		m.put(56, Items.DIAMOND_ORE);
		m.put(57, Items.DIAMOND_BLOCK);
		m.put(58, Items.CRAFTING_TABLE);
		m.put(73, Items.REDSTONE_ORE);
		m.put(79, Items.ICE);
		m.put(80, Items.SNOW_BLOCK);
		m.put(81, Items.CACTUS);
		m.put(82, Items.CLAY);
		m.put(86, Items.CARVED_PUMPKIN);
		m.put(87, Items.NETHERRACK);
		m.put(89, Items.GLOWSTONE);
		m.put(97, Items.INFESTED_STONE);
		m.put(103, Items.MELON);
		m.put(110, Items.MYCELIUM);
		m.put(112, Items.NETHER_BRICKS);
		m.put(121, Items.END_STONE);
		m.put(129, Items.EMERALD_ORE);
		m.put(133, Items.EMERALD_BLOCK);
		m.put(152, Items.REDSTONE_BLOCK);
		m.put(153, Items.NETHER_QUARTZ_ORE);
		m.put(155, Items.QUARTZ_BLOCK);
		m.put(159, Items.WHITE_TERRACOTTA);
		m.put(162, Items.ACACIA_LOG);
		m.put(165, Items.SLIME_BLOCK);
		m.put(172, Items.TERRACOTTA);
		m.put(173, Items.COAL_BLOCK);
		m.put(174, Items.PACKED_ICE);
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
		m.put(325, Items.BUCKET);
		m.put(328, Items.MINECART);
		m.put(331, Items.REDSTONE);
		m.put(332, Items.SNOWBALL);
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
		m.put(351, Items.INK_SAC);
		m.put(352, Items.BONE);
		m.put(353, Items.SUGAR);
		m.put(354, Items.CAKE);
		m.put(357, Items.COOKIE);
		m.put(360, Items.MELON_SLICE);
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
		m.put(374, Items.GLASS_BOTTLE);
		m.put(375, Items.SPIDER_EYE);
		m.put(376, Items.FERMENTED_SPIDER_EYE);
		m.put(377, Items.BLAZE_POWDER);
		m.put(378, Items.MAGMA_CREAM);
		m.put(381, Items.ENDER_EYE);
		m.put(382, Items.GLISTERING_MELON_SLICE);
		m.put(384, Items.EXPERIENCE_BOTTLE);
		m.put(388, Items.EMERALD);
		m.put(392, Items.POTATO);
		m.put(391, Items.CARROT);
		m.put(393, Items.BAKED_POTATO);
		m.put(396, Items.GOLDEN_CARROT);
		m.put(397, Items.PLAYER_HEAD);
		m.put(399, Items.NETHER_STAR);
		m.put(400, Items.PUMPKIN_PIE);
		m.put(403, Items.ENCHANTED_BOOK);
		m.put(405, Items.NETHER_BRICK);
		m.put(406, Items.QUARTZ);
		m.put(409, Items.PRISMARINE_SHARD);
		m.put(410, Items.PRISMARINE_CRYSTALS);
		m.put(411, Items.RABBIT);
		m.put(414, Items.RABBIT_FOOT);
		m.put(415, Items.RABBIT_HIDE);
		m.put(423, Items.MUTTON);
		m.put(424, Items.COOKED_MUTTON);
		m.put(434, Items.BEETROOT);
		return m;
	}

	/** Cached per id - a full inventory tab re-resolves the same handful of ids on every frame otherwise. */
	public static ItemStack resolve(String skyblockId, int legacyId, String tier, String displayName) {
		String key = (skyblockId != null ? skyblockId : "?") + "/" + legacyId;
		ItemStack cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		ItemStack stack = new ItemStack(resolveItem(skyblockId, legacyId, tier));
		if (displayName != null && !displayName.isBlank()) {
			stack.set(DataComponents.CUSTOM_NAME, Component.literal(displayName));
		}
		CACHE.put(key, stack);
		return stack;
	}

	private static Item resolveItem(String skyblockId, int legacyId, String tier) {
		if (skyblockId != null) {
			Item override = OVERRIDES.get(skyblockId);
			if (override != null) {
				return override;
			}
			Item vanilla = vanillaById(skyblockId);
			if (vanilla != null) {
				return vanilla;
			}
			// Most SkyBlock ids are a vanilla item with a prefix, e.g. ENCHANTED_DIAMOND -> DIAMOND.
			for (String prefix : new String[]{"ENCHANTED_", "SUPER_ENCHANTED_", "REFINED_"}) {
				if (skyblockId.startsWith(prefix)) {
					Item base = vanillaById(skyblockId.substring(prefix.length()));
					if (base != null) {
						return base;
					}
				}
			}
		}
		Item legacy = LEGACY.get(legacyId);
		if (legacy != null) {
			return legacy;
		}
		return placeholderFor(tier);
	}

	private static Item vanillaById(String id) {
		Identifier location = Identifier.tryParse("minecraft:" + id.toLowerCase(Locale.ROOT));
		if (location == null) {
			return null;
		}
		return BuiltInRegistries.ITEM.getOptional(location).filter(item -> item != Items.AIR).orElse(null);
	}

	/** No usable id at all - a dye in roughly the rarity's own colour still reads better than a blank slot. */
	private static Item placeholderFor(String tier) {
		if (tier == null) {
			return Items.GRAY_DYE;
		}
		return switch (tier.toUpperCase(Locale.ROOT)) {
			case "COMMON" -> Items.WHITE_DYE;
			case "UNCOMMON" -> Items.LIME_DYE;
			case "RARE" -> Items.BLUE_DYE;
			case "EPIC" -> Items.PURPLE_DYE;
			case "LEGENDARY" -> Items.ORANGE_DYE;
			case "MYTHIC" -> Items.PINK_DYE;
			case "DIVINE" -> Items.LIGHT_BLUE_DYE;
			case "SPECIAL", "VERY_SPECIAL" -> Items.RED_DYE;
			default -> Items.GRAY_DYE;
		};
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
