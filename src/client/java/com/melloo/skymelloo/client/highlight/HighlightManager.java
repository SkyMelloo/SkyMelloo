package com.melloo.skymelloo.client.highlight;

import com.melloo.skymelloo.client.block.BlockHighlightRenderer;
import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.cosmetics.MagicMissileManager;
import com.melloo.skymelloo.client.fishing.FishingHelper;
import com.melloo.skymelloo.client.fishing.FishingMinigameManager;
import com.melloo.skymelloo.client.party.PartyTracker;
import com.melloo.skymelloo.client.social.FriendsManager;
import com.melloo.skymelloo.client.social.ModPresenceManager;
import com.melloo.skymelloo.client.social.WhitelistManager;
import com.melloo.skymelloo.client.util.SkyblockDetector;
import com.melloo.skymelloo.client.util.VisibilityUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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

	/**
	 * Whether the entity's outline should be forced to glow right now. Player/mob highlighting still
	 * glows through walls (that's the point - knowing where your party is). Chests and items are
	 * different: they only glow when there's an actual clear line of sight, so they read as a normal
	 * outline effect rather than seeing through walls.
	 */
	public static boolean shouldGlow(Entity entity) {
		if (!WhitelistManager.isAllowed()) {
			return false;
		}
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();

		if (MagicMissileManager.isTemporarilyInvisible(entity)) {
			// A player briefly hidden by a magic-missile hit should actually disappear from this
			// user's view, not turn into a glowing silhouette (which is what invisible+glowing
			// normally renders as) - suppress all Highlighting treatment for the duration.
			return false;
		}

		if (BlockHighlightRenderer.isChestMarker(entity)) {
			// Only actually visible chests get the outline - a genuine clear line of sight from the
			// player's eyes, checked fresh every frame so it tracks camera movement/corners in real
			// time, not seeing through walls.
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
			// Must be checked before the isDeadOrDying() gate below - the kill flash is meant to
			// show exactly while/right after the victim is dying, which isDeadOrDying() would
			// otherwise immediately short-circuit to "don't glow" before we ever get here.
			return true;
		}

		if (!(entity instanceof LivingEntity living) || living.isDeadOrDying()) {
			return false;
		}

		if (living instanceof Player player) {
			// Player highlighting (party members and staff) is a SkyBlock-specific feature - it
			// shouldn't apply on other Hypixel game modes or the lobby.
			if (!com.melloo.skymelloo.client.util.SkyblockDetector.isInSkyblock()) {
				return false;
			}
			// playerHighlightEnabled is a separate config toggle from the dungeon mob highlight's
			// toggle below, so switching one off doesn't affect the other.
			if (!config.playerHighlightEnabled) {
				return false;
			}
			if (player.isInvisible() && !config.showInvisiblePlayers) {
				// Off by default - forcing the glow-outline on a REAL vanilla-invisible player
				// defeats their invisibility entirely (glowing+invisible renders as a visible
				// colored silhouette through walls, see EntityGlowMixin) - a much more
				// consequential capability than just coloring an already-visible player.
				return false;
			}
			// Only party members and SkyMelloo staff get highlighted at all - everyone else
			// (regular other players, NPCs included - NPCs are never party/staff) gets nothing, no
			// special-casing needed for them anymore.
			if (classifyPlayer(player) == PlayerCategory.NONE) {
				return false;
			}
			// Forcing the glow-outline pass on players can hide cosmetic layers from mods
			// like Lunar Client (capes/wings) for some players, so it's opt-in; the colored
			// nametag (see colorizeName) already gives a see-through indicator on its own.
			return config.playerGlowOutline;
		}

		// Only the dungeon current-room mob highlight remains - isInCurrentDungeonRoom already
		// requires an active run, so this is inherently dungeon-only. The old general "highlight
		// every hostile mob everywhere" system (name filters, friendly mobs, default/named colors)
		// is gone entirely, not just defaulted off.
		return config.dungeonRoomMobHighlightEnabled
				&& living instanceof Enemy && isInCurrentDungeonRoom(living);
	}

	/** Whether this entity gets ANY Highlighting treatment right now (glow, colored name, or item name) - used to gate the distance display. */
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
			SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
			if (player.isInvisible() && !config.showInvisiblePlayers) {
				return false;
			}
			if (!config.playerHighlightEnabled) {
				return false;
			}
			return classifyPlayer(player) != PlayerCategory.NONE;
		}
		return shouldGlow(entity);
	}

	/**
	 * Hypixel NPCs (shopkeepers, dungeon NPCs, etc.) are implemented as real Player entities with a
	 * fake GameProfile. Used to check tab-list absence for this, but that's not reliable at range -
	 * Hypixel apparently does put some NPCs in the tab list under some circumstances, which let them
	 * slip through (e.g. get hit by Magic Missile) once far enough away. A real Mojang account UUID
	 * is always version 4 (random); Hypixel's fake NPC UUIDs aren't - same signal a proven, actively
	 * maintained SkyBlock mod (SkyHanni) uses for exactly this, and it's a static property of the
	 * UUID itself, not dependent on tab-list/network timing or distance at all.
	 */
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
			return switch (classifyPlayer(player)) {
				case PARTY -> lowHpBlinkColor(player, config, toRgb(config.partyHighlightColor));
				case STAFF -> toRgb(config.adminHighlightColor);
				case FRIEND -> toRgb(config.friendHighlightColor);
				// Unreachable in practice - shouldGlow/isHighlightTarget already refuse NONE before a
				// color is ever needed for it.
				case NONE -> toRgb(config.partyHighlightColor);
			};
		}
		// Only the dungeon current-room mob highlight remains - see shouldGlow's own comment.
		return toRgb(config.dungeonRoomMobHighlightColor);
	}

	private static final int LOW_HP_BLINK_COLOR = 0xFFFF0000;
	private static final int LOW_HP_BLINK_INTERVAL_MS = 400;
	private static final double LOW_HP_BLINK_THRESHOLD = 0.25;

	/**
	 * A party member's Highlighting normally stays their fixed party color, but blinks bright red once their
	 * HP drops under 25% - an urgent "someone needs help" signal readable at a glance during a fight,
	 * using the same HP data already read for the Party HUD's own display. Blinks (alternates every
	 * ~400ms) rather than just going solid red, so it's noticeably distinct from a static color choice.
	 */
	private static int lowHpBlinkColor(Player player, SkyMellooConfig config, int normalColor) {
		if (!config.lowHpBlinkEnabled || player.getMaxHealth() <= 0) {
			return normalColor;
		}
		if (player.getHealth() / player.getMaxHealth() >= LOW_HP_BLINK_THRESHOLD) {
			return normalColor;
		}
		boolean blinkOn = (System.currentTimeMillis() / LOW_HP_BLINK_INTERVAL_MS) % 2 == 0;
		return blinkOn ? LOW_HP_BLINK_COLOR : normalColor;
	}

	/**
	 * Whether {@code living} is inside the LOCAL player's current dungeon room right now - used to
	 * highlight the mobs still standing between you and the next door, distinct from mobs elsewhere
	 * on the floor. Only meaningful during an active run; {@link DungeonRoomTracker#getCurrentRoomBounds}
	 * documents why the vertical bound is only approximate.
	 */
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

	/**
	 * Appends a small colored Highlighting-category marker after a player's nametag, instead of the old
	 * behavior of overwriting the whole name's style - Hypixel bakes rank color (MVP+/VIP/etc.)
	 * into the name via the scoreboard team style, and flattening the whole component to one
	 * color wiped that out. This way the real rank color stays intact and only a marker is added.
	 */
	public static Component colorizeName(Player player, Component original) {
		if (!WhitelistManager.isAllowed()) {
			return original;
		}
		if (MagicMissileManager.isTemporarilyInvisible(player)) {
			return original;
		}
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		boolean flashing = isKillFlashing(player.getUUID());
		if (!flashing && !config.playerHighlightEnabled) {
			return original;
		}
		if (!flashing && classifyPlayer(player) == PlayerCategory.NONE) {
			return original;
		}
		TextColor color = TextColor.fromRgb(getGlowColor(player) & 0xFFFFFF);
		MutableComponent copy = original.copy();
		copy.append(Component.literal(" ●").withStyle(Style.EMPTY.withColor(color)));
		return copy;
	}

	// Covers party, SkyMelloo staff (owner/admin/developer), and SkyMelloo Friends (a confirmed
	// mutual friend via the mod's OWN friends system, see FriendsManager - NOT a Hypixel-/friend-
	// list-based highlight). Self/regular-other-player/NPC/plain-mod-user get no highlight at all.
	private enum PlayerCategory {
		PARTY, STAFF, FRIEND, NONE
	}

	private static PlayerCategory classifyPlayer(Player player) {
		// Staff checked first - developer/admin/owner get a pink highlight everywhere, taking
		// priority even for a staff member who also happens to be in your party.
		// Role comes from the presence server, resolved there from the player's real linked
		// account/team-role record (see server.js's roleForUuid) - never something the other client
		// could self-report to fake for themselves.
		if (ModPresenceManager.isModUser(player.getUUID()) && ModPresenceManager.isAdminOrOwner(player.getUUID())) {
			return PlayerCategory.STAFF;
		}
		if (PartyTracker.isMember(player.getUUID())) {
			return PlayerCategory.PARTY;
		}
		if (FriendsManager.isFriend(player.getName().getString())) {
			return PlayerCategory.FRIEND;
		}
		return PlayerCategory.NONE;
	}

	private static int toRgb(Color color) {
		return color.getRGB() | 0xFF000000;
	}
}
