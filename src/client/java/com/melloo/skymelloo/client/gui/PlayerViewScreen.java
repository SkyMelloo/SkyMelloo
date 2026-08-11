package com.melloo.skymelloo.client.gui;

import com.melloo.skymelloo.client.api.ModAuthManager;
import com.melloo.skymelloo.client.api.SkyMellooApiClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "/skymelloo view &lt;player&gt;" - an in-game recreation of the website's player-stats view:
 * tabs across the top, a scrollable info list, and profile-switcher pills, all read-only. Backed
 * by the same {@link SkyMellooApiClient#fetchSummary} data the chat-based getdata commands use,
 * just laid out as a proper screen instead of a wall of chat messages.
 */
public class PlayerViewScreen extends Screen {
	private static final int PANEL_BG = 0x30000000;
	private static final int ROW_BG_HOVER_BONUS = 0x14FFFFFF;
	private static final int TEXT_ON = 0xFFFFFFFF;
	private static final int TEXT_OFF = 0xFFAAAAAA;
	private static final int ACCENT = 0xFFFF6EC7;
	private static final int ROW_H = 16;
	private static final int ROW_GAP = 2;
	private static final int MARGIN = 10;

	private enum Tab {
		OVERVIEW("skymelloo.gui.player_view.tab.overview"), SKILLS("skymelloo.gui.player_view.tab.skills"), DUNGEONS("skymelloo.gui.player_view.tab.dungeons"), SLAYERS("skymelloo.gui.player_view.tab.slayers"),
		MINIONS("skymelloo.gui.player_view.tab.minions"), BESTIARY("skymelloo.gui.player_view.tab.bestiary"), COLLECTIONS("skymelloo.gui.player_view.tab.collections");

		// Translation key, not display text - resolved at render time since Tab constants are
		// created at class-load, before Minecraft's language system is guaranteed to be ready.
		final String labelKey;

		Tab(String labelKey) {
			this.labelKey = labelKey;
		}
	}

	private interface RowFactory {
		AbstractWidget create(int x, int y, int w, int h);
	}

	private final String username;
	private String profile; // null = the player's currently-selected profile
	private Tab activeTab = Tab.OVERVIEW;

	private final List<AbstractWidget> contentWidgets = new ArrayList<>();
	private final List<AbstractWidget> chromeWidgets = new ArrayList<>();
	private List<String> profileNames = List.of();
	private SkyMellooApiClient.SummaryResult data;
	private String errorMessage;
	private boolean loading = true;
	private long requestId = 0;

	private int scrollOffset = 0;
	private int maxScroll = 0;
	private int listX1, listX2, listTop, listBottom;

	public PlayerViewScreen(String username) {
		super(Component.translatable("skymelloo.gui.player_view.title"));
		this.username = username;
	}

	/** Resolves a translation key to its display string, for row labels rendered as raw text. */
	private static String tr(String key) {
		return Component.translatable(key).getString();
	}

	public static void open(String username) {
		Minecraft.getInstance().setScreen(new PlayerViewScreen(username));
	}

	@Override
	protected void init() {
		listX1 = MARGIN;
		listX2 = this.width - MARGIN;
		int y = MARGIN + 40;

		// Profile pills row (rebuilt whenever profileNames arrives) and the tab bar sit above the
		// scrollable list - both rebuilt by rebuildChrome()/rebuildRows() rather than being fixed
		// widgets, since the profile list only becomes known after the first fetch completes.
		rebuildChrome();
		listTop = y + 24 + 6;
		listBottom = this.height - MARGIN;

		if (data == null && errorMessage == null) {
			loadData();
		} else {
			buildRows();
		}
	}

	private void loadData() {
		loading = true;
		errorMessage = null;
		long thisRequest = ++requestId;

		ModAuthManager.getIdentity(Minecraft.getInstance()).thenCompose(identity -> SkyMellooApiClient.fetchProfileNames(username, identity))
				.whenComplete((names, err) ->
						Minecraft.getInstance().execute(() -> {
							if (thisRequest != requestId || Minecraft.getInstance().screen != this) {
								return;
							}
							if (err == null && names != null) {
								profileNames = names;
								rebuildChrome();
							}
						})
				);

		ModAuthManager.getIdentity(Minecraft.getInstance()).thenCompose(identity -> SkyMellooApiClient.fetchSummary(username, profile, identity))
				.whenComplete((summary, err) ->
						Minecraft.getInstance().execute(() -> {
							if (thisRequest != requestId || Minecraft.getInstance().screen != this) {
								return;
							}
							loading = false;
							if (err != null) {
								errorMessage = err.getMessage();
								data = null;
							} else {
								data = summary;
								errorMessage = null;
							}
							buildRows();
						})
				);
	}

	private void switchProfile(String newProfile) {
		if (java.util.Objects.equals(newProfile, profile)) {
			return;
		}
		profile = newProfile;
		scrollOffset = 0;
		loadData();
	}

	private void switchTab(Tab tab) {
		if (tab == activeTab) {
			return;
		}
		activeTab = tab;
		scrollOffset = 0;
		buildRows();
	}

	/** Rebuilds the profile-pill row and tab bar - called on init and whenever the profile list arrives. */
	private void rebuildChrome() {
		for (AbstractWidget widget : chromeWidgets) {
			removeWidget(widget);
		}
		chromeWidgets.clear();

		int y = MARGIN + 40;
		if (!profileNames.isEmpty()) {
			int pillH = 16;
			int x = listX1;
			for (String name : profileNames) {
				int w = Minecraft.getInstance().font.width(name) + 16;
				ProfilePillWidget pill = new ProfilePillWidget(x, y, w, pillH, name);
				chromeWidgets.add(pill);
				addRenderableWidget(pill);
				x += w + 4;
			}
			y += pillH + 6;
		}

		int tabH = 18;
		Tab[] tabs = Tab.values();
		int tabW = (listX2 - listX1) / tabs.length;
		for (int i = 0; i < tabs.length; i++) {
			Tab tab = tabs[i];
			int x = listX1 + i * tabW;
			int w = (i == tabs.length - 1) ? (listX2 - x) : (tabW - 2);
			TabButtonWidget button = new TabButtonWidget(x, y, w, tabH, tab);
			chromeWidgets.add(button);
			addRenderableWidget(button);
		}
		listTop = y + tabH + 6;
		listBottom = this.height - MARGIN;
	}

	private void buildRows() {
		for (AbstractWidget widget : contentWidgets) {
			removeWidget(widget);
		}
		contentWidgets.clear();

		List<RowFactory> rows = new ArrayList<>();
		if (loading) {
			rows.add(textRow(Component.translatable("skymelloo.gui.player_view.loading", username).getString()));
		} else if (errorMessage != null) {
			rows.add(textRow(Component.translatable("skymelloo.gui.player_view.error", errorMessage).getString()));
		} else if (data != null) {
			rows.addAll(rowsFor(activeTab, data));
		}

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

	private List<RowFactory> rowsFor(Tab tab, SkyMellooApiClient.SummaryResult d) {
		List<RowFactory> rows = new ArrayList<>();
		switch (tab) {
			case OVERVIEW -> {
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.skyblock_level"), String.valueOf(d.skyblockLevel())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.rank"), d.rankLabel() != null ? d.rankLabel() : tr("skymelloo.gui.player_view.value.none")));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.guild"), d.guildName() != null ? (d.guildTag() != null ? Component.translatable("skymelloo.gui.player_view.value.guild_with_tag", d.guildName(), d.guildTag()).getString() : d.guildName()) : tr("skymelloo.gui.player_view.value.none")));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.purse"), formatAmount(d.purse())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.bank"), formatAmount(d.bank())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.networth"), formatAmount(d.netWorth())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.fairy_souls"), String.valueOf(d.fairySouls())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.pets"), d.bestPetLabel() != null ? Component.translatable("skymelloo.gui.player_view.value.pets_with_best", d.petCount(), d.bestPetLabel()).getString() : String.valueOf(d.petCount())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.minion_slots"), String.valueOf(d.minionSlots())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.dungeon_runs"), String.valueOf(d.dungeonCompletions())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.bestiary_kills"), String.valueOf(d.bestiaryKills())));
				if (d.firstJoin() > 0) {
					long days = (System.currentTimeMillis() - d.firstJoin()) / 86_400_000L;
					rows.add(infoRow(tr("skymelloo.gui.player_view.row.playing_since"), Component.translatable("skymelloo.gui.player_view.value.days_ago", days).getString()));
				}
			}
			case SKILLS -> {
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.average_level"), String.format("%.1f", d.averageSkillLevel())));
				rows.addAll(mapRows(d.skillLevels()));
			}
			case DUNGEONS -> {
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.catacombs"), String.valueOf(d.catacombsLevel())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.selected_class"), d.selectedClass() != null ? d.selectedClass() : tr("skymelloo.gui.player_view.value.none")));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.highest_floor"), d.highestFloor() == 0 ? tr("skymelloo.gui.player_view.value.none") : "F/M" + d.highestFloor()));
				rows.addAll(mapRows(d.classLevels()));
			}
			case SLAYERS -> rows.addAll(mapRows(d.slayerLevels()));
			case MINIONS -> {
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.unique_minions"), String.valueOf(d.minionUniqueCount())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.total_upgrades"), String.valueOf(d.minionUpgrades())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.minion_slots"), String.valueOf(d.minionSlots())));
			}
			case BESTIARY -> rows.add(infoRow(tr("skymelloo.gui.player_view.row.total_kills"), String.valueOf(d.bestiaryKills())));
			case COLLECTIONS -> rows.add(infoRow(tr("skymelloo.gui.player_view.row.collections_started"), String.valueOf(d.collectionsStarted())));
		}
		return rows;
	}

	private List<RowFactory> mapRows(Map<String, Integer> levels) {
		List<RowFactory> rows = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : levels.entrySet()) {
			String name = entry.getKey().substring(0, 1).toUpperCase(java.util.Locale.ROOT) + entry.getKey().substring(1);
			rows.add(infoRow(name, String.valueOf(entry.getValue())));
		}
		if (rows.isEmpty()) {
			rows.add(textRow(tr("skymelloo.gui.player_view.no_data")));
		}
		return rows;
	}

	private static String formatAmount(double amount) {
		if (amount >= 1_000_000_000) {
			return String.format("%.2fB", amount / 1_000_000_000);
		}
		if (amount >= 1_000_000) {
			return String.format("%.2fM", amount / 1_000_000);
		}
		if (amount >= 1_000) {
			return String.format("%.1fK", amount / 1_000);
		}
		return String.format("%.0f", amount);
	}

	private RowFactory infoRow(String label, String value) {
		return (x, y, w, h) -> new InfoRowWidget(x, y, w, h, label, value);
	}

	private RowFactory textRow(String text) {
		return (x, y, w, h) -> new InfoRowWidget(x, y, w, h, text, "");
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseX >= listX1 && mouseX <= listX2 && mouseY >= listTop && mouseY <= listBottom && maxScroll > 0) {
			int next = (int) Math.round(scrollOffset - scrollY * (ROW_H + ROW_GAP));
			scrollOffset = Math.max(0, Math.min(next, maxScroll));
			buildRows();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
		gg.fill(0, 0, this.width, this.height, PANEL_BG);
		gg.centeredText(this.font, username, this.width / 2, MARGIN + 6, TEXT_ON);
		gg.centeredText(this.font, tr("skymelloo.gui.player_view.hint"), this.width / 2, MARGIN + 20, TEXT_OFF);
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
			int bg = active ? 0x40FF6EC7 : (this.isHovered() ? 0x20FFFFFF : 0x00000000);
			if (bg != 0) {
				gg.fill(x1, y1, x2, y2, bg);
			}
			if (active) {
				gg.fill(x1, y2 - 2, x2, y2, ACCENT);
			}
			gg.centeredText(Minecraft.getInstance().font, tr(tab.labelKey), (x1 + x2) / 2, y1 + (getHeight() - 8) / 2, active ? TEXT_ON : TEXT_OFF);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			switchTab(tab);
		}
	}

	private final class ProfilePillWidget extends AbstractWidget {
		private final String name;

		ProfilePillWidget(int x, int y, int width, int height, String name) {
			super(x, y, width, height, Component.literal(name));
			this.name = name;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
			boolean active = name.equals(profile);
			int x1 = getX();
			int y1 = getY();
			int x2 = getX() + getWidth();
			int y2 = getY() + getHeight();
			int bg = active ? 0x40FF6EC7 : (this.isHovered() ? 0x20FFFFFF : 0x14FFFFFF);
			gg.fill(x1, y1, x2, y2, bg);
			gg.centeredText(Minecraft.getInstance().font, name, (x1 + x2) / 2, y1 + (getHeight() - 8) / 2, active ? TEXT_ON : TEXT_OFF);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			switchProfile(name);
		}
	}

	private final class InfoRowWidget extends AbstractWidget {
		private final String label;
		private final String value;

		InfoRowWidget(int x, int y, int width, int height, String label, String value) {
			super(x, y, width, height, Component.literal(label));
			this.label = label;
			this.value = value;
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
			gg.text(font, label, x1 + 2, y1 + (getHeight() - 8) / 2, TEXT_ON);
			if (!value.isEmpty()) {
				int valueWidth = font.width(value);
				gg.text(font, value, x2 - valueWidth - 4, y1 + (getHeight() - 8) / 2, ACCENT);
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			// Not interactive - just a stat display.
		}
	}
}
