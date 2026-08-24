package com.melloo.skymelloo.client.gui;

import com.melloo.skymelloo.client.api.ModAuthManager;
import com.melloo.skymelloo.client.api.SkyMellooApiClient;
import com.melloo.skymelloo.client.util.ChatUtil;
import com.melloo.skymelloo.client.util.TickDelay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "/skymelloo view &lt;player&gt;" - an in-game recreation of the website's player-stats view:
 * a scrollable tab bar, item grids with real icons and lore tooltips, and progress bars, all
 * read-only. Backed by {@link SkyMellooApiClient#fetchSummary} and {@link SkyMellooApiClient#fetchInventory}.
 */
public class PlayerViewScreen extends Screen {
	private static final int PANEL_BG = 0xE8101014;
	private static final int CARD_BG = 0x18FFFFFF;
	private static final int SLOT_BG = 0x24FFFFFF;
	private static final int ROW_BG_HOVER_BONUS = 0x14FFFFFF;
	private static final int TEXT_ON = 0xFFFFFFFF;
	private static final int TEXT_OFF = 0xFFAAAAAA;
	private static final int TEXT_DIM = 0xFF6E6E78;
	private static final int ACCENT = 0xFFFF6EC7;
	private static final int ROW_H = 16;
	private static final int ROW_GAP = 2;
	private static final int MARGIN = 10;
	private static final int SLOT = 18;
	private static final int SLOT_PITCH = 20;
	private static final int TAB_WIDTH = 74;
	private static final int TAB_H = 18;
	private static final int HEADER_H = 34;

	private enum Tab {
		OVERVIEW("skymelloo.gui.player_view.tab.overview"), NETWORTH("skymelloo.gui.player_view.tab.networth"),
		GEAR("skymelloo.gui.player_view.tab.gear"), ACCESSORIES("skymelloo.gui.player_view.tab.accessories"),
		PETS("skymelloo.gui.player_view.tab.pets"), INVENTORY("skymelloo.gui.player_view.tab.inventory"),
		SACKS("skymelloo.gui.player_view.tab.sacks"), SKILLS("skymelloo.gui.player_view.tab.skills"),
		DUNGEONS("skymelloo.gui.player_view.tab.dungeons"), SLAYERS("skymelloo.gui.player_view.tab.slayers"),
		MINIONS("skymelloo.gui.player_view.tab.minions"), BESTIARY("skymelloo.gui.player_view.tab.bestiary"),
		COLLECTIONS("skymelloo.gui.player_view.tab.collections");

		// Translation key, not display text - resolved at render time since Tab constants are
		// created at class-load, before Minecraft's language system is guaranteed to be ready.
		final String labelKey;

		Tab(String labelKey) {
			this.labelKey = labelKey;
		}
	}

	/** Whether a tab needs the inventory endpoint, which is fetched separately from the summary. */
	private static boolean needsInventory(Tab tab) {
		return tab == Tab.GEAR || tab == Tab.ACCESSORIES || tab == Tab.INVENTORY || tab == Tab.SACKS;
	}

	private interface RowFactory {
		AbstractWidget create(int x, int y, int w, int h);
	}

	private record Row(int height, RowFactory factory) {
	}

	private final String username;
	private String profile; // null = the player's currently-selected profile
	private Tab activeTab = Tab.OVERVIEW;

	private final List<AbstractWidget> contentWidgets = new ArrayList<>();
	private final List<AbstractWidget> chromeWidgets = new ArrayList<>();
	private List<String> profileNames = List.of();
	private SkyMellooApiClient.SummaryResult data;
	private SkyMellooApiClient.InventoryResult inventory;
	private String errorMessage;
	private boolean loading = true;
	private boolean inventoryLoading = false;
	private long requestId = 0;

	private int scrollOffset = 0;
	private int maxScroll = 0;
	private int tabScrollX = 0;
	private int tabBarMaxScrollX = 0;
	private int tabBarY = 0;
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
		// Deferred a tick: Minecraft closes the chat screen right after a command runs, which would wipe this one.
		TickDelay.schedule(1, () -> Minecraft.getInstance().setScreen(new PlayerViewScreen(username)));
	}

	@Override
	protected void init() {
		// setScreen() runs init() outside the command's own try/catch, so failures here need their own reporting.
		try {
			listX1 = MARGIN;
			listX2 = this.width - MARGIN;
			rebuildChrome();
			if (data == null && errorMessage == null) {
				loadData();
			} else {
				buildRows();
			}
		} catch (Throwable t) {
			Minecraft client = Minecraft.getInstance();
			client.setScreen(null);
			if (client.player != null) {
				client.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.command.common.failed", ChatUtil.friendlyError(t))));
			}
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
								errorMessage = ChatUtil.friendlyError(err);
								data = null;
							} else {
								data = summary;
								errorMessage = null;
							}
							buildRows();
						})
				);

		if (needsInventory(activeTab)) {
			loadInventory(thisRequest);
		}
	}

	/** Separate from the summary - only fetched once, and only for the tabs that actually show items. */
	private void loadInventory(long thisRequest) {
		if (inventory != null || inventoryLoading) {
			return;
		}
		inventoryLoading = true;
		ModAuthManager.getIdentity(Minecraft.getInstance()).thenCompose(identity -> SkyMellooApiClient.fetchInventory(username, profile, identity))
				.whenComplete((result, err) ->
						Minecraft.getInstance().execute(() -> {
							if (thisRequest != requestId || Minecraft.getInstance().screen != this) {
								return;
							}
							inventoryLoading = false;
							if (err == null) {
								inventory = result;
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
		inventory = null;
		loadData();
	}

	private void switchTab(Tab tab) {
		if (tab == activeTab) {
			return;
		}
		activeTab = tab;
		scrollOffset = 0;
		if (needsInventory(tab)) {
			loadInventory(requestId);
		}
		buildRows();
	}

	/** Rebuilds the profile-pill row and tab bar - called on init and whenever the profile list arrives. */
	private void rebuildChrome() {
		for (AbstractWidget widget : chromeWidgets) {
			removeWidget(widget);
		}
		chromeWidgets.clear();

		int y = MARGIN + HEADER_H;
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

		tabBarY = y;
		Tab[] tabs = Tab.values();
		tabBarMaxScrollX = Math.max(0, tabs.length * TAB_WIDTH - (listX2 - listX1));
		tabScrollX = Math.max(0, Math.min(tabScrollX, tabBarMaxScrollX));
		for (int i = 0; i < tabs.length; i++) {
			int x = listX1 - tabScrollX + i * TAB_WIDTH;
			TabButtonWidget button = new TabButtonWidget(x, y, TAB_WIDTH - 2, TAB_H, tabs[i]);
			boolean onScreen = x + TAB_WIDTH - 2 > listX1 && x < listX2;
			button.visible = onScreen;
			button.active = onScreen;
			chromeWidgets.add(button);
			addRenderableWidget(button);
		}
		listTop = y + TAB_H + (tabBarMaxScrollX > 0 ? 8 : 6);
		listBottom = this.height - MARGIN;
	}

	private void buildRows() {
		for (AbstractWidget widget : contentWidgets) {
			removeWidget(widget);
		}
		contentWidgets.clear();

		List<Row> rows = new ArrayList<>();
		if (loading) {
			rows.add(textRow(Component.translatable("skymelloo.gui.player_view.loading", username).getString()));
		} else if (errorMessage != null) {
			rows.add(textRow(Component.translatable("skymelloo.gui.player_view.error", errorMessage).getString()));
		} else if (data != null) {
			rows.addAll(rowsFor(activeTab, data));
		}

		int w = listX2 - listX1;
		int visibleHeight = listBottom - listTop;
		int contentHeight = 0;
		for (Row row : rows) {
			contentHeight += row.height() + ROW_GAP;
		}
		maxScroll = Math.max(0, contentHeight - visibleHeight);
		scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

		int y = listTop - scrollOffset;
		for (Row row : rows) {
			AbstractWidget widget = row.factory().create(listX1, y, w, row.height());
			boolean onScreen = y + row.height() > listTop && y < listBottom;
			widget.visible = onScreen;
			widget.active = onScreen;
			contentWidgets.add(widget);
			addRenderableWidget(widget);
			y += row.height() + ROW_GAP;
		}
	}

	private List<Row> rowsFor(Tab tab, SkyMellooApiClient.SummaryResult d) {
		List<Row> rows = new ArrayList<>();
		switch (tab) {
			case OVERVIEW -> {
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.skyblock_level"), String.valueOf(d.skyblockLevel())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.rank"), or(d.rankLabel())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.guild"), d.guildName() != null
						? (d.guildTag() != null ? Component.translatable("skymelloo.gui.player_view.value.guild_with_tag", d.guildName(), d.guildTag()).getString() : d.guildName())
						: tr("skymelloo.gui.player_view.value.none")));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.game_mode"), or(d.gameMode())));
				rows.add(sectionRow(tr("skymelloo.gui.player_view.section.wealth")));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.purse"), formatAmount(d.purse())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.bank"), formatAmount(d.bank())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.networth"), formatAmount(d.netWorth())));
				rows.add(sectionRow(tr("skymelloo.gui.player_view.section.progress")));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.average_level"), String.format(Locale.ROOT, "%.1f", d.averageSkillLevel())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.catacombs"), String.valueOf(d.catacombsLevel())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.fairy_souls"), String.valueOf(d.fairySouls())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.pets"), d.bestPetLabel() != null
						? Component.translatable("skymelloo.gui.player_view.value.pets_with_best", d.petCount(), d.bestPetLabel()).getString()
						: String.valueOf(d.petCount())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.minion_slots"), String.valueOf(d.minionSlots())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.dungeon_runs"), String.valueOf(d.dungeonCompletions())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.bestiary_kills"), String.valueOf(d.bestiaryKills())));
				if (d.firstJoin() > 0) {
					long days = (System.currentTimeMillis() - d.firstJoin()) / 86_400_000L;
					rows.add(infoRow(tr("skymelloo.gui.player_view.row.playing_since"), Component.translatable("skymelloo.gui.player_view.value.days_ago", days).getString()));
				}
				if (!d.combatStats().isEmpty()) {
					rows.add(sectionRow(tr("skymelloo.gui.player_view.section.combat_stats")));
					for (Map.Entry<String, Double> entry : d.combatStats().entrySet()) {
						rows.add(infoRow(titleCase(entry.getKey()), String.format(Locale.ROOT, "%.0f", entry.getValue())));
					}
				}
			}
			case NETWORTH -> {
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.networth_total"), formatAmount(d.netWorth())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.networth_non_cosmetic"), formatAmount(d.netWorthNonCosmetic())));
				if (d.netWorthCategories().isEmpty()) {
					rows.add(textRow(tr("skymelloo.gui.player_view.no_data")));
					break;
				}
				rows.add(sectionRow(tr("skymelloo.gui.player_view.section.breakdown")));
				double max = d.netWorthCategories().values().stream().mapToDouble(Math::abs).max().orElse(1);
				d.netWorthCategories().entrySet().stream()
						.sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
						.forEach(entry -> rows.add(barRow(titleCase(entry.getKey()), formatAmount(entry.getValue()), entry.getValue() / max, ACCENT)));
			}
			case GEAR -> {
				if (!inventoryReady(rows)) {
					break;
				}
				addItemSection(rows, tr("skymelloo.gui.player_view.section.armor"), inventory.armor());
				addItemSection(rows, tr("skymelloo.gui.player_view.section.equipment"), inventory.equipment());
			}
			case ACCESSORIES -> {
				if (!inventoryReady(rows)) {
					break;
				}
				if (inventory.accessoryPower() >= 0) {
					rows.add(infoRow(tr("skymelloo.gui.player_view.row.accessory_power"), String.valueOf(inventory.accessoryPower())));
				}
				if (inventory.selectedPower() != null) {
					rows.add(infoRow(tr("skymelloo.gui.player_view.row.selected_power"), titleCase(inventory.selectedPower())));
				}
				addItemSection(rows, tr("skymelloo.gui.player_view.section.accessories"), inventory.accessories());
			}
			case INVENTORY -> {
				if (!inventoryReady(rows)) {
					break;
				}
				addItemSection(rows, tr("skymelloo.gui.player_view.section.inventory"), inventory.inventory());
				addItemSection(rows, tr("skymelloo.gui.player_view.section.ender_chest"), inventory.enderChest());
				addItemSection(rows, tr("skymelloo.gui.player_view.section.vault"), inventory.vault());
			}
			case SACKS -> {
				if (!inventoryReady(rows)) {
					break;
				}
				if (inventory.sacks().isEmpty()) {
					rows.add(textRow(tr("skymelloo.gui.player_view.no_data")));
					break;
				}
				for (SkyMellooApiClient.SackEntry sack : inventory.sacks()) {
					rows.add(infoRow(titleCase(sack.id()), formatCount(sack.amount())));
				}
			}
			case PETS -> {
				if (d.pets().isEmpty()) {
					rows.add(textRow(tr("skymelloo.gui.player_view.no_data")));
					break;
				}
				for (SkyMellooApiClient.PetEntry pet : d.pets()) {
					int maxLevel = pet.maxLevel() > 0 ? pet.maxLevel() : 100;
					String label = titleCase(pet.type()) + (pet.active() ? " " + tr("skymelloo.gui.player_view.value.active_suffix") : "");
					rows.add(barRow(label, "Lv " + pet.level() + "/" + maxLevel,
							pet.level() / (double) maxLevel, SkyblockItemIcons.tierColor(pet.tier())));
				}
			}
			case SKILLS -> {
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.average_level"), String.format(Locale.ROOT, "%.1f", d.averageSkillLevel())));
				rows.add(sectionRow(tr("skymelloo.gui.player_view.tab.skills")));
				addLevelBars(rows, d.skillLevels(), 60);
			}
			case DUNGEONS -> {
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.catacombs"), String.valueOf(d.catacombsLevel())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.selected_class"), or(d.selectedClass())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.highest_floor"), d.highestFloor() == 0
						? tr("skymelloo.gui.player_view.value.none") : "F" + d.highestFloor()));
				rows.add(sectionRow(tr("skymelloo.gui.player_view.section.classes")));
				addLevelBars(rows, d.classLevels(), 50);
				addFloorSection(rows, tr("skymelloo.gui.player_view.section.floors"), d.floors());
				addFloorSection(rows, tr("skymelloo.gui.player_view.section.master_floors"), d.masterFloors());
			}
			case SLAYERS -> addLevelBars(rows, d.slayerLevels(), 9);
			case MINIONS -> {
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.unique_minions"), String.valueOf(d.minionUniqueCount())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.total_upgrades"), String.valueOf(d.minionUpgrades())));
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.minion_slots"), String.valueOf(d.minionSlots())));
			}
			case BESTIARY -> rows.add(infoRow(tr("skymelloo.gui.player_view.row.total_kills"), formatCount(d.bestiaryKills())));
			case COLLECTIONS -> {
				rows.add(infoRow(tr("skymelloo.gui.player_view.row.collections_started"), String.valueOf(d.collectionsStarted())));
				if (d.collections().isEmpty()) {
					rows.add(textRow(tr("skymelloo.gui.player_view.no_data")));
					break;
				}
				for (SkyMellooApiClient.CollectionGroup group : d.collections()) {
					rows.add(sectionRow(group.category()));
					for (SkyMellooApiClient.CollectionEntry entry : group.items()) {
						int maxTier = entry.maxTier() > 0 ? entry.maxTier() : 1;
						rows.add(barRow(entry.name(), formatCount(entry.amount()) + "  T" + entry.tier() + "/" + maxTier,
								entry.tier() / (double) maxTier, ACCENT));
					}
				}
			}
		}
		if (rows.isEmpty()) {
			rows.add(textRow(tr("skymelloo.gui.player_view.no_data")));
		}
		return rows;
	}

	/** Adds a loading/empty placeholder and reports whether the inventory is actually usable yet. */
	private boolean inventoryReady(List<Row> rows) {
		if (inventory == null) {
			rows.add(textRow(tr(inventoryLoading ? "skymelloo.gui.player_view.loading_items" : "skymelloo.gui.player_view.no_data")));
			return false;
		}
		return true;
	}

	private void addItemSection(List<Row> rows, String title, List<SkyMellooApiClient.SkyblockItem> items) {
		List<SkyMellooApiClient.SkyblockItem> present = items.stream().filter(i -> i.name() != null).toList();
		if (present.isEmpty()) {
			return;
		}
		rows.add(sectionRow(title + " (" + present.size() + ")"));
		int cols = Math.max(1, (listX2 - listX1 - 4) / SLOT_PITCH);
		for (int i = 0; i < present.size(); i += cols) {
			List<SkyMellooApiClient.SkyblockItem> chunk = present.subList(i, Math.min(present.size(), i + cols));
			rows.add(new Row(SLOT_PITCH, (x, y, w, h) -> new ItemGridWidget(x, y, w, h, chunk)));
		}
	}

	private void addFloorSection(List<Row> rows, String title, List<SkyMellooApiClient.FloorEntry> floors) {
		if (floors.isEmpty()) {
			return;
		}
		rows.add(sectionRow(title));
		for (SkyMellooApiClient.FloorEntry floor : floors) {
			String value = floor.completions() + "x";
			if (floor.bestScore() != null) {
				value += "  " + tr("skymelloo.gui.player_view.value.best_score") + " " + floor.bestScore();
			}
			if (floor.fastestTimeMs() != null && floor.fastestTimeMs() > 0) {
				value += "  " + formatDuration(floor.fastestTimeMs());
			}
			rows.add(infoRow(floor.floor(), value));
		}
	}

	private void addLevelBars(List<Row> rows, Map<String, Integer> levels, int maxLevel) {
		if (levels.isEmpty()) {
			rows.add(textRow(tr("skymelloo.gui.player_view.no_data")));
			return;
		}
		for (Map.Entry<String, Integer> entry : levels.entrySet()) {
			rows.add(barRow(titleCase(entry.getKey()), String.valueOf(entry.getValue()),
					entry.getValue() / (double) maxLevel, ACCENT));
		}
	}

	private static String or(String value) {
		return value != null && !value.isBlank() ? value : tr("skymelloo.gui.player_view.value.none");
	}

	private static String titleCase(String raw) {
		if (raw == null || raw.isEmpty()) {
			return "?";
		}
		String[] parts = raw.replace('-', '_').split("_");
		StringBuilder sb = new StringBuilder();
		for (String part : parts) {
			if (part.isEmpty()) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase(Locale.ROOT));
		}
		return sb.toString();
	}

	private static String formatAmount(double amount) {
		if (Math.abs(amount) >= 1_000_000_000) {
			return String.format(Locale.ROOT, "%.2fB", amount / 1_000_000_000);
		}
		if (Math.abs(amount) >= 1_000_000) {
			return String.format(Locale.ROOT, "%.2fM", amount / 1_000_000);
		}
		if (Math.abs(amount) >= 1_000) {
			return String.format(Locale.ROOT, "%.1fK", amount / 1_000);
		}
		return String.format(Locale.ROOT, "%.0f", amount);
	}

	private static String formatCount(long count) {
		if (count >= 1_000_000) {
			return String.format(Locale.ROOT, "%.2fM", count / 1_000_000.0);
		}
		if (count >= 10_000) {
			return String.format(Locale.ROOT, "%.1fK", count / 1_000.0);
		}
		return String.valueOf(count);
	}

	private static String formatDuration(long millis) {
		long totalSeconds = millis / 1000;
		return (totalSeconds / 60) + "m " + (totalSeconds % 60) + "s";
	}

	private Row infoRow(String label, String value) {
		return new Row(ROW_H, (x, y, w, h) -> new InfoRowWidget(x, y, w, h, label, value));
	}

	private Row textRow(String text) {
		return new Row(ROW_H, (x, y, w, h) -> new InfoRowWidget(x, y, w, h, text, ""));
	}

	private Row sectionRow(String title) {
		return new Row(ROW_H + 4, (x, y, w, h) -> new SectionRowWidget(x, y, w, h, title));
	}

	private Row barRow(String label, String value, double progress, int color) {
		return new Row(ROW_H, (x, y, w, h) -> new BarRowWidget(x, y, w, h, label, value, progress, color));
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		// Over the tab bar the wheel pans it sideways, since 13 tabs never fit at once.
		if (mouseY >= tabBarY && mouseY <= tabBarY + TAB_H && tabBarMaxScrollX > 0) {
			tabScrollX = Math.max(0, Math.min(tabScrollX - (int) (scrollY * TAB_WIDTH), tabBarMaxScrollX));
			rebuildChrome();
			buildRows();
			return true;
		}
		if (mouseX >= listX1 && mouseX <= listX2 && mouseY >= listTop && mouseY <= listBottom && maxScroll > 0) {
			int next = (int) Math.round(scrollOffset - scrollY * (ROW_H + ROW_GAP) * 3);
			scrollOffset = Math.max(0, Math.min(next, maxScroll));
			buildRows();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
		// A render-time throw would otherwise fail this frame silently with nothing visible at all.
		try {
			gg.fill(0, 0, this.width, this.height, PANEL_BG);
			gg.fill(0, 0, this.width, MARGIN + HEADER_H - 4, CARD_BG);
			gg.fill(0, MARGIN + HEADER_H - 4, this.width, MARGIN + HEADER_H - 3, 0x33FF6EC7);

			gg.text(this.font, username, listX1, MARGIN + 4, TEXT_ON);
			if (data != null) {
				int x = listX1 + this.font.width(username) + 8;
				if (data.rankLabel() != null) {
					gg.text(this.font, data.rankLabel(), x, MARGIN + 4, ACCENT);
					x += this.font.width(data.rankLabel()) + 8;
				}
				if (data.guildName() != null) {
					String guild = data.guildTag() != null ? data.guildName() + " [" + data.guildTag() + "]" : data.guildName();
					gg.text(this.font, guild, x, MARGIN + 4, TEXT_OFF);
				}
				String subtitle = tr("skymelloo.gui.player_view.row.skyblock_level") + " " + data.skyblockLevel()
						+ "   " + tr("skymelloo.gui.player_view.row.networth") + " " + formatAmount(data.netWorth());
				gg.text(this.font, subtitle, listX1, MARGIN + 16, TEXT_DIM);
			}
			gg.text(this.font, tr("skymelloo.gui.player_view.hint"), listX1, MARGIN + 26, TEXT_DIM);

			if (maxScroll > 0) {
				int trackX = listX2 - 3;
				int trackHeight = listBottom - listTop;
				gg.fill(trackX, listTop, trackX + 3, listBottom, 0x30FFFFFF);
				int thumbHeight = Math.max(14, trackHeight * trackHeight / (trackHeight + maxScroll));
				int thumbY = listTop + (int) ((trackHeight - thumbHeight) * (scrollOffset / (double) maxScroll));
				gg.fill(trackX, thumbY, trackX + 3, thumbY + thumbHeight, ACCENT);
			}

			if (tabBarMaxScrollX > 0) {
				int trackY = tabBarY + TAB_H + 1;
				int trackWidth = listX2 - listX1;
				gg.fill(listX1, trackY, listX2, trackY + 2, 0x30FFFFFF);
				int thumbWidth = Math.max(20, trackWidth * trackWidth / (trackWidth + tabBarMaxScrollX));
				int thumbX = listX1 + (int) ((trackWidth - thumbWidth) * (tabScrollX / (double) tabBarMaxScrollX));
				gg.fill(thumbX, trackY, thumbX + thumbWidth, trackY + 2, ACCENT);
			}

			super.extractRenderState(gg, mouseX, mouseY, partialTick);
		} catch (Throwable t) {
			gg.fill(0, 0, this.width, this.height, 0xE0000000);
			gg.text(this.font, "SkyMelloo view error: " + t, 10, 10, 0xFFFF5555);
		}
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

	/** One row of item slots, with a real icon, count and the item's own lore as a hover tooltip. */
	private final class ItemGridWidget extends AbstractWidget {
		private final List<SkyMellooApiClient.SkyblockItem> items;

		ItemGridWidget(int x, int y, int width, int height, List<SkyMellooApiClient.SkyblockItem> items) {
			super(x, y, width, height, Component.empty());
			this.items = items;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
			var font = Minecraft.getInstance().font;
			for (int i = 0; i < items.size(); i++) {
				SkyMellooApiClient.SkyblockItem item = items.get(i);
				int x = getX() + i * SLOT_PITCH;
				int y = getY();
				boolean hovered = mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT;
				gg.fill(x, y, x + SLOT, y + SLOT, hovered ? 0x40FFFFFF : SLOT_BG);
				gg.outline(x, y, SLOT, SLOT, SkyblockItemIcons.tierColor(item.tier()) & 0x66FFFFFF);
				ItemStack stack = SkyblockItemIcons.resolve(item.skyblockId(), item.legacyId(), item.tier(), item.name())
						.copyWithCount(item.count());
				gg.item(stack, x + 1, y + 1);
				gg.itemDecorations(font, stack, x + 1, y + 1);
				if (hovered) {
					List<Component> tooltip = new ArrayList<>();
					tooltip.add(Component.literal(item.name() != null ? item.name() : "?")
							.withColor(SkyblockItemIcons.tierColor(item.tier()) & 0xFFFFFF));
					for (String line : item.lore()) {
						tooltip.add(Component.literal(line).withColor(0xAAAAAA));
					}
					if (item.value() != null) {
						tooltip.add(Component.literal(tr("skymelloo.gui.player_view.value.worth") + " " + formatAmount(item.value())).withColor(0xFFAA00));
					}
					gg.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
				}
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			// Read-only, same as every other row here.
		}
	}

	private final class SectionRowWidget extends AbstractWidget {
		private final String title;

		SectionRowWidget(int x, int y, int width, int height, String title) {
			super(x, y, width, height, Component.literal(title));
			this.title = title;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
			var font = Minecraft.getInstance().font;
			int textY = getY() + getHeight() - 10;
			gg.text(font, title, getX() + 2, textY, ACCENT);
			int lineX = getX() + font.width(title) + 8;
			gg.fill(lineX, textY + 3, getX() + getWidth() - 6, textY + 4, 0x22FFFFFF);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			// Just a heading.
		}
	}

	/** A label with a filled progress track behind it - used for skills, slayers, pets and collections. */
	private final class BarRowWidget extends AbstractWidget {
		private final String label;
		private final String value;
		private final double progress;
		private final int color;

		BarRowWidget(int x, int y, int width, int height, String label, String value, double progress, int color) {
			super(x, y, width, height, Component.literal(label));
			this.label = label;
			this.value = value;
			this.progress = Math.max(0, Math.min(1, progress));
			this.color = color;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
			var font = Minecraft.getInstance().font;
			int x1 = getX();
			int y1 = getY();
			int x2 = getX() + getWidth() - 6;
			int y2 = getY() + getHeight();
			gg.fill(x1, y1, x2, y2, 0x18FFFFFF);
			gg.fill(x1, y1, x1 + (int) ((x2 - x1) * progress), y2, color & 0x55FFFFFF);
			if (this.isHovered()) {
				gg.fill(x1, y1, x2, y2, ROW_BG_HOVER_BONUS);
			}
			int textY = y1 + (getHeight() - 8) / 2;
			gg.text(font, label, x1 + 3, textY, TEXT_ON);
			gg.text(font, value, x2 - font.width(value) - 3, textY, color);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			// Read-only.
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
			int x2 = getX() + getWidth() - 6;
			int y2 = getY() + getHeight();
			if (this.isHovered()) {
				gg.fill(x1, y1, x2, y2, ROW_BG_HOVER_BONUS);
			}
			var font = Minecraft.getInstance().font;
			int textY = y1 + (getHeight() - 8) / 2;
			gg.text(font, label, x1 + 3, textY, TEXT_ON);
			if (!value.isEmpty()) {
				gg.text(font, value, x2 - font.width(value) - 3, textY, ACCENT);
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
