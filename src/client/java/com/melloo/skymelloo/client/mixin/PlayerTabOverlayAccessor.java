package com.melloo.skymelloo.client.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Comparator;

// Exposes vanilla's own private tab-list sort order, needed to read Hypixel's dungeon tab list at
// stable line indices (see DungeonTabList).
@Mixin(PlayerTabOverlay.class)
public interface PlayerTabOverlayAccessor {
	@Accessor("PLAYER_COMPARATOR")
	static Comparator<PlayerInfo> skymelloo$getPlayerComparator() {
		throw new AssertionError("Mixin not applied");
	}
}
