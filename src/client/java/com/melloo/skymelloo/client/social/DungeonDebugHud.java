package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

// Raw internal run-tracker state, only shown while actually in a dungeon. Unstyled on purpose,
// unlike the polished player-facing DungeonScoreHud.
public final class DungeonDebugHud implements HudElement {
	public static final DungeonDebugHud INSTANCE = new DungeonDebugHud();

	private DungeonDebugHud() {
	}

	private static String flag(String label, boolean value) {
		// Heavier ✔ (U+2714) matches ✖'s visual weight better than the thinner ✓ (U+2713).
		return (value ? "§a✔ " : "§c✖ ") + label;
	}

	// " [Xm Ys Zms]" of real wall-clock time since the run started, not the scoreboard's own
	// elapsed time (which freezes during the boss fight). Empty if untimed.

	private static String elapsedSuffix(long eventMillis) {
		long runStart = DungeonRunTracker.getRunStartedAtMillis();
		if (eventMillis == 0 || runStart == 0) {
			return "";
		}
		long elapsed = Math.max(0, eventMillis - runStart);
		long minutes = elapsed / 60_000;
		long seconds = (elapsed / 1000) % 60;
		long millis = elapsed % 1000;
		return String.format(" §8[%dm %02ds %03dms]", minutes, seconds, millis);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gg, DeltaTracker deltaTracker) {
		SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
		if (!config.dungeonDebugHudEnabled) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}
		// Hidden outside dungeons and in the Dungeon Hub, same as the Score HUD next to it.
		if (!DungeonRunTracker.isRunActive() && DungeonRunTracker.getFloor() == null) {
			return;
		}

		List<String> lines = new ArrayList<>();
		lines.add(flag(Component.translatable("skymelloo.chat.dungeon_debug.run_active").getString(), DungeonRunTracker.isRunActive()));
		// One line per Wither Key obtained this run (a floor can have several Wither Doors), added
		// the moment the key is picked up rather than once the door is opened.
		List<Boolean> witherDoors = DungeonRunTracker.getWitherDoors();
		List<Long> witherDoorMillis = DungeonRunTracker.getWitherDoorOpenedMillis();
		String stateOpened = Component.translatable("skymelloo.chat.dungeon_debug.state_opened").getString();
		String stateNotOpenedYet = Component.translatable("skymelloo.chat.dungeon_debug.state_not_opened_yet").getString();
		if (witherDoors.isEmpty()) {
			lines.add(flag(Component.translatable("skymelloo.chat.dungeon_debug.wither_key_obtained").getString(), false));
		} else {
			for (int i = 0; i < witherDoors.size(); i++) {
				boolean opened = witherDoors.get(i);
				lines.add(flag(Component.translatable("skymelloo.chat.dungeon_debug.wither_door_state",
						i + 1, opened ? stateOpened : stateNotOpenedYet, elapsedSuffix(witherDoorMillis.get(i))).getString(), opened));
			}
		}
		// Same "key obtained" framing as Wither Doors above, gated on the actual key pickup message.
		boolean bloodKeyObtained = DungeonRunTracker.isBloodKeyObtained();
		boolean bloodCleared = DungeonRunTracker.isBloodRoomCleared();
		if (!bloodKeyObtained) {
			lines.add(flag(Component.translatable("skymelloo.chat.dungeon_debug.blood_key_obtained").getString(), false));
		} else {
			boolean bloodDoorOpened = DungeonRunTracker.isBloodDoorOpened();
			lines.add(flag(Component.translatable("skymelloo.chat.dungeon_debug.blood_door_state",
					bloodDoorOpened ? stateOpened : stateNotOpenedYet, elapsedSuffix(DungeonRunTracker.getBloodDoorOpenedMillis())).getString(), bloodDoorOpened));
		}
		String stateCleared = Component.translatable("skymelloo.chat.dungeon_debug.state_cleared").getString();
		String stateNotClearedYet = Component.translatable("skymelloo.chat.dungeon_debug.state_not_cleared_yet").getString();
		lines.add(flag(Component.translatable("skymelloo.chat.dungeon_debug.blood_room_state",
				bloodCleared ? stateCleared : stateNotClearedYet, elapsedSuffix(DungeonRunTracker.getBloodRoomClearedMillis())).getString(), bloodCleared));
		lines.add(flag(Component.translatable("skymelloo.chat.dungeon_debug.boss_room_entered", elapsedSuffix(DungeonRunTracker.getBossRoomEnteredMillis())).getString(), DungeonRunTracker.isBossRoomEntered()));
		lines.add(flag(Component.translatable("skymelloo.chat.dungeon_debug.boss_room_cleared", elapsedSuffix(DungeonRunTracker.getBossRoomClearedMillis())).getString(), DungeonRunTracker.isBossRoomCleared()));
		// Only shown once it's happened. A run can fail either by the local player dying (the party
		// can still clear the floor around them) or by the whole party wiping while they survive.
		if (DungeonRunTracker.hasLocalPlayerDied()) {
			lines.add(flag(Component.translatable("skymelloo.chat.dungeon_debug.run_failed_died").getString(), true));
		} else if (DungeonRunTracker.isEntirePartyDead()) {
			lines.add(flag(Component.translatable("skymelloo.chat.dungeon_debug.run_failed_wiped").getString(), true));
		}
		// Raw values straight from Hypixel's own Mod API location event - not documented anywhere
		// public, shown here so the real strings can actually be read off live instead of guessed at.
		lines.add(Component.translatable("skymelloo.chat.dungeon_debug.hypixel_mode", com.melloo.mellooessentials.client.social.HypixelLocationTracker.getMode() != null ? com.melloo.mellooessentials.client.social.HypixelLocationTracker.getMode() : "?").getString());
		lines.add(Component.translatable("skymelloo.chat.dungeon_debug.hypixel_map", com.melloo.mellooessentials.client.social.HypixelLocationTracker.getMap() != null ? com.melloo.mellooessentials.client.social.HypixelLocationTracker.getMap() : "?").getString());

		int x = config.hudDebugX;
		int y = config.hudDebugY;

		int width = 8;
		for (String line : lines) {
			width = Math.max(width, client.font.width(line) + 8);
		}
		int height = 4 + lines.size() * 10 + 2;

		gg.fill(x - 4, y - 3, x + width, y + height, 0x99101018);
		int lineY = y;
		for (String line : lines) {
			gg.text(client.font, line, x, lineY, 0xFFFFFFFF);
			lineY += 10;
		}
	}
}
