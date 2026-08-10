package com.melloo.skymelloo.client.gui;

import com.melloo.skymelloo.client.SkyMellooClient;
import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.social.CloudSyncManager;
import com.melloo.skymelloo.client.social.PermissionsManager;
import com.melloo.skymelloo.client.social.WhitelistManager;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The full custom (non-YACL) settings screen: a rotatable 3D preview of your own character on
 * the left, a slim non-boxy row list on the right, and a tab bar across the top of the list
 * covering every config category. Replaces YACL's generated screen entirely. Particle cosmetics
 * moved to MellooEssentials (a hard dependency now) - configured from its own settings screen, or
 * from SkyMelloo's H-menu (see SkyMellooMenuScreen's Cosmetics page), not here.
 */
public class SkyMellooSettingsScreen extends Screen {
	private static final int PANEL_BG = 0x30000000;
	private static final int PREVIEW_BG = 0x800C0C14;
	private static final int ROW_BG_HOVER_BONUS = 0x14FFFFFF;
	private static final int TEXT_ON = 0xFFFFFFFF;
	private static final int TEXT_OFF = 0xFFAAAAAA;

	private static final int ROW_H = 16;
	private static final int ROW_GAP = 2;
	private static final int MARGIN = 10;
	private static final double PREVIEW_WIDTH_FRACTION = 0.32;
	private static final float ZOOM_MIN = 0.5F;
	private static final float ZOOM_MAX = 2.2F;

	private static final int[] COLOR_PALETTE = {
			0xFFFF5555, 0xFFFFAA00, 0xFFFFFF55, 0xFF55FF55, 0xFF55FFFF,
			0xFF5599FF, 0xFFAA33FF, 0xFFFF55FF, 0xFFFFFFFF, 0xFF888888,
			0xFF227777, 0xFFFF8800
	};

	public enum Tab {
		// Item/Chest/Mob highlighting all relocated into DUNGEONS (see rowsFor), Player highlighting
		// (now "Party Highlighting", including the admin/dev/owner gold color) moved here instead.
		// HP Armor Stand highlighting is gone entirely, not relocated.
		PARTY("Party"), FUN("Fun"), HP("HP & Distance"), FISHING("Fishing"), DUNGEONS("Dungeons"), GENERAL("General"), CLOUD("Cloud"), DEBUG("Debug");

		final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	private interface RowFactory {
		AbstractWidget create(int x, int y, int w, int h);
	}

	private static final int TAB_WIDTH = 96;

	private final List<AbstractWidget> contentWidgets = new ArrayList<>();
	private final List<AbstractWidget> tabWidgets = new ArrayList<>();
	private final List<AbstractWidget> dropdownWidgets = new ArrayList<>();
	private Tab activeTab = Tab.PARTY;
	private Tab[] visibleTabsCache = new Tab[0];
	private AbstractWidget openDropdownOwner;
	private int dropdownX, dropdownY, dropdownW, dropdownH;
	// Non-null while a KeybindRowWidget is waiting for the next key press - see keyPressed() below.
	private KeyMapping capturingKeybind;

	private int previewX1, previewY1, previewX2, previewY2;
	private int listX1, listX2, listTop, listBottom;
	private int tabBarY, tabBarH;
	private float previewZoom = 1.0F;
	private int scrollOffset = 0;
	private int maxScroll = 0;
	private int tabScrollX = 0;
	private int tabBarMaxScrollX = 0;

	/** Only tabs whose feature the account is actually permitted to use show up in the tab bar. */
	private static Tab[] visibleTabs() {
		List<Tab> tabs = new ArrayList<>();
		for (Tab tab : Tab.values()) {
			boolean visible = switch (tab) {
				case HP -> true;
				case PARTY -> true;
				case FISHING -> true;
				// Chest/Item/Mob highlighting moved in here too, and Death Recap moved in from Fun
				// the same day - this tab always shows since every feature it contains is available
				// to everyone now.
				case DUNGEONS -> true;
				// Only Spell/Spell Essence live here now (Kill Tracker/Death Double removed
				// entirely, Death Recap moved to Dungeons) - both need "cosmetics".
				case FUN -> PermissionsManager.has("spell");
				case GENERAL, CLOUD, DEBUG -> true;
			};
			if (visible) {
				tabs.add(tab);
			}
		}
		return tabs.toArray(new Tab[0]);
	}

	// Null for every existing open path (H-menu, SkyMelloo Menu item, /skymelloo) - those all close
	// straight back to the game via vanilla's own default onClose(), same as before. Only set when
	// opened FROM another screen (currently just Mod Menu's config button - see ModMenuIntegration),
	// so closing this one returns to that screen instead of exiting to the game world underneath it.
	private final Screen parent;

	public SkyMellooSettingsScreen() {
		this(null);
	}

	/** Used by ModMenuIntegration - {@code parent} is Mod Menu's own mod-list screen, returned to on close instead of the game world. */
	public SkyMellooSettingsScreen(Screen parent) {
		super(Component.literal("SkyMelloo Settings"));
		this.parent = parent;
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(parent);
	}

	public static void open(Tab initialTab) {
		SkyMellooSettingsScreen screen = new SkyMellooSettingsScreen();
		screen.activeTab = initialTab;
		Minecraft client = Minecraft.getInstance();
		// Re-sync whitelist/admin/permissions right now instead of trusting the once-per-join
		// check - a link made moments ago (e.g. /skymelloo verify) shouldn't need a reconnect to show.
		WhitelistManager.forceRecheck(client);
		PermissionsManager.forceRefetch(client);
		Minecraft.getInstance().setScreen(screen);
	}

	@Override
	protected void init() {
		int previewWidth = (int) (this.width * PREVIEW_WIDTH_FRACTION);
		previewX1 = MARGIN;
		previewY1 = MARGIN + 22;
		previewX2 = MARGIN + previewWidth;
		previewY2 = this.height - MARGIN;

		listX1 = previewX2 + MARGIN;
		listX2 = this.width - MARGIN;

		tabBarY = MARGIN + 22;
		tabBarH = 18;
		visibleTabsCache = visibleTabs();
		if (visibleTabsCache.length == 0) {
			visibleTabsCache = new Tab[] { Tab.GENERAL };
		}
		boolean activeStillVisible = false;
		for (Tab tab : visibleTabsCache) {
			if (tab == activeTab) {
				activeStillVisible = true;
				break;
			}
		}
		if (!activeStillVisible) {
			activeTab = visibleTabsCache[0];
		}
		buildTabBar();

		listTop = tabBarY + tabBarH + 6;
		listBottom = this.height - MARGIN;

		buildRows();

		// This screen (opened via key H) is a separate screen from the main SkyMelloo Menu item's
		// nav row - Report a Bug living only there meant it was missing from the single most-used
		// entry point into the mod's UI, so it's added here too. Uses the shared
		// SkyMellooButtonWidget rather than vanilla Button - this was the one place in the mod's UI
		// still using the plain grey Minecraft button style instead of matching everywhere else's
		// pink-glow look.
		int reportBugWidth = 90;
		addRenderableWidget(new SkyMellooButtonWidget(this.width - MARGIN - reportBugWidth, MARGIN, reportBugWidth, 18,
				"Report a Bug", SkyMellooButtonWidget.RED, SkyMellooMenuScreen::openReportBug));
	}

	/**
	 * Fixed-width tabs that scroll horizontally (mouse wheel over the tab bar) instead of shrinking
	 * to fit - with 8 tabs now, equal-division would make labels unreadably cramped.
	 */
	private void buildTabBar() {
		for (AbstractWidget widget : tabWidgets) {
			removeWidget(widget);
		}
		tabWidgets.clear();

		int visibleWidth = listX2 - listX1;
		int totalWidth = visibleTabsCache.length * TAB_WIDTH;
		tabBarMaxScrollX = Math.max(0, totalWidth - visibleWidth);
		tabScrollX = Math.max(0, Math.min(tabScrollX, tabBarMaxScrollX));

		for (int i = 0; i < visibleTabsCache.length; i++) {
			Tab tab = visibleTabsCache[i];
			int x = listX1 - tabScrollX + i * TAB_WIDTH;
			int w = TAB_WIDTH - 2;
			TabButtonWidget button = new TabButtonWidget(x, tabBarY, w, tabBarH, tab);
			boolean onScreen = x + w > listX1 && x < listX2;
			button.visible = onScreen;
			button.active = onScreen;
			tabWidgets.add(button);
			addRenderableWidget(button);
		}
	}

	/**
	 * Single scrollable column instead of the old multi-column layout - a tab with more rows than
	 * fit on screen (Cosmetics has ~30) now scrolls down with the mouse wheel instead of spilling
	 * into extra columns off to the right.
	 */
	private void buildRows() {
		closeColorDropdown();
		for (AbstractWidget widget : contentWidgets) {
			removeWidget(widget);
		}
		contentWidgets.clear();

		List<RowFactory> rows = rowsFor(activeTab);
		int w = listX2 - listX1;
		int visibleHeight = listBottom - listTop;
		int contentHeight = rows.size() * (ROW_H + ROW_GAP);
		maxScroll = Math.max(0, contentHeight - visibleHeight);
		scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

		for (int i = 0; i < rows.size(); i++) {
			int y = listTop - scrollOffset + i * (ROW_H + ROW_GAP);
			AbstractWidget widget = rows.get(i).create(listX1, y, w, ROW_H);
			boolean onScreen = y + ROW_H > listTop && y < listBottom;
			widget.visible = onScreen;
			widget.active = onScreen;
			contentWidgets.add(widget);
			addRenderableWidget(widget);
		}
	}

	/** Adjusts the scroll offset and re-lays out the current tab's rows at their new position. */
	private void scrollBy(double amount) {
		int next = (int) Math.round(scrollOffset - amount * (ROW_H + ROW_GAP));
		scrollOffset = Math.max(0, Math.min(next, maxScroll));
		buildRows();
	}

