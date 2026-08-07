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
 * Appends a small pink dye marker AFTER a nearby player's name if they're also running SkyMelloo
 * right now (see {@link ModPresenceManager#isModUser}) - purely cosmetic, visible to other SkyMelloo
 * users so it's obvious at a glance who else has the mod. Not gated on a linked sky.melloo.me
 * account or presence-sharing being on beyond what's already required to be detected as a mod user
 * at all in the first place - showing for every detected mod user, not just linked-account ones, is
 * intentional.
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
		// The local player is always "a SkyMelloo user" by definition (this code is only even running
		// because the mod is installed) - ModPresenceManager's own tracking only ever covers OTHER
		// nearby players reporting themselves in via presence (see its own doc comment), never the
		// local player's own uuid, since you never "query" yourself as a nearby other player.
		boolean isModUser = player == Minecraft.getInstance().player
				|| ModPresenceManager.isModUser(player.getUUID());
		if (!isModUser) {
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
