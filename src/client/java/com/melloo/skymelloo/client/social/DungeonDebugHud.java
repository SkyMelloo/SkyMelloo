package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.config.SkyMellooConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Raw internal run-tracker state - only shown while actually in a dungeon (an active run, or a floor
 * already detected), not a permanent overlay everywhere on Hypixel. Separate from
 * {@link DungeonScoreHud}, which is the polished player-facing score panel; this one is unstyled on
 * purpose.
 */
public final class DungeonDebugHud implements HudElement {
	public static final DungeonDebugHud INSTANCE = new DungeonDebugHud();

	private DungeonDebugHud() {
	}

	private static String flag(String label, boolean value) {
		// Heavier ✔ (U+2714) instead of the thin ✓ (U+2713) - "anderes häkchen symbol eins wie das x
		// nutzen", matching the visual weight of ✖ instead of looking noticeably thinner beside it.
		return (value ? "§a✔ " : "§c✖ ") + label;
	}

	/**
	 * " [Xm Ys Zms]" - elapsed real time since the run started, for the moment {@code eventMillis}
	 * happened (2026-07-26). Empty string if the event hasn't happened yet (0) or no run is timed. Real
	 * wall-clock millis, not the scoreboard's own elapsed time, which freezes for the whole boss fight
	 * (see DungeonRunTracker's FLOOR_NULL_END_RUN_TICKS comment) - not useful for anything happening
	 * during it, which is exactly when most of these events (Blood/Boss room) happen.
	 */
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
		// Reported as showing constantly everywhere (other Hypixel games, lobbies, etc.) - the whole
		// point is watching these flags transition DURING a dungeon, not a permanent overlay. Active
		// run OR a floor already detected covers being in an actual instance; still hidden in the
		// Dungeon Hub before a run starts, same as the Score HUD next to it.
		if (!DungeonRunTracker.isRunActive() && DungeonRunTracker.getFloor() == null) {
			return;
		}

		List<String> lines = new ArrayList<>();
		lines.add(flag("Run active", DungeonRunTracker.isRunActive()));
		// One line per Wither Key obtained this run (a floor can have several Wither Doors) - added the
		// moment a key is picked up, well before that door is actually opened, rather than one shared
		// flag for the whole run. Label text used to always read "...opened (key: yes)" regardless of
		// state, so a red ✖ next to the word "opened" looked like a contradiction ("it says opened but
		// it's red?") - the text now actually says whether it's opened or not, matching the ✓/✖.
		List<Boolean> witherDoors = DungeonRunTracker.getWitherDoors();
		List<Long> witherDoorMillis = DungeonRunTracker.getWitherDoorOpenedMillis();
		if (witherDoors.isEmpty()) {
			lines.add(flag("Wither key obtained", false));
		} else {
			for (int i = 0; i < witherDoors.size(); i++) {
				boolean opened = witherDoors.get(i);
				lines.add(flag("Wither door " + (i + 1) + " " + (opened ? "opened" : "not opened yet") + " (key obtained)" + elapsedSuffix(witherDoorMillis.get(i)), opened));
			}
		}
		// Same "key obtained" framing as Wither Doors above - Catacombs has no separate pickup message
		// for a literal Blood Key the way Wither Essence drops one, so entering the Blood Room (the
		// Watcher speaking) is the real-world equivalent of "having the key" here. Once that's true,
		// the actual Blood door-opened event (a real "X opened a BLOOD door!" chat line, same pattern
		// as Wither's) is now shown too (2026-07-26), mirroring Wither Door's exact "opened/not opened
		// yet (key obtained)" wording instead of the old flat "Blood key obtained" line. Blood room
		// cleared stays its own separate line.
		boolean bloodEntered = DungeonRunTracker.isBloodRoomEntered();
		boolean bloodCleared = DungeonRunTracker.isBloodRoomCleared();
		if (!bloodEntered) {
			lines.add(flag("Blood key obtained", false));
		} else {
			boolean bloodDoorOpened = DungeonRunTracker.isBloodDoorOpened();
			lines.add(flag("Blood door " + (bloodDoorOpened ? "opened" : "not opened yet") + " (key obtained)" + elapsedSuffix(DungeonRunTracker.getBloodDoorOpenedMillis()), bloodDoorOpened));
		}
		lines.add(flag("Blood room " + (bloodCleared ? "cleared" : "not cleared yet") + elapsedSuffix(DungeonRunTracker.getBloodRoomClearedMillis()), bloodCleared));
		lines.add(flag("Boss room entered" + elapsedSuffix(DungeonRunTracker.getBossRoomEnteredMillis()), DungeonRunTracker.isBossRoomEntered()));
		lines.add(flag("Boss room cleared" + elapsedSuffix(DungeonRunTracker.getBossRoomClearedMillis()), DungeonRunTracker.isBossRoomCleared()));
		// Only shown at all once it's actually happened - the death case that would otherwise make
		// "Boss room cleared" look like a contradiction (it can legitimately still go green even after
		// this, since the PARTY can finish the floor around a dead/ghosted player - see
		// DungeonRunTracker#localPlayerDied's own doc comment) instead gets its own dedicated line here,
		// rather than forcing some unrelated flag to (incorrectly) read as green (2026-07-26). Covers
		// BOTH ways a run can fail: the local player personally dying, and the local player surviving
		// while the rest of the party wipes (2026-07-27).
		if (DungeonRunTracker.hasLocalPlayerDied()) {
			lines.add(flag("Run failed (you died)", true));
		} else if (DungeonRunTracker.isEntirePartyDead()) {
			lines.add(flag("Run failed (party wiped)", true));
		}
		// Raw values straight from Hypixel's own Mod API location event - not documented anywhere
		// public, shown here so the real strings can actually be read off live instead of guessed at.
		lines.add("§7Hypixel mode: §f" + (HypixelLocationTracker.getMode() != null ? HypixelLocationTracker.getMode() : "?"));
		lines.add("§7Hypixel map: §f" + (HypixelLocationTracker.getMap() != null ? HypixelLocationTracker.getMap() : "?"));

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
