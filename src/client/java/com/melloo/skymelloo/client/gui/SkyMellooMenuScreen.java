package com.melloo.skymelloo.client.gui;

import com.melloo.skymelloo.client.api.SkyMellooApiClient;
import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.cosmetics.MagicMissileManager;
import com.melloo.skymelloo.client.util.TickDelay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A fake chest-style menu, opened via {@link SkyMellooMenuItemManager}'s hotbar item - the same
 * click-through-items UX Hypixel's own SkyBlock Menu uses, just for SkyMelloo's own settings. Purely
 * client-drawn (plain filled rectangles for the slot grid, matching this mod's other custom screens
 * like {@link HudLayoutEditorScreen} - no real vanilla chest texture/Container/Slot involved, since
 * there's no server-side inventory to sync with here at all).
 * <p>
 * Pages only ever produce a flat, unordered {@link MenuAction} list (icon + click handler) - this
 * screen is what turns that into a real grid, auto-flowing through a 45-slot (5-row) content area and
 * splitting into multiple sub-pages with Prev/Next arrows in the bottom corners once a page has more
 * entries than fit on one sheet. A page never has to think about slot numbers or pagination itself.
 */
public class SkyMellooMenuScreen extends Screen {
	private record MenuAction(ItemStack icon, Runnable onClick, Runnable onRightClick) {
		MenuAction(ItemStack icon, Runnable onClick) {
			this(icon, onClick, null);
		}
	}

	private record MenuEntry(int slot, ItemStack icon, Runnable onClick, Runnable onRightClick) {
		MenuEntry(int slot, ItemStack icon, Runnable onClick) {
			this(slot, icon, onClick, null);
		}
	}

	private interface Page {
		String title();

		List<MenuAction> buildActions(SkyMellooMenuScreen screen);

		/** Optional, pinned to the bottom-right corner slot regardless of how the normal flowing entries fill up - see {@link MainPage#cornerAction}. */
		default MenuAction cornerAction(SkyMellooMenuScreen screen) {
			return null;
		}

		/** Optional, pinned to the nav row right next to the Back arrow. */
		default MenuAction navExtraAction(SkyMellooMenuScreen screen) {
			return null;
		}
	}

	private static final int COLS = 9;
	private static final int ROWS = 6;
	private static final int SLOT_SIZE = 18;
	private static final int CONTENT_SLOTS = (ROWS - 1) * COLS; // bottom row reserved for navigation
	private static final int PREV_SLOT = (ROWS - 1) * COLS;
	private static final int BACK_SLOT = (ROWS - 1) * COLS + 4;
	private static final int NEXT_SLOT = ROWS * COLS - 1;

	private final Deque<Page> pageStack = new ArrayDeque<>();
	private int currentSheet = 0;
	private List<MenuEntry> entries = List.of();
	private int gridX;
	private int gridY;

	public SkyMellooMenuScreen() {
		super(Component.translatable("skymelloo.gui.menu.title"));
		pageStack.push(new MainPage());
	}

	/** Resolves a translation key to its display string, for lore/name text built via concatenation. */
	private static String tr(String key) {
		return Component.translatable(key).getString();
	}

	private static String tr(String key, Object... args) {
		return Component.translatable(key, args).getString();
	}

	@Override
	protected void init() {
		gridX = (this.width - COLS * SLOT_SIZE) / 2;
		gridY = (this.height - ROWS * SLOT_SIZE) / 2;
		rebuild();
	}

	private void rebuild() {
		List<MenuAction> actions = pageStack.peek().buildActions(this);
		int totalSheets = Math.max(1, (actions.size() + CONTENT_SLOTS - 1) / CONTENT_SLOTS);
		currentSheet = Math.max(0, Math.min(currentSheet, totalSheets - 1));
		int start = currentSheet * CONTENT_SLOTS;
		int end = Math.min(start + CONTENT_SLOTS, actions.size());

		entries = new ArrayList<>();
		// Centered in the content grid rather than always flowing from the top-left corner - a page
		// with only a handful of entries (most of them) used to leave everything crammed up top
		// instead of looking like an intentional, finished layout. Full rows are left as-is (an
		// entirely full row has no "center" to speak of); only a final partial row gets horizontally
		// centered, and the whole block is centered vertically within the 5 content rows.
		int itemsOnSheet = end - start;
		int contentRows = ROWS - 1;
		int rowsNeeded = Math.max(1, (int) Math.ceil(itemsOnSheet / (double) COLS));
		int verticalOffset = Math.max(0, (contentRows - rowsNeeded) / 2);
		int fullRows = itemsOnSheet / COLS;
		int lastRowCount = itemsOnSheet % COLS;

		int index = start;
		for (int row = 0; row < rowsNeeded && index < end; row++) {
			int itemsInRow = row < fullRows ? COLS : lastRowCount;
			int colOffset = (COLS - itemsInRow) / 2;
			for (int c = 0; c < itemsInRow && index < end; c++) {
				MenuAction action = actions.get(index);
				int slot = (verticalOffset + row) * COLS + colOffset + c;
				entries.add(new MenuEntry(slot, action.icon(), action.onClick(), action.onRightClick()));
				index++;
			}
		}
		MenuAction corner = pageStack.peek().cornerAction(this);
		if (corner != null && (end - start) < CONTENT_SLOTS) {
			entries.add(new MenuEntry(CONTENT_SLOTS - 1, corner.icon(), corner.onClick(), corner.onRightClick()));
		}
		if (currentSheet > 0) {
			entries.add(new MenuEntry(PREV_SLOT, named(Items.ARROW, tr("skymelloo.gui.menu.nav.previous_page"), List.of()), () -> {
				currentSheet--;
				rebuild();
			}));
		}
		if (pageStack.size() > 1) {
			entries.add(new MenuEntry(BACK_SLOT, named(Items.ARROW, tr("skymelloo.gui.menu.nav.back"), List.of()), this::goBack));
		}
		// Always present, on every page - not per-page like navExtraAction below, since a bug can be
		// found from anywhere in this menu.
		entries.add(new MenuEntry(BACK_SLOT - 1, named(Items.WRITABLE_BOOK, tr("skymelloo.gui.menu.nav.report_bug.name"),
				List.of(tr("skymelloo.gui.menu.nav.report_bug.lore_1"), tr("skymelloo.gui.menu.nav.report_bug.lore_2"))), SkyMellooMenuScreen::openReportBug));
		MenuAction navExtra = pageStack.peek().navExtraAction(this);
		if (navExtra != null) {
			entries.add(new MenuEntry(BACK_SLOT + 1, navExtra.icon(), navExtra.onClick(), navExtra.onRightClick()));
		}
		if (currentSheet < totalSheets - 1) {
			entries.add(new MenuEntry(NEXT_SLOT, named(Items.ARROW, tr("skymelloo.gui.menu.nav.next_page"), List.of()), () -> {
				currentSheet++;
				rebuild();
			}));
		}
	}

	private void goBack() {
		if (pageStack.size() > 1) {
			pageStack.pop();
			currentSheet = 0;
			rebuild();
		}
	}

	void push(Page page) {
		pageStack.push(page);
		currentSheet = 0;
		rebuild();
	}

	static void openReportBug() {
		net.minecraft.util.Util.getPlatform().openUri(java.net.URI.create(com.melloo.skymelloo.client.api.SiteConfig.url("/report-bug")));
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int mx = (int) event.x();
		int my = (int) event.y();
		for (MenuEntry entry : entries) {
			int col = entry.slot() % COLS;
			int row = entry.slot() / COLS;
			int x = gridX + col * SLOT_SIZE;
			int y = gridY + row * SLOT_SIZE;
			if (mx >= x && mx < x + SLOT_SIZE && my >= y && my < y + SLOT_SIZE) {
				if (event.button() == 1 && entry.onRightClick() != null) {
					entry.onRightClick().run();
				} else {
					entry.onClick().run();
				}
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
		int panelW = COLS * SLOT_SIZE;
		int panelH = ROWS * SLOT_SIZE;
		gg.fill(gridX - 8, gridY - 20, gridX + panelW + 8, gridY + panelH + 8, 0xEE202020);
		gg.outline(gridX - 8, gridY - 20, panelW + 16, panelH + 28, 0xFF444444);
		gg.centeredText(this.font, pageStack.peek().title(), gridX + panelW / 2, gridY - 14, 0xFFFFFFFF);
		gg.fill(gridX, gridY + (ROWS - 1) * SLOT_SIZE, gridX + panelW, gridY + ROWS * SLOT_SIZE, 0x66000000);

		MenuEntry hovered = null;
		for (MenuEntry entry : entries) {
			int col = entry.slot() % COLS;
			int row = entry.slot() / COLS;
			int x = gridX + col * SLOT_SIZE;
			int y = gridY + row * SLOT_SIZE;
			boolean isHovered = mouseX >= x && mouseX < x + SLOT_SIZE && mouseY >= y && mouseY < y + SLOT_SIZE;
			gg.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, isHovered ? 0xAA5A5A5A : 0xAA373737);
			gg.outline(x, y, SLOT_SIZE, SLOT_SIZE, 0xFF8B8B8B);
			gg.item(entry.icon(), x + 1, y + 1);
			gg.itemDecorations(this.font, entry.icon(), x + 1, y + 1);
			if (isHovered) {
				hovered = entry;
			}
		}
		if (hovered != null) {
			gg.setTooltipForNextFrame(this.font, hovered.icon(), mouseX, mouseY);
		}
		super.extractRenderState(gg, mouseX, mouseY, partialTick);
	}

	private static ItemStack named(Item item, String name, List<String> lore) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
		if (!lore.isEmpty()) {
			stack.set(DataComponents.LORE, new ItemLore(lore.stream().<Component>map(Component::literal).toList()));
		}
		return stack;
	}

	// Styled after Hypixel's own SkyBlock Menu tooltip (title + "(Click)", description, yellow
	// "Click to open!") but in this mod's pink rather than Hypixel's green, matching the pink-dye
	// theming used everywhere else in this menu.
	private static MenuAction linkAction(Item item, String name, String description, Page target, SkyMellooMenuScreen screen) {
		ItemStack icon = named(item, tr("skymelloo.gui.menu.format.link_title", name), List.of(tr("skymelloo.gui.menu.format.description_line", description), "", tr("skymelloo.gui.menu.link.click_to_open")));
		return new MenuAction(icon, () -> screen.push(target));
	}

	/** Green/gray wool, "click to enable/disable" - the standard on/off representation used throughout every settings page below. */
	private static ItemStack toggleIcon(boolean on, String name, String description) {
		return named(on ? Items.LIME_WOOL : Items.GRAY_WOOL, tr(on ? "skymelloo.gui.menu.format.toggle_name_on" : "skymelloo.gui.menu.format.toggle_name_off", name),
				List.of(tr("skymelloo.gui.menu.format.description_line", description), "", on ? tr("skymelloo.gui.menu.toggle.enabled") : tr("skymelloo.gui.menu.toggle.disabled")));
	}

	private static MenuAction toggleAction(String name, String description, BooleanSupplier getter, Consumer<Boolean> setter, SkyMellooMenuScreen screen) {
		return new MenuAction(toggleIcon(getter.getAsBoolean(), name, description), () -> {
			setter.accept(!getter.getAsBoolean());
			SkyMellooConfig.HANDLER.save();
			screen.rebuild();
		});
	}

	/** A LOCAL/PARTY delivery setting - cycles on click rather than a plain on/off. */
	private static MenuAction deliveryAction(String name, String description, Supplier<String> getter, Consumer<String> setter, SkyMellooMenuScreen screen) {
		return cycleAction(Items.PAPER, name, description, getter, setter, new String[] { "LOCAL", "PARTY", "PARTY SM" }, screen);
	}

	/** Any small fixed set of string options (not just LOCAL/PARTY) - cycles to the next option on click, wrapping around. */
	private static MenuAction cycleAction(Item item, String name, String description, Supplier<String> getter, Consumer<String> setter, String[] options, SkyMellooMenuScreen screen) {
		String current = getter.get();
		int index = 0;
		for (int i = 0; i < options.length; i++) {
			if (options[i].equalsIgnoreCase(current)) {
				index = i;
				break;
			}
		}
		int nextIndex = (index + 1) % options.length;
		ItemStack icon = named(item, tr("skymelloo.gui.menu.format.cycle_name", name), List.of(tr("skymelloo.gui.menu.format.description_line", description), "", tr("skymelloo.gui.menu.cycle.current", current), tr("skymelloo.gui.menu.cycle.click_to_cycle")));
		return new MenuAction(icon, () -> {
			setter.accept(options[nextIndex]);
			SkyMellooConfig.HANDLER.save();
			screen.rebuild();
		});
	}

	// ---- pages ----

	private static final class MainPage implements Page {
		@Override
		public String title() {
			return tr("skymelloo.gui.menu.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			List<MenuAction> list = new ArrayList<>();
			list.add(linkAction(Items.WRITABLE_BOOK, tr("skymelloo.gui.menu.link.settings.name"), tr("skymelloo.gui.menu.link.settings.description"), new SettingsPage(), screen));
			list.add(linkAction(Items.BLAZE_ROD, tr("skymelloo.gui.menu.link.spells.name"), tr("skymelloo.gui.menu.link.spells.description"), new SpellsPage(), screen));
			if (com.melloo.mellooessentials.client.config.EssentialsConfig.get().cosmeticsEnabled) {
				// Opens MellooEssentials' own settings screen directly (straight to its Cosmetics tab)
				// instead of maintaining a second, duplicate cosmetics UI here - see that screen's own
				// two-arg constructor.
				list.add(new MenuAction(named(Items.FIREWORK_STAR, tr("skymelloo.gui.menu.link.cosmetics.name"), List.of(tr("skymelloo.gui.menu.link.cosmetics.lore_1"), "", tr("skymelloo.gui.menu.link.cosmetics.lore_2"))), () ->
						Minecraft.getInstance().setScreen(new com.melloo.mellooessentials.client.gui.SettingsScreen(screen, true))));
			}
			list.add(new MenuAction(named(Items.BARRIER, tr("skymelloo.gui.menu.link.games.name"), List.of(tr("skymelloo.gui.menu.link.games.lore_1"))), () -> {
			}));
			return list;
		}

		@Override
		public MenuAction cornerAction(SkyMellooMenuScreen screen) {
			return linkAction(Items.PLAYER_HEAD, tr("skymelloo.gui.menu.link.credits.name"), tr("skymelloo.gui.menu.link.credits.description"), new CreditsPage(), screen);
		}
	}

	// The credit list itself (names/roles) is cached across screen instances - that part changes
	// rarely, no need to re-fetch just for re-opening the menu. The per-entry online/offline status
	// is NOT stable like that though - it should
	// actually update live, so once the Credits page is open, creditsRefreshRunning drives a
	// periodic re-fetch (see ensureRefreshRunning/scheduleCreditsRefresh) that stops itself the
	// moment the page/screen is no longer the Credits page, rather than refreshing forever in the
	// background after it's closed.
	private static List<SkyMellooApiClient.CreditEntry> creditsCache = null;
	private static boolean creditsLoading = false;
	private static boolean creditsRefreshRunning = false;

	private static final class CreditsPage implements Page {
		@Override
		public String title() {
			return tr("skymelloo.gui.menu.page.credits.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			List<MenuAction> list = new ArrayList<>();
			// Required disclaimer (Minecraft Brand and Asset Usage Guidelines) - shown here rather
			// than on every page, since Credits is the one place a player is already reading "who
			// made this", the same context this belongs in. Not clickable, same treatment as the
			// "Loading..."/"No credits yet" placeholder entries below.
			list.add(new MenuAction(named(Items.PAPER, tr("skymelloo.gui.menu.credits.about.name"), List.of(
					tr("skymelloo.gui.menu.credits.about.lore_1"),
					tr("skymelloo.gui.menu.credits.about.lore_2"),
					tr("skymelloo.gui.menu.credits.about.lore_3"))), () -> {
			}));
			if (creditsCache == null) {
				if (!creditsLoading) {
					creditsLoading = true;
					com.melloo.skymelloo.client.api.ModAuthManager.getIdentity(Minecraft.getInstance())
							.thenCompose(SkyMellooApiClient::fetchCredits)
							.thenAccept(result -> Minecraft.getInstance().execute(() -> onCreditsLoaded(result)))
							.exceptionally(error -> {
								Minecraft.getInstance().execute(() -> onCreditsLoaded(List.of()));
								return null;
							});
				}
				list.add(new MenuAction(named(Items.CLOCK, tr("skymelloo.gui.menu.credits.loading.name"), List.of(tr("skymelloo.gui.menu.credits.loading.lore_1"))), () -> {
				}));
				return list;
			}
			if (creditsCache.isEmpty()) {
				list.add(new MenuAction(named(Items.BARRIER, tr("skymelloo.gui.menu.credits.none.name"), List.of()), () -> {
				}));
				return list;
			}
			ensureRefreshRunning();
			for (SkyMellooApiClient.CreditEntry credit : creditsCache) {
				ItemStack head = new ItemStack(Items.PLAYER_HEAD);
				head.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(credit.username()));
				head.set(DataComponents.CUSTOM_NAME, Component.literal(tr("skymelloo.gui.menu.credits.entry_name", credit.username())));
				List<Component> lore = new ArrayList<>();
				if (credit.role() != null && !credit.role().isEmpty()) {
					lore.add(Component.literal(tr("skymelloo.gui.menu.format.description_line", credit.role())));
				}
				lore.add(Component.translatable(credit.online() ? "skymelloo.gui.menu.credits.online" : "skymelloo.gui.menu.credits.offline"));
				head.set(DataComponents.LORE, new ItemLore(lore));
				list.add(new MenuAction(head, () -> {
				}));
			}
			return list;
		}

		private static void onCreditsLoaded(List<SkyMellooApiClient.CreditEntry> result) {
			creditsCache = result;
			creditsLoading = false;
			if (Minecraft.getInstance().screen instanceof SkyMellooMenuScreen current) {
				current.rebuild();
			}
		}

		/** Starts the periodic online-status refresh loop the first time the Credits page actually
		 * shows entries, if it isn't already running - guarded so re-opening the page while a loop
		 * from an earlier open is already ticking doesn't stack a second one. */
		private static void ensureRefreshRunning() {
			if (creditsRefreshRunning) {
				return;
			}
			creditsRefreshRunning = true;
			scheduleCreditsRefresh();
		}

		// 5s - frequent enough that the online dot feels live (matches the mod's own presence report
		// cadence closely), without hammering /api/credits, which itself does a small Mojang lookup
		// per credited account server-side.
		private static void scheduleCreditsRefresh() {
			TickDelay.schedule(100, () -> {
				if (!(Minecraft.getInstance().screen instanceof SkyMellooMenuScreen current) || !(current.pageStack.peek() instanceof CreditsPage)) {
					// Menu closed, or navigated to a different page - stop refreshing in the
					// background; ensureRefreshRunning() restarts this the next time Credits opens.
					creditsRefreshRunning = false;
					return;
				}
				com.melloo.skymelloo.client.api.ModAuthManager.getIdentity(Minecraft.getInstance())
						.thenCompose(SkyMellooApiClient::fetchCredits)
						.thenAccept(result -> Minecraft.getInstance().execute(() -> onCreditsLoaded(result)))
						.exceptionally(error -> null);
				scheduleCreditsRefresh();
			});
		}
	}

	private static final class SettingsPage implements Page {
		@Override
		public String title() {
			return tr("skymelloo.gui.menu.page.settings.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			List<MenuAction> list = new ArrayList<>();
			list.add(linkAction(Items.SKELETON_SKULL, tr("skymelloo.gui.menu.link.dungeons.name"), tr("skymelloo.gui.menu.link.dungeons.description"), new DungeonsHubPage(), screen));
			return list;
		}
	}

	/** Links out to each dungeon settings category below - mirrors the section headers in the real settings screen 1:1, just navigable as menu items instead of a scrolling list. */
	private static final class DungeonsHubPage implements Page {
		@Override
		public String title() {
			return tr("skymelloo.gui.menu.page.dungeons_hub.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			List<MenuAction> list = new ArrayList<>();
			list.add(linkAction(Items.WRITTEN_BOOK, tr("skymelloo.gui.menu.link.dungeon_info.name"), tr("skymelloo.gui.menu.link.dungeon_info.description"), new DungeonInfoPage(), screen));
			list.add(linkAction(Items.IRON_SWORD, tr("skymelloo.gui.menu.link.auto_kick.name"), tr("skymelloo.gui.menu.link.auto_kick.description"), new AutoKickPage(), screen));
			list.add(linkAction(Items.GOLDEN_SWORD, tr("skymelloo.gui.menu.link.carry_auto_kick.name"), tr("skymelloo.gui.menu.link.carry_auto_kick.description"), new CarryAutoKickPage(), screen));
			list.add(linkAction(Items.CLOCK, tr("skymelloo.gui.menu.link.run_tracker.name"), tr("skymelloo.gui.menu.link.run_tracker.description"), new RunTrackerPage(), screen));
			list.add(linkAction(Items.NETHER_STAR, tr("skymelloo.gui.menu.link.boss_room.name"), tr("skymelloo.gui.menu.link.boss_room.description"), new BossRoomPage(), screen));
			list.add(linkAction(Items.SKELETON_SKULL, tr("skymelloo.gui.menu.link.death_message.name"), tr("skymelloo.gui.menu.link.death_message.description"), new DeathMessagePage(), screen));
			list.add(linkAction(Items.SHIELD, tr("skymelloo.gui.menu.link.pre_boss_warning.name"), tr("skymelloo.gui.menu.link.pre_boss_warning.description"), new PreBossWarningPage(), screen));
			list.add(linkAction(Items.MAP, tr("skymelloo.gui.menu.link.rooms_discovered.name"), tr("skymelloo.gui.menu.link.rooms_discovered.description"), new RoomsDiscoveredPage(), screen));
			list.add(linkAction(Items.COMPASS, tr("skymelloo.gui.menu.link.secrets_pace.name"), tr("skymelloo.gui.menu.link.secrets_pace.description"), new SecretsPacePage(), screen));
			list.add(linkAction(Items.REDSTONE, tr("skymelloo.gui.menu.link.puzzle_retry.name"), tr("skymelloo.gui.menu.link.puzzle_retry.description"), new PuzzleRetryPage(), screen));
			list.add(linkAction(Items.GOLD_INGOT, tr("skymelloo.gui.menu.link.s_plus_warnings.name"), tr("skymelloo.gui.menu.link.s_plus_warnings.description"), new SPlusPage(), screen));
			list.add(linkAction(Items.EXPERIENCE_BOTTLE, tr("skymelloo.gui.menu.link.grade_milestone.name"), tr("skymelloo.gui.menu.link.grade_milestone.description"), new GradeMilestonePage(), screen));
			list.add(linkAction(Items.LIME_DYE, tr("skymelloo.gui.menu.link.self_ready.name"), tr("skymelloo.gui.menu.link.self_ready.description"), new SelfReadyPage(), screen));
			list.add(linkAction(Items.CLOCK, tr("skymelloo.gui.menu.link.time_limit.name"), tr("skymelloo.gui.menu.link.time_limit.description"), new TimeLimitPage(), screen));
			list.add(linkAction(Items.DIAMOND_BOOTS, tr("skymelloo.gui.menu.link.floor_requirement.name"), tr("skymelloo.gui.menu.link.floor_requirement.description"), new FloorRequirementPage(), screen));
			return list;
		}
	}

	private static final class DungeonInfoPage implements Page {
		@Override
		public String title() {
			return tr("skymelloo.gui.menu.page.dungeon_info.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction(tr("skymelloo.gui.menu.row.dungeon_info.enabled.name"), tr("skymelloo.gui.menu.row.dungeon_info.enabled.description"), () -> c.partyJoinStatsEnabled, v -> c.partyJoinStatsEnabled = v, screen));
			list.add(toggleAction(tr("skymelloo.gui.menu.row.dungeon_info.show_mp.name"), tr("skymelloo.gui.menu.row.dungeon_info.show_mp.description"), () -> c.dungeonInfoShowMp, v -> c.dungeonInfoShowMp = v, screen));
			list.add(deliveryAction(tr("skymelloo.gui.menu.row.dungeon_info.delivery.name"), tr("skymelloo.gui.menu.row.dungeon_info.delivery.description"), () -> c.dungeonInfoMessageDelivery, v -> c.dungeonInfoMessageDelivery = v, screen));
			return list;
		}
	}

	private static final class AutoKickPage implements Page {
		@Override
		public String title() {
			return tr("skymelloo.gui.menu.page.auto_kick.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction(tr("skymelloo.gui.menu.row.auto_kick.enabled.name"), tr("skymelloo.gui.menu.row.auto_kick.enabled.description"), () -> c.dungeonAutoKickEnabled, v -> c.dungeonAutoKickEnabled = v, screen));
			list.add(cycleAction(Items.NETHER_STAR, tr("skymelloo.gui.menu.row.auto_kick.check_stat.name"), tr("skymelloo.gui.menu.row.auto_kick.check_stat.description"), () -> c.dungeonAutoKickStat, v -> c.dungeonAutoKickStat = v, new String[] { "AP", "LEVEL" }, screen));
			list.add(deliveryAction(tr("skymelloo.gui.menu.row.auto_kick.delivery.name"), tr("skymelloo.gui.menu.row.auto_kick.delivery.description"), () -> c.dungeonAutoKickDelivery, v -> c.dungeonAutoKickDelivery = v, screen));
			return list;
		}
	}

	private static final class CarryAutoKickPage implements Page {
		@Override
		public String title() {
			return tr("skymelloo.gui.menu.page.carry_auto_kick.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction(tr("skymelloo.gui.menu.row.carry_auto_kick.enabled.name"), tr("skymelloo.gui.menu.row.carry_auto_kick.enabled.description"), () -> c.dungeonAutoKickMaxEnabled, v -> c.dungeonAutoKickMaxEnabled = v, screen));
			list.add(cycleAction(Items.NETHER_STAR, tr("skymelloo.gui.menu.row.carry_auto_kick.check_stat.name"), tr("skymelloo.gui.menu.row.carry_auto_kick.check_stat.description"), () -> c.dungeonAutoKickMaxStat, v -> c.dungeonAutoKickMaxStat = v, new String[] { "AP", "LEVEL" }, screen));
			list.add(deliveryAction(tr("skymelloo.gui.menu.row.carry_auto_kick.delivery.name"), tr("skymelloo.gui.menu.row.carry_auto_kick.delivery.description"), () -> c.dungeonAutoKickMaxDelivery, v -> c.dungeonAutoKickMaxDelivery = v, screen));
			return list;
		}
	}

	private static final class RunTrackerPage implements Page {
		@Override
		public String title() {
			return tr("skymelloo.gui.menu.page.run_tracker.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(cycleAction(Items.PLAYER_HEAD, tr("skymelloo.gui.menu.row.run_tracker.party_hud.name"), tr("skymelloo.gui.menu.row.run_tracker.party_hud.description"), () -> c.partyHudMode, v -> c.partyHudMode = v, new String[] { "OFF", "COMPACT", "FULL" }, screen));
			list.add(toggleAction(tr("skymelloo.gui.menu.row.run_tracker.party_hud_puzzle_history.name"), tr("skymelloo.gui.menu.row.run_tracker.party_hud_puzzle_history.description"), () -> c.partyHudShowPuzzleHistory, v -> c.partyHudShowPuzzleHistory = v, screen));
			list.add(toggleAction(tr("skymelloo.gui.menu.row.run_tracker.party_mp_bar.name"), tr("skymelloo.gui.menu.row.run_tracker.party_mp_bar.description"), () -> c.partyMpBarEnabled, v -> c.partyMpBarEnabled = v, screen));
			list.add(toggleAction(tr("skymelloo.gui.menu.row.run_tracker.run_report.name"), tr("skymelloo.gui.menu.row.run_tracker.run_report.description"), () -> c.dungeonRunReportEnabled, v -> c.dungeonRunReportEnabled = v, screen));
			list.add(toggleAction(tr("skymelloo.gui.menu.row.run_tracker.party_run_summary.name"), tr("skymelloo.gui.menu.row.run_tracker.party_run_summary.description"), () -> c.dungeonRunPartySummaryEnabled, v -> c.dungeonRunPartySummaryEnabled = v, screen));
			list.add(deliveryAction(tr("skymelloo.gui.menu.row.run_tracker.party_summary_delivery.name"), tr("skymelloo.gui.menu.row.run_tracker.party_summary_delivery.description"), () -> c.dungeonRunPartySummaryDelivery, v -> c.dungeonRunPartySummaryDelivery = v, screen));
			list.add(toggleAction(tr("skymelloo.gui.menu.row.run_tracker.death_auto_kick.name"), tr("skymelloo.gui.menu.row.run_tracker.death_auto_kick.description"), () -> c.dungeonDeathKickEnabled, v -> c.dungeonDeathKickEnabled = v, screen));
			list.add(toggleAction(tr("skymelloo.gui.menu.row.run_tracker.afk_auto_kick.name"), tr("skymelloo.gui.menu.row.run_tracker.afk_auto_kick.description"), () -> c.dungeonAfkKickEnabled, v -> c.dungeonAfkKickEnabled = v, screen));
			list.add(cycleAction(Items.CLOCK, tr("skymelloo.gui.menu.row.run_tracker.afk_threshold.name"), tr("skymelloo.gui.menu.row.run_tracker.afk_threshold.description"), () -> c.dungeonAfkKickThreshold, v -> c.dungeonAfkKickThreshold = v, new String[] { "30", "60", "120" }, screen));
			list.add(toggleAction(tr("skymelloo.gui.menu.row.run_tracker.score_hud.name"), tr("skymelloo.gui.menu.row.run_tracker.score_hud.description"), () -> c.dungeonScoreHudEnabled, v -> c.dungeonScoreHudEnabled = v, screen));
			list.add(toggleAction(tr("skymelloo.gui.menu.row.run_tracker.show_breakdown.name"), tr("skymelloo.gui.menu.row.run_tracker.show_breakdown.description"), () -> c.dungeonScoreShowBreakdown, v -> c.dungeonScoreShowBreakdown = v, screen));
			list.add(toggleAction(tr("skymelloo.gui.menu.row.run_tracker.show_room_secrets.name"), tr("skymelloo.gui.menu.row.run_tracker.show_room_secrets.description"), () -> c.dungeonScoreShowRoomSecrets, v -> c.dungeonScoreShowRoomSecrets = v, screen));
			list.add(toggleAction(tr("skymelloo.gui.menu.row.run_tracker.dungeon_sync.name"), tr("skymelloo.gui.menu.row.run_tracker.dungeon_sync.description"), () -> c.dungeonSyncEnabled, v -> c.dungeonSyncEnabled = v, screen));
			list.add(toggleAction(tr("skymelloo.gui.menu.row.run_tracker.show_puzzles.name"), tr("skymelloo.gui.menu.row.run_tracker.show_puzzles.description"), () -> c.dungeonScoreShowPuzzles, v -> c.dungeonScoreShowPuzzles = v, screen));
			list.add(toggleAction(tr("skymelloo.gui.menu.row.run_tracker.show_possible.name"), tr("skymelloo.gui.menu.row.run_tracker.show_possible.description"), () -> c.dungeonScoreShowPossible, v -> c.dungeonScoreShowPossible = v, screen));
			list.add(toggleAction(tr("skymelloo.gui.menu.row.run_tracker.show_pace.name"), tr("skymelloo.gui.menu.row.run_tracker.show_pace.description"), () -> c.dungeonScoreShowPaceAndCountdown, v -> c.dungeonScoreShowPaceAndCountdown = v, screen));
			list.add(toggleAction(tr("skymelloo.gui.menu.row.run_tracker.show_final_result.name"), tr("skymelloo.gui.menu.row.run_tracker.show_final_result.description"), () -> c.dungeonScoreFinalResultEnabled, v -> c.dungeonScoreFinalResultEnabled = v, screen));
			list.add(toggleAction(tr("skymelloo.gui.menu.row.run_tracker.debug_hud.name"), tr("skymelloo.gui.menu.row.run_tracker.debug_hud.description"), () -> c.dungeonDebugHudEnabled, v -> c.dungeonDebugHudEnabled = v, screen));
			return list;
		}
	}

	private static final class BossRoomPage implements Page {
		@Override
		public String title() {
			return tr("skymelloo.gui.menu.page.boss_room.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction(tr("skymelloo.gui.menu.row.boss_room.enabled.name"), tr("skymelloo.gui.menu.row.boss_room.enabled.description"), () -> c.dungeonBossRoomAnnounceEnabled, v -> c.dungeonBossRoomAnnounceEnabled = v, screen));
			list.add(deliveryAction(tr("skymelloo.gui.menu.row.boss_room.delivery.name"), tr("skymelloo.gui.menu.row.boss_room.delivery.description"), () -> c.dungeonBossRoomMessageDelivery, v -> c.dungeonBossRoomMessageDelivery = v, screen));
			return list;
		}
	}

	private static final class DeathMessagePage implements Page {
		@Override
		public String title() {
			return tr("skymelloo.gui.menu.page.death_message.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction(tr("skymelloo.gui.menu.row.death_message.enabled.name"), tr("skymelloo.gui.menu.row.death_message.enabled.description"), () -> c.dungeonDeathMessageEnabled, v -> c.dungeonDeathMessageEnabled = v, screen));
			list.add(deliveryAction(tr("skymelloo.gui.menu.row.death_message.delivery.name"), tr("skymelloo.gui.menu.row.death_message.delivery.description"), () -> c.dungeonDeathMessageDelivery, v -> c.dungeonDeathMessageDelivery = v, screen));
			return list;
		}
	}

	private static final class PreBossWarningPage implements Page {
		@Override
		public String title() {
			return tr("skymelloo.gui.menu.page.pre_boss_warning.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction(tr("skymelloo.gui.menu.row.pre_boss_warning.enabled.name"), tr("skymelloo.gui.menu.row.pre_boss_warning.enabled.description"), () -> c.dungeonPreBossScoreWarningEnabled, v -> c.dungeonPreBossScoreWarningEnabled = v, screen));
			list.add(deliveryAction(tr("skymelloo.gui.menu.row.pre_boss_warning.delivery.name"), tr("skymelloo.gui.menu.row.pre_boss_warning.delivery.description"), () -> c.dungeonPreBossScoreWarningDelivery, v -> c.dungeonPreBossScoreWarningDelivery = v, screen));
			return list;
		}
	}

	private static final class RoomsDiscoveredPage implements Page {
		@Override
		public String title() {
			return tr("skymelloo.gui.menu.page.rooms_discovered.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction(tr("skymelloo.gui.menu.row.rooms_discovered.enabled.name"), tr("skymelloo.gui.menu.row.rooms_discovered.enabled.description"), () -> c.dungeonRoomsDiscoveredAnnounceEnabled, v -> c.dungeonRoomsDiscoveredAnnounceEnabled = v, screen));
			list.add(deliveryAction(tr("skymelloo.gui.menu.row.rooms_discovered.delivery.name"), tr("skymelloo.gui.menu.row.rooms_discovered.delivery.description"), () -> c.dungeonRoomsDiscoveredDelivery, v -> c.dungeonRoomsDiscoveredDelivery = v, screen));
			return list;
		}
	}

	private static final class SecretsPacePage implements Page {
		@Override
		public String title() {
			return tr("skymelloo.gui.menu.page.secrets_pace.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction(tr("skymelloo.gui.menu.row.secrets_pace.enabled.name"), tr("skymelloo.gui.menu.row.secrets_pace.enabled.description"), () -> c.dungeonSecretsPaceWarningEnabled, v -> c.dungeonSecretsPaceWarningEnabled = v, screen));
			list.add(deliveryAction(tr("skymelloo.gui.menu.row.secrets_pace.delivery.name"), tr("skymelloo.gui.menu.row.secrets_pace.delivery.description"), () -> c.dungeonSecretsPaceWarningDelivery, v -> c.dungeonSecretsPaceWarningDelivery = v, screen));
			return list;
		}
	}

	private static final class PuzzleRetryPage implements Page {
		@Override
		public String title() {
			return tr("skymelloo.gui.menu.page.puzzle_retry.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction(tr("skymelloo.gui.menu.row.puzzle_retry.enabled.name"), tr("skymelloo.gui.menu.row.puzzle_retry.enabled.description"), () -> c.dungeonPuzzleRetryFailEnabled, v -> c.dungeonPuzzleRetryFailEnabled = v, screen));
			list.add(deliveryAction(tr("skymelloo.gui.menu.row.puzzle_retry.delivery.name"), tr("skymelloo.gui.menu.row.puzzle_retry.delivery.description"), () -> c.dungeonPuzzleRetryFailDelivery, v -> c.dungeonPuzzleRetryFailDelivery = v, screen));
			return list;
		}
	}

	private static final class SPlusPage implements Page {
		@Override
		public String title() {
			return tr("skymelloo.gui.menu.page.s_plus_warnings.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction(tr("skymelloo.gui.menu.row.s_plus.impossible_enabled.name"), tr("skymelloo.gui.menu.row.s_plus.impossible_enabled.description"), () -> c.dungeonSPlusImpossibleEnabled, v -> c.dungeonSPlusImpossibleEnabled = v, screen));
			list.add(deliveryAction(tr("skymelloo.gui.menu.row.s_plus.impossible_delivery.name"), tr("skymelloo.gui.menu.row.s_plus.impossible_delivery.description"), () -> c.dungeonSPlusImpossibleDelivery, v -> c.dungeonSPlusImpossibleDelivery = v, screen));
			list.add(toggleAction(tr("skymelloo.gui.menu.row.s_plus.back_enabled.name"), tr("skymelloo.gui.menu.row.s_plus.back_enabled.description"), () -> c.dungeonSPlusBackEnabled, v -> c.dungeonSPlusBackEnabled = v, screen));
			list.add(deliveryAction(tr("skymelloo.gui.menu.row.s_plus.back_delivery.name"), tr("skymelloo.gui.menu.row.s_plus.back_delivery.description"), () -> c.dungeonSPlusBackDelivery, v -> c.dungeonSPlusBackDelivery = v, screen));
			return list;
		}
	}

	private static final class GradeMilestonePage implements Page {
		@Override
		public String title() {
			return tr("skymelloo.gui.menu.page.grade_milestone.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction(tr("skymelloo.gui.menu.row.grade_milestone.enabled.name"), tr("skymelloo.gui.menu.row.grade_milestone.enabled.description"), () -> c.dungeonGradeMilestoneEnabled, v -> c.dungeonGradeMilestoneEnabled = v, screen));
			list.add(deliveryAction(tr("skymelloo.gui.menu.row.grade_milestone.delivery.name"), tr("skymelloo.gui.menu.row.grade_milestone.delivery.description"), () -> c.dungeonGradeMilestoneDelivery, v -> c.dungeonGradeMilestoneDelivery = v, screen));
			return list;
		}
	}

	private static final class SelfReadyPage implements Page {
		@Override
		public String title() {
			return tr("skymelloo.gui.menu.page.self_ready.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction(tr("skymelloo.gui.menu.row.self_ready.enabled.name"), tr("skymelloo.gui.menu.row.self_ready.enabled.description"), () -> c.dungeonSelfReadyReminderEnabled, v -> c.dungeonSelfReadyReminderEnabled = v, screen));
			return list;
		}
	}

	private static final class TimeLimitPage implements Page {
		@Override
		public String title() {
			return tr("skymelloo.gui.menu.page.time_limit.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction(tr("skymelloo.gui.menu.row.time_limit.warning_enabled.name"), tr("skymelloo.gui.menu.row.time_limit.warning_enabled.description"), () -> c.dungeonTimeLimitWarningEnabled, v -> c.dungeonTimeLimitWarningEnabled = v, screen));
			list.add(cycleAction(Items.CLOCK, tr("skymelloo.gui.menu.row.time_limit.start_at.name"), tr("skymelloo.gui.menu.row.time_limit.start_at.description"), () -> c.dungeonTimeLimitWarningStart, v -> c.dungeonTimeLimitWarningStart = v, new String[] { "60", "30", "15", "10" }, screen));
			list.add(deliveryAction(tr("skymelloo.gui.menu.row.time_limit.warning_delivery.name"), tr("skymelloo.gui.menu.row.time_limit.warning_delivery.description"), () -> c.dungeonTimeLimitWarningDelivery, v -> c.dungeonTimeLimitWarningDelivery = v, screen));
			list.add(toggleAction(tr("skymelloo.gui.menu.row.time_limit.exceeded_enabled.name"), tr("skymelloo.gui.menu.row.time_limit.exceeded_enabled.description"), () -> c.dungeonTimeLimitExceededEnabled, v -> c.dungeonTimeLimitExceededEnabled = v, screen));
			list.add(deliveryAction(tr("skymelloo.gui.menu.row.time_limit.exceeded_delivery.name"), tr("skymelloo.gui.menu.row.time_limit.exceeded_delivery.description"), () -> c.dungeonTimeLimitExceededDelivery, v -> c.dungeonTimeLimitExceededDelivery = v, screen));
			return list;
		}
	}

	private static final class FloorRequirementPage implements Page {
		@Override
		public String title() {
			return tr("skymelloo.gui.menu.page.floor_requirement.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction(tr("skymelloo.gui.menu.row.floor_requirement.enabled.name"), tr("skymelloo.gui.menu.row.floor_requirement.enabled.description"), () -> c.dungeonFloorKickEnabled, v -> c.dungeonFloorKickEnabled = v, screen));
			list.add(deliveryAction(tr("skymelloo.gui.menu.row.floor_requirement.delivery.name"), tr("skymelloo.gui.menu.row.floor_requirement.delivery.description"), () -> c.dungeonFloorKickDelivery, v -> c.dungeonFloorKickDelivery = v, screen));
			list.add(toggleAction(tr("skymelloo.gui.menu.row.floor_requirement.max_enabled.name"), tr("skymelloo.gui.menu.row.floor_requirement.max_enabled.description"), () -> c.dungeonFloorKickMaxEnabled, v -> c.dungeonFloorKickMaxEnabled = v, screen));
			list.add(deliveryAction(tr("skymelloo.gui.menu.row.floor_requirement.max_delivery.name"), tr("skymelloo.gui.menu.row.floor_requirement.max_delivery.description"), () -> c.dungeonFloorKickMaxDelivery, v -> c.dungeonFloorKickMaxDelivery = v, screen));
			return list;
		}
	}

	/** Landing page for the Spells section - just three links, each opening straight into its own full page rather than mixing switch/stats/kills into one flat list. */
	private static final class SpellsPage implements Page {
		@Override
		public String title() {
			return tr("skymelloo.gui.menu.page.spells.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(linkAction(Items.BLAZE_ROD, tr("skymelloo.gui.menu.link.switch_spell.name"), tr("skymelloo.gui.menu.link.switch_spell.description"), new SwitchSpellPage(), screen));
			list.add(linkAction(Items.PURPLE_DYE, tr("skymelloo.gui.menu.link.spell_color.name"), tr("skymelloo.gui.menu.link.spell_color.description"), new SpellColorPage(), screen));
			list.add(linkAction(Items.EXPERIENCE_BOTTLE, tr("skymelloo.gui.menu.link.spell_stats.name"), tr("skymelloo.gui.menu.link.spell_stats.description"), new SpellStatsPage(), screen));
			list.add(linkAction(Items.PAPER, tr("skymelloo.gui.menu.link.recent_kills.name"), tr("skymelloo.gui.menu.link.recent_kills.description"), new LastKillsPage(), screen));
			return list;
		}
	}

	/** Same 12-color palette as SkyMellooSettingsScreen's own color dropdown - moved here so the spell's color is fully configurable from this item-menu too, not just the (now-removed) Fun settings tab. */
	private static final class SpellColorPage implements Page {
		private static final int[] COLOR_PALETTE = {
				0xFFFF5555, 0xFFFFAA00, 0xFFFFFF55, 0xFF55FF55, 0xFF55FFFF,
				0xFF5599FF, 0xFFAA33FF, 0xFFFF55FF, 0xFFFFFFFF, 0xFF888888,
				0xFF227777, 0xFFFF8800
		};
		private static final Item[] PALETTE_ICONS = {
				Items.RED_DYE, Items.ORANGE_DYE, Items.YELLOW_DYE, Items.LIME_DYE, Items.LIGHT_BLUE_DYE,
				Items.BLUE_DYE, Items.PURPLE_DYE, Items.MAGENTA_DYE, Items.WHITE_DYE, Items.GRAY_DYE,
				Items.CYAN_DYE, Items.ORANGE_DYE
		};

		@Override
		public String title() {
			return tr("skymelloo.gui.menu.page.spell_color.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			for (int i = 0; i < COLOR_PALETTE.length; i++) {
				list.add(colorSwatchAction(PALETTE_ICONS[i], COLOR_PALETTE[i], c, screen));
			}
			return list;
		}

		private static MenuAction colorSwatchAction(Item item, int rgb, SkyMellooConfig c, SkyMellooMenuScreen screen) {
			boolean selected = c.magicMissileColor.getRGB() == (rgb | 0xFF000000);
			ItemStack icon = named(item, tr(selected ? "skymelloo.gui.menu.format.selected_name" : "skymelloo.gui.menu.format.toggle_name_off", tr("skymelloo.gui.menu.format.color_swatch_name")),
					List.of(selected ? tr("skymelloo.gui.menu.spell_type.currently_selected") : tr("skymelloo.gui.menu.spell_type.click_to_select")));
			return new MenuAction(icon, () -> {
				c.magicMissileColor = new java.awt.Color(rgb, true);
				SkyMellooConfig.HANDLER.save();
				screen.rebuild();
			});
		}
	}

	private static final class SwitchSpellPage implements Page {
		@Override
		public String title() {
			return tr("skymelloo.gui.menu.page.switch_spell.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			// Previously the only way to stop casting entirely was the separate full settings screen's
			// "Spell" toggle, not reachable from this quick menu at all.
			// Off is just another selectable entry here, same as any spell type.
			list.add(offAction(c, screen));
			list.add(spellTypeAction(Items.SNOWBALL, tr("skymelloo.gui.menu.spell_type.icy.name"), "MISSILE", tr("skymelloo.gui.menu.spell_type.icy.description"), c, screen));
			list.add(spellTypeAction(Items.TRIDENT, tr("skymelloo.gui.menu.spell_type.lightning.name"), "LIGHTNING", tr("skymelloo.gui.menu.spell_type.lightning.description"), c, screen));
			list.add(spellTypeAction(Items.ENDER_EYE, tr("skymelloo.gui.menu.spell_type.plasma.name"), "PLASMA", tr("skymelloo.gui.menu.spell_type.plasma.description"), c, screen));
			list.add(spellTypeAction(Items.SPECTRAL_ARROW, tr("skymelloo.gui.menu.spell_type.homing_arrow.name"), "ARROW", tr("skymelloo.gui.menu.spell_type.homing_arrow.description"), c, screen));
			list.add(spellTypeAction(Items.PHANTOM_MEMBRANE, tr("skymelloo.gui.menu.spell_type.levitate.name"), "LEVITATE", tr("skymelloo.gui.menu.spell_type.levitate.description"), c, screen));
			return list;
		}

		private static MenuAction offAction(SkyMellooConfig c, SkyMellooMenuScreen screen) {
			boolean selected = !c.magicMissileEnabled;
			ItemStack icon = named(Items.BARRIER, tr(selected ? "skymelloo.gui.menu.format.selected_name" : "skymelloo.gui.menu.format.toggle_name_off", tr("skymelloo.gui.menu.spell_type.off.name")),
					List.of(tr("skymelloo.gui.menu.spell_type.off.description"), "", selected ? tr("skymelloo.gui.menu.spell_type.currently_selected") : tr("skymelloo.gui.menu.spell_type.click_to_disable")));
			return new MenuAction(icon, () -> {
				c.magicMissileEnabled = false;
				SkyMellooConfig.HANDLER.save();
				screen.rebuild();
			});
		}

		private static MenuAction spellTypeAction(Item item, String name, String typeValue, String description, SkyMellooConfig c, SkyMellooMenuScreen screen) {
			boolean selected = c.magicMissileEnabled && typeValue.equalsIgnoreCase(c.magicMissileSpellType);
			ItemStack icon = named(item, tr(selected ? "skymelloo.gui.menu.format.selected_name" : "skymelloo.gui.menu.format.toggle_name_off", name),
					List.of(tr("skymelloo.gui.menu.format.description_line", description), "", selected ? tr("skymelloo.gui.menu.spell_type.currently_selected") : tr("skymelloo.gui.menu.spell_type.click_to_select")));
			return new MenuAction(icon, () -> {
				c.magicMissileSpellType = typeValue;
				c.magicMissileEnabled = true;
				SkyMellooConfig.HANDLER.save();
				screen.rebuild();
			});
		}
	}

	private static final class SpellStatsPage implements Page {
		@Override
		public String title() {
			return tr("skymelloo.gui.menu.page.spell_stats.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(new MenuAction(named(Items.BLAZE_ROD, tr("skymelloo.gui.menu.spell_stats.cast.name"),
					List.of(tr("skymelloo.gui.menu.spell_stats.cast.lore_1"), "", "§f" + c.totalSpellsCast)), () -> {
			}));
			list.add(new MenuAction(named(Items.PLAYER_HEAD, tr("skymelloo.gui.menu.spell_stats.kills.name"),
					List.of(tr("skymelloo.gui.menu.spell_stats.kills.lore_1"), "", "§f" + c.totalPlayersKilled)), () -> {
			}));
			list.add(new MenuAction(named(Items.AMETHYST_SHARD, tr("skymelloo.gui.menu.spell_stats.essence.name"),
					List.of(tr("skymelloo.gui.menu.spell_stats.essence.lore_1"), "", "§f" + c.totalSpellEssenceCollected)), () -> {
			}));
			return list;
		}
	}

	private static final class LastKillsPage implements Page {
		@Override
		public String title() {
			return tr("skymelloo.gui.menu.page.last_kills.title");
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			List<MagicMissileManager.RecentKill> kills = MagicMissileManager.getRecentKills();
			List<MenuAction> list = new ArrayList<>();
			if (kills.isEmpty()) {
				list.add(new MenuAction(named(Items.BARRIER, tr("skymelloo.gui.menu.last_kills.none.name"), List.of(tr("skymelloo.gui.menu.last_kills.none.lore_1"))), () -> {
				}));
				return list;
			}
			for (MagicMissileManager.RecentKill kill : kills) {
				long secondsAgo = (System.currentTimeMillis() - kill.timestampMillis()) / 1000;
				ItemStack head = new ItemStack(Items.PLAYER_HEAD);
				head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(kill.profile()));
				// #N is the real total kill count against THIS player, not this entry's position in
				// the recent-kills list.
				head.set(DataComponents.CUSTOM_NAME, Component.literal(tr("skymelloo.gui.menu.last_kills.entry_name", kill.victimKillNumber(), kill.profile().name())));
				head.set(DataComponents.LORE, new ItemLore(List.<Component>of(Component.translatable("skymelloo.gui.menu.last_kills.seconds_ago", secondsAgo))));
				list.add(new MenuAction(head, () -> {
				}));
			}
			return list;
		}
	}

}
