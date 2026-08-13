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
		// HP Armor Stand highlighting is gone entirely, not relocated. FUN removed entirely - its one
		// row (Spell enabled/color) is fully configurable from the item-menu now (Spells -> Switch
		// Spell / Spell Color), so this tab was a pure duplicate.
		PARTY("skymelloo.gui.settings.tab.party"), HP("skymelloo.gui.settings.tab.hp"), FISHING("skymelloo.gui.settings.tab.fishing"), DUNGEONS("skymelloo.gui.settings.tab.dungeons"), GENERAL("skymelloo.gui.settings.tab.general"), CLOUD("skymelloo.gui.settings.tab.cloud"), DEBUG("skymelloo.gui.settings.tab.debug");

		// Stores a translation key, not display text - resolved at render time (Tab constants are
		// created at class-load, before Minecraft's language system is guaranteed to be ready).
		final String labelKey;

		Tab(String labelKey) {
			this.labelKey = labelKey;
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
		super(Component.translatable("skymelloo.gui.settings.title"));
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
				Component.translatable("skymelloo.gui.settings.button.report_bug"), SkyMellooButtonWidget.RED, SkyMellooMenuScreen::openReportBug));
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

	/** Sets every dungeon-announcement delivery setting on this tab to {@code value} in one go, instead of clicking through each row individually - see the "Delivery" section at the top of the Dungeons tab. */
	private void setAllDungeonDeliveries(SkyMellooConfig c, String value) {
		c.deathRecapPartyAnnounceDelivery = value;
		c.dungeonInfoMessageDelivery = value;
		c.dungeonAutoKickDelivery = value;
		c.dungeonAutoKickMaxDelivery = value;
		c.dungeonRunPartySummaryDelivery = value;
		c.dungeonBossRoomMessageDelivery = value;
		c.dungeonDeathMessageDelivery = value;
		c.dungeonPreBossScoreWarningDelivery = value;
		c.dungeonRoomsDiscoveredDelivery = value;
		c.dungeonSecretsPaceWarningDelivery = value;
		c.dungeonPuzzleRetryFailDelivery = value;
		c.dungeonSPlusImpossibleDelivery = value;
		c.dungeonSPlusBackDelivery = value;
		c.dungeonGradeMilestoneDelivery = value;
		c.dungeonTimeLimitWarningDelivery = value;
		c.dungeonTimeLimitExceededDelivery = value;
		c.dungeonFloorKickDelivery = value;
		c.dungeonFloorKickMaxDelivery = value;
		c.dungeonFloorCompletionKickDelivery = value;
		c.dungeonFloorCompletionKickMaxDelivery = value;
		SkyMellooConfig.HANDLER.save();
		buildRows();
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
				// Party/staff/friend highlighting (toggle, colors, glow-outline opt-in) all moved to
				// MellooEssentials' own Settings screen (General tab, "Friend Highlighting" - party/staff
				// are fixed colors, always on, not user-adjustable there either). Low HP Blink stays here
				// since SkyMelloo is the only one of the two mods that knows how to compute it, hooked
				// into essentials' own color decision via setPartyBlinkColorOverride. Party Join Stats
				// stays here too - that's a stats lookup, not highlighting.
				rows.add(headerRow(tr("skymelloo.gui.settings.header.party")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.party.low_hp_blink"), () -> c.lowHpBlinkEnabled, v -> c.lowHpBlinkEnabled = v, 0xFFFF5555), Component.translatable("skymelloo.gui.settings.tip.party.low_hp_blink")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.party.party_join_stats"), () -> c.partyJoinStatsEnabled, v -> c.partyJoinStatsEnabled = v, 0xFFFFAA00), Component.translatable("skymelloo.gui.settings.tip.party.party_join_stats")));

				rows.add(headerRow(tr("skymelloo.gui.settings.header.lobby_player_search")));
				rows.add(tip(colorRow(tr("skymelloo.gui.settings.row.lobby_player_search.search_highlight_color"), () -> c.lobbySearchColor, v -> c.lobbySearchColor = v), Component.translatable("skymelloo.gui.settings.tip.lobby_player_search.search_highlight_color")));

				rows.add(headerRow(tr("skymelloo.gui.settings.header.misc")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.misc.show_invisible_players"), () -> c.showInvisiblePlayersEnabled, v -> c.showInvisiblePlayersEnabled = v, 0xFF888888), Component.translatable("skymelloo.gui.settings.tip.misc.show_invisible_players")));
			}
			case HP -> {
				rows.add(headerRow(tr("skymelloo.gui.settings.header.distance")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.distance.show_distance"), () -> c.showDistanceEnabled, v -> c.showDistanceEnabled = v, 0xFF55FFFF), Component.translatable("skymelloo.gui.settings.tip.distance.show_distance")));
			}
			case FISHING -> {
				rows.add(headerRow(tr("skymelloo.gui.settings.header.fishing")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.fishing.fishing_helper"), () -> c.fishingHelperEnabled, v -> c.fishingHelperEnabled = v, 0xFF5599FF), Component.translatable("skymelloo.gui.settings.tip.fishing.fishing_helper")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.fishing.fishing_helper_sound"), () -> c.fishingHelperSound, v -> c.fishingHelperSound = v, 0xFFFFAA00), Component.translatable("skymelloo.gui.settings.tip.fishing.fishing_helper_sound")));
				rows.add(tip(colorRow(tr("skymelloo.gui.settings.row.fishing.waiting_color"), () -> c.fishingWaitingColor, v -> c.fishingWaitingColor = v), Component.translatable("skymelloo.gui.settings.tip.fishing.waiting_color")));
				rows.add(tip(colorRow(tr("skymelloo.gui.settings.row.fishing.biting_color"), () -> c.fishingBitingColor, v -> c.fishingBitingColor = v), Component.translatable("skymelloo.gui.settings.tip.fishing.biting_color")));

				rows.add(headerRow(tr("skymelloo.gui.settings.header.minigame")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.minigame.fishing_minigame"), () -> c.fishingMinigameEnabled, v -> c.fishingMinigameEnabled = v, 0xFFFF6600), Component.translatable("skymelloo.gui.settings.tip.minigame.fishing_minigame")));
				rows.add(tip(colorRow(tr("skymelloo.gui.settings.row.minigame.minigame_glow_color"), () -> c.fishingMinigameColor, v -> c.fishingMinigameColor = v), Component.translatable("skymelloo.gui.settings.tip.minigame.minigame_glow_color")));
			}
			case DUNGEONS -> {
				// One bulk control for every LOCAL/PARTY/PARTY SM delivery setting on this tab (and Death
				// Recap's, also here) - so switching how you want announcements delivered doesn't mean
				// clicking through 18 separate rows one at a time.
				rows.add(headerRow(tr("skymelloo.gui.settings.header.delivery_bulk")));
				rows.add(tip(actionRow(tr("skymelloo.gui.settings.row.delivery_bulk.all_local"), tr("skymelloo.gui.settings.button.delivery_bulk.apply"), () -> setAllDungeonDeliveries(c, "LOCAL")), Component.translatable("skymelloo.gui.settings.tip.delivery_bulk.all_local")));
				rows.add(tip(actionRow(tr("skymelloo.gui.settings.row.delivery_bulk.all_party"), tr("skymelloo.gui.settings.button.delivery_bulk.apply"), () -> setAllDungeonDeliveries(c, "PARTY")), Component.translatable("skymelloo.gui.settings.tip.delivery_bulk.all_party")));

				// Chest/Item/Mob highlighting all relocated here from their previous tabs - purely a
				// settings-screen reorg, the underlying scan/render logic (HighlightManager,
				// BlockHighlightRenderer) is unchanged and still works everywhere in-game, not just dungeons.
				rows.add(headerRow(tr("skymelloo.gui.settings.header.chest_highlight")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.chest_highlight.enabled"), () -> c.chestHighlightEnabled, v -> c.chestHighlightEnabled = v, 0xFFFFD700), Component.translatable("skymelloo.gui.settings.tip.chest_highlight.enabled")));
				rows.add(tip(colorRow(tr("skymelloo.gui.settings.row.chest_highlight.color"), () -> c.chestHighlightColor, v -> c.chestHighlightColor = v), Component.translatable("skymelloo.gui.settings.tip.chest_highlight.color")));
				rows.add(tip(intStepRow(tr("skymelloo.gui.settings.row.chest_highlight.scan_range"), () -> c.blockHighlightRange, v -> c.blockHighlightRange = v, 8, 48, 8), Component.translatable("skymelloo.gui.settings.tip.chest_highlight.scan_range")));

				rows.add(headerRow(tr("skymelloo.gui.settings.header.item_highlighting")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.item_highlighting.enabled"), () -> c.itemHighlightEnabled, v -> c.itemHighlightEnabled = v, 0xFF55FF55), Component.translatable("skymelloo.gui.settings.tip.item_highlighting.enabled")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.item_highlighting.name_filters"), () -> c.itemHighlightNameFilters, v -> c.itemHighlightNameFilters = v), Component.translatable("skymelloo.gui.settings.tip.item_highlighting.name_filters")));
				rows.add(tip(colorRow(tr("skymelloo.gui.settings.row.item_highlighting.color"), () -> c.itemHighlightColor, v -> c.itemHighlightColor = v), Component.translatable("skymelloo.gui.settings.tip.item_highlighting.color")));

				// Drastically simplified - the old general "highlight every hostile mob everywhere"
				// system (name filters, friendly mobs, default/named colors) is gone entirely. Only
				// the current-room highlight remains, and it's dungeon-only by nature
				// (isInCurrentDungeonRoom requires an active run).
				rows.add(headerRow(tr("skymelloo.gui.settings.header.mob_highlighting")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.mob_highlighting.enabled"), () -> c.dungeonRoomMobHighlightEnabled, v -> c.dungeonRoomMobHighlightEnabled = v, 0xFFFF0000), Component.translatable("skymelloo.gui.settings.tip.mob_highlighting.enabled")));
				rows.add(tip(colorRow(tr("skymelloo.gui.settings.row.mob_highlighting.color"), () -> c.dungeonRoomMobHighlightColor, v -> c.dungeonRoomMobHighlightColor = v), Component.translatable("skymelloo.gui.settings.tip.mob_highlighting.color")));

				// Moved here from Fun, just relocated in the menu.
				rows.add(headerRow(tr("skymelloo.gui.settings.header.death_recap")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.death_recap.enabled"), () -> c.deathRecapEnabled, v -> c.deathRecapEnabled = v, 0xFFFF5555), Component.translatable("skymelloo.gui.settings.tip.death_recap.enabled")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.death_recap.party_announce"), () -> c.deathRecapPartyAnnounceEnabled, v -> c.deathRecapPartyAnnounceEnabled = v, 0xFFFF5555), Component.translatable("skymelloo.gui.settings.tip.death_recap.party_announce")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.death_recap.message_text"), () -> c.deathRecapPartyAnnounceTemplate, v -> c.deathRecapPartyAnnounceTemplate = v), Component.translatable("skymelloo.gui.settings.tip.death_recap.message_text")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.death_recap.delivery"), () -> c.deathRecapPartyAnnounceDelivery, v -> c.deathRecapPartyAnnounceDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.death_recap.delivery")));

				rows.add(headerRow(tr("skymelloo.gui.settings.header.dungeon_info")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.dungeon_info.enabled"), () -> c.partyJoinStatsEnabled, v -> c.partyJoinStatsEnabled = v, 0xFF5599FF), Component.translatable("skymelloo.gui.settings.tip.dungeon_info.enabled")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.dungeon_info.show_mp"), () -> c.dungeonInfoShowMp, v -> c.dungeonInfoShowMp = v, 0xFFAA33FF), Component.translatable("skymelloo.gui.settings.tip.dungeon_info.show_mp")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.dungeon_info.message_template"), () -> c.dungeonInfoMessageTemplate, v -> c.dungeonInfoMessageTemplate = v),
						Component.translatable("skymelloo.gui.settings.tip.dungeon_info.message_template")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.dungeon_info.delivery"), () -> c.dungeonInfoMessageDelivery, v -> c.dungeonInfoMessageDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.dungeon_info.delivery")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.dungeon_info.timezone"), () -> c.dungeonInfoTimezone, v -> c.dungeonInfoTimezone = v),
						Component.translatable("skymelloo.gui.settings.tip.dungeon_info.timezone")));

				rows.add(headerRow(tr("skymelloo.gui.settings.header.auto_kick")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.auto_kick.enabled"), () -> c.dungeonAutoKickEnabled, v -> c.dungeonAutoKickEnabled = v, 0xFFFF5555), Component.translatable("skymelloo.gui.settings.tip.auto_kick.enabled")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.auto_kick.check_stat"), () -> c.dungeonAutoKickStat, v -> c.dungeonAutoKickStat = v, new String[] { "AP", "LEVEL" }), Component.translatable("skymelloo.gui.settings.tip.auto_kick.check_stat")));
				rows.add(tip(intTextRow(tr("skymelloo.gui.settings.row.auto_kick.threshold"), () -> c.dungeonAutoKickThreshold, v -> c.dungeonAutoKickThreshold = v, 0, 100000), Component.translatable("skymelloo.gui.settings.tip.auto_kick.threshold")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.auto_kick.message_text"), () -> c.dungeonAutoKickMessageTemplate, v -> c.dungeonAutoKickMessageTemplate = v), Component.translatable("skymelloo.gui.settings.tip.auto_kick.message_text")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.auto_kick.delivery"), () -> c.dungeonAutoKickDelivery, v -> c.dungeonAutoKickDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.auto_kick.delivery")));

				rows.add(headerRow(tr("skymelloo.gui.settings.header.auto_kick_max")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.auto_kick_max.enabled"), () -> c.dungeonAutoKickMaxEnabled, v -> c.dungeonAutoKickMaxEnabled = v, 0xFFFF9955), Component.translatable("skymelloo.gui.settings.tip.auto_kick_max.enabled")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.auto_kick_max.check_stat"), () -> c.dungeonAutoKickMaxStat, v -> c.dungeonAutoKickMaxStat = v, new String[] { "AP", "LEVEL" }), Component.translatable("skymelloo.gui.settings.tip.auto_kick_max.check_stat")));
				rows.add(tip(intTextRow(tr("skymelloo.gui.settings.row.auto_kick_max.threshold"), () -> c.dungeonAutoKickMaxThreshold, v -> c.dungeonAutoKickMaxThreshold = v, 0, 100000), Component.translatable("skymelloo.gui.settings.tip.auto_kick_max.threshold")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.auto_kick_max.message_text"), () -> c.dungeonAutoKickMaxMessageTemplate, v -> c.dungeonAutoKickMaxMessageTemplate = v), Component.translatable("skymelloo.gui.settings.tip.auto_kick_max.message_text")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.auto_kick_max.delivery"), () -> c.dungeonAutoKickMaxDelivery, v -> c.dungeonAutoKickMaxDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.auto_kick_max.delivery")));

				rows.add(headerRow(tr("skymelloo.gui.settings.header.run_tracker")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.run_tracker.party_hud"), () -> c.partyHudMode, v -> c.partyHudMode = v, new String[] { "OFF", "COMPACT", "FULL" }), Component.translatable("skymelloo.gui.settings.tip.run_tracker.party_hud")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.run_tracker.party_hud_puzzle_history"), () -> c.partyHudShowPuzzleHistory, v -> c.partyHudShowPuzzleHistory = v, 0xFFFFAA00), Component.translatable("skymelloo.gui.settings.tip.run_tracker.party_hud_puzzle_history")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.run_tracker.party_mp_bar"), () -> c.partyMpBarEnabled, v -> c.partyMpBarEnabled = v, 0xFFFF6EC7), Component.translatable("skymelloo.gui.settings.tip.run_tracker.party_mp_bar")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.run_tracker.run_report"), () -> c.dungeonRunReportEnabled, v -> c.dungeonRunReportEnabled = v, 0xFF5599FF), Component.translatable("skymelloo.gui.settings.tip.run_tracker.run_report")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.run_tracker.party_run_summary"), () -> c.dungeonRunPartySummaryEnabled, v -> c.dungeonRunPartySummaryEnabled = v, 0xFF5599FF), Component.translatable("skymelloo.gui.settings.tip.run_tracker.party_run_summary")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.run_tracker.party_summary_text"), () -> c.dungeonRunPartySummaryTemplate, v -> c.dungeonRunPartySummaryTemplate = v), Component.translatable("skymelloo.gui.settings.tip.run_tracker.party_summary_text")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.run_tracker.party_summary_delivery"), () -> c.dungeonRunPartySummaryDelivery, v -> c.dungeonRunPartySummaryDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.run_tracker.party_summary_delivery")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.run_tracker.death_auto_kick"), () -> c.dungeonDeathKickEnabled, v -> c.dungeonDeathKickEnabled = v, 0xFFFF5555), Component.translatable("skymelloo.gui.settings.tip.run_tracker.death_auto_kick")));
				rows.add(tip(intTextRow(tr("skymelloo.gui.settings.row.run_tracker.death_threshold"), () -> c.dungeonDeathKickThreshold, v -> c.dungeonDeathKickThreshold = v, 1, 10), Component.translatable("skymelloo.gui.settings.tip.run_tracker.death_threshold")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.run_tracker.afk_auto_kick"), () -> c.dungeonAfkKickEnabled, v -> c.dungeonAfkKickEnabled = v, 0xFFFF5555), Component.translatable("skymelloo.gui.settings.tip.run_tracker.afk_auto_kick")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.run_tracker.afk_threshold"), () -> c.dungeonAfkKickThreshold, v -> c.dungeonAfkKickThreshold = v, new String[] { "30", "60", "120" }), Component.translatable("skymelloo.gui.settings.tip.run_tracker.afk_threshold")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.run_tracker.score_hud"), () -> c.dungeonScoreHudEnabled, v -> c.dungeonScoreHudEnabled = v, 0xFFFFD700), Component.translatable("skymelloo.gui.settings.tip.run_tracker.score_hud")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.run_tracker.show_breakdown"), () -> c.dungeonScoreShowBreakdown, v -> c.dungeonScoreShowBreakdown = v, 0xFFAAAAAA), Component.translatable("skymelloo.gui.settings.tip.run_tracker.show_breakdown")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.run_tracker.show_room_secrets"), () -> c.dungeonScoreShowRoomSecrets, v -> c.dungeonScoreShowRoomSecrets = v, 0xFF66DDFF), Component.translatable("skymelloo.gui.settings.tip.run_tracker.show_room_secrets")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.run_tracker.show_puzzles"), () -> c.dungeonScoreShowPuzzles, v -> c.dungeonScoreShowPuzzles = v, 0xFFFFAA00), Component.translatable("skymelloo.gui.settings.tip.run_tracker.show_puzzles")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.run_tracker.show_possible"), () -> c.dungeonScoreShowPossible, v -> c.dungeonScoreShowPossible = v, 0xFF55FF55), Component.translatable("skymelloo.gui.settings.tip.run_tracker.show_possible")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.run_tracker.show_next_grade"), () -> c.dungeonScoreShowNextGrade, v -> c.dungeonScoreShowNextGrade = v, 0xFFFFD700), Component.translatable("skymelloo.gui.settings.tip.run_tracker.show_next_grade")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.run_tracker.show_pace"), () -> c.dungeonScoreShowPaceAndCountdown, v -> c.dungeonScoreShowPaceAndCountdown = v, 0xFFAAAAAA), Component.translatable("skymelloo.gui.settings.tip.run_tracker.show_pace")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.run_tracker.show_final_result"), () -> c.dungeonScoreFinalResultEnabled, v -> c.dungeonScoreFinalResultEnabled = v, 0xFFFFD700), Component.translatable("skymelloo.gui.settings.tip.run_tracker.show_final_result")));
				rows.add(tip(intStepRow(tr("skymelloo.gui.settings.row.run_tracker.final_result_duration"), () -> c.dungeonScoreFinalResultDurationSeconds, v -> c.dungeonScoreFinalResultDurationSeconds = v, 5, 60, 5), Component.translatable("skymelloo.gui.settings.tip.run_tracker.final_result_duration")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.run_tracker.debug_hud"), () -> c.dungeonDebugHudEnabled, v -> c.dungeonDebugHudEnabled = v, 0xFF66DDFF), Component.translatable("skymelloo.gui.settings.tip.run_tracker.debug_hud")));

				// Each message below is fully self-contained (toggle, text, delivery) rather than
				// scattered across sections with one shared delivery setting at the bottom - lets e.g.
				// death spam stay local while a boss-room announcement goes to the whole party.
				rows.add(headerRow(tr("skymelloo.gui.settings.header.boss_room")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.boss_room.enabled"), () -> c.dungeonBossRoomAnnounceEnabled, v -> c.dungeonBossRoomAnnounceEnabled = v, 0xFFAA33FF), Component.translatable("skymelloo.gui.settings.tip.boss_room.enabled")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.boss_room.message_text"), () -> c.dungeonBossRoomMessageTemplate, v -> c.dungeonBossRoomMessageTemplate = v), Component.translatable("skymelloo.gui.settings.tip.boss_room.message_text")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.boss_room.low_score_message_text"), () -> c.dungeonBossRoomLowScoreMessageTemplate, v -> c.dungeonBossRoomLowScoreMessageTemplate = v), Component.translatable("skymelloo.gui.settings.tip.boss_room.low_score_message_text")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.boss_room.delivery"), () -> c.dungeonBossRoomMessageDelivery, v -> c.dungeonBossRoomMessageDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.boss_room.delivery")));

				rows.add(headerRow(tr("skymelloo.gui.settings.header.death_message")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.death_message.enabled"), () -> c.dungeonDeathMessageEnabled, v -> c.dungeonDeathMessageEnabled = v, 0xFFFF5555), Component.translatable("skymelloo.gui.settings.tip.death_message.enabled")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.death_message.message_text"), () -> c.dungeonDeathMessageTemplate, v -> c.dungeonDeathMessageTemplate = v), Component.translatable("skymelloo.gui.settings.tip.death_message.message_text")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.death_message.delivery"), () -> c.dungeonDeathMessageDelivery, v -> c.dungeonDeathMessageDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.death_message.delivery")));

				rows.add(headerRow(tr("skymelloo.gui.settings.header.pre_boss_score_warning")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.pre_boss_score_warning.enabled"), () -> c.dungeonPreBossScoreWarningEnabled, v -> c.dungeonPreBossScoreWarningEnabled = v, 0xFFFFAA00), Component.translatable("skymelloo.gui.settings.tip.pre_boss_score_warning.enabled")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.pre_boss_score_warning.message_text"), () -> c.dungeonPreBossScoreWarningTemplate, v -> c.dungeonPreBossScoreWarningTemplate = v), Component.translatable("skymelloo.gui.settings.tip.pre_boss_score_warning.message_text")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.pre_boss_score_warning.already_impossible_text"), () -> c.dungeonPreBossScoreWarningAlreadyImpossibleTemplate, v -> c.dungeonPreBossScoreWarningAlreadyImpossibleTemplate = v), Component.translatable("skymelloo.gui.settings.tip.pre_boss_score_warning.already_impossible_text")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.pre_boss_score_warning.delivery"), () -> c.dungeonPreBossScoreWarningDelivery, v -> c.dungeonPreBossScoreWarningDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.pre_boss_score_warning.delivery")));

				rows.add(headerRow(tr("skymelloo.gui.settings.header.rooms_discovered")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.rooms_discovered.enabled"), () -> c.dungeonRoomsDiscoveredAnnounceEnabled, v -> c.dungeonRoomsDiscoveredAnnounceEnabled = v, 0xFF66DDFF), Component.translatable("skymelloo.gui.settings.tip.rooms_discovered.enabled")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.rooms_discovered.message_text"), () -> c.dungeonRoomsDiscoveredTemplate, v -> c.dungeonRoomsDiscoveredTemplate = v), Component.translatable("skymelloo.gui.settings.tip.rooms_discovered.message_text")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.rooms_discovered.delivery"), () -> c.dungeonRoomsDiscoveredDelivery, v -> c.dungeonRoomsDiscoveredDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.rooms_discovered.delivery")));

				rows.add(headerRow(tr("skymelloo.gui.settings.header.secrets_pace_warning")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.secrets_pace_warning.enabled"), () -> c.dungeonSecretsPaceWarningEnabled, v -> c.dungeonSecretsPaceWarningEnabled = v, 0xFF66DDFF), Component.translatable("skymelloo.gui.settings.tip.secrets_pace_warning.enabled")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.secrets_pace_warning.message_text"), () -> c.dungeonSecretsPaceWarningTemplate, v -> c.dungeonSecretsPaceWarningTemplate = v), Component.translatable("skymelloo.gui.settings.tip.secrets_pace_warning.message_text")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.secrets_pace_warning.delivery"), () -> c.dungeonSecretsPaceWarningDelivery, v -> c.dungeonSecretsPaceWarningDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.secrets_pace_warning.delivery")));

				rows.add(headerRow(tr("skymelloo.gui.settings.header.puzzle_retry_fail")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.puzzle_retry_fail.enabled"), () -> c.dungeonPuzzleRetryFailEnabled, v -> c.dungeonPuzzleRetryFailEnabled = v, 0xFFFFAA00), Component.translatable("skymelloo.gui.settings.tip.puzzle_retry_fail.enabled")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.puzzle_retry_fail.message_text"), () -> c.dungeonPuzzleRetryFailTemplate, v -> c.dungeonPuzzleRetryFailTemplate = v), Component.translatable("skymelloo.gui.settings.tip.puzzle_retry_fail.message_text")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.puzzle_retry_fail.delivery"), () -> c.dungeonPuzzleRetryFailDelivery, v -> c.dungeonPuzzleRetryFailDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.puzzle_retry_fail.delivery")));

				rows.add(headerRow(tr("skymelloo.gui.settings.header.s_plus_impossible_warning")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.s_plus_impossible.enabled"), () -> c.dungeonSPlusImpossibleEnabled, v -> c.dungeonSPlusImpossibleEnabled = v, 0xFFFF5555), Component.translatable("skymelloo.gui.settings.tip.s_plus_impossible.enabled")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.s_plus_impossible.message_text"), () -> c.dungeonSPlusImpossibleTemplate, v -> c.dungeonSPlusImpossibleTemplate = v), Component.translatable("skymelloo.gui.settings.tip.s_plus_impossible.message_text")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.s_plus_impossible.delivery"), () -> c.dungeonSPlusImpossibleDelivery, v -> c.dungeonSPlusImpossibleDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.s_plus_impossible.delivery")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.s_plus_back.enabled"), () -> c.dungeonSPlusBackEnabled, v -> c.dungeonSPlusBackEnabled = v, 0xFF55FF55), Component.translatable("skymelloo.gui.settings.tip.s_plus_back.enabled")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.s_plus_back.message_text"), () -> c.dungeonSPlusBackTemplate, v -> c.dungeonSPlusBackTemplate = v), Component.translatable("skymelloo.gui.settings.tip.s_plus_back.message_text")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.s_plus_back.delivery"), () -> c.dungeonSPlusBackDelivery, v -> c.dungeonSPlusBackDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.s_plus_back.delivery")));

				rows.add(headerRow(tr("skymelloo.gui.settings.header.grade_milestone")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.grade_milestone.enabled"), () -> c.dungeonGradeMilestoneEnabled, v -> c.dungeonGradeMilestoneEnabled = v, 0xFF66DDFF), Component.translatable("skymelloo.gui.settings.tip.grade_milestone.enabled")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.grade_milestone.message_text"), () -> c.dungeonGradeMilestoneTemplate, v -> c.dungeonGradeMilestoneTemplate = v), Component.translatable("skymelloo.gui.settings.tip.grade_milestone.message_text")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.grade_milestone.delivery"), () -> c.dungeonGradeMilestoneDelivery, v -> c.dungeonGradeMilestoneDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.grade_milestone.delivery")));

				rows.add(headerRow(tr("skymelloo.gui.settings.header.self_ready_reminder")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.self_ready_reminder.enabled"), () -> c.dungeonSelfReadyReminderEnabled, v -> c.dungeonSelfReadyReminderEnabled = v, 0xFF55FF55), Component.translatable("skymelloo.gui.settings.tip.self_ready_reminder.enabled")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.self_ready_reminder.reminder_text"), () -> c.dungeonSelfReadyReminderText, v -> c.dungeonSelfReadyReminderText = v), Component.translatable("skymelloo.gui.settings.tip.self_ready_reminder.reminder_text")));

				rows.add(headerRow(tr("skymelloo.gui.settings.header.time_limit_warning")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.time_limit_warning.enabled"), () -> c.dungeonTimeLimitWarningEnabled, v -> c.dungeonTimeLimitWarningEnabled = v, 0xFFFFAA00), Component.translatable("skymelloo.gui.settings.tip.time_limit_warning.enabled")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.time_limit_warning.start_at"), () -> c.dungeonTimeLimitWarningStart, v -> c.dungeonTimeLimitWarningStart = v, new String[] { "60", "30", "15", "10" }), Component.translatable("skymelloo.gui.settings.tip.time_limit_warning.start_at")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.time_limit_warning.message_text"), () -> c.dungeonTimeLimitWarningTemplate, v -> c.dungeonTimeLimitWarningTemplate = v), Component.translatable("skymelloo.gui.settings.tip.time_limit_warning.message_text")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.time_limit_warning.delivery"), () -> c.dungeonTimeLimitWarningDelivery, v -> c.dungeonTimeLimitWarningDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.time_limit_warning.delivery")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.time_limit_exceeded.enabled"), () -> c.dungeonTimeLimitExceededEnabled, v -> c.dungeonTimeLimitExceededEnabled = v, 0xFFFF5555), Component.translatable("skymelloo.gui.settings.tip.time_limit_exceeded.enabled")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.time_limit_exceeded.message_text"), () -> c.dungeonTimeLimitExceededTemplate, v -> c.dungeonTimeLimitExceededTemplate = v), Component.translatable("skymelloo.gui.settings.tip.time_limit_exceeded.message_text")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.time_limit_exceeded.delivery"), () -> c.dungeonTimeLimitExceededDelivery, v -> c.dungeonTimeLimitExceededDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.time_limit_exceeded.delivery")));

				rows.add(headerRow(tr("skymelloo.gui.settings.header.floor_requirement")));
				rows.add(tip(intStepRow(tr("skymelloo.gui.settings.row.floor_kick.target_floor"), () -> c.dungeonTargetFloor, v -> c.dungeonTargetFloor = v, 0, 7, 1), Component.translatable("skymelloo.gui.settings.tip.floor_kick.target_floor")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.floor_kick.enabled"), () -> c.dungeonFloorKickEnabled, v -> c.dungeonFloorKickEnabled = v, 0xFFFF5555), Component.translatable("skymelloo.gui.settings.tip.floor_kick.enabled")));
				rows.add(tip(intStepRow(tr("skymelloo.gui.settings.row.floor_kick.required_floor"), () -> c.dungeonFloorKickThreshold, v -> c.dungeonFloorKickThreshold = v, 0, 7, 1), Component.translatable("skymelloo.gui.settings.tip.floor_kick.required_floor")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.floor_kick.message_text"), () -> c.dungeonFloorKickMessageTemplate, v -> c.dungeonFloorKickMessageTemplate = v), Component.translatable("skymelloo.gui.settings.tip.floor_kick.message_text")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.floor_kick.delivery"), () -> c.dungeonFloorKickDelivery, v -> c.dungeonFloorKickDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.floor_kick.delivery")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.floor_kick_max.enabled"), () -> c.dungeonFloorKickMaxEnabled, v -> c.dungeonFloorKickMaxEnabled = v, 0xFFFF9955), Component.translatable("skymelloo.gui.settings.tip.floor_kick_max.enabled")));
				rows.add(tip(intStepRow(tr("skymelloo.gui.settings.row.floor_kick_max.allowed_floor"), () -> c.dungeonFloorKickMaxThreshold, v -> c.dungeonFloorKickMaxThreshold = v, 0, 7, 1), Component.translatable("skymelloo.gui.settings.tip.floor_kick_max.allowed_floor")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.floor_kick_max.message_text"), () -> c.dungeonFloorKickMaxMessageTemplate, v -> c.dungeonFloorKickMaxMessageTemplate = v), Component.translatable("skymelloo.gui.settings.tip.floor_kick_max.message_text")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.floor_kick_max.delivery"), () -> c.dungeonFloorKickMaxDelivery, v -> c.dungeonFloorKickMaxDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.floor_kick_max.delivery")));

				// Floor Requirement above only checks whether a member currently MEETS the level
				// requirements; this checks whether they've actually COMPLETED that floor before
				// (Hypixel's own record), an independent signal.
				rows.add(headerRow(tr("skymelloo.gui.settings.header.floor_completion")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.floor_completion_kick.enabled"), () -> c.dungeonFloorCompletionKickEnabled, v -> c.dungeonFloorCompletionKickEnabled = v, 0xFFFF5555), Component.translatable("skymelloo.gui.settings.tip.floor_completion_kick.enabled")));
				rows.add(tip(intStepRow(tr("skymelloo.gui.settings.row.floor_completion_kick.required_floor"), () -> c.dungeonFloorCompletionKickThreshold, v -> c.dungeonFloorCompletionKickThreshold = v, 0, 7, 1), Component.translatable("skymelloo.gui.settings.tip.floor_completion_kick.required_floor")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.floor_completion_kick.message_text"), () -> c.dungeonFloorCompletionKickMessageTemplate, v -> c.dungeonFloorCompletionKickMessageTemplate = v), Component.translatable("skymelloo.gui.settings.tip.floor_completion_kick.message_text")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.floor_completion_kick.delivery"), () -> c.dungeonFloorCompletionKickDelivery, v -> c.dungeonFloorCompletionKickDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.floor_completion_kick.delivery")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.floor_completion_kick_max.enabled"), () -> c.dungeonFloorCompletionKickMaxEnabled, v -> c.dungeonFloorCompletionKickMaxEnabled = v, 0xFFFF9955), Component.translatable("skymelloo.gui.settings.tip.floor_completion_kick_max.enabled")));
				rows.add(tip(intStepRow(tr("skymelloo.gui.settings.row.floor_completion_kick_max.allowed_floor"), () -> c.dungeonFloorCompletionKickMaxThreshold, v -> c.dungeonFloorCompletionKickMaxThreshold = v, 0, 7, 1), Component.translatable("skymelloo.gui.settings.tip.floor_completion_kick_max.allowed_floor")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.floor_completion_kick_max.message_text"), () -> c.dungeonFloorCompletionKickMaxMessageTemplate, v -> c.dungeonFloorCompletionKickMaxMessageTemplate = v), Component.translatable("skymelloo.gui.settings.tip.floor_completion_kick_max.message_text")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.floor_completion_kick_max.delivery"), () -> c.dungeonFloorCompletionKickMaxDelivery, v -> c.dungeonFloorCompletionKickMaxDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.floor_completion_kick_max.delivery")));
			}
			case GENERAL -> {
				rows.add(headerRow(tr("skymelloo.gui.settings.header.hotkeys")));
				rows.add(tip(keybindRow(tr("skymelloo.gui.settings.row.hotkeys.open_this_menu"), SkyMellooClient.getOpenConfigKey()), Component.translatable("skymelloo.gui.settings.tip.hotkeys.open_this_menu")));
				rows.add(headerRow(tr("skymelloo.gui.settings.header.menu")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.menu.skymelloo_menu_item"), () -> c.skyMellooMenuItemEnabled, v -> c.skyMellooMenuItemEnabled = v, 0xFF66DDFF), Component.translatable("skymelloo.gui.settings.tip.menu.skymelloo_menu_item")));
				rows.add(headerRow(tr("skymelloo.gui.settings.header.hud")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.hud.health_mana_bars"), () -> c.healthManaBarsEnabled, v -> c.healthManaBarsEnabled = v, 0xFF55DD55), Component.translatable("skymelloo.gui.settings.tip.hud.health_mana_bars")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.hud.health_bar"), () -> c.healthBarEnabled, v -> c.healthBarEnabled = v, 0xFF55DD55), Component.translatable("skymelloo.gui.settings.tip.hud.health_bar")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.hud.mana_bar"), () -> c.manaBarEnabled, v -> c.manaBarEnabled = v, 0xFF55CCFF), Component.translatable("skymelloo.gui.settings.tip.hud.mana_bar")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.hud.side_by_side"), () -> c.healthManaBarsSideBySide, v -> c.healthManaBarsSideBySide = v, 0xFFAAAAAA), Component.translatable("skymelloo.gui.settings.tip.hud.side_by_side")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.hud.hide_native_bar"), () -> c.hideNativeStatusActionBarEnabled, v -> c.hideNativeStatusActionBarEnabled = v, 0xFFAAAAAA), Component.translatable("skymelloo.gui.settings.tip.hud.hide_native_bar")));
				rows.add(headerRow(tr("skymelloo.gui.settings.header.chat")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.chat.mention_highlight"), () -> c.chatMentionHighlightEnabled, v -> c.chatMentionHighlightEnabled = v, 0xFFFFD700), Component.translatable("skymelloo.gui.settings.tip.chat.mention_highlight")));
				rows.add(tip(colorRow(tr("skymelloo.gui.settings.row.chat.mention_highlight_color"), () -> c.chatMentionHighlightColor, v -> c.chatMentionHighlightColor = v), Component.translatable("skymelloo.gui.settings.tip.chat.mention_highlight_color")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.chat.anti_scam"), () -> c.antiScamEnabled, v -> c.antiScamEnabled = v, 0xFFFF5555), Component.translatable("skymelloo.gui.settings.tip.chat.anti_scam")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.chat.anti_scam_hide"), () -> c.antiScamHideMessages, v -> c.antiScamHideMessages = v, 0xFFFF5555), Component.translatable("skymelloo.gui.settings.tip.chat.anti_scam_hide")));
			}
			case DEBUG -> {
				rows.add(headerRow(tr("skymelloo.gui.settings.header.debug_messages")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.debug_messages.enabled"), () -> c.debugMessagesEnabled, v -> c.debugMessagesEnabled = v, 0xFFAAAAAA), Component.translatable("skymelloo.gui.settings.tip.debug_messages.enabled")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.debug_messages.sync_log"), () -> c.debugSync, v -> c.debugSync = v, 0xFF66DDFF), Component.translatable("skymelloo.gui.settings.tip.debug_messages.sync_log")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.debug_messages.sync_log_delivery"), () -> c.debugSyncDelivery, v -> c.debugSyncDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.debug_messages.sync_log_delivery")));
				if (WhitelistManager.isAdmin()) {
					rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.debug_messages.permissions_log"), () -> c.debugPermissions, v -> c.debugPermissions = v, 0xFF55FF55), Component.translatable("skymelloo.gui.settings.tip.debug_messages.permissions_log")));
					rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.debug_messages.permissions_log_delivery"), () -> c.debugPermissionsDelivery, v -> c.debugPermissionsDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.debug_messages.permissions_log_delivery")));
				}
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.debug_messages.cloud_sync_log"), () -> c.debugCloudSync, v -> c.debugCloudSync = v, 0xFF5599FF), Component.translatable("skymelloo.gui.settings.tip.debug_messages.cloud_sync_log")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.debug_messages.cloud_sync_log_delivery"), () -> c.debugCloudSyncDelivery, v -> c.debugCloudSyncDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.debug_messages.cloud_sync_log_delivery")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.debug_messages.presence_log"), () -> c.debugPresence, v -> c.debugPresence = v, 0xFFAA33FF), Component.translatable("skymelloo.gui.settings.tip.debug_messages.presence_log")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.debug_messages.presence_log_delivery"), () -> c.debugPresenceDelivery, v -> c.debugPresenceDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.debug_messages.presence_log_delivery")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.debug_messages.party_log"), () -> c.debugParty, v -> c.debugParty = v, 0xFF55FFFF), Component.translatable("skymelloo.gui.settings.tip.debug_messages.party_log")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.debug_messages.party_log_delivery"), () -> c.debugPartyDelivery, v -> c.debugPartyDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.debug_messages.party_log_delivery")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.debug_messages.dungeon_log"), () -> c.debugDungeon, v -> c.debugDungeon = v, 0xFFAA33FF), Component.translatable("skymelloo.gui.settings.tip.debug_messages.dungeon_log")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.debug_messages.dungeon_log_delivery"), () -> c.debugDungeonDelivery, v -> c.debugDungeonDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.debug_messages.dungeon_log_delivery")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.debug_messages.staff_log"), () -> c.debugStaff, v -> c.debugStaff = v, 0xFFFFD700), Component.translatable("skymelloo.gui.settings.tip.debug_messages.staff_log")));
				rows.add(tip(cycleRow(tr("skymelloo.gui.settings.row.debug_messages.staff_log_delivery"), () -> c.debugStaffDelivery, v -> c.debugStaffDelivery = v, new String[] { "LOCAL", "PARTY", "PARTY SM" }), Component.translatable("skymelloo.gui.settings.tip.debug_messages.staff_log_delivery")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.debug_messages.mana_debug"), () -> c.manaDebugEnabled, v -> c.manaDebugEnabled = v, 0xFF55CCFF), Component.translatable("skymelloo.gui.settings.tip.debug_messages.mana_debug")));

				rows.add(headerRow(tr("skymelloo.gui.settings.header.connection")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.connection.quality_check"), () -> c.connectionQualityCheckEnabled, v -> c.connectionQualityCheckEnabled = v, 0xFFFFCC00), Component.translatable("skymelloo.gui.settings.tip.connection.quality_check")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.connection.auto_reconnect"), () -> c.autoReconnectEnabled, v -> c.autoReconnectEnabled = v, 0xFFFFCC00), Component.translatable("skymelloo.gui.settings.tip.connection.auto_reconnect")));
			}
			case CLOUD -> {
				rows.add(headerRow(tr("skymelloo.gui.settings.header.account")));
				rows.add(infoRow("sky.melloo.me", this::connectionStatusLabel, this::connectionStatusColor));

				rows.add(headerRow(tr("skymelloo.gui.settings.header.cloud_sync")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.cloud_sync.enabled"), () -> c.cloudSyncEnabled, v -> c.cloudSyncEnabled = v, 0xFF5599FF), Component.translatable("skymelloo.gui.settings.tip.cloud_sync.enabled")));
				rows.add(tip(actionRow(tr("skymelloo.gui.settings.row.cloud_sync.push_now"), tr("skymelloo.gui.settings.button.cloud_sync.upload"), () -> CloudSyncManager.push(Minecraft.getInstance())), Component.translatable("skymelloo.gui.settings.tip.cloud_sync.push_now")));
				rows.add(tip(actionRow(tr("skymelloo.gui.settings.row.cloud_sync.pull_now"), tr("skymelloo.gui.settings.button.cloud_sync.download"), () -> CloudSyncManager.forcePull(Minecraft.getInstance(), this::buildRows)), Component.translatable("skymelloo.gui.settings.tip.cloud_sync.pull_now")));

				// Everything below is stuff other people (or the public sky.melloo.me website) can see
				// about you - grouped here together rather than scattered under Dungeons/General, so
				// it's obvious at a glance everything this account shares and there's one place to shut
				// it all off for privacy.
				rows.add(headerRow(tr("skymelloo.gui.settings.header.sharing_privacy")));
				// Renamed from "Presence Sharing" - this and Dungeon Sync below are now presented as
				// the two peer halves of sharing: this one is the lightweight "general sync" (online
				// status + which world/island/dungeon floor you're in), Dungeon Sync is the heavy
				// per-tick dungeon-run detail. The underlying field is still presenceSharingEnabled -
				// only the label/description changed, not the config key.
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.sharing_privacy.sync"), () -> c.presenceSharingEnabled, v -> c.presenceSharingEnabled = v, 0xFFAA33FF), Component.translatable("skymelloo.gui.settings.tip.sharing_privacy.sync")));
				rows.add(tip(stringRow(tr("skymelloo.gui.settings.row.sharing_privacy.status"), () -> c.customStatusText, v -> c.customStatusText = v),
						Component.translatable("skymelloo.gui.settings.tip.sharing_privacy.status")));
				rows.add(tip(boolRow(tr("skymelloo.gui.settings.row.sharing_privacy.dungeon_sync"), () -> c.dungeonSyncEnabled, v -> c.dungeonSyncEnabled = v, 0xFF66DDFF), Component.translatable("skymelloo.gui.settings.tip.sharing_privacy.dungeon_sync")));
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

	private RowFactory colorRow(String label, Supplier<Color> getter, Consumer<Color> setter) {
		return (x, y, w, h) -> new ColorRowWidget(x, y, w, h, label, getter, setter);
	}

	private RowFactory stringRow(String label, Supplier<String> getter, Consumer<String> setter) {
		return (x, y, w, h) -> new StringRowWidget(x, y, w, h, label, getter, setter);
	}

	private RowFactory intStepRow(String label, IntSupplier getter, IntConsumer setter, int min, int max, int step) {
		return (x, y, w, h) -> new IntStepRowWidget(x, y, w, h, label, getter, setter, min, max, step);
	}

	/** Like {@link #intStepRow}, but opens a text-input screen instead of cycling by a fixed step - for values worth typing directly (e.g. an AP threshold in the thousands). */
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
			return tr("skymelloo.gui.settings.status.connected_admin");
		}
		if (WhitelistManager.isAllowed()) {
			return tr("skymelloo.gui.settings.status.connected");
		}
		return tr("skymelloo.gui.settings.status.not_connected");
	}

	private int connectionStatusColor() {
		return WhitelistManager.isAllowed() ? 0xFF55FF55 : 0xFFFF5555;
	}

	/** Resolves a translation key to its display string, for row labels rendered as raw text. */
	private static String tr(String key) {
		return Component.translatable(key).getString();
	}

	/** Wraps any row factory with a hover tooltip explaining what the setting does. */
	private RowFactory tip(RowFactory factory, Component tooltip) {
		return (x, y, w, h) -> {
			AbstractWidget widget = factory.create(x, y, w, h);
			widget.setTooltip(Tooltip.create(tooltip));
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
		gg.centeredText(this.font, tr("skymelloo.gui.settings.title"), this.width / 2, MARGIN + 6, TEXT_ON);

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
			gg.centeredText(this.font, tr("skymelloo.gui.settings.hint.preview"), centerX, previewY2 - 12, TEXT_OFF);
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
			super(x, y, width, height, Component.translatable(tab.labelKey));
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
			gg.centeredText(Minecraft.getInstance().font, tr(tab.labelKey), (x1 + x2) / 2, y1 + (getHeight() - 8) / 2, active ? TEXT_ON : TEXT_OFF);

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

	private final class SwatchWidget extends AbstractWidget {
		private final int color;
		private final Runnable onPick;

		SwatchWidget(int x, int y, int width, int height, int color, Runnable onPick) {
			super(x, y, width, height, Component.translatable("skymelloo.gui.settings.color_swatch"));
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
			String shown = value.isEmpty() ? tr("skymelloo.gui.settings.value.empty") : (value.length() > 14 ? value.substring(0, 12) + "…" : value);
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

	/** A row that cycles through a small fixed set of string options on click - e.g. "AP" / "LEVEL". */
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
			String shownButton = capturing ? "[ " + tr("skymelloo.gui.settings.keybind.press_a_key") + " ]" : "[ " + mapping.getTranslatedKeyMessage().getString() + " ]";
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
