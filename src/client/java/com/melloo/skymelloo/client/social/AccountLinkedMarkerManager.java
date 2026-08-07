package com.melloo.skymelloo.client.social;

import net.minecraft.client.Minecraft;
import net.minecraft.data.AtlasIds;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.ObjectContents;
import net.minecraft.network.chat.contents.objects.AtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;

/**
 * Appends a small pink dye marker AFTER a nearby SkyMelloo user's name if they report a linked
 * sky.melloo.me account (see ModPresenceManager#isAccountLinked / PermissionsManager#has's
 * cosmetics gate) - purely cosmetic, visible to other SkyMelloo users so it's obvious at a glance
 * who has cosmetics unlocked.
 * <p>
 * A real Pink Dye item icon, not a colored unicode glyph stand-in - this game version added inline
 * sprite support to text components ({@code ObjectContents}/{@code AtlasSprite}, confirmed via
 * javap against this exact game version, not guessed/remembered - the classes and
 * {@code AtlasIds.ITEMS} constant genuinely exist here). The unicode-glyph fallback (still used if
 * the sprite somehow can't resolve) is passed as {@code ObjectContents}'s own fallback component.
 * No forced color tint on either - the dye sprite already has its own natural texture color, and
 * multiplying a text color over it just made it look off, so both pieces use an explicit but
 * colorless style ({@link Style#EMPTY}, not simply left unstyled) purely to still block an earlier
 * rank/name color code from bleeding onto it.
 */
public final class AccountLinkedMarkerManager {
	private static final String FALLBACK_GLYPH = " ❖"; // used only if the sprite itself can't resolve
	private static final Identifier PINK_DYE_SPRITE = Identifier.withDefaultNamespace("item/pink_dye");

	private AccountLinkedMarkerManager() {
	}

	public static Component apply(Player player, Component original) {
		// Real bugfix - ModPresenceManager's
		// isAccountLinked(uuid) only ever tracks OTHER nearby players reporting themselves in via the
		// presence system (see its own doc comment); it never contains the LOCAL player's own uuid,
		// since you never "query" yourself as a nearby other player. That silently meant your OWN
		// nametag (visible to yourself in third person) could never show the marker even with a
		// linked account, regardless of whether it showed correctly to OTHER SkyMelloo users looking
		// at you (which it already did, via their own presence query). PermissionsManager's own direct
		// isAccountLinked() reflects your own account status without going through presence at all.
		boolean linked = player == Minecraft.getInstance().player
				? PermissionsManager.isAccountLinked()
				: ModPresenceManager.isAccountLinked(player.getUUID());
		if (!linked) {
			return original;
		}
		MutableComponent fallback = Component.literal(FALLBACK_GLYPH).setStyle(Style.EMPTY);
		MutableComponent icon = MutableComponent.create(new ObjectContents(new AtlasSprite(AtlasIds.ITEMS, PINK_DYE_SPRITE), Optional.of(fallback)));
		// Explicit (colorless) style on the icon component ITSELF, not just its unused fallback child -
		// real report of the dye rendering the wrong color: a rank/name colour code earlier in the
		// component (e.g. a Hypixel rank prefix) was visibly bleeding into the dye's render. The
		// connecting space also gets its own explicit style for the same reason - whichever exact
		// rendering path (nametag vs chat) was letting that inherit through, an explicit style on every
		// piece here breaks the inheritance regardless of which one it actually was.
		icon.setStyle(Style.EMPTY);
		MutableComponent spacer = Component.literal(" ").setStyle(Style.EMPTY);
		return original.copy().append(spacer).append(icon);
	}
}
