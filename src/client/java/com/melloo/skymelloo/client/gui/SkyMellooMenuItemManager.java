package com.melloo.skymelloo.client.gui;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.util.SkyblockDetector;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/**
 * A fake "SkyMelloo Menu" item, always sitting in hotbar slot 8 (index 7 - the second-to-last slot,
 * NOT the last one, which Hypixel's own real SkyBlock Menu item already owns) - right-click opens
 * {@link SkyMellooMenuScreen}, the same way Hypixel's own menu item works.
 * <p>
 * Entirely client-side: the server has no idea this item exists. Only ever shown when that slot is
 * genuinely EMPTY server-side - re-applied every tick so it survives inventory sync packets, but
 * never overwrites a real item actually sitting there (checked fresh each tick, not just once).
 */
public final class SkyMellooMenuItemManager {
	public static final int MENU_SLOT = 7; // "slot 8" (1-indexed) - deliberately not 8 (Hypixel's own menu slot)

	private static boolean initialized = false;

	private SkyMellooMenuItemManager() {
	}

	// Resolved lazily (not cached in a static final field) so it's read after Minecraft's language
	// system is actually loaded, not at class-load time - also used to identity-check the item below.
	private static String markerName() {
		return Component.translatable("skymelloo.gui.menu.marker.name").getString();
	}

	private static ItemStack createMarkerStack() {
		ItemStack stack = new ItemStack(Items.PINK_DYE);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(markerName()));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				Component.translatable("skymelloo.gui.menu.marker.lore_1"),
				Component.translatable("skymelloo.gui.menu.marker.lore_2"),
				Component.literal(""),
				Component.translatable("skymelloo.gui.menu.marker.lore_3"))));
		return stack;
	}

	private static boolean isMarker(ItemStack stack) {
		if (stack.isEmpty() || stack.getItem() != Items.PINK_DYE) {
			return false;
		}
		Component name = stack.get(DataComponents.CUSTOM_NAME);
		return name != null && markerName().equals(name.getString());
	}

	/** Whether this stack is the SkyMelloo Menu marker item - public so other trigger paths (left-click, inventory-slot click) can recognize it too, not just the right-click handler below. */
	public static boolean isMarkerStack(ItemStack stack) {
		return isMarker(stack);
	}

	/** Opens the menu if it isn't already open and the feature is enabled and actually usable right now - the single shared entry point for every trigger path (right-click, left-click, inventory click). */
	public static void openMenu(Minecraft client) {
		if (!SkyMellooConfig.HANDLER.instance().skyMellooMenuItemEnabled || !SkyblockDetector.isInSkyblock()) {
			return;
		}
		if (client.screen == null || client.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen) {
			client.setScreen(new SkyMellooMenuScreen());
		}
	}

	public static void init() {
		if (initialized) {
			return;
		}
		initialized = true;
		UseItemCallback.EVENT.register((player, world, hand) -> {
			if (!SkyMellooConfig.HANDLER.instance().skyMellooMenuItemEnabled || !SkyblockDetector.isInSkyblock()
					|| hand != InteractionHand.MAIN_HAND || !isMarker(player.getMainHandItem())) {
				return InteractionResult.PASS;
			}
			openMenu(Minecraft.getInstance());
			return InteractionResult.CONSUME;
		});
	}

	/** Only ever placed while actually in Hypixel SkyBlock (see {@link SkyblockDetector}) - the menu's own content (cosmetics, dungeon tools, whitelist status) is all SkyBlock-specific, so the item has no business sitting in the hotbar in the main lobby, another Hypixel gamemode, or a different server entirely. */
	public static void tick(Minecraft client) {
		if (client.player == null) {
			return;
		}
		Inventory inventory = client.player.getInventory();
		if (!SkyMellooConfig.HANDLER.instance().skyMellooMenuItemEnabled || !SkyblockDetector.isInSkyblock()) {
			// The setting turning off (or simply not being in SkyBlock right now) used to just stop
			// RE-applying the marker each tick, silently leaving whatever copy was already sitting in
			// the slot from before - looking like nothing actually happened until the next inventory
			// sync packet happened to overwrite it. Actively clear our own leftover marker back to
			// empty right away instead.
			if (isMarker(inventory.getItem(MENU_SLOT))) {
				inventory.setItem(MENU_SLOT, ItemStack.EMPTY);
			}
			return;
		}
		ItemStack current = inventory.getItem(MENU_SLOT);
		// Only fills in when the real slot is empty (or already our own marker from a previous tick) -
		// never overwrites/hides an actual item the player is carrying there.
		if (current.isEmpty() || isMarker(current)) {
			inventory.setItem(MENU_SLOT, createMarkerStack());
		}
	}
}
