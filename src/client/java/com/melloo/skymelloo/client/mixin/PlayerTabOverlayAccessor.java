package com.melloo.skymelloo.client.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Comparator;

/**
 * Exposes vanilla's own private tab-list sort order, the same one used to render the Tab-key
 * overlay - needed to read Hypixel's dungeon tab list at stable line indices (see
 * {@link com.melloo.skymelloo.client.social.DungeonTabList}), ported from SkyblockerMod/Skyblocker's
 * identical PlayerTabOverlayAccessor mixin (GitHub, LGPL-3.0). Field name confirmed via javap against
 * this exact game version.
 */
@Mixin(PlayerTabOverlay.class)
public interface PlayerTabOverlayAccessor {
	@Accessor("PLAYER_COMPARATOR")
	static Comparator<PlayerInfo> skymelloo$getPlayerComparator() {
		throw new AssertionError("Mixin not applied");
	}
}
