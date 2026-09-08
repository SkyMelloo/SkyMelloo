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

// A fake, entirely client-side "SkyMelloo Menu" item in hotbar slot 8 (index 7, not the last
// slot, which Hypixel's own SkyBlock Menu item owns). Re-applied each tick if that slot is empty.
public final class SkyMellooMenuItemManager {
	public static final int MENU_SLOT = 7; // "slot 8" (1-indexed)

	private static boolean initialized = false;

	private SkyMellooMenuItemManager() {
	}

	// Resolved lazily so it's read after Minecraft's language system loads, not at class-load time.
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

	public static boolean isMarkerStack(ItemStack stack) {
		return isMarker(stack);
	}

	// Shared entry point for every trigger path (right-click, left-click, inventory click).
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

	public static void tick(Minecraft client) {
		if (client.player == null) {
			return;
		}
		Inventory inventory = client.player.getInventory();
		if (!SkyMellooConfig.HANDLER.instance().skyMellooMenuItemEnabled || !SkyblockDetector.isInSkyblock()) {
			// Actively clear a leftover marker instead of leaving it until the next inventory sync.
			if (isMarker(inventory.getItem(MENU_SLOT))) {
				inventory.setItem(MENU_SLOT, ItemStack.EMPTY);
			}
			return;
		}
		ItemStack current = inventory.getItem(MENU_SLOT);
		// Never overwrites/hides an actual item the player is carrying there.
		if (current.isEmpty() || isMarker(current)) {
			inventory.setItem(MENU_SLOT, createMarkerStack());
		}
	}
}
