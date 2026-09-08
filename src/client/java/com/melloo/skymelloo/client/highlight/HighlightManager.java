package com.melloo.skymelloo.client.highlight;

import com.melloo.skymelloo.client.block.BlockHighlightRenderer;
import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.cosmetics.MagicMissileManager;
import com.melloo.skymelloo.client.fishing.FishingHelper;
import com.melloo.skymelloo.client.fishing.FishingMinigameManager;
import com.melloo.skymelloo.client.social.WhitelistManager;
import com.melloo.skymelloo.client.util.VisibilityUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Decides which entities get the forced-glow highlight treatment and what color they get.
 * Hooked from {@link com.melloo.skymelloo.client.mixin.EntityGlowMixin}.
 */
public final class HighlightManager {
	private static final int KILL_FLASH_COLOR = 0xFFFFA500;
	private static final long KILL_FLASH_DURATION_MS = 3000;
	private static final Map<UUID, Long> killFlashExpiry = new HashMap<>();

	private HighlightManager() {
	}

	/** Briefly (3s) forces a player's highlight color to orange - called right after you kill them. */
	public static void flashKillHighlight(UUID victimUuid) {
		killFlashExpiry.put(victimUuid, System.currentTimeMillis() + KILL_FLASH_DURATION_MS);
	}

	private static boolean isKillFlashing(UUID uuid) {
		Long expiry = killFlashExpiry.get(uuid);
		return expiry != null && expiry > System.currentTimeMillis();
	}

	/** Player/mob highlighting glows through walls; chests/items only glow with a clear line of sight. */
	public static boolean shouldGlow(Entity entity) {
		if (!WhitelistManager.isAllowed()) {
			return false;
		}
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();

		if (MagicMissileManager.isTemporarilyInvisible(entity)) {
			// Suppress highlighting entirely - otherwise invisible+glowing renders as a visible silhouette.
			return false;
		}

		if (BlockHighlightRenderer.isChestMarker(entity)) {
			return VisibilityUtil.hasLineOfSight(entity.position().add(0.5, 0.5, 0.5));
		}

		if (entity instanceof FishingHook && FishingHelper.isTracked(entity)) {
			return config.fishingHelperEnabled;
		}

		if (FishingMinigameManager.isTarget(entity)) {
			return true;
		}

		if (entity instanceof ItemEntity item) {
			return shouldGlowItem(item, config) && VisibilityUtil.hasLineOfSight(item.position());
		}

		if (entity instanceof Player player && isKillFlashing(player.getUUID())) {
			// Must run before isDeadOrDying() below, which would otherwise suppress the flash.
			return true;
		}

		if (!(entity instanceof LivingEntity living) || living.isDeadOrDying()) {
			return false;
		}

		if (living instanceof Player player) {
			// The only player-highlighting SkyMelloo decides itself - party/staff/friend highlighting is MellooEssentials' job.
			return LobbySearchManager.isSearchedPlayer(player.getUUID());
		}

		return config.dungeonRoomMobHighlightEnabled
				&& isDungeonMobEntity(living) && isInCurrentDungeonRoom(living);
	}

	/** Many dungeon bosses/reskins are a disguised ArmorStand, not a real Enemy; marker stands are pure decoration, excluded. */
	private static boolean isDungeonMobEntity(LivingEntity living) {
		if (living instanceof Enemy) {
			return true;
		}
		if (living instanceof ArmorStand stand) {
			return !stand.isMarker();
		}
		return !(living instanceof Player);
	}

	/** Whether this entity gets ANY highlight treatment right now (glow, colored name, or item name) - used to gate the distance display. */
	public static boolean isHighlightTarget(Entity entity) {
		if (!WhitelistManager.isAllowed()) {
			return false;
		}
		if (MagicMissileManager.isTemporarilyInvisible(entity)) {
			return false;
		}
		if (entity instanceof Player player) {
			if (isKillFlashing(player.getUUID())) {
				return true;
			}
			// /sm search is the only player-highlighting SkyMelloo still decides - see shouldGlow's own comment.
			return LobbySearchManager.isSearchedPlayer(player.getUUID());
		}
		return shouldGlow(entity);
	}

	/** Hypixel NPCs are real Player entities with a fake GameProfile - detected by UUID version (real accounts are always v4). */
	public static boolean isNpc(Player player) {
		if (Minecraft.getInstance().player == player) {
			return false;
		}
		return player.getUUID().version() != 4;
	}