	private List<RowFactory> rowsFor(Tab tab) {
		SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
		List<RowFactory> rows = new ArrayList<>();
		switch (tab) {
			case PARTY -> {
				// Party (light blue) and staff (pink) are both handled by MellooEssentials now, always
				// on, not affected by anything below except the Low HP Blink toggle (SkyMelloo is the
				// only one of the two mods that knows how to compute that, hooked into essentials' own
				// color decision). SkyMelloo Friends (aqua) is the only category left that this mod
				// still decides on its own. Self/regular-other-player/NPC colors are gone entirely, not
				// just defaulted off.
				rows.add(headerRow("Party Highlighting"));
				rows.add(tip(boolRow("Player Highlighting", () -> c.playerHighlightEnabled, v -> c.playerHighlightEnabled = v, 0xFF5599FF), "Highlight SkyMelloo Friends. Party members and SkyMelloo staff also glow (fixed light blue/pink) - those are handled by MellooEssentials and always on, not affected by this toggle."));
				rows.add(tip(boolRow("Low HP Blink", () -> c.lowHpBlinkEnabled, v -> c.lowHpBlinkEnabled = v, 0xFFFF5555), "A party member's highlight (glow outline and nametag marker) blinks bright red once their HP drops under 25% - an urgent \"someone needs help\" signal readable at a glance."));
				rows.add(tip(colorRow("SkyMelloo Friend Color", () -> c.friendHighlightColor, v -> c.friendHighlightColor = v), "Glow color for confirmed SkyMelloo Friends (Social menu, key G) - not Hypixel's own /friend list."));
				rows.add(tip(boolRow("Player Glow Outline", () -> c.playerGlowOutlineEnabled, v -> c.playerGlowOutlineEnabled = v, 0xFF5599FF), "Also force the glow outline (visible through walls) for SkyMelloo Friends, not just colored names. Party/staff outline is handled by MellooEssentials and always on."));
				rows.add(tip(boolRow("Party Join Stats", () -> c.partyJoinStatsEnabled, v -> c.partyJoinStatsEnabled = v, 0xFFFFAA00), "When someone joins your party, look up their SkyBlock stats (sky.melloo.me/api) and post a summary in chat."));

				rows.add(headerRow("Lobby Player Search"));
				rows.add(tip(colorRow("Search Highlight Color", () -> c.lobbySearchColor, v -> c.lobbySearchColor = v), "Glow color for the player targeted with /sm search <name> - only works in a Hypixel lobby, not SkyBlock (which has its own party/staff/friend highlighting above). /sm search clear removes it."));

				rows.add(headerRow("Misc"));
				rows.add(tip(boolRow("Show Invisible Players", () -> c.showInvisiblePlayersEnabled, v -> c.showInvisiblePlayersEnabled = v, 0xFF888888), "Makes other invisible players (e.g. from an Invisibility Potion) render normally instead of staying hidden. Not a highlight effect - they're still blocked by walls/line-of-sight like anyone else, just no longer artificially hidden."));
			}
			case HP -> {
				rows.add(headerRow("Distance"));
				rows.add(tip(boolRow("Show Distance", () -> c.showDistanceEnabled, v -> c.showDistanceEnabled = v, 0xFF55FFFF), "Show distance in blocks next to highlighted mobs/players/items."));
			}
			case FISHING -> {
				rows.add(headerRow("Fishing"));
				rows.add(tip(boolRow("Fishing Helper", () -> c.fishingHelperEnabled, v -> c.fishingHelperEnabled = v, 0xFF5599FF), "Alert when your fishing bobber gets a bite."));
				rows.add(tip(boolRow("Fishing Helper Sound", () -> c.fishingHelperSound, v -> c.fishingHelperSound = v, 0xFFFFAA00), "Play a sound in addition to the actionbar alert."));
				rows.add(tip(colorRow("Waiting Color", () -> c.fishingWaitingColor, v -> c.fishingWaitingColor = v), "Glow color of your bobber while waiting for a bite."));
				rows.add(tip(colorRow("Biting Color", () -> c.fishingBitingColor, v -> c.fishingBitingColor = v), "Glow color of your bobber the moment it's biting."));

				rows.add(headerRow("Minigame"));
				rows.add(tip(boolRow("Fishing Minigame", () -> c.fishingMinigameEnabled, v -> c.fishingMinigameEnabled = v, 0xFFFF6600), "Pufferfish targets pop up in front of you while your rod is cast - click them before they fully puff up for points."));
				rows.add(tip(colorRow("Minigame Glow Color", () -> c.fishingMinigameColor, v -> c.fishingMinigameColor = v), "Extra glow tint on the fishing minigame targets."));
			}
			case DUNGEONS -> {
				// Chest/Item/Mob highlighting all relocated here from their previous tabs - purely a
				// settings-screen reorg, the underlying scan/render logic (HighlightManager,
				// BlockHighlightRenderer) is unchanged and still works everywhere in-game, not just dungeons.
				rows.add(headerRow("Chest Highlight"));
				rows.add(tip(boolRow("Chest Highlight", () -> c.chestHighlightEnabled, v -> c.chestHighlightEnabled = v, 0xFFFFD700), "Highlight chests with a glowing box outline (only when actually in view, not through walls)."));
				rows.add(tip(colorRow("Chest Highlight Color", () -> c.chestHighlightColor, v -> c.chestHighlightColor = v), "Box color for chests."));
				rows.add(tip(intStepRow("Block Scan Range", () -> c.blockHighlightRange, v -> c.blockHighlightRange = v, 8, 48, 8), "Scan radius (in blocks) around you for Chest Highlight."));

				rows.add(headerRow("Item Highlighting"));
				rows.add(tip(boolRow("Item Highlighting", () -> c.itemHighlightEnabled, v -> c.itemHighlightEnabled = v, 0xFF55FF55), "Highlight dropped items with a glowing outline and show their name (only when actually in view, not through walls)."));
				rows.add(tip(stringRow("Item Name Filters", () -> c.itemHighlightNameFilters, v -> c.itemHighlightNameFilters = v), "Comma-separated, case-insensitive item name filters. Leave empty to highlight all dropped items."));
				rows.add(tip(colorRow("Item Highlighting Color", () -> c.itemHighlightColor, v -> c.itemHighlightColor = v), "Glow color for dropped items."));

				// Drastically simplified - the old general "highlight every hostile mob everywhere"
				// system (name filters, friendly mobs, default/named colors) is gone entirely. Only
				// the current-room highlight remains, and it's dungeon-only by nature
				// (isInCurrentDungeonRoom requires an active run).
				rows.add(headerRow("Mob Highlighting"));
				rows.add(tip(boolRow("Mob Highlighting", () -> c.dungeonRoomMobHighlightEnabled, v -> c.dungeonRoomMobHighlightEnabled = v, 0xFFFF0000), "During a dungeon run, highlight hostile mobs inside your CURRENT room, so the ones you still need to clear stand out from mobs elsewhere on the floor."));
				rows.add(tip(colorRow("Mob Highlight Color", () -> c.dungeonRoomMobHighlightColor, v -> c.dungeonRoomMobHighlightColor = v), "Glow color for hostile mobs inside your current dungeon room."));

				// Moved here from Fun, just relocated in the menu.
				rows.add(headerRow("Death Recap"));
				rows.add(tip(boolRow("Death Recap", () -> c.deathRecapEnabled, v -> c.deathRecapEnabled = v, 0xFFFF5555), "When you die, post a short chat recap of the last few hits you took and what/who dealt them - real damage-source data, not a proximity guess."));
				rows.add(tip(boolRow("Death Recap Party Announce", () -> c.deathRecapPartyAnnounceEnabled, v -> c.deathRecapPartyAnnounceEnabled = v, 0xFFFF5555), "Also share a short one-line version with the party (who/what killed you) - independent of the local Death Recap above."));
				rows.add(tip(stringRow("Message Text", () -> c.deathRecapPartyAnnounceTemplate, v -> c.deathRecapPartyAnnounceTemplate = v), "Placeholders: {player} {cause}"));
				rows.add(tip(cycleRow("Delivery", () -> c.deathRecapPartyAnnounceDelivery, v -> c.deathRecapPartyAnnounceDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the message above goes. Falls back to LOCAL automatically if you're not actually in a party."));

				rows.add(headerRow("Dungeon Info"));
				rows.add(tip(boolRow("Dungeon Info", () -> c.partyJoinStatsEnabled, v -> c.partyJoinStatsEnabled = v, 0xFF5599FF), "When someone joins your dungeon party finder group, look up their stats and post a summary in chat."));
				rows.add(tip(boolRow("Show MP", () -> c.dungeonInfoShowMp, v -> c.dungeonInfoShowMp = v, 0xFFAA33FF), "Include Magical Power in the Dungeon Info message."));
				rows.add(tip(stringRow("Message Template", () -> c.dungeonInfoMessageTemplate, v -> c.dungeonInfoMessageTemplate = v),
						"Placeholders: {username} {mp} {level} {cata} {class} {skillavg} {networth} {rank} {guild} {time} {date}"));
				rows.add(tip(cycleRow("Delivery", () -> c.dungeonInfoMessageDelivery, v -> c.dungeonInfoMessageDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the message above goes. Falls back to LOCAL automatically if you're not actually in a party."));
				rows.add(tip(stringRow("Timezone", () -> c.dungeonInfoTimezone, v -> c.dungeonInfoTimezone = v),
						"IANA timezone ID used for {time}/{date} above, e.g. Europe/Berlin or America/New_York."));

				rows.add(headerRow("Auto-Kick"));
				rows.add(tip(boolRow("Auto-Kick", () -> c.dungeonAutoKickEnabled, v -> c.dungeonAutoKickEnabled = v, 0xFFFF5555), "Automatically /party kick a joining member if their chosen stat below is under the threshold."));
				rows.add(tip(cycleRow("Check Stat", () -> c.dungeonAutoKickStat, v -> c.dungeonAutoKickStat = v, new String[] { "MP", "LEVEL" }), "Which stat Auto-Kick checks - Magical Power or SkyBlock Level."));
				rows.add(tip(intTextRow("Threshold", () -> c.dungeonAutoKickThreshold, v -> c.dungeonAutoKickThreshold = v, 0, 100000), "A joining player whose stat is below this gets kicked from the party. Click to type a value directly."));
				rows.add(tip(stringRow("Message Text", () -> c.dungeonAutoKickMessageTemplate, v -> c.dungeonAutoKickMessageTemplate = v), "Placeholders: {player} {stat} {value} {threshold}"));
				rows.add(tip(cycleRow("Delivery", () -> c.dungeonAutoKickDelivery, v -> c.dungeonAutoKickDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the message above goes. Falls back to LOCAL automatically if you're not actually in a party."));

				rows.add(headerRow("Carry Auto-Kick (Max)"));
				rows.add(tip(boolRow("Max Auto-Kick", () -> c.dungeonAutoKickMaxEnabled, v -> c.dungeonAutoKickMaxEnabled = v, 0xFFFF9955), "For carry parties: automatically /party kick a joining member if their chosen stat below is OVER the threshold - they don't need carrying, and may be taking a slot from someone who does."));
				rows.add(tip(cycleRow("Check Stat", () -> c.dungeonAutoKickMaxStat, v -> c.dungeonAutoKickMaxStat = v, new String[] { "MP", "LEVEL" }), "Which stat Max Auto-Kick checks - independent of the min check above, can be a different stat."));
				rows.add(tip(intTextRow("Threshold", () -> c.dungeonAutoKickMaxThreshold, v -> c.dungeonAutoKickMaxThreshold = v, 0, 100000), "A joining player whose stat is OVER this gets kicked from the party. Click to type a value directly."));
				rows.add(tip(stringRow("Message Text", () -> c.dungeonAutoKickMaxMessageTemplate, v -> c.dungeonAutoKickMaxMessageTemplate = v), "Placeholders: {player} {stat} {value} {threshold}"));
				rows.add(tip(cycleRow("Delivery", () -> c.dungeonAutoKickMaxDelivery, v -> c.dungeonAutoKickMaxDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the message above goes. Falls back to LOCAL automatically if you're not actually in a party."));

				rows.add(headerRow("Run Tracker"));
				rows.add(tip(cycleRow("Party HUD", () -> c.partyHudMode, v -> c.partyHudMode = v, new String[] { "OFF", "COMPACT", "FULL" }), "Compact shows name+MP; Full also shows each member's death count for the current run. Position set via the HUD layout editor (default J)."));
				rows.add(tip(boolRow("Party HUD Puzzle History", () -> c.partyHudShowPuzzleHistory, v -> c.partyHudShowPuzzleHistory = v, 0xFFFFAA00), "In FULL mode during a run, show each puzzle that member specifically solved/failed as extra lines under their row - only appears for members actually involved in a puzzle."));
				rows.add(tip(boolRow("Party MP Bar", () -> c.partyMpBarEnabled, v -> c.partyMpBarEnabled = v, 0xFFFF6EC7), "A horizontal MP \"spread\" bar for the party - each member's face is placed along it from lowest to highest MP, range labeled above. Shows the shape of the party's gear gap at a glance. Position set via the HUD layout editor (default J)."));
				rows.add(tip(boolRow("Run Report", () -> c.dungeonRunReportEnabled, v -> c.dungeonRunReportEnabled = v, 0xFF5599FF), "Track deaths and puzzles solved during the run and post a detailed summary in chat when the dungeon completes - with clickable [Kick] buttons if you're the party leader. LOCAL only, always (see Party Run Summary below to also notify the party)."));
				rows.add(tip(boolRow("Party Run Summary", () -> c.dungeonRunPartySummaryEnabled, v -> c.dungeonRunPartySummaryEnabled = v, 0xFF5599FF), "A short, separate one-line result sent to party chat when a run ends (score/grade/floor/time only) - independent of Run Report above, which is deliberately never sent to party anymore since a long combined line with many player names in it could crash the game."));
				rows.add(tip(stringRow("Party Summary Text", () -> c.dungeonRunPartySummaryTemplate, v -> c.dungeonRunPartySummaryTemplate = v), "Placeholders: {floor} {score} {grade} {time}"));
				rows.add(tip(cycleRow("Party Summary Delivery", () -> c.dungeonRunPartySummaryDelivery, v -> c.dungeonRunPartySummaryDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the message above goes. Falls back to LOCAL automatically if you're not actually in a party."));
				rows.add(tip(boolRow("Death Auto-Kick", () -> c.dungeonDeathKickEnabled, v -> c.dungeonDeathKickEnabled = v, 0xFFFF5555), "Automatically /party kick a member once their death count for the current run exceeds the threshold below."));
				rows.add(tip(intTextRow("Death Threshold", () -> c.dungeonDeathKickThreshold, v -> c.dungeonDeathKickThreshold = v, 1, 10), "Death count a member has to exceed (during one run) to trigger Death Auto-Kick."));
				rows.add(tip(boolRow("AFK Auto-Kick", () -> c.dungeonAfkKickEnabled, v -> c.dungeonAfkKickEnabled = v, 0xFFFF5555), "Automatically /party kick a member who hasn't moved at all for the threshold below during a run."));
				rows.add(tip(cycleRow("AFK Threshold", () -> c.dungeonAfkKickThreshold, v -> c.dungeonAfkKickThreshold = v, new String[] { "30", "60", "120" }), "How long a member has to stand completely still (seconds) before AFK Auto-Kick fires."));
				rows.add(tip(boolRow("Score HUD", () -> c.dungeonScoreHudEnabled, v -> c.dungeonScoreHudEnabled = v, 0xFFFFD700), "Live dungeon score estimate during the run, calculated the same way SkyblockerMod/Skyblocker does - real completed-rooms/secrets%/puzzle-state/crypts from the dungeon tab list, not an optimistic ceiling."));
				rows.add(tip(boolRow("Show Breakdown", () -> c.dungeonScoreShowBreakdown, v -> c.dungeonScoreShowBreakdown = v, 0xFFAAAAAA), "Show the Skill/Explore/Speed/Bonus line on the Score HUD."));
				rows.add(tip(boolRow("Show Room Secrets", () -> c.dungeonScoreShowRoomSecrets, v -> c.dungeonScoreShowRoomSecrets = v, 0xFF66DDFF), "Show the current room's secrets found/total and per-secret dots - only ever populated if Skyblocker is also installed."));
				rows.add(tip(boolRow("Show Puzzles", () -> c.dungeonScoreShowPuzzles, v -> c.dungeonScoreShowPuzzles = v, 0xFFFFAA00), "Show the puzzle outcome blocks and solved/failed detail lines on the Score HUD."));
				rows.add(tip(boolRow("Show Possible/Penalties", () -> c.dungeonScoreShowPossible, v -> c.dungeonScoreShowPossible = v, 0xFF55FF55), "Show the best-case ceiling score (\"Possible: X\") next to the current total, plus a breakdown of permanent penalties (puzzle fails, deaths) applied so far."));
				rows.add(tip(boolRow("Show Next Grade", () -> c.dungeonScoreShowNextGrade, v -> c.dungeonScoreShowNextGrade = v, 0xFFFFD700), "Show how many more points are needed to reach the next grade above your current one (e.g. \"Next grade (A): +14\")."));
				rows.add(tip(boolRow("Show Pace/Countdown", () -> c.dungeonScoreShowPaceAndCountdown, v -> c.dungeonScoreShowPaceAndCountdown = v, 0xFFAAAAAA), "Show an up/down arrow next to the score showing whether it climbed or fell over the last ~10s, plus a persistent \"Time left\" countdown against the floor's time limit (turns red once past it)."));
				rows.add(tip(boolRow("Show Final Result", () -> c.dungeonScoreFinalResultEnabled, v -> c.dungeonScoreFinalResultEnabled = v, 0xFFFFD700), "When a run ends, keep the Score HUD up for a while showing a distinct \"Final Result\" panel (frozen final score/breakdown/puzzles) instead of just disappearing the instant the run ends."));
				rows.add(tip(intStepRow("Final Result Duration", () -> c.dungeonScoreFinalResultDurationSeconds, v -> c.dungeonScoreFinalResultDurationSeconds = v, 5, 60, 5), "How many seconds the Final Result panel above stays up before the Score HUD disappears."));
				rows.add(tip(boolRow("Debug HUD", () -> c.dungeonDebugHudEnabled, v -> c.dungeonDebugHudEnabled = v, 0xFF66DDFF), "Shows the run tracker's own internal state flags (run active, wither door opened, blood room entered/cleared, boss room entered) so you can see at a glance whether detection is actually keeping up. Position set via the HUD layout editor (default J)."));

				// Each message below is fully self-contained (toggle, text, delivery) rather than
				// scattered across sections with one shared delivery setting at the bottom - lets e.g.
				// death spam stay local while a boss-room announcement goes to the whole party.
				rows.add(headerRow("Boss Room Announcement"));
				rows.add(tip(boolRow("Announce Boss Room", () -> c.dungeonBossRoomAnnounceEnabled, v -> c.dungeonBossRoomAnnounceEnabled = v, 0xFFAA33FF), "Announce when the boss room is entered."));
				rows.add(tip(stringRow("Message Text", () -> c.dungeonBossRoomMessageTemplate, v -> c.dungeonBossRoomMessageTemplate = v), "Used when the run's score is still 300+ at entry. Placeholder: {player} (\"The party\" for the party-wide trigger, or a specific name for the per-player trigger)."));
				rows.add(tip(stringRow("Low-Score Message Text", () -> c.dungeonBossRoomLowScoreMessageTemplate, v -> c.dungeonBossRoomLowScoreMessageTemplate = v), "Used INSTEAD of the message above when the run's score is under 300 at entry. Placeholders: {player} {score}."));
				rows.add(tip(cycleRow("Delivery", () -> c.dungeonBossRoomMessageDelivery, v -> c.dungeonBossRoomMessageDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where either message above goes. Falls back to LOCAL automatically if you're not actually in a party."));

				rows.add(headerRow("Death Message"));
				rows.add(tip(boolRow("Death Message", () -> c.dungeonDeathMessageEnabled, v -> c.dungeonDeathMessageEnabled = v, 0xFFFF5555), "Announce in chat when a party member dies during the run."));
				rows.add(tip(stringRow("Message Text", () -> c.dungeonDeathMessageTemplate, v -> c.dungeonDeathMessageTemplate = v), "Placeholders: {player} {count}"));
				rows.add(tip(cycleRow("Delivery", () -> c.dungeonDeathMessageDelivery, v -> c.dungeonDeathMessageDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the message above goes. Falls back to LOCAL automatically if you're not actually in a party."));

				rows.add(headerRow("Pre-Boss Score Warning"));
				rows.add(tip(boolRow("Pre-Boss Score Warning", () -> c.dungeonPreBossScoreWarningEnabled, v -> c.dungeonPreBossScoreWarningEnabled = v, 0xFFFFAA00), "Warn the moment the Blood Room fight ends if the run's score is still under 300 (S+) - fires while the boss portal doesn't exist yet, so the party can still decide not to go in."));
				rows.add(tip(stringRow("Message Text", () -> c.dungeonPreBossScoreWarningTemplate, v -> c.dungeonPreBossScoreWarningTemplate = v), "Placeholder: {score}"));
				rows.add(tip(stringRow("Already-Impossible Text", () -> c.dungeonPreBossScoreWarningAlreadyImpossibleTemplate, v -> c.dungeonPreBossScoreWarningAlreadyImpossibleTemplate = v), "Used instead of the text above when S+ was already confirmed impossible earlier this run for a different reason (puzzle fail/death/time limit) - avoids implying entering now is what costs the points. Placeholder: {score}"));
				rows.add(tip(cycleRow("Delivery", () -> c.dungeonPreBossScoreWarningDelivery, v -> c.dungeonPreBossScoreWarningDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the message above goes. Falls back to LOCAL automatically if you're not actually in a party."));

				rows.add(headerRow("All Rooms Discovered"));
				rows.add(tip(boolRow("Announce Possible Score", () -> c.dungeonRoomsDiscoveredAnnounceEnabled, v -> c.dungeonRoomsDiscoveredAnnounceEnabled = v, 0xFF66DDFF), "Announce once per run, the moment cleared% hits 100 (every room discovered), exactly what score is still possible from here - only then is the best-case ceiling not a guess anymore."));
				rows.add(tip(stringRow("Message Text", () -> c.dungeonRoomsDiscoveredTemplate, v -> c.dungeonRoomsDiscoveredTemplate = v), "Placeholders: {possible} {grade}"));
				rows.add(tip(cycleRow("Delivery", () -> c.dungeonRoomsDiscoveredDelivery, v -> c.dungeonRoomsDiscoveredDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the message above goes. Falls back to LOCAL automatically if you're not actually in a party."));

				rows.add(headerRow("Secrets Pace Warning"));
				rows.add(tip(boolRow("Secrets Pace Warning", () -> c.dungeonSecretsPaceWarningEnabled, v -> c.dungeonSecretsPaceWarningEnabled = v, 0xFF66DDFF), "Warn once per run the moment your secret-finding rate first falls behind what's needed to hit the floor's required secrets% before its time limit. Independent of the Time Limit Warning below - this is pace-based, not a fixed countdown checkpoint, so they won't overlap."));
				rows.add(tip(stringRow("Message Text", () -> c.dungeonSecretsPaceWarningTemplate, v -> c.dungeonSecretsPaceWarningTemplate = v), "Placeholders: {secrets} {required} {timeleft}"));
				rows.add(tip(cycleRow("Delivery", () -> c.dungeonSecretsPaceWarningDelivery, v -> c.dungeonSecretsPaceWarningDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the message above goes. Falls back to LOCAL automatically if you're not actually in a party."));

				rows.add(headerRow("Puzzle Retry Fail"));
				rows.add(tip(boolRow("Puzzle Retry Fail", () -> c.dungeonPuzzleRetryFailEnabled, v -> c.dungeonPuzzleRetryFailEnabled = v, 0xFFFFAA00), "Announce when the SAME puzzle fails again after already having failed once (a reset-and-retried room). Doesn't double-penalize Skill score or duplicate the Score HUD line either way - this is purely an optional heads-up."));
				rows.add(tip(stringRow("Message Text", () -> c.dungeonPuzzleRetryFailTemplate, v -> c.dungeonPuzzleRetryFailTemplate = v), "Placeholders: {player} {detail}"));
				rows.add(tip(cycleRow("Delivery", () -> c.dungeonPuzzleRetryFailDelivery, v -> c.dungeonPuzzleRetryFailDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the message above goes. Falls back to LOCAL automatically if you're not actually in a party."));

				rows.add(headerRow("S+ Impossible Warning"));
				rows.add(tip(boolRow("S+ Impossible Warning", () -> c.dungeonSPlusImpossibleEnabled, v -> c.dungeonSPlusImpossibleEnabled = v, 0xFFFF5555), "Warn once per run (after a puzzle fail, a death, or the time limit passing) if S+ has become mathematically impossible even in the best case for everything still open. Fires at most once."));
				rows.add(tip(stringRow("Message Text", () -> c.dungeonSPlusImpossibleTemplate, v -> c.dungeonSPlusImpossibleTemplate = v), "Placeholder: {reason} (\"a puzzle failed\", \"a death occurred\", or \"the time limit passed\")"));
				rows.add(tip(cycleRow("Delivery", () -> c.dungeonSPlusImpossibleDelivery, v -> c.dungeonSPlusImpossibleDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the message above goes. Falls back to LOCAL automatically if you're not actually in a party."));
				rows.add(tip(boolRow("S+ Back In Reach", () -> c.dungeonSPlusBackEnabled, v -> c.dungeonSPlusBackEnabled = v, 0xFF55FF55), "The counterpart to S+ Impossible above: if the score ceiling recovers back over 300 afterward, say so once. Fires at most once per run either way, so it can never come twice."));
				rows.add(tip(stringRow("Message Text", () -> c.dungeonSPlusBackTemplate, v -> c.dungeonSPlusBackTemplate = v), "No placeholders."));
				rows.add(tip(cycleRow("Delivery", () -> c.dungeonSPlusBackDelivery, v -> c.dungeonSPlusBackDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the message above goes. Falls back to LOCAL automatically if you're not actually in a party."));

				rows.add(headerRow("Live Grade Milestone"));
				rows.add(tip(boolRow("Grade Milestone", () -> c.dungeonGradeMilestoneEnabled, v -> c.dungeonGradeMilestoneEnabled = v, 0xFF66DDFF), "Announce live the moment the run's grade first reaches a new tier (C/B/A/S/S+) - each tier only ever announced once per run, even if the grade fluctuates back down and up again."));
				rows.add(tip(stringRow("Message Text", () -> c.dungeonGradeMilestoneTemplate, v -> c.dungeonGradeMilestoneTemplate = v), "Placeholder: {grade}"));
				rows.add(tip(cycleRow("Delivery", () -> c.dungeonGradeMilestoneDelivery, v -> c.dungeonGradeMilestoneDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the message above goes. Falls back to LOCAL automatically if you're not actually in a party."));

				rows.add(headerRow("Self-Ready Reminder"));
				rows.add(tip(boolRow("Self-Ready Reminder", () -> c.dungeonSelfReadyReminderEnabled, v -> c.dungeonSelfReadyReminderEnabled = v, 0xFF55FF55), "Nudge yourself via the action bar the moment you're the only one left who hasn't readied up - personal, on-screen only, not sent to chat."));
				rows.add(tip(stringRow("Reminder Text", () -> c.dungeonSelfReadyReminderText, v -> c.dungeonSelfReadyReminderText = v), "No placeholders."));

				rows.add(headerRow("Time Limit Warning"));
				rows.add(tip(boolRow("Time Limit Warning", () -> c.dungeonTimeLimitWarningEnabled, v -> c.dungeonTimeLimitWarningEnabled = v, 0xFFFFAA00), "Warn in chat as the floor's time limit approaches, plus a personal on-screen countdown for the last 10 seconds."));
				rows.add(tip(cycleRow("Start At", () -> c.dungeonTimeLimitWarningStart, v -> c.dungeonTimeLimitWarningStart = v, new String[] { "60", "30", "15", "10" }), "Which checkpoint to start warning at (seconds remaining) - everything from here down to 10s fires. E.g. \"30\" fires at 30s and 10s but not 60s."));
				rows.add(tip(stringRow("Message Text", () -> c.dungeonTimeLimitWarningTemplate, v -> c.dungeonTimeLimitWarningTemplate = v), "Placeholder: {time} (e.g. \"30 seconds\")"));
				rows.add(tip(cycleRow("Delivery", () -> c.dungeonTimeLimitWarningDelivery, v -> c.dungeonTimeLimitWarningDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the checkpoint messages above go. The last-10-seconds countdown is always personal/on-screen regardless."));
				rows.add(tip(boolRow("Time Limit Exceeded", () -> c.dungeonTimeLimitExceededEnabled, v -> c.dungeonTimeLimitExceededEnabled = v, 0xFFFF5555), "Announce once the floor's time limit is actually exceeded - separate from the S+-impossible warning, which only fires once per run for whichever reason hits first."));
				rows.add(tip(stringRow("Message Text", () -> c.dungeonTimeLimitExceededTemplate, v -> c.dungeonTimeLimitExceededTemplate = v), "Placeholder: {floor}"));
				rows.add(tip(cycleRow("Delivery", () -> c.dungeonTimeLimitExceededDelivery, v -> c.dungeonTimeLimitExceededDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the message above goes."));

				rows.add(headerRow("Floor Requirement"));
				rows.add(tip(intStepRow("Target Floor", () -> c.dungeonTargetFloor, v -> c.dungeonTargetFloor = v, 0, 7, 1), "Which floor to benchmark the readiness/stats score and Floor Auto-Kick against (0 = Entrance, 7 = Floor VII) - Magical Power expectations are very different for Entrance vs. Floor VII, so this has to be set manually."));
				rows.add(tip(boolRow("Floor Auto-Kick", () -> c.dungeonFloorKickEnabled, v -> c.dungeonFloorKickEnabled = v, 0xFFFF5555), "Automatically /party kick a member who doesn't currently meet the REQUIREMENTS (Catacombs/Combat Skill, verified in-game) for the Target Floor above - not whether they've ever completed it before."));
				rows.add(tip(intStepRow("Required Floor", () -> c.dungeonFloorKickThreshold, v -> c.dungeonFloorKickThreshold = v, 0, 7, 1), "Minimum floor a member needs to be level-ELIGIBLE for right now (0 = just needs Combat Skill 15 for Entrance, 7 = needs Catacombs Skill 24 for Floor VII)."));
				rows.add(tip(stringRow("Message Text", () -> c.dungeonFloorKickMessageTemplate, v -> c.dungeonFloorKickMessageTemplate = v), "Placeholders: {player} {value} {threshold}"));
				rows.add(tip(cycleRow("Delivery", () -> c.dungeonFloorKickDelivery, v -> c.dungeonFloorKickDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the message above goes. Falls back to LOCAL automatically if you're not actually in a party."));
				rows.add(tip(boolRow("Max Floor Auto-Kick", () -> c.dungeonFloorKickMaxEnabled, v -> c.dungeonFloorKickMaxEnabled = v, 0xFFFF9955), "For carry parties: automatically /party kick a member who's ALREADY level-eligible for a floor higher than the Allowed Floor below - they don't need to be carried through it."));
				rows.add(tip(intStepRow("Allowed Floor", () -> c.dungeonFloorKickMaxThreshold, v -> c.dungeonFloorKickMaxThreshold = v, 0, 7, 1), "Maximum floor a member is allowed to already be level-eligible for - anyone eligible for something higher gets kicked by Max Floor Auto-Kick above."));
				rows.add(tip(stringRow("Message Text", () -> c.dungeonFloorKickMaxMessageTemplate, v -> c.dungeonFloorKickMaxMessageTemplate = v), "Placeholders: {player} {value} {threshold}"));
				rows.add(tip(cycleRow("Delivery", () -> c.dungeonFloorKickMaxDelivery, v -> c.dungeonFloorKickMaxDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the message above goes. Falls back to LOCAL automatically if you're not actually in a party."));

				// Floor Requirement above only checks whether a member currently MEETS the level
				// requirements; this checks whether they've actually COMPLETED that floor before
				// (Hypixel's own record), an independent signal.
				rows.add(headerRow("Floor Completion"));
				rows.add(tip(boolRow("Floor Completion Auto-Kick", () -> c.dungeonFloorCompletionKickEnabled, v -> c.dungeonFloorCompletionKickEnabled = v, 0xFFFF5555), "Automatically /party kick a member who hasn't ACTUALLY COMPLETED the Required Floor below yet - unlike Floor Auto-Kick above, this checks real completion, not just current level-eligibility."));
				rows.add(tip(intStepRow("Required Floor", () -> c.dungeonFloorCompletionKickThreshold, v -> c.dungeonFloorCompletionKickThreshold = v, 0, 7, 1), "Minimum floor a member needs to have ACTUALLY COMPLETED before (0 = Entrance, 7 = Floor VII)."));
				rows.add(tip(stringRow("Message Text", () -> c.dungeonFloorCompletionKickMessageTemplate, v -> c.dungeonFloorCompletionKickMessageTemplate = v), "Placeholders: {player} {value} {threshold}"));
				rows.add(tip(cycleRow("Delivery", () -> c.dungeonFloorCompletionKickDelivery, v -> c.dungeonFloorCompletionKickDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the message above goes. Falls back to LOCAL automatically if you're not actually in a party."));
				rows.add(tip(boolRow("Max Floor Completion Auto-Kick", () -> c.dungeonFloorCompletionKickMaxEnabled, v -> c.dungeonFloorCompletionKickMaxEnabled = v, 0xFFFF9955), "For carry parties: automatically /party kick a member who's ALREADY completed a floor higher than the Allowed Floor below - they have real experience past it and don't need a carry slot."));
				rows.add(tip(intStepRow("Allowed Floor", () -> c.dungeonFloorCompletionKickMaxThreshold, v -> c.dungeonFloorCompletionKickMaxThreshold = v, 0, 7, 1), "Maximum floor a member is allowed to have already completed - anyone who's completed something higher gets kicked by Max Floor Completion Auto-Kick above."));
				rows.add(tip(stringRow("Message Text", () -> c.dungeonFloorCompletionKickMaxMessageTemplate, v -> c.dungeonFloorCompletionKickMaxMessageTemplate = v), "Placeholders: {player} {value} {threshold}"));
				rows.add(tip(cycleRow("Delivery", () -> c.dungeonFloorCompletionKickMaxDelivery, v -> c.dungeonFloorCompletionKickMaxDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the message above goes. Falls back to LOCAL automatically if you're not actually in a party."));
			}
			case FUN -> {
				// Kill Tracker's toggle/delivery choice and Death Double (both variants) removed
				// entirely - the Spell kill announcement is always on and always LOCAL now, hardcoded
				// at its call site (MagicMissileManager#announceMissileKill) rather than
				// user-configurable, and Death Double no longer exists at all (see
				// combat/DeathDoubleManager.java's deletion). Death Recap moved to the Dungeons tab
				// (see case DUNGEONS below).
				if (PermissionsManager.has("spell")) {
					rows.add(headerRow("Spell"));
					rows.add(tip(cosmeticColorRow("Spell", () -> c.magicMissileEnabled, v -> c.magicMissileEnabled = v, () -> c.magicMissileColor, v -> c.magicMissileColor = v), "Cast by punching empty air with an empty hand - shoots a small particle projectile that bursts on impact. Drops a collectible \"Spell Essence\" item on a kill too - always on, no separate toggle."));
				}
			}
			case GENERAL -> {
				rows.add(headerRow("Hotkeys"));
				rows.add(tip(keybindRow("Open This Menu", SkyMellooClient.getOpenConfigKey()), "Click, then press any key to rebind - same as vanilla's Controls > Key Binds, just without leaving this screen. Escape cancels without changing it."));
				rows.add(headerRow("Menu"));
				rows.add(tip(boolRow("SkyMelloo Menu Item", () -> c.skyMellooMenuItemEnabled, v -> c.skyMellooMenuItemEnabled = v, 0xFF66DDFF), "A fake \"SkyMelloo Menu\" item in hotbar slot 8 whenever that slot is empty - right-click to open a chest-style menu for SkyMelloo's own settings, the same way Hypixel's own SkyBlock Menu item (slot 9) works. Client-side only, never overwrites a real item actually in that slot."));
				rows.add(headerRow("HUD"));
				rows.add(tip(boolRow("Health/Mana Bars", () -> c.healthManaBarsEnabled, v -> c.healthManaBarsEnabled = v, 0xFF55DD55), "Master switch - a sleek custom health (green, gold where absorption extends past normal max) and mana (light blue, %) bar pair. Flashes white briefly where health was just lost. Position set via the HUD layout editor (default J)."));
				rows.add(tip(boolRow("Health Bar", () -> c.healthBarEnabled, v -> c.healthBarEnabled = v, 0xFF55DD55), "Show the health bar specifically."));
				rows.add(tip(boolRow("Mana Bar", () -> c.manaBarEnabled, v -> c.manaBarEnabled = v, 0xFF55CCFF), "Show the mana bar specifically."));
				rows.add(tip(boolRow("Side By Side", () -> c.healthManaBarsSideBySide, v -> c.healthManaBarsSideBySide = v, 0xFFAAAAAA), "Mana bar next to the health bar instead of stacked below it."));
				rows.add(tip(boolRow("Hide Hypixel's Native Bar", () -> c.hideNativeStatusActionBarEnabled, v -> c.hideNativeStatusActionBarEnabled = v, 0xFFAAAAAA), "Hides Hypixel's own plain Health/Defense/Mana actionbar text above the hotbar, since the custom bars above already show the same info. Turn off to see both at once."));
				rows.add(headerRow("Chat"));
				rows.add(tip(boolRow("Mention Highlight", () -> c.chatMentionHighlightEnabled, v -> c.chatMentionHighlightEnabled = v, 0xFFFFD700), "Highlight (bold + colored marker) any chat message that mentions your own username, and play a short sound - so a mention doesn't slip by unnoticed."));
				rows.add(tip(colorRow("Mention Highlight Color", () -> c.chatMentionHighlightColor, v -> c.chatMentionHighlightColor = v), "Marker color for a chat mention of your own username."));
			}
			case DEBUG -> {
				rows.add(headerRow("Debug Messages"));
				rows.add(tip(boolRow("Debug Messages", () -> c.debugMessagesEnabled, v -> c.debugMessagesEnabled = v, 0xFFAAAAAA), "Master switch - turns every category below on/off at once. Command replies and safety warnings always show regardless. Every row below only controls whether that system prints extra diagnostic chat messages for troubleshooting - none of them turn the actual feature on or off (e.g. \"Party Debug Log\" does NOT toggle the Party HUD, it just logs what the Party HUD is doing behind the scenes)."));
				rows.add(tip(boolRow("Sync Debug Log", () -> c.debugSync, v -> c.debugSync = v, 0xFF66DDFF), "Prints friend list / party sync results to chat. Doesn't affect the sync itself."));
				rows.add(tip(cycleRow("Sync Log Delivery", () -> c.debugSyncDelivery, v -> c.debugSyncDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the Sync debug messages above go. Falls back to LOCAL automatically if you're not actually in a party."));
				if (WhitelistManager.isAdmin()) {
					rows.add(tip(boolRow("Permissions Debug Log", () -> c.debugPermissions, v -> c.debugPermissions = v, 0xFF55FF55), "Prints whitelist checks and per-feature permission fetches to chat. Admin-only - these messages never show to normal users regardless of this setting."));
					rows.add(tip(cycleRow("Permissions Log Delivery", () -> c.debugPermissionsDelivery, v -> c.debugPermissionsDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the Permissions debug messages above go. Falls back to LOCAL automatically if you're not actually in a party."));
				}
				rows.add(tip(boolRow("Cloud Sync Debug Log", () -> c.debugCloudSync, v -> c.debugCloudSync = v, 0xFF5599FF), "Prints cloud settings push/pull results to chat. Doesn't affect the sync itself."));
				rows.add(tip(cycleRow("Cloud Sync Log Delivery", () -> c.debugCloudSyncDelivery, v -> c.debugCloudSyncDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the Cloud Sync debug messages above go. Falls back to LOCAL automatically if you're not actually in a party."));
				rows.add(tip(boolRow("Presence Debug Log", () -> c.debugPresence, v -> c.debugPresence = v, 0xFFAA33FF), "Prints mod-presence reporting activity (detecting other SkyMelloo users nearby) to chat."));
				rows.add(tip(cycleRow("Presence Log Delivery", () -> c.debugPresenceDelivery, v -> c.debugPresenceDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the Presence debug messages above go. Falls back to LOCAL automatically if you're not actually in a party."));
				rows.add(tip(boolRow("Party Debug Log", () -> c.debugParty, v -> c.debugParty = v, 0xFF55FFFF), "Prints the Party HUD's username/Magical Power resolution steps to chat. Does NOT toggle the Party HUD itself - that's the \"Party HUD\" option under Dungeons."));
				rows.add(tip(cycleRow("Party Log Delivery", () -> c.debugPartyDelivery, v -> c.debugPartyDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the Party debug messages above go. Falls back to LOCAL automatically if you're not actually in a party."));
				rows.add(tip(boolRow("Dungeon Debug Log", () -> c.debugDungeon, v -> c.debugDungeon = v, 0xFFAA33FF), "Prints the dungeon run tracker's floor/cleared%/time detection and run start/end to chat. Does NOT toggle the Score HUD/Run Report/etc. themselves - those are their own options under Dungeons."));
				rows.add(tip(cycleRow("Dungeon Log Delivery", () -> c.debugDungeonDelivery, v -> c.debugDungeonDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the Dungeon debug messages above go. Falls back to LOCAL automatically if you're not actually in a party."));
				rows.add(tip(boolRow("Staff Debug Log", () -> c.debugStaff, v -> c.debugStaff = v, 0xFFFFD700), "Prints staff-encounter scanning activity (/sm hitstaff) to chat."));
				rows.add(tip(cycleRow("Staff Log Delivery", () -> c.debugStaffDelivery, v -> c.debugStaffDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), "Where the Staff debug messages above go. Falls back to LOCAL automatically if you're not actually in a party."));
				rows.add(tip(boolRow("Mana Debug", () -> c.manaDebugEnabled, v -> c.manaDebugEnabled = v, 0xFF55CCFF), "Shows every (color, text) run actually seen in Hypixel's actionbar below the mana bar - for tracking down why mana detection isn't matching."));

				rows.add(headerRow("Connection"));
				rows.add(tip(boolRow("Connection Quality Check", () -> c.connectionQualityCheckEnabled, v -> c.connectionQualityCheckEnabled = v, 0xFFFFCC00), "On connecting to a server, show a \"Connecting...\" title and check ping stability for the first 5 seconds."));
				rows.add(tip(boolRow("Auto-Reconnect", () -> c.autoReconnectEnabled, v -> c.autoReconnectEnabled = v, 0xFFFFCC00), "Automatically rejoin Hypixel a few seconds after an unexpected disconnect - Hypixel only, capped at 3 attempts in a row so a real kick/ban doesn't turn into hammering reconnects forever."));
			}
			case CLOUD -> {
				rows.add(headerRow("Account"));
				rows.add(infoRow("sky.melloo.me", this::connectionStatusLabel, this::connectionStatusColor));

				rows.add(headerRow("Cloud Sync"));
				rows.add(tip(boolRow("Cloud Sync", () -> c.cloudSyncEnabled, v -> c.cloudSyncEnabled = v, 0xFF5599FF), "Sync your settings to sky.melloo.me while your account is linked - a new device/reinstall under the same account starts from your existing settings instead of all defaults. Requires a linked account in addition to this toggle."));
				rows.add(tip(actionRow("Push Now", "Upload", () -> CloudSyncManager.push(Minecraft.getInstance())), "Upload your current settings right now, without waiting for this menu to close. Requires a linked account."));
				rows.add(tip(actionRow("Pull Now", "Download", () -> CloudSyncManager.forcePull(Minecraft.getInstance(), this::buildRows)), "Download your cloud settings right now and overwrite everything here - use this if you want to discard local changes."));

				// Everything below is stuff other people (or the public sky.melloo.me website) can see
				// about you - grouped here together rather than scattered under Dungeons/General, so
				// it's obvious at a glance everything this account shares and there's one place to shut
				// it all off for privacy.
				rows.add(headerRow("Sharing & Privacy"));
				// Renamed from "Presence Sharing" - this and Dungeon Sync below are now presented as
				// the two peer halves of sharing: this one is the lightweight "general sync" (online
				// status + which world/island/dungeon floor you're in), Dungeon Sync is the heavy
				// per-tick dungeon-run detail. The underlying field is still presenceSharingEnabled -
				// only the label/description changed, not the config key.
				rows.add(tip(boolRow("Sync", () -> c.presenceSharingEnabled, v -> c.presenceSharingEnabled = v, 0xFFAA33FF), "Master switch for showing up as \"online\" to anyone else, and sharing which world/island/dungeon floor you're currently in - other SkyMelloo users' mod-user detection, the Credits online dot, the website's public online-user count, and your friends list entry on sky.melloo.me all depend on this. Off hides you from all of these, but you can still see OTHERS who have this on. Doesn't affect Cloud Sync above (that's just your own settings, never shown to anyone else)."));
				rows.add(tip(stringRow("Status", () -> c.customStatusText, v -> c.customStatusText = v),
						"Shown next to your name to other SkyMelloo users nearby, via sky.melloo.me. Leave empty to show nothing. Requires Sync above."));
				rows.add(tip(boolRow("Dungeon Sync", () -> c.dungeonSyncEnabled, v -> c.dungeonSyncEnabled = v, 0xFF66DDFF), "Share your current room/floor/secrets/score with party members also running SkyMelloo, and with the sky.melloo.me Dungeon lookup page - relayed over sky.melloo.me, invisible to anyone not running the mod. Room/secrets detail needs Skyblocker installed too (see Show Room Secrets under Dungeons). Requires Sync above. Reports once a second, fixed - not adjustable (see SkyMellooConfig's own comment on why)."));
			}
		}
		return rows;
	}

	private RowFactory headerRow(String label) {
		return (x, y, w, h) -> new HeaderRowWidget(x, y, w, h, label);
	}

	private RowFactory boolRow(String label, BooleanSupplier getter, Consumer<Boolean> setter, int accent) {
		return (x, y, w, h) -> new BoolRowWidget(x, y, w, h, label, getter, setter, accent);
	}

	/** A cosmetic toggle with a color swatch inline on the same row, so it can never get split
	 * across a column break the way two separate stacked rows could. */
	private RowFactory cosmeticColorRow(String label, BooleanSupplier getter, Consumer<Boolean> setter, Supplier<Color> colorGetter, Consumer<Color> colorSetter) {
		return (x, y, w, h) -> new CosmeticFullRowWidget(x, y, w, h, label, getter, setter, () -> colorGetter.get().getRGB(), colorGetter, colorSetter);
	}

	private RowFactory colorRow(String label, Supplier<Color> getter, Consumer<Color> setter) {
		return (x, y, w, h) -> new ColorRowWidget(x, y, w, h, label, getter, setter);
	}

	private RowFactory stringRow(String label, Supplier<String> getter, Consumer<String> setter) {
		return (x, y, w, h) -> new StringRowWidget(x, y, w, h, label, getter, setter);
	}

	private RowFactory intStepRow(String label, IntSupplier getter, IntConsumer setter, int min, int max, int step) {
		return (x, y, w, h) -> new IntStepRowWidget(x, y, w, h, label, getter, setter, min, max, step);
	}

	/** Like {@link #intStepRow}, but opens a text-input screen instead of cycling by a fixed step - for values worth typing directly (e.g. an MP threshold in the thousands). */
	private RowFactory intTextRow(String label, IntSupplier getter, IntConsumer setter, int min, int max) {
		return (x, y, w, h) -> new IntTextRowWidget(x, y, w, h, label, getter, setter, min, max);
	}

	/** A row that cycles through a fixed list of string options on click - for small enum-like settings that don't need a full text input screen. */
	private RowFactory cycleRow(String label, Supplier<String> getter, Consumer<String> setter, String[] options) {
		return (x, y, w, h) -> new CycleRowWidget(x, y, w, h, label, getter, setter, options);
	}

	/** A plain action button row - label on the left, button text on the right, runs {@code onClick} when clicked. */
	private RowFactory actionRow(String label, String buttonText, Runnable onClick) {
		return (x, y, w, h) -> new ActionRowWidget(x, y, w, h, label, buttonText, onClick);
	}

	/** A rebindable hotkey row - shows the currently bound key, click then press any key (Escape cancels) to change it, right here instead of vanilla's separate Controls screen. */
	private RowFactory keybindRow(String label, KeyMapping mapping) {
		return (x, y, w, h) -> new KeybindRowWidget(x, y, w, h, label, mapping);
	}

	/** A non-interactive row - label on the left, a colored status value on the right. */
	private RowFactory infoRow(String label, Supplier<String> valueGetter, IntSupplier colorGetter) {
		return (x, y, w, h) -> new InfoRowWidget(x, y, w, h, label, valueGetter, colorGetter);
	}

	private String connectionStatusLabel() {
		if (WhitelistManager.isAdmin()) {
			return "Connected (Admin)";
		}
		if (WhitelistManager.isAllowed()) {
			return "Connected";
		}
		return "Not connected";
	}

	private int connectionStatusColor() {
		return WhitelistManager.isAllowed() ? 0xFF55FF55 : 0xFFFF5555;
	}

	/** Wraps any row factory with a hover tooltip explaining what the setting does. */
	private RowFactory tip(RowFactory factory, String description) {
		return (x, y, w, h) -> {
			AbstractWidget widget = factory.create(x, y, w, h);
			widget.setTooltip(Tooltip.create(Component.literal(description)));
			return widget;
		};
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return capturingKeybind == null;
	}

	/** While {@link #capturingKeybind} is set, the next key press (including Escape, which just cancels rather than closing the screen) binds it - same convention as vanilla's own Controls > Key Binds screen. */
	@Override
	public boolean keyPressed(KeyEvent event) {
		if (capturingKeybind != null) {
			if (event.key() != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
				capturingKeybind.setKey(InputConstants.getKey(event));
			}
			capturingKeybind = null;
			KeyMapping.resetMapping();
			Minecraft.getInstance().options.save();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void removed() {
		// Push whenever the menu closes - this screen is the only way to change these settings
		// now (chat commands were removed), so this covers essentially every real change.
		CloudSyncManager.push(Minecraft.getInstance());
		super.removed();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseX >= previewX1 && mouseX <= previewX2 && mouseY >= previewY1 && mouseY <= previewY2) {
			previewZoom = (float) Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, previewZoom + scrollY * 0.1));
			return true;
		}
		if (mouseX >= listX1 && mouseX <= listX2 && mouseY >= tabBarY && mouseY <= tabBarY + tabBarH && tabBarMaxScrollX > 0) {
			int next = (int) Math.round(tabScrollX - scrollY * 40);
			tabScrollX = Math.max(0, Math.min(next, tabBarMaxScrollX));
			buildTabBar();
			return true;
		}
		if (mouseX >= listX1 && mouseX <= listX2 && mouseY >= listTop && mouseY <= listBottom && maxScroll > 0) {
			scrollBy(scrollY);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		// A dropdown's own widgets (swatches/options) only cover their exact button rectangles -
		// the panel background and gaps between them aren't widgets at all, so without this check
		// a click there (or anywhere else outside the dropdown) fell straight through to whatever
		// row happened to be underneath instead of just closing the dropdown like a normal menu.
		if (!dropdownWidgets.isEmpty()) {
			double mouseX = event.x();
			double mouseY = event.y();
			boolean insideDropdown = mouseX >= dropdownX && mouseX <= dropdownX + dropdownW && mouseY >= dropdownY && mouseY <= dropdownY + dropdownH;
			if (!insideDropdown) {
				closeColorDropdown();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private void closeColorDropdown() {
		for (AbstractWidget widget : dropdownWidgets) {
			removeWidget(widget);
		}
		dropdownWidgets.clear();
		openDropdownOwner = null;
	}

	private void openColorDropdown(AbstractWidget owner, int anchorX, int anchorY, Consumer<Color> onPick) {
		closeColorDropdown();
		openDropdownOwner = owner;

		int cols = 6;
		int swatch = 10;
		int gap = 2;
		int rows = (int) Math.ceil(COLOR_PALETTE.length / (double) cols);

		dropdownW = cols * (swatch + gap) + gap;
		dropdownH = rows * (swatch + gap) + gap;
		dropdownX = Math.min(anchorX, this.width - MARGIN - dropdownW);
		dropdownY = Math.min(anchorY, this.height - MARGIN - dropdownH);

		for (int i = 0; i < COLOR_PALETTE.length; i++) {
			int col = i % cols;
			int row = i / cols;
			int x = dropdownX + gap + col * (swatch + gap);
			int y = dropdownY + gap + row * (swatch + gap);
			int color = COLOR_PALETTE[i];
			SwatchWidget widget = new SwatchWidget(x, y, swatch, swatch, color, () -> {
				onPick.accept(new Color(color, true));
				closeColorDropdown();
			});
			dropdownWidgets.add(widget);
			addRenderableWidget(widget);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
		gg.fill(0, 0, this.width, this.height, PANEL_BG);
		gg.centeredText(this.font, "SkyMelloo Settings", this.width / 2, MARGIN + 6, TEXT_ON);

		gg.fill(previewX1, previewY1, previewX2, previewY2, PREVIEW_BG);
		gg.outline(previewX1, previewY1, previewX2 - previewX1, previewY2 - previewY1, 0x55FF6EC7);

		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			int size = (int) (((Math.min(previewX2 - previewX1, previewY2 - previewY1) / 2) - 10) * previewZoom);
			int centerX = (previewX1 + previewX2) / 2;
			// The entity render helper doesn't clip itself to the box it's given - zooming in (or a
			// tall skin) can poke the model out past the frame, so crop it to the box explicitly.
			gg.enableScissor(previewX1, previewY1, previewX2, previewY2);
			InventoryScreen.extractEntityInInventoryFollowsMouse(
					gg,
					previewX1 + 4, previewY1 + 4, previewX2 - 4, previewY2 - 4,
					size, 0.0625F,
					(float) mouseX, (float) mouseY,
					client.player
			);
			gg.disableScissor();
			gg.centeredText(this.font, "Ziehen zum Drehen, Scrollen zum Zoomen", centerX, previewY2 - 12, TEXT_OFF);
		}

		if (maxScroll > 0) {
			int trackX = listX2 - 3;
			int trackHeight = listBottom - listTop;
			gg.fill(trackX, listTop, trackX + 3, listBottom, 0x30FFFFFF);
			int thumbHeight = Math.max(14, trackHeight * trackHeight / (trackHeight + maxScroll));
			int thumbY = listTop + (int) ((trackHeight - thumbHeight) * (scrollOffset / (double) maxScroll));
			gg.fill(trackX, thumbY, trackX + 3, thumbY + thumbHeight, 0xFFFF6EC7);
		}

		if (tabBarMaxScrollX > 0) {
			int trackY = tabBarY + tabBarH + 1;
			int trackWidth = listX2 - listX1;
			gg.fill(listX1, trackY, listX2, trackY + 2, 0x30FFFFFF);
			int thumbWidth = Math.max(20, trackWidth * trackWidth / (trackWidth + tabBarMaxScrollX));
			int thumbX = listX1 + (int) ((trackWidth - thumbWidth) * (tabScrollX / (double) tabBarMaxScrollX));
			gg.fill(thumbX, trackY, thumbX + thumbWidth, trackY + 2, 0xFFFF6EC7);
		}

		if (!dropdownWidgets.isEmpty()) {
			gg.fill(dropdownX, dropdownY, dropdownX + dropdownW, dropdownY + dropdownH, 0xEE151520);
			gg.outline(dropdownX, dropdownY, dropdownW, dropdownH, 0xFFFF6EC7);
		}

		super.extractRenderState(gg, mouseX, mouseY, partialTick);
	}

	private final class TabButtonWidget extends AbstractWidget {
		private final Tab tab;

		TabButtonWidget(int x, int y, int width, int height, Tab tab) {
			super(x, y, width, height, Component.literal(tab.label));
			this.tab = tab;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
			boolean active = tab == activeTab;
			int x1 = getX();
			int y1 = getY();
			int x2 = getX() + getWidth();
			int y2 = getY() + getHeight();

			// Scrolling the tab bar can push a tab partially past listX1, into the character
			// preview box on the left - clip it there instead of letting it overlap on top.
			// (enableScissor(x1,y1,x2,y2) really does take corner coordinates, verified via
			// javap - unlike gg.outline(), which takes width/height and was the actual bug.)
			gg.enableScissor(listX1, tabBarY, listX2, tabBarY + tabBarH);

			int bg = active ? 0x40FF6EC7 : (this.isHovered() ? 0x20FFFFFF : 0x00000000);
			if (bg != 0) {
				gg.fill(x1, y1, x2, y2, bg);
			}
			if (active) {
				gg.fill(x1, y2 - 2, x2, y2, 0xFFFF6EC7);
			}
			gg.centeredText(Minecraft.getInstance().font, tab.label, (x1 + x2) / 2, y1 + (getHeight() - 8) / 2, active ? TEXT_ON : TEXT_OFF);

			gg.disableScissor();
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			if (tab != activeTab) {
				activeTab = tab;
				scrollOffset = 0;
				buildRows();
			}
		}
	}

	/** A non-interactive section label (e.g. "Players", "Minigame") shown above a cluster of related rows. */
	private final class HeaderRowWidget extends AbstractWidget {
		private final String label;

		HeaderRowWidget(int x, int y, int width, int height, String label) {
			super(x, y, width, height, Component.literal(label));
			this.label = label;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
			int x1 = getX();
			int y2 = getY() + getHeight();
			gg.text(Minecraft.getInstance().font, label.toUpperCase(java.util.Locale.ROOT), x1 + 2, y2 - 9, 0xFFFF6EC7);
			gg.fill(x1 + 2, y2 - 1, getX() + getWidth(), y2, 0x40FF6EC7);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			// Not interactive - just a label.
		}
	}

	private final class BoolRowWidget extends AbstractWidget {
		private final String label;
		private final BooleanSupplier getter;
		private final Consumer<Boolean> setter;
		private final int accent;

		BoolRowWidget(int x, int y, int width, int height, String label, BooleanSupplier getter, Consumer<Boolean> setter, int accent) {
			super(x, y, width, height, Component.literal(label));
			this.label = label;
			this.getter = getter;
			this.setter = setter;
			this.accent = accent;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
			boolean enabled = getter.getAsBoolean();
			int x1 = getX();
			int y1 = getY();
			int x2 = getX() + getWidth();
			int y2 = getY() + getHeight();

			if (this.isHovered()) {
				gg.fill(x1, y1, x2, y2, ROW_BG_HOVER_BONUS);
			}

			int dotSize = 6;
			int dotY = y1 + (getHeight() - dotSize) / 2;
			gg.fill(x1 + 2, dotY, x1 + 2 + dotSize, dotY + dotSize, enabled ? (accent | 0xFF000000) : 0xFF555555);
			gg.text(Minecraft.getInstance().font, label, x1 + 2 + dotSize + 6, y1 + (getHeight() - 8) / 2, enabled ? TEXT_ON : TEXT_OFF);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			setter.accept(!getter.getAsBoolean());
			SkyMellooConfig.HANDLER.save();
		}
	}

	private final class ColorRowWidget extends AbstractWidget {
		private final String label;
		private final Supplier<Color> getter;
		private final Consumer<Color> setter;

		ColorRowWidget(int x, int y, int width, int height, String label, Supplier<Color> getter, Consumer<Color> setter) {
			super(x, y, width, height, Component.literal(label));
			this.label = label;
			this.getter = getter;
			this.setter = setter;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
			int x1 = getX();
			int y1 = getY();
			int x2 = getX() + getWidth();
			int y2 = getY() + getHeight();

			if (this.isHovered()) {
				gg.fill(x1, y1, x2, y2, ROW_BG_HOVER_BONUS);
			}

			int dotColor = getter.get().getRGB() | 0xFF000000;
			int dotSize = 6;
			int dotY = y1 + (getHeight() - dotSize) / 2;
			gg.fill(x1 + 2, dotY, x1 + 2 + dotSize, dotY + dotSize, dotColor);
			boolean open = openDropdownOwner == this;
			gg.text(Minecraft.getInstance().font, label, x1 + 2 + dotSize + 6, y1 + (getHeight() - 8) / 2, open ? 0xFFFF6EC7 : TEXT_ON);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			if (openDropdownOwner == this) {
				closeColorDropdown();
				return;
			}
			openColorDropdown(this, getX(), getY() + getHeight() + 2, color -> {
				setter.accept(color);
				SkyMellooConfig.HANDLER.save();
			});
		}
	}

	/**
	 * A cosmetic entry as ONE row: toggle dot + name on the left, an optional color swatch on the
	 * right of the same row. Keeping name/toggle/color together in a single widget means they can
	 * never end up split across a column break the way two separately-flowed rows could.
	 */
	private final class CosmeticFullRowWidget extends AbstractWidget {
		private static final int SWATCH_SIZE = 10;

		private final String label;
		private final BooleanSupplier getter;
		private final Consumer<Boolean> setter;
		private final IntSupplier accent;
		private final Supplier<Color> colorGetter;
		private final Consumer<Color> colorSetter;

		CosmeticFullRowWidget(int x, int y, int width, int height, String label, BooleanSupplier getter, Consumer<Boolean> setter,
				IntSupplier accent, Supplier<Color> colorGetter, Consumer<Color> colorSetter) {
			super(x, y, width, height, Component.literal(label));
			this.label = label;
			this.getter = getter;
			this.setter = setter;
			this.accent = accent;
			this.colorGetter = colorGetter;
			this.colorSetter = colorSetter;
		}

		private int swatchX() {
			return getX() + getWidth() - SWATCH_SIZE - 4;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
			boolean enabled = getter.getAsBoolean();
			int x1 = getX();
			int y1 = getY();
			int x2 = getX() + getWidth();
			int y2 = getY() + getHeight();

			if (this.isHovered()) {
				gg.fill(x1, y1, x2, y2, ROW_BG_HOVER_BONUS);
			}

			int dotSize = 6;
			int dotY = y1 + (getHeight() - dotSize) / 2;
			gg.fill(x1 + 2, dotY, x1 + 2 + dotSize, dotY + dotSize, enabled ? (accent.getAsInt() | 0xFF000000) : 0xFF555555);
			gg.text(Minecraft.getInstance().font, label, x1 + 2 + dotSize + 6, y1 + (getHeight() - 8) / 2, enabled ? TEXT_ON : TEXT_OFF);

			if (colorGetter != null) {
				int sx = swatchX();
				int sy = y1 + (getHeight() - SWATCH_SIZE) / 2;
				int col = colorGetter.get().getRGB() | 0xFF000000;
				gg.fill(sx, sy, sx + SWATCH_SIZE, sy + SWATCH_SIZE, col);
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			if (colorGetter != null && event.x() >= swatchX() - 2) {
				if (openDropdownOwner == this) {
					closeColorDropdown();
					return;
				}
				openColorDropdown(this, getX(), getY() + getHeight() + 2, color -> {
					colorSetter.accept(color);
					SkyMellooConfig.HANDLER.save();
				});
				return;
			}
			setter.accept(!getter.getAsBoolean());
			SkyMellooConfig.HANDLER.save();
		}
	}

	private final class SwatchWidget extends AbstractWidget {
		private final int color;
		private final Runnable onPick;

		SwatchWidget(int x, int y, int width, int height, int color, Runnable onPick) {
			super(x, y, width, height, Component.literal("color swatch"));
			this.color = color;
			this.onPick = onPick;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
			int x1 = getX();
			int y1 = getY();
			int x2 = getX() + getWidth();
			int y2 = getY() + getHeight();
			gg.fill(x1, y1, x2, y2, color | 0xFF000000);
			if (this.isHovered()) {
				gg.fill(x1, y1, x2, y2, 0x50FFFFFF);
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			onPick.run();
		}
	}

	private final class StringRowWidget extends AbstractWidget {
		private final String label;
		private final Supplier<String> getter;
		private final Consumer<String> setter;

		StringRowWidget(int x, int y, int width, int height, String label, Supplier<String> getter, Consumer<String> setter) {
			super(x, y, width, height, Component.literal(label));
			this.label = label;
			this.getter = getter;
			this.setter = setter;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
			int x1 = getX();
			int y1 = getY();
			int x2 = getX() + getWidth();
			int y2 = getY() + getHeight();

			if (this.isHovered()) {
				gg.fill(x1, y1, x2, y2, ROW_BG_HOVER_BONUS);
			}

			int dotSize = 6;
			int dotY = y1 + (getHeight() - dotSize) / 2;
			gg.fill(x1 + 2, dotY, x1 + 2 + dotSize, dotY + dotSize, 0xFF5599FF);

			var font = Minecraft.getInstance().font;
			String value = getter.get();
			String shown = value.isEmpty() ? "(leer)" : (value.length() > 14 ? value.substring(0, 12) + "…" : value);
			int valueWidth = font.width(shown);

			gg.text(font, label, x1 + 2 + dotSize + 6, y1 + (getHeight() - 8) / 2, TEXT_ON);
			gg.text(font, shown, x2 - valueWidth - 4, y1 + (getHeight() - 8) / 2, TEXT_OFF);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			Minecraft.getInstance().setScreen(new StringInputScreen(SkyMellooSettingsScreen.this, label, getter.get(), value -> {
				setter.accept(value);
				SkyMellooConfig.HANDLER.save();
			}));
		}
	}

	private final class IntStepRowWidget extends AbstractWidget {
		private final String label;
		private final IntSupplier getter;
		private final IntConsumer setter;
		private final int min, max, step;

		IntStepRowWidget(int x, int y, int width, int height, String label, IntSupplier getter, IntConsumer setter, int min, int max, int step) {
			super(x, y, width, height, Component.literal(label));
			this.label = label;
			this.getter = getter;
			this.setter = setter;
			this.min = min;
			this.max = max;
			this.step = step;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
			int x1 = getX();
			int y1 = getY();
			int x2 = getX() + getWidth();
			int y2 = getY() + getHeight();

			if (this.isHovered()) {
				gg.fill(x1, y1, x2, y2, ROW_BG_HOVER_BONUS);
			}

			int dotSize = 6;
			int dotY = y1 + (getHeight() - dotSize) / 2;
			gg.fill(x1 + 2, dotY, x1 + 2 + dotSize, dotY + dotSize, 0xFF55FFFF);

			var font = Minecraft.getInstance().font;
			String shown = String.valueOf(getter.getAsInt());
			int valueWidth = font.width(shown);

			gg.text(font, label, x1 + 2 + dotSize + 6, y1 + (getHeight() - 8) / 2, TEXT_ON);
			gg.text(font, shown, x2 - valueWidth - 4, y1 + (getHeight() - 8) / 2, TEXT_OFF);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			int next = getter.getAsInt() + step;
			if (next > max) {
				next = min;
			}
			setter.accept(next);
			SkyMellooConfig.HANDLER.save();
		}
	}

	/** Like {@link IntStepRowWidget}, but clicking opens a text-input screen to type the value directly instead of cycling by a fixed step. */
	private final class IntTextRowWidget extends AbstractWidget {
		private final String label;
		private final IntSupplier getter;
		private final IntConsumer setter;
		private final int min, max;

		IntTextRowWidget(int x, int y, int width, int height, String label, IntSupplier getter, IntConsumer setter, int min, int max) {
			super(x, y, width, height, Component.literal(label));
			this.label = label;
			this.getter = getter;
			this.setter = setter;
			this.min = min;
			this.max = max;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
			int x1 = getX();
			int y1 = getY();
			int x2 = getX() + getWidth();
			int y2 = getY() + getHeight();

			if (this.isHovered()) {
				gg.fill(x1, y1, x2, y2, ROW_BG_HOVER_BONUS);
			}

			int dotSize = 6;
			int dotY = y1 + (getHeight() - dotSize) / 2;
			gg.fill(x1 + 2, dotY, x1 + 2 + dotSize, dotY + dotSize, 0xFF55FFFF);

			var font = Minecraft.getInstance().font;
			String shown = String.valueOf(getter.getAsInt());
			int valueWidth = font.width(shown);

			gg.text(font, label, x1 + 2 + dotSize + 6, y1 + (getHeight() - 8) / 2, TEXT_ON);
			gg.text(font, shown, x2 - valueWidth - 4, y1 + (getHeight() - 8) / 2, TEXT_OFF);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			Minecraft.getInstance().setScreen(new StringInputScreen(SkyMellooSettingsScreen.this, label, String.valueOf(getter.getAsInt()), value -> {
				try {
					int parsed = Integer.parseInt(value.trim());
					setter.accept(Math.max(min, Math.min(max, parsed)));
					SkyMellooConfig.HANDLER.save();
				} catch (NumberFormatException ignored) {
					// not a valid number - leave the existing value untouched
				}
			}));
		}
	}

	/** A row that cycles through a small fixed set of string options on click - e.g. "MP" / "LEVEL". */
	private final class CycleRowWidget extends AbstractWidget {
		private final String label;
		private final Supplier<String> getter;
		private final Consumer<String> setter;
		private final String[] options;

		CycleRowWidget(int x, int y, int width, int height, String label, Supplier<String> getter, Consumer<String> setter, String[] options) {
			super(x, y, width, height, Component.literal(label));
			this.label = label;
			this.getter = getter;
			this.setter = setter;
			this.options = options;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
			int x1 = getX();
			int y1 = getY();
			int x2 = getX() + getWidth();
			int y2 = getY() + getHeight();

			if (this.isHovered()) {
				gg.fill(x1, y1, x2, y2, ROW_BG_HOVER_BONUS);
			}

			var font = Minecraft.getInstance().font;
			String shown = getter.get();
			int valueWidth = font.width(shown);

			gg.text(font, label, x1 + 2, y1 + (getHeight() - 8) / 2, TEXT_ON);
			gg.text(font, shown, x2 - valueWidth - 4, y1 + (getHeight() - 8) / 2, 0xFFFF6EC7);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			String current = getter.get();
			int index = -1;
			for (int i = 0; i < options.length; i++) {
				if (options[i].equalsIgnoreCase(current)) {
					index = i;
					break;
				}
			}
			String next = options[(index + 1) % options.length];
			setter.accept(next);
			SkyMellooConfig.HANDLER.save();
		}
	}

	/** A plain action button row - label on the left, a button-styled text on the right, no toggle state of its own. */
	private final class ActionRowWidget extends AbstractWidget {
		private final String label;
		private final String buttonText;
		private final Runnable onClick;

		ActionRowWidget(int x, int y, int width, int height, String label, String buttonText, Runnable onClick) {
			super(x, y, width, height, Component.literal(label));
			this.label = label;
			this.buttonText = buttonText;
			this.onClick = onClick;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
			int x1 = getX();
			int y1 = getY();
			int x2 = getX() + getWidth();
			int y2 = getY() + getHeight();

			if (this.isHovered()) {
				gg.fill(x1, y1, x2, y2, ROW_BG_HOVER_BONUS);
			}

			var font = Minecraft.getInstance().font;
			String shownButton = "[ " + buttonText + " ]";
			int buttonWidth = font.width(shownButton);

			gg.text(font, label, x1 + 2, y1 + (getHeight() - 8) / 2, TEXT_ON);
			gg.text(font, shownButton, x2 - buttonWidth - 4, y1 + (getHeight() - 8) / 2, this.isHovered() ? 0xFFFF6EC7 : 0xFF5599FF);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			onClick.run();
		}
	}

	/** Label on the left, the currently bound key on the right - click it, then press any key (Escape cancels) to rebind, handled by the screen's own {@link #keyPressed}. */
	private final class KeybindRowWidget extends AbstractWidget {
		private final String label;
		private final KeyMapping mapping;

		KeybindRowWidget(int x, int y, int width, int height, String label, KeyMapping mapping) {
			super(x, y, width, height, Component.literal(label));
			this.label = label;
			this.mapping = mapping;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
			int x1 = getX();
			int y1 = getY();
			int x2 = getX() + getWidth();
			int y2 = getY() + getHeight();
			boolean capturing = capturingKeybind == mapping;

			if (this.isHovered() || capturing) {
				gg.fill(x1, y1, x2, y2, ROW_BG_HOVER_BONUS);
			}

			var font = Minecraft.getInstance().font;
			String shownButton = capturing ? "[ > Press a key < ]" : "[ " + mapping.getTranslatedKeyMessage().getString() + " ]";
			int buttonWidth = font.width(shownButton);

			gg.text(font, label, x1 + 2, y1 + (getHeight() - 8) / 2, TEXT_ON);
			gg.text(font, shownButton, x2 - buttonWidth - 4, y1 + (getHeight() - 8) / 2, capturing ? 0xFFFFCC00 : (this.isHovered() ? 0xFFFF6EC7 : 0xFF5599FF));
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			capturingKeybind = mapping;
		}
	}

	/** A non-interactive row - label on the left, a colored status value on the right. Clicking does nothing. */
	private final class InfoRowWidget extends AbstractWidget {
		private final String label;
		private final Supplier<String> valueGetter;
		private final IntSupplier colorGetter;

		InfoRowWidget(int x, int y, int width, int height, String label, Supplier<String> valueGetter, IntSupplier colorGetter) {
			super(x, y, width, height, Component.literal(label));
			this.label = label;
			this.valueGetter = valueGetter;
			this.colorGetter = colorGetter;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
			int x1 = getX();
			int y1 = getY();
			int x2 = getX() + getWidth();

			var font = Minecraft.getInstance().font;
			String value = valueGetter.get();
			int valueWidth = font.width(value);

			gg.text(font, label, x1 + 2, y1 + (getHeight() - 8) / 2, TEXT_ON);
			gg.text(font, value, x2 - valueWidth - 4, y1 + (getHeight() - 8) / 2, colorGetter.getAsInt());
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			// Not interactive - just a status display.
		}
	}
}
