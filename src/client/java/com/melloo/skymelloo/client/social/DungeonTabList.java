package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.mixin.PlayerTabOverlayAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Reads Hypixel's dungeon TAB list, which fills fake "player" entries with dungeon stats text at
// fixed line positions - must reproduce vanilla's own sort order for those indices to stay reliable.
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

	public static List<String> getAllLines() {
		return lines;
	}

	public static String at(int index) {
		if (index < 0 || index >= lines.size()) {
			return null;
		}
		String line = lines.get(index);
		return line.isEmpty() ? null : line;
	}

	public static Matcher matchAt(int index, Pattern pattern) {
		String line = at(index);
		if (line == null) {
			return null;
		}
		Matcher matcher = pattern.matcher(line);
		return matcher.matches() ? matcher : null;
	}
}
