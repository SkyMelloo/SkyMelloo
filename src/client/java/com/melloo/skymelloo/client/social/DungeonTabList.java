package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.mixin.PlayerTabOverlayAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Hypixel's dungeon TAB list (the Tab-key player list overlay) the way SkyblockerMod/
 * Skyblocker's {@code PlayerListManager} does (GitHub, LGPL-3.0,
 * {@code src/main/java/de/hysky/skyblocker/skyblock/tabhud/util/PlayerListManager.java}) - Hypixel
 * fills the tab list with extra fake "player" entries carrying dungeon stats text (completed rooms,
 * secrets%, puzzle states, crypts) at fixed, predictable line positions, sorted the exact same way
 * vanilla's own tab overlay sorts real players ({@link PlayerTabOverlayAccessor}, the private
 * PLAYER_COMPARATOR field confirmed via javap against this game version). Reproducing that same
 * sort is what makes the fixed line indices (see {@link DungeonRunTracker}'s score calculation)
 * reliable - anything else would just be reading entries in an arbitrary order.
 * <p>
 * This is a different data source than the sidebar Scoreboard {@link DungeonRunTracker} already
 * reads for floor/cleared%/time - the tab list carries additional stats the sidebar doesn't.
 */
public final class DungeonTabList {
	private static List<String> lines = List.of();

	private DungeonTabList() {
	}

	public static void tick(Minecraft client) {
		if (client.getConnection() == null) {
			lines = List.of();
			return;
		}
		List<PlayerInfo> sorted = new ArrayList<>(client.getConnection().getOnlinePlayers());
		sorted.sort(PlayerTabOverlayAccessor.skymelloo$getPlayerComparator());
		List<String> newLines = new ArrayList<>(sorted.size());
		for (PlayerInfo info : sorted) {
			Component name = info.getTabListDisplayName();
			newLines.add(name != null ? name.getString().strip() : "");
		}
		lines = newLines;
	}

	/** Every reconstructed tab-list line, in order - {@code /sm debug score}'s diagnostic scan for where "Completed Rooms" actually ended up (a real report of the score staying frozen the whole run traced back to the fixed line index this class's doc comment relies on possibly not matching reality). */
	public static List<String> getAllLines() {
		return lines;
	}

	/** Trimmed tab-list line at {@code index}, or {@code null} if out of range/blank. */
	public static String at(int index) {
		if (index < 0 || index >= lines.size()) {
			return null;
		}
		String line = lines.get(index);
		return line.isEmpty() ? null : line;
	}

	/** {@code pattern} matched (fully) against the line at {@code index}, or {@code null} if there's no line there or it doesn't match. */
	public static Matcher matchAt(int index, Pattern pattern) {
		String line = at(index);
		if (line == null) {
			return null;
		}
		Matcher matcher = pattern.matcher(line);
		return matcher.matches() ? matcher : null;
	}
}
