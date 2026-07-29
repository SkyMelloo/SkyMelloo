package com.melloo.skymelloo.client.block;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.mixin.BlockDisplayInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * Highlights chests through walls by spawning invisible, client-side-only (never sent to the
 * server) marker entities on top of them and forcing them to glow - reusing the same vanilla
 * "invisible + glowing = colored silhouette through walls" mechanic already used for mob/player/item
 * ESP. This is more shader-pack compatible than a custom terrain-render-pipeline hook (which Iris
 * and other shader mods commonly intercept/break), since it's the same core vanilla system shaders
 * already have to support for the vanilla Glowing effect.
 * <p>
 * Markers are {@link Display.BlockDisplay} entities carrying the actual block's model/shape
 * (instead of a generic humanoid ArmorStand silhouette), so the glow outline matches the block.
 * Every matching block gets its own marker (not merged into one per vein) - adjacent glowing
 * outlines already read visually as one connected vein.
 * <p>
 * Ore ESP was removed entirely (2026-07-27, "die sacchen ore esp ganz weg machen ... auch in der
 * mod") - this class used to also track ore blocks the same way; only the chest side remains now.
 */
public final class BlockEspRenderer {
	private static int nextMarkerId = Integer.MAX_VALUE - 100_000;

	private static final Map<BlockPos, Display.BlockDisplay> chestMarkers = new HashMap<>();
	private static int tickCounter = 0;

	private BlockEspRenderer() {
	}

	public static void init() {
		// No render event needed - marker entities are drawn by the normal (glow-aware) entity renderer.
	}

	public static void tick(Minecraft client) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.chestEspEnabled) {
			clearAll(client);
			return;
		}
		if (tickCounter++ % 20 != 0) {
			return;
		}
		scanAndUpdateMarkers(client, config);
	}

	public static boolean isChestMarker(Entity entity) {
		return chestMarkers.containsValue(entity);
	}

	private static void scanAndUpdateMarkers(Minecraft client, SkyMellooConfig config) {
		if (client.player == null || client.level == null) {
			return;
		}

		Map<BlockPos, BlockState> wantedChests = new HashMap<>();

		BlockPos center = client.player.blockPosition();
		int r = config.blockEspRange;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
			BlockState state = client.level.getBlockState(pos);
			if (state.isAir()) {
				continue;
			}
			Block block = state.getBlock();
			if (isChest(block)) {
				wantedChests.put(pos.immutable(), state);
			}
		}

		updateMarkerSet(client, chestMarkers, wantedChests);
	}

	private static void updateMarkerSet(Minecraft client, Map<BlockPos, Display.BlockDisplay> markers, Map<BlockPos, BlockState> wanted) {
		markers.entrySet().removeIf(entry -> {
			if (!wanted.containsKey(entry.getKey())) {
				removeMarker(client, entry.getValue());
				return true;
			}
			return false;
		});

		for (Map.Entry<BlockPos, BlockState> entry : wanted.entrySet()) {
			Display.BlockDisplay marker = markers.computeIfAbsent(entry.getKey(), pos -> spawnMarker(client, pos));
			((BlockDisplayInvoker) marker).skymelloo$setBlockState(entry.getValue());
		}
	}

	private static Display.BlockDisplay spawnMarker(Minecraft client, BlockPos pos) {
		Display.BlockDisplay display = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, client.level);
		display.setPos(pos.getX(), pos.getY(), pos.getZ());
		display.setInvisible(true);
		display.setNoGravity(true);
		display.setSilent(true);
		display.setId(nextMarkerId--);
		client.level.addEntity(display);
		return display;
	}

	private static void removeMarker(Minecraft client, Display.BlockDisplay marker) {
		client.level.removeEntity(marker.getId(), Entity.RemovalReason.DISCARDED);
	}

	private static void clearAll(Minecraft client) {
		if (chestMarkers.isEmpty()) {
			return;
		}
		chestMarkers.values().forEach(marker -> removeMarker(client, marker));
		chestMarkers.clear();
	}

	private static boolean isChest(Block block) {
		return block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.ENDER_CHEST;
	}
}