	/** Whether a dropped item's floating name should be forced visible (vanilla hides it by default). */
	public static boolean shouldShowItemName(Entity entity) {
		return entity instanceof ItemEntity item && shouldGlowItem(item, SkyMellooConfig.HANDLER.instance());
	}

	private static boolean shouldGlowItem(ItemEntity item, SkyMellooConfig config) {
		if (!config.itemHighlightEnabled) {
			return false;
		}
		Set<String> filters = config.parsedItemFilters();
		if (filters.isEmpty()) {
			return true;
		}
		String name = item.getItem().getHoverName().getString().toLowerCase();
		for (String filter : filters) {
			if (name.contains(filter)) {
				return true;
			}
		}
		return false;
	}

	public static int getGlowColor(Entity entity) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (BlockHighlightRenderer.isChestMarker(entity)) {
			return toRgb(config.chestHighlightColor);
		}
		if (entity instanceof FishingHook && FishingHelper.isTracked(entity)) {
			return toRgb(FishingHelper.isBiting() ? config.fishingBitingColor : config.fishingWaitingColor);
		}
		if (FishingMinigameManager.isTarget(entity)) {
			return toRgb(config.fishingMinigameColor);
		}
		if (entity instanceof ItemEntity) {
			return toRgb(config.itemHighlightColor);
		}
		if (entity instanceof Player player) {
			if (isKillFlashing(player.getUUID())) {
				return KILL_FLASH_COLOR;
			}
			// /sm search is the only player color SkyMelloo still decides on its own - see shouldGlow's
			// own comment for why party/staff/friend colors moved to MellooEssentials.
			return toRgb(config.lobbySearchColor);
		}
		// Only the dungeon current-room mob highlight remains - see shouldGlow's own comment.
		return toRgb(config.dungeonRoomMobHighlightColor);
	}

	private static final int LOW_HP_BLINK_COLOR = 0xFFFF0000;
	private static final int LOW_HP_BLINK_INTERVAL_MS = 400;
	private static final double LOW_HP_BLINK_THRESHOLD = 0.25;

	/** Blinks a party member's highlight red under 25% HP (MellooEssentials' override callback). Dungeon-only - vanilla HP isn't synced with real SkyBlock HP outside a run. */
	public static Integer partyBlinkOverride(java.util.UUID uuid, int normalColor) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.lowHpBlinkEnabled) {
			return null;
		}
		if (!com.melloo.skymelloo.client.social.DungeonRunTracker.isRunActive()) {
			return null;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			return null;
		}
		Player player = client.level.getPlayerByUUID(uuid);
		if (player == null || player.getMaxHealth() <= 0) {
			return null;
		}
		if (player.getHealth() / player.getMaxHealth() >= LOW_HP_BLINK_THRESHOLD) {
			return null;
		}
		boolean blinkOn = (System.currentTimeMillis() / LOW_HP_BLINK_INTERVAL_MS) % 2 == 0;
		return blinkOn ? LOW_HP_BLINK_COLOR : normalColor;
	}

	/** Whether {@code living} is in the local player's current dungeon room, not just anywhere on the floor. Only meaningful during an active run. */
	private static boolean isInCurrentDungeonRoom(LivingEntity living) {
		if (!com.melloo.skymelloo.client.social.DungeonRunTracker.isRunActive()) {
			return false;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return false;
		}
		net.minecraft.world.phys.AABB bounds = com.melloo.skymelloo.client.social.DungeonRoomTracker.getCurrentRoomBounds(client.player.getY(), 20.0);
		return bounds != null && bounds.intersects(living.getBoundingBox());
	}

	/** Appends a colored marker after the nametag rather than recoloring it, so Hypixel's own rank color stays intact. */
	public static Component colorizeName(Player player, Component original) {
		if (!WhitelistManager.isAllowed()) {
			return original;
		}
		if (MagicMissileManager.isTemporarilyInvisible(player)) {
			return original;
		}
		boolean flashing = isKillFlashing(player.getUUID());
		boolean searched = LobbySearchManager.isSearchedPlayer(player.getUUID());
		if (!flashing && !searched) {
			return original;
		}
		TextColor color = TextColor.fromRgb(getGlowColor(player) & 0xFFFFFF);
		MutableComponent copy = original.copy();
		copy.append(Component.literal(" ●").withStyle(Style.EMPTY.withColor(color)));
		return copy;
	}

	// Staff/party/friend highlighting is entirely MellooEssentials' job now - both mods' glow mixins
	// hook the same vanilla methods, so a second branch here would race them with no defined winner.

	private static int toRgb(Color color) {
		return color.getRGB() | 0xFF000000;
	}
}
