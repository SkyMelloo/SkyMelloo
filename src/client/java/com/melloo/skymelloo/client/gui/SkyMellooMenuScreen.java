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

import java.awt.Color;
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

		/** Optional, pinned to the nav row right next to the Back arrow - see {@link CosmeticsListPage#navExtraAction}. */
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
		super(Component.literal("SkyMelloo Menu"));
		pageStack.push(new MainPage());
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
			entries.add(new MenuEntry(PREV_SLOT, named(Items.ARROW, "§e< Previous Page", List.of()), () -> {
				currentSheet--;
				rebuild();
			}));
		}
		if (pageStack.size() > 1) {
			entries.add(new MenuEntry(BACK_SLOT, named(Items.ARROW, "§eBack", List.of()), this::goBack));
		}
		// Always present, on every page - not per-page like navExtraAction below, since a bug can be
		// found from anywhere in this menu.
		entries.add(new MenuEntry(BACK_SLOT - 1, named(Items.WRITABLE_BOOK, "§eReport a Bug",
				List.of("§7Opens the bug report page", "§7in your browser.")), SkyMellooMenuScreen::openReportBug));
		MenuAction navExtra = pageStack.peek().navExtraAction(this);
		if (navExtra != null) {
			entries.add(new MenuEntry(BACK_SLOT + 1, navExtra.icon(), navExtra.onClick(), navExtra.onRightClick()));
		}
		if (currentSheet < totalSheets - 1) {
			entries.add(new MenuEntry(NEXT_SLOT, named(Items.ARROW, "§eNext Page >", List.of()), () -> {
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
		net.minecraft.util.Util.getPlatform().openUri(java.net.URI.create("https://sky.melloo.me/report-bug"));
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
		ItemStack icon = named(item, "§d" + name + " §7(Click)", List.of("§7" + description, "", "§eClick to open!"));
		return new MenuAction(icon, () -> screen.push(target));
	}

	/** Green/gray wool, "click to enable/disable" - the standard on/off representation used throughout every settings page below. */
	private static ItemStack toggleIcon(boolean on, String name, String description) {
		return named(on ? Items.LIME_WOOL : Items.GRAY_WOOL, (on ? "§a" : "§7") + name,
				List.of("§7" + description, "", on ? "§aEnabled §7- click to disable" : "§cDisabled §7- click to enable"));
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
		ItemStack icon = named(item, "§b" + name, List.of("§7" + description, "", "§eCurrent: §f" + current, "§7Click to cycle"));
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
			return "SkyMelloo Menu";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			List<MenuAction> list = new ArrayList<>();
			list.add(linkAction(Items.WRITABLE_BOOK, "Settings", "All SkyMelloo settings", new SettingsPage(), screen));
			list.add(linkAction(Items.BLAZE_ROD, "Spells", "Switch spell type, view recent kills", new SpellsPage(), screen));
			list.add(linkAction(Items.FIREWORK_STAR, "Cosmetics", "Toggle and color every cosmetic effect", new CosmeticsListPage(), screen));
			list.add(new MenuAction(named(Items.BARRIER, "§7Games", List.of("§7Coming soon")), () -> {
			}));
			return list;
		}

		@Override
		public MenuAction cornerAction(SkyMellooMenuScreen screen) {
			return linkAction(Items.PLAYER_HEAD, "Credits", "See who made SkyMelloo", new CreditsPage(), screen);
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
			return "Credits";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			List<MenuAction> list = new ArrayList<>();
			// Required disclaimer (Minecraft Brand and Asset Usage Guidelines) - shown here rather
			// than on every page, since Credits is the one place a player is already reading "who
			// made this", the same context this belongs in. Not clickable, same treatment as the
			// "Loading..."/"No credits yet" placeholder entries below.
			list.add(new MenuAction(named(Items.PAPER, "§7About SkyMelloo", List.of(
					"§7Not an official Minecraft product.",
					"§7Not approved by or associated with",
					"§7Mojang, Microsoft, or Hypixel Inc.")), () -> {
			}));
			if (creditsCache == null) {
				if (!creditsLoading) {
					creditsLoading = true;
					SkyMellooApiClient.fetchCredits()
							.thenAccept(result -> Minecraft.getInstance().execute(() -> onCreditsLoaded(result)))
							.exceptionally(error -> {
								Minecraft.getInstance().execute(() -> onCreditsLoaded(List.of()));
								return null;
							});
				}
				list.add(new MenuAction(named(Items.CLOCK, "§7Loading...", List.of("§7Fetching credits from sky.melloo.me")), () -> {
				}));
				return list;
			}
			if (creditsCache.isEmpty()) {
				list.add(new MenuAction(named(Items.BARRIER, "§7No credits yet", List.of()), () -> {
				}));
				return list;
			}
			ensureRefreshRunning();
			for (SkyMellooApiClient.CreditEntry credit : creditsCache) {
				ItemStack head = new ItemStack(Items.PLAYER_HEAD);
				head.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(credit.username()));
				head.set(DataComponents.CUSTOM_NAME, Component.literal("§d" + credit.username()));
				List<Component> lore = new ArrayList<>();
				if (credit.role() != null && !credit.role().isEmpty()) {
					lore.add(Component.literal("§7" + credit.role()));
				}
				lore.add(Component.literal(credit.online() ? "§a● Online now" : "§8○ Offline"));
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
				SkyMellooApiClient.fetchCredits()
						.thenAccept(result -> Minecraft.getInstance().execute(() -> onCreditsLoaded(result)))
						.exceptionally(error -> null);
				scheduleCreditsRefresh();
			});
		}
	}

	private static final class SettingsPage implements Page {
		@Override
		public String title() {
			return "Settings";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			List<MenuAction> list = new ArrayList<>();
			list.add(linkAction(Items.SKELETON_SKULL, "Dungeons", "Dungeon run tracking, HUDs, announcements", new DungeonsHubPage(), screen));
			return list;
		}
	}

	/** Links out to each dungeon settings category below - mirrors the section headers in the real settings screen 1:1, just navigable as menu items instead of a scrolling list. */
	private static final class DungeonsHubPage implements Page {
		@Override
		public String title() {
			return "Dungeon Settings";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			List<MenuAction> list = new ArrayList<>();
			list.add(linkAction(Items.WRITTEN_BOOK, "Dungeon Info", "Stats lookup announced when someone joins your party", new DungeonInfoPage(), screen));
			list.add(linkAction(Items.IRON_SWORD, "Auto-Kick", "Kick a joining member under a stat threshold", new AutoKickPage(), screen));
			list.add(linkAction(Items.GOLDEN_SWORD, "Carry Auto-Kick", "Kick a joining member OVER a stat threshold", new CarryAutoKickPage(), screen));
			list.add(linkAction(Items.CLOCK, "Run Tracker", "HUDs, score display, run/party summaries", new RunTrackerPage(), screen));
			list.add(linkAction(Items.NETHER_STAR, "Boss Room", "Announce when the boss room is entered", new BossRoomPage(), screen));
			list.add(linkAction(Items.SKELETON_SKULL, "Death Message", "Announce when a party member dies", new DeathMessagePage(), screen));
			list.add(linkAction(Items.SHIELD, "Pre-Boss Warning", "Warn if score is under 300 before the boss room", new PreBossWarningPage(), screen));
			list.add(linkAction(Items.MAP, "All Rooms Discovered", "Announce the true best-case score once fully explored", new RoomsDiscoveredPage(), screen));
			list.add(linkAction(Items.COMPASS, "Secrets Pace", "Warn if secret-finding rate falls behind", new SecretsPacePage(), screen));
			list.add(linkAction(Items.REDSTONE, "Puzzle Retry Fail", "Announce a puzzle failing again after a reset", new PuzzleRetryPage(), screen));
			list.add(linkAction(Items.GOLD_INGOT, "S+ Warnings", "S+ impossible / back-in-reach announcements", new SPlusPage(), screen));
			list.add(linkAction(Items.EXPERIENCE_BOTTLE, "Grade Milestone", "Announce live grade tier climbs", new GradeMilestonePage(), screen));
			list.add(linkAction(Items.LIME_DYE, "Self-Ready Reminder", "Personal nudge when you're the last to ready", new SelfReadyPage(), screen));
			list.add(linkAction(Items.CLOCK, "Time Limit", "Countdown checkpoints and exceeded announcement", new TimeLimitPage(), screen));
			list.add(linkAction(Items.DIAMOND_BOOTS, "Floor Requirement", "Target floor, Floor Auto-Kick (min/max)", new FloorRequirementPage(), screen));
			return list;
		}
	}

	private static final class DungeonInfoPage implements Page {
		@Override
		public String title() {
			return "Dungeon Info";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction("Dungeon Info", "Look up + post stats when someone joins your party.", () -> c.partyJoinStatsEnabled, v -> c.partyJoinStatsEnabled = v, screen));
			list.add(toggleAction("Show MP", "Include Magical Power in the message.", () -> c.dungeonInfoShowMp, v -> c.dungeonInfoShowMp = v, screen));
			list.add(deliveryAction("Delivery", "Where the message above goes.", () -> c.dungeonInfoMessageDelivery, v -> c.dungeonInfoMessageDelivery = v, screen));
			return list;
		}
	}

	private static final class AutoKickPage implements Page {
		@Override
		public String title() {
			return "Auto-Kick";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction("Auto-Kick", "Kick a joining member if their stat is under the threshold.", () -> c.dungeonAutoKickEnabled, v -> c.dungeonAutoKickEnabled = v, screen));
			list.add(cycleAction(Items.NETHER_STAR, "Check Stat", "Which stat Auto-Kick checks.", () -> c.dungeonAutoKickStat, v -> c.dungeonAutoKickStat = v, new String[] { "MP", "LEVEL" }, screen));
			list.add(deliveryAction("Delivery", "Where the kick message goes.", () -> c.dungeonAutoKickDelivery, v -> c.dungeonAutoKickDelivery = v, screen));
			return list;
		}
	}

	private static final class CarryAutoKickPage implements Page {
		@Override
		public String title() {
			return "Carry Auto-Kick (Max)";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction("Max Auto-Kick", "Kick a joining member if their stat is OVER the threshold.", () -> c.dungeonAutoKickMaxEnabled, v -> c.dungeonAutoKickMaxEnabled = v, screen));
			list.add(cycleAction(Items.NETHER_STAR, "Check Stat", "Which stat Max Auto-Kick checks.", () -> c.dungeonAutoKickMaxStat, v -> c.dungeonAutoKickMaxStat = v, new String[] { "MP", "LEVEL" }, screen));
			list.add(deliveryAction("Delivery", "Where the kick message goes.", () -> c.dungeonAutoKickMaxDelivery, v -> c.dungeonAutoKickMaxDelivery = v, screen));
			return list;
		}
	}

	private static final class RunTrackerPage implements Page {
		@Override
		public String title() {
			return "Run Tracker";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(cycleAction(Items.PLAYER_HEAD, "Party HUD", "Compact/Full/Off party member display.", () -> c.partyHudMode, v -> c.partyHudMode = v, new String[] { "OFF", "COMPACT", "FULL" }, screen));
			list.add(toggleAction("Party HUD Puzzle History", "Show each member's puzzle result in FULL mode.", () -> c.partyHudShowPuzzleHistory, v -> c.partyHudShowPuzzleHistory = v, screen));
			list.add(toggleAction("Party MP Bar", "Horizontal MP spread bar for the party.", () -> c.partyMpBarEnabled, v -> c.partyMpBarEnabled = v, screen));
			list.add(toggleAction("Run Report", "Detailed local chat summary when a run ends.", () -> c.dungeonRunReportEnabled, v -> c.dungeonRunReportEnabled = v, screen));
			list.add(toggleAction("Party Run Summary", "Short one-line result sent to the party.", () -> c.dungeonRunPartySummaryEnabled, v -> c.dungeonRunPartySummaryEnabled = v, screen));
			list.add(deliveryAction("Party Summary Delivery", "Where the summary above goes.", () -> c.dungeonRunPartySummaryDelivery, v -> c.dungeonRunPartySummaryDelivery = v, screen));
			list.add(toggleAction("Death Auto-Kick", "Kick a member once their death count exceeds the threshold.", () -> c.dungeonDeathKickEnabled, v -> c.dungeonDeathKickEnabled = v, screen));
			list.add(toggleAction("AFK Auto-Kick", "Kick a member who hasn't moved during the run.", () -> c.dungeonAfkKickEnabled, v -> c.dungeonAfkKickEnabled = v, screen));
			list.add(cycleAction(Items.CLOCK, "AFK Threshold", "How long counts as AFK.", () -> c.dungeonAfkKickThreshold, v -> c.dungeonAfkKickThreshold = v, new String[] { "30", "60", "120" }, screen));
			list.add(toggleAction("Score HUD", "Live dungeon score estimate during the run.", () -> c.dungeonScoreHudEnabled, v -> c.dungeonScoreHudEnabled = v, screen));
			list.add(toggleAction("Show Breakdown", "Skill/Explore/Speed/Bonus line on the Score HUD.", () -> c.dungeonScoreShowBreakdown, v -> c.dungeonScoreShowBreakdown = v, screen));
			list.add(toggleAction("Show Room Secrets", "Current room's secrets found/total.", () -> c.dungeonScoreShowRoomSecrets, v -> c.dungeonScoreShowRoomSecrets = v, screen));
			list.add(toggleAction("Dungeon Sync", "Share room/floor/secrets/score with party members also on SkyMelloo, and with the website's Dungeon lookup.", () -> c.dungeonSyncEnabled, v -> c.dungeonSyncEnabled = v, screen));
			list.add(toggleAction("Show Puzzles", "Puzzle outcome lines on the Score HUD.", () -> c.dungeonScoreShowPuzzles, v -> c.dungeonScoreShowPuzzles = v, screen));
			list.add(toggleAction("Show Possible/Penalties", "Best-case ceiling + penalty breakdown.", () -> c.dungeonScoreShowPossible, v -> c.dungeonScoreShowPossible = v, screen));
			list.add(toggleAction("Show Pace/Countdown", "Score trend arrow + time-left countdown.", () -> c.dungeonScoreShowPaceAndCountdown, v -> c.dungeonScoreShowPaceAndCountdown = v, screen));
			list.add(toggleAction("Show Final Result", "Keep the Score HUD up briefly after a run ends.", () -> c.dungeonScoreFinalResultEnabled, v -> c.dungeonScoreFinalResultEnabled = v, screen));
			list.add(toggleAction("Debug HUD", "Internal run-tracker state flags, for troubleshooting.", () -> c.dungeonDebugHudEnabled, v -> c.dungeonDebugHudEnabled = v, screen));
			return list;
		}
	}

	private static final class BossRoomPage implements Page {
		@Override
		public String title() {
			return "Boss Room Announcement";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction("Announce Boss Room", "Announce when the boss room is entered.", () -> c.dungeonBossRoomAnnounceEnabled, v -> c.dungeonBossRoomAnnounceEnabled = v, screen));
			list.add(deliveryAction("Delivery", "Where the message above goes.", () -> c.dungeonBossRoomMessageDelivery, v -> c.dungeonBossRoomMessageDelivery = v, screen));
			return list;
		}
	}

	private static final class DeathMessagePage implements Page {
		@Override
		public String title() {
			return "Death Message";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction("Death Message", "Announce when a party member dies.", () -> c.dungeonDeathMessageEnabled, v -> c.dungeonDeathMessageEnabled = v, screen));
			list.add(deliveryAction("Delivery", "Where the message above goes.", () -> c.dungeonDeathMessageDelivery, v -> c.dungeonDeathMessageDelivery = v, screen));
			return list;
		}
	}

	private static final class PreBossWarningPage implements Page {
		@Override
		public String title() {
			return "Pre-Boss Score Warning";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction("Pre-Boss Score Warning", "Warn if score is still under 300 when the Blood Room ends.", () -> c.dungeonPreBossScoreWarningEnabled, v -> c.dungeonPreBossScoreWarningEnabled = v, screen));
			list.add(deliveryAction("Delivery", "Where the message above goes.", () -> c.dungeonPreBossScoreWarningDelivery, v -> c.dungeonPreBossScoreWarningDelivery = v, screen));
			return list;
		}
	}

	private static final class RoomsDiscoveredPage implements Page {
		@Override
		public String title() {
			return "All Rooms Discovered";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction("Announce Possible Score", "Announce the true best-case score once 100% explored.", () -> c.dungeonRoomsDiscoveredAnnounceEnabled, v -> c.dungeonRoomsDiscoveredAnnounceEnabled = v, screen));
			list.add(deliveryAction("Delivery", "Where the message above goes.", () -> c.dungeonRoomsDiscoveredDelivery, v -> c.dungeonRoomsDiscoveredDelivery = v, screen));
			return list;
		}
	}

	private static final class SecretsPacePage implements Page {
		@Override
		public String title() {
			return "Secrets Pace Warning";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction("Secrets Pace Warning", "Warn once your secret-finding rate falls behind.", () -> c.dungeonSecretsPaceWarningEnabled, v -> c.dungeonSecretsPaceWarningEnabled = v, screen));
			list.add(deliveryAction("Delivery", "Where the message above goes.", () -> c.dungeonSecretsPaceWarningDelivery, v -> c.dungeonSecretsPaceWarningDelivery = v, screen));
			return list;
		}
	}

	private static final class PuzzleRetryPage implements Page {
		@Override
		public String title() {
			return "Puzzle Retry Fail";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction("Puzzle Retry Fail", "Announce the SAME puzzle failing again after a reset.", () -> c.dungeonPuzzleRetryFailEnabled, v -> c.dungeonPuzzleRetryFailEnabled = v, screen));
			list.add(deliveryAction("Delivery", "Where the message above goes.", () -> c.dungeonPuzzleRetryFailDelivery, v -> c.dungeonPuzzleRetryFailDelivery = v, screen));
			return list;
		}
	}

	private static final class SPlusPage implements Page {
		@Override
		public String title() {
			return "S+ Warnings";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction("S+ Impossible Warning", "Warn once S+ becomes mathematically impossible.", () -> c.dungeonSPlusImpossibleEnabled, v -> c.dungeonSPlusImpossibleEnabled = v, screen));
			list.add(deliveryAction("Impossible Delivery", "Where the message above goes.", () -> c.dungeonSPlusImpossibleDelivery, v -> c.dungeonSPlusImpossibleDelivery = v, screen));
			list.add(toggleAction("S+ Back In Reach", "Say so once if the ceiling recovers back over 300.", () -> c.dungeonSPlusBackEnabled, v -> c.dungeonSPlusBackEnabled = v, screen));
			list.add(deliveryAction("Back Delivery", "Where the message above goes.", () -> c.dungeonSPlusBackDelivery, v -> c.dungeonSPlusBackDelivery = v, screen));
			return list;
		}
	}

	private static final class GradeMilestonePage implements Page {
		@Override
		public String title() {
			return "Live Grade Milestone";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction("Grade Milestone", "Announce live the moment the grade reaches a new tier.", () -> c.dungeonGradeMilestoneEnabled, v -> c.dungeonGradeMilestoneEnabled = v, screen));
			list.add(deliveryAction("Delivery", "Where the message above goes.", () -> c.dungeonGradeMilestoneDelivery, v -> c.dungeonGradeMilestoneDelivery = v, screen));
			return list;
		}
	}

	private static final class SelfReadyPage implements Page {
		@Override
		public String title() {
			return "Self-Ready Reminder";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction("Self-Ready Reminder", "Personal action-bar nudge when you're last to ready.", () -> c.dungeonSelfReadyReminderEnabled, v -> c.dungeonSelfReadyReminderEnabled = v, screen));
			return list;
		}
	}

	private static final class TimeLimitPage implements Page {
		@Override
		public String title() {
			return "Time Limit";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction("Time Limit Warning", "Countdown checkpoints as the time limit approaches.", () -> c.dungeonTimeLimitWarningEnabled, v -> c.dungeonTimeLimitWarningEnabled = v, screen));
			list.add(cycleAction(Items.CLOCK, "Start At", "Which checkpoint to start warning at.", () -> c.dungeonTimeLimitWarningStart, v -> c.dungeonTimeLimitWarningStart = v, new String[] { "60", "30", "15", "10" }, screen));
			list.add(deliveryAction("Warning Delivery", "Where the checkpoint messages go.", () -> c.dungeonTimeLimitWarningDelivery, v -> c.dungeonTimeLimitWarningDelivery = v, screen));
			list.add(toggleAction("Time Limit Exceeded", "Announce once the time limit is actually exceeded.", () -> c.dungeonTimeLimitExceededEnabled, v -> c.dungeonTimeLimitExceededEnabled = v, screen));
			list.add(deliveryAction("Exceeded Delivery", "Where the message above goes.", () -> c.dungeonTimeLimitExceededDelivery, v -> c.dungeonTimeLimitExceededDelivery = v, screen));
			return list;
		}
	}

	private static final class FloorRequirementPage implements Page {
		@Override
		public String title() {
			return "Floor Requirement";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction("Floor Auto-Kick", "Kick a member who doesn't meet the Target Floor requirement.", () -> c.dungeonFloorKickEnabled, v -> c.dungeonFloorKickEnabled = v, screen));
			list.add(deliveryAction("Floor Kick Delivery", "Where the kick message goes.", () -> c.dungeonFloorKickDelivery, v -> c.dungeonFloorKickDelivery = v, screen));
			list.add(toggleAction("Max Floor Auto-Kick", "Kick a member already eligible for MORE than the Allowed Floor.", () -> c.dungeonFloorKickMaxEnabled, v -> c.dungeonFloorKickMaxEnabled = v, screen));
			list.add(deliveryAction("Max Floor Kick Delivery", "Where the kick message goes.", () -> c.dungeonFloorKickMaxDelivery, v -> c.dungeonFloorKickMaxDelivery = v, screen));
			return list;
		}
	}

	/** Landing page for the Spells section - just three links, each opening straight into its own full page rather than mixing switch/stats/kills into one flat list. */
	private static final class SpellsPage implements Page {
		@Override
		public String title() {
			return "Spells";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(castSpellAction(c, screen));
			list.add(linkAction(Items.BLAZE_ROD, "Switch Spell", "Choose which spell your missile casts", new SwitchSpellPage(), screen));
			list.add(linkAction(Items.EXPERIENCE_BOTTLE, "Stats", "Spells cast and players killed", new SpellStatsPage(), screen));
			list.add(linkAction(Items.PAPER, "Recent Kills", "Your most recent spell kills", new LastKillsPage(), screen));
			return list;
		}

		/** Casting moved here entirely (2026-07-27) - punching
		 * empty air no longer fires anything; this is now the only way to actually cast. Closes the
		 * menu on click so the cast is immediately visible instead of hidden behind the GUI. */
		private static MenuAction castSpellAction(SkyMellooConfig c, SkyMellooMenuScreen screen) {
			boolean canCast = c.magicMissileEnabled;
			ItemStack icon = named(Items.BLAZE_POWDER, canCast ? "§eCast Spell" : "§7Cast Spell",
					List.of("§7Casts your currently selected spell", "§7in whatever direction you're looking.", "",
							canCast ? "§eClick to cast!" : "§cSelect a spell under Switch Spell first"));
			return new MenuAction(icon, () -> {
				if (!canCast) {
					return;
				}
				Minecraft client = Minecraft.getInstance();
				client.setScreen(null);
				MagicMissileManager.trigger(client);
			});
		}
	}

	private static final class SwitchSpellPage implements Page {
		@Override
		public String title() {
			return "Switch Spell";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			// Previously the only way to stop casting entirely was the separate full settings screen's
			// "Spell" toggle, not reachable from this quick menu at all - "mach spells auch dass man
			// ausschalten kann". Off is just another selectable entry here, same as any spell type.
			list.add(offAction(c, screen));
			list.add(spellTypeAction(Items.SNOWBALL, "Icy", "MISSILE", "Traditional travelling particle projectile.", c, screen));
			list.add(spellTypeAction(Items.TRIDENT, "Lightning", "LIGHTNING", "Instant - only fires if a player is right under your crosshair. Strikes them with a lightning bolt.", c, screen));
			list.add(spellTypeAction(Items.ENDER_EYE, "Plasma", "PLASMA", "Particles swirl in and bundle around the target into a growing, unstable ball - then it detonates.", c, screen));
			list.add(spellTypeAction(Items.SPECTRAL_ARROW, "Homing Arrow", "ARROW", "Travels like Icy, but curves to track the nearest player in front of you as it flies, steering around walls.", c, screen));
			list.add(spellTypeAction(Items.PHANTOM_MEMBRANE, "Levitate", "LEVITATE", "Instant, like Lightning - the target lifts off the ground, then a shockwave, then they're gone.", c, screen));
			return list;
		}

		private static MenuAction offAction(SkyMellooConfig c, SkyMellooMenuScreen screen) {
			boolean selected = !c.magicMissileEnabled;
			ItemStack icon = named(Items.BARRIER, (selected ? "§a✓ " : "§7") + "Off",
					List.of("§7Cast Spell does nothing.", "", selected ? "§aCurrently selected" : "§7Click to disable spells"));
			return new MenuAction(icon, () -> {
				c.magicMissileEnabled = false;
				SkyMellooConfig.HANDLER.save();
				screen.rebuild();
			});
		}

		private static MenuAction spellTypeAction(Item item, String name, String typeValue, String description, SkyMellooConfig c, SkyMellooMenuScreen screen) {
			boolean selected = c.magicMissileEnabled && typeValue.equalsIgnoreCase(c.magicMissileSpellType);
			ItemStack icon = named(item, (selected ? "§a✓ " : "§7") + name,
					List.of("§7" + description, "", selected ? "§aCurrently selected" : "§7Click to select"));
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
			return "Spell Stats";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
			List<MenuAction> list = new ArrayList<>();
			list.add(new MenuAction(named(Items.BLAZE_ROD, "§eSpells Cast",
					List.of("§7Total spells you've cast", "", "§f" + c.totalSpellsCast)), () -> {
			}));
			list.add(new MenuAction(named(Items.PLAYER_HEAD, "§ePlayers Killed",
					List.of("§7Total spell kills landed", "", "§f" + c.totalPlayersKilled)), () -> {
			}));
			list.add(new MenuAction(named(Items.AMETHYST_SHARD, "§dSpell Essence Collected",
					List.of("§7Total essence picked up", "", "§f" + c.totalSpellEssenceCollected)), () -> {
			}));
			return list;
		}
	}

	private static final class LastKillsPage implements Page {
		@Override
		public String title() {
			return "Last Kills";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			List<MagicMissileManager.RecentKill> kills = MagicMissileManager.getRecentKills();
			List<MenuAction> list = new ArrayList<>();
			if (kills.isEmpty()) {
				list.add(new MenuAction(named(Items.BARRIER, "§7No kills yet", List.of("§7Land a spell kill and it'll show up here")), () -> {
				}));
				return list;
			}
			for (MagicMissileManager.RecentKill kill : kills) {
				long secondsAgo = (System.currentTimeMillis() - kill.timestampMillis()) / 1000;
				ItemStack head = new ItemStack(Items.PLAYER_HEAD);
				head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(kill.profile()));
				// #N is the real total kill count against THIS player, not this entry's position in
				// the recent-kills list.
				head.set(DataComponents.CUSTOM_NAME, Component.literal("§e#" + kill.victimKillNumber() + " §f" + kill.profile().name()));
				head.set(DataComponents.LORE, new ItemLore(List.<Component>of(Component.literal("§7" + secondsAgo + "s ago"))));
				list.add(new MenuAction(head, () -> {
				}));
			}
			return list;
		}
	}

	// ---- cosmetics ----

	private record CosmeticDef(String name, BooleanSupplier enabledGetter, Consumer<Boolean> enabledSetter, Supplier<Color> colorGetter, Consumer<Color> colorSetter) {
		boolean hasColor() {
			return colorGetter != null;
		}
	}

	/** Every purely-cosmetic particle effect (excludes Magic Missile - that's its own richer page under Spells, with spell-type switching). */
	private static List<CosmeticDef> buildCosmeticDefs() {
		SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
		List<CosmeticDef> list = new ArrayList<>();
		list.add(new CosmeticDef("Halo", () -> c.haloEnabled, v -> c.haloEnabled = v, () -> c.haloColor, v -> c.haloColor = v));
		list.add(new CosmeticDef("Cherry Blossom", () -> c.cherryBlossomEnabled, v -> c.cherryBlossomEnabled = v, null, null));
		list.add(new CosmeticDef("Rainbow Helix", () -> c.rainbowHelixEnabled, v -> c.rainbowHelixEnabled = v, () -> c.rainbowHelixColor, v -> c.rainbowHelixColor = v));
		list.add(new CosmeticDef("Aura", () -> c.auraEnabled, v -> c.auraEnabled = v, () -> c.auraColor, v -> c.auraColor = v));
		list.add(new CosmeticDef("Wave", () -> c.waveEnabled, v -> c.waveEnabled = v, () -> c.waveColor, v -> c.waveColor = v));
		list.add(new CosmeticDef("Rain Cloud", () -> c.rainCloudEnabled, v -> c.rainCloudEnabled = v, null, null));
		list.add(new CosmeticDef("Fire Ring", () -> c.fireRingEnabled, v -> c.fireRingEnabled = v, null, null));
		list.add(new CosmeticDef("Star Rain", () -> c.starRainEnabled, v -> c.starRainEnabled = v, null, null));
		list.add(new CosmeticDef("Spark Aura", () -> c.sparkAuraEnabled, v -> c.sparkAuraEnabled = v, null, null));
		list.add(new CosmeticDef("Lissajous", () -> c.lissajousEnabled, v -> c.lissajousEnabled = v, () -> c.lissajousColor, v -> c.lissajousColor = v));
		list.add(new CosmeticDef("Rose Curve", () -> c.roseCurveEnabled, v -> c.roseCurveEnabled = v, () -> c.roseCurveColor, v -> c.roseCurveColor = v));
		list.add(new CosmeticDef("Landing Shockwave", () -> c.landingShockwaveEnabled, v -> c.landingShockwaveEnabled = v, () -> c.landingShockwaveColor, v -> c.landingShockwaveColor = v));
		list.add(new CosmeticDef("Firework Burst", () -> c.fireworkBurstEnabled, v -> c.fireworkBurstEnabled = v, null, null));
		list.add(new CosmeticDef("Frost Aura", () -> c.frostAuraEnabled, v -> c.frostAuraEnabled = v, () -> c.frostAuraColor, v -> c.frostAuraColor = v));
		list.add(new CosmeticDef("Note Melody", () -> c.noteMelodyEnabled, v -> c.noteMelodyEnabled = v, null, null));
		list.add(new CosmeticDef("Portal Vortex", () -> c.portalVortexEnabled, v -> c.portalVortexEnabled = v, () -> c.portalVortexColor, v -> c.portalVortexColor = v));
		list.add(new CosmeticDef("Heart Trail", () -> c.heartTrailEnabled, v -> c.heartTrailEnabled = v, null, null));
		list.add(new CosmeticDef("Spiral Galaxy", () -> c.spiralGalaxyEnabled, v -> c.spiralGalaxyEnabled = v, () -> c.spiralGalaxyColor, v -> c.spiralGalaxyColor = v));
		list.add(new CosmeticDef("Jump Trail", () -> c.jumpTrailEnabled, v -> c.jumpTrailEnabled = v, () -> c.jumpTrailColor, v -> c.jumpTrailColor = v));
		list.add(new CosmeticDef("Totem Flash", () -> c.totemFlashEnabled, v -> c.totemFlashEnabled = v, null, null));
		list.add(new CosmeticDef("Sculk Pulse", () -> c.sculkPulseEnabled, v -> c.sculkPulseEnabled = v, null, null));
		list.add(new CosmeticDef("Omen Aura", () -> c.omenAuraEnabled, v -> c.omenAuraEnabled = v, null, null));
		list.add(new CosmeticDef("Gust Aura", () -> c.gustAuraEnabled, v -> c.gustAuraEnabled = v, () -> c.gustAuraColor, v -> c.gustAuraColor = v));
		list.add(new CosmeticDef("Ash Fall", () -> c.ashFallEnabled, v -> c.ashFallEnabled = v, () -> c.ashFallColor, v -> c.ashFallColor = v));
		list.add(new CosmeticDef("Campfire Smoke", () -> c.campfireSmokeEnabled, v -> c.campfireSmokeEnabled = v, null, null));
		list.add(new CosmeticDef("Tornado", () -> c.tornadoEnabled, v -> c.tornadoEnabled = v, () -> c.tornadoColor, v -> c.tornadoColor = v));
		list.add(new CosmeticDef("Black Hole", () -> c.blackHoleEnabled, v -> c.blackHoleEnabled = v, () -> c.blackHoleColor, v -> c.blackHoleColor = v));
		list.add(new CosmeticDef("Twin Vortex", () -> c.twinVortexEnabled, v -> c.twinVortexEnabled = v, () -> c.twinVortexColor, v -> c.twinVortexColor = v));
		list.add(new CosmeticDef("Enchanted Crit Sparkle", () -> c.enchantedCritSparkleEnabled, v -> c.enchantedCritSparkleEnabled = v, null, null));
		list.add(new CosmeticDef("Dust Plume Trail", () -> c.dustPlumeTrailEnabled, v -> c.dustPlumeTrailEnabled = v, null, null));
		list.add(new CosmeticDef("Charge Up", () -> c.chargeUpEnabled, v -> c.chargeUpEnabled = v, () -> c.chargeUpColor, v -> c.chargeUpColor = v));
		list.add(new CosmeticDef("Orbit Rings", () -> c.orbitRingsEnabled, v -> c.orbitRingsEnabled = v, () -> c.orbitRingsColor, v -> c.orbitRingsColor = v));
		list.add(new CosmeticDef("Lightning Aura", () -> c.lightningAuraEnabled, v -> c.lightningAuraEnabled = v, null, null));
		list.add(new CosmeticDef("Confetti Burst", () -> c.confettiBurstEnabled, v -> c.confettiBurstEnabled = v, null, null));
		list.add(new CosmeticDef("Phoenix Wings", () -> c.phoenixWingsEnabled, v -> c.phoenixWingsEnabled = v, () -> c.phoenixWingsColor, v -> c.phoenixWingsColor = v));
		list.add(new CosmeticDef("Void Rift", () -> c.voidRiftEnabled, v -> c.voidRiftEnabled = v, null, null));
		list.add(new CosmeticDef("Star Weave", () -> c.starWeaveEnabled, v -> c.starWeaveEnabled = v, null, null));
		list.add(new CosmeticDef("Ascending Sparkles", () -> c.ascendingSparklesEnabled, v -> c.ascendingSparklesEnabled = v, null, null));
		list.add(new CosmeticDef("Comet Trail", () -> c.cometTrailEnabled, v -> c.cometTrailEnabled = v, null, null));
		list.add(new CosmeticDef("Star Veil", () -> c.starVeilEnabled, v -> c.starVeilEnabled = v, null, null));
		list.add(new CosmeticDef("Radiant Pulse", () -> c.radiantPulseEnabled, v -> c.radiantPulseEnabled = v, null, null));
		return list;
	}

	private static final class CosmeticsListPage implements Page {
		@Override
		public String title() {
			return "Cosmetics";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			List<MenuAction> list = new ArrayList<>();
			for (CosmeticDef def : buildCosmeticDefs()) {
				boolean on = def.enabledGetter().getAsBoolean();
				ItemStack icon = toggleIcon(on, def.name(), "Left-click to configure, right-click to toggle");
				list.add(new MenuAction(icon, () -> screen.push(new CosmeticDetailPage(def)), () -> {
					def.enabledSetter().accept(!def.enabledGetter().getAsBoolean());
					SkyMellooConfig.HANDLER.save();
					screen.rebuild();
				}));
			}
			return list;
		}

		@Override
		public MenuAction navExtraAction(SkyMellooMenuScreen screen) {
			return linkAction(Items.YELLOW_DYE, "Favorite Color", "Set one color for every colorable cosmetic at once", new FavoriteColorPickerPage(), screen);
		}
	}

	/** Same 16 dye choices as a single cosmetic's color picker, but applies the pick to EVERY colorable cosmetic at once instead of just one. */
	private static final class FavoriteColorPickerPage implements Page {
		@Override
		public String title() {
			return "Favorite Color";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			List<MenuAction> list = new ArrayList<>();
			for (DyeOption dye : DYE_OPTIONS) {
				ItemStack icon = named(dye.dye(), "§f" + dye.name(), List.of("§7Sets this color for every colorable cosmetic", "", "§eClick to apply"));
				list.add(new MenuAction(icon, () -> {
					Color color = new Color(0xFF000000 | dye.rgb(), true);
					for (CosmeticDef def : buildCosmeticDefs()) {
						if (def.hasColor()) {
							def.colorSetter().accept(color);
						}
					}
					SkyMellooConfig.HANDLER.save();
					screen.goBack();
				}));
			}
			return list;
		}
	}

	/** Left = on/off toggle, right = a link to the color picker (only if this cosmetic actually has a color). */
	private static final class CosmeticDetailPage implements Page {
		private final CosmeticDef def;

		CosmeticDetailPage(CosmeticDef def) {
			this.def = def;
		}

		@Override
		public String title() {
			return def.name();
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			List<MenuAction> list = new ArrayList<>();
			list.add(toggleAction(def.name(), "Turn this cosmetic on or off.", def.enabledGetter(), def.enabledSetter(), screen));
			if (def.hasColor()) {
				ParticleKindSupport kindSupport = particleKindSupportFor(def);
				boolean usingKind = kindSupport != null && !"DUST".equals(kindSupport.getter().get());
				// The icon reflects whichever color/particle is actually currently selected, not a fixed dye.
				Item colorIcon;
				if (usingKind) {
					colorIcon = PARTICLE_KIND_CHOICES.stream()
							.filter(choice -> choice.kindName().equals(kindSupport.getter().get()))
							.map(ParticleKindChoice::icon).findFirst().orElse(Items.END_ROD);
				} else {
					int currentRgb = def.colorGetter().get().getRGB() & 0xFFFFFF;
					colorIcon = DYE_OPTIONS.stream().filter(d -> d.rgb() == currentRgb).map(DyeOption::dye).findFirst().orElse(Items.RED_DYE);
				}
				String label = kindSupport != null ? "Color / Particle" : "Color";
				list.add(linkAction(colorIcon, label, "Pick a color or particle for " + def.name() + ".", new ColorPickerPage(def), screen));
			}
			return list;
		}
	}

	private record ParticleKindSupport(Supplier<String> getter, Consumer<String> setter) {
	}

	private record ParticleKindChoice(String kindName, String label, Item icon) {
	}

	// Alternate fixed particles a cosmetic can use instead of colored dust - reuses the existing
	// ParticleKind enum (SPARKLE is already END_ROD) rather than inventing new particle constants.
	private static final List<ParticleKindChoice> PARTICLE_KIND_CHOICES = List.of(
			new ParticleKindChoice("SPARKLE", "Starlight", Items.END_ROD),
			new ParticleKindChoice("SOUL", "Soul", Items.SOUL_TORCH),
			new ParticleKindChoice("SNOWFLAKE", "Snowflake", Items.SNOWBALL),
			new ParticleKindChoice("HAPPY_VILLAGER", "Emerald Sparkle", Items.EMERALD)
	);

	/** Which cosmetics also support switching to a fixed particle kind instead of colored dust - only a subset for now, same pattern can be extended to more. */
	private static ParticleKindSupport particleKindSupportFor(CosmeticDef def) {
		SkyMellooConfig c = SkyMellooConfig.HANDLER.instance();
		return switch (def.name()) {
			case "Halo" -> new ParticleKindSupport(() -> c.haloParticleKind, v -> c.haloParticleKind = v);
			case "Aura" -> new ParticleKindSupport(() -> c.auraParticleKind, v -> c.auraParticleKind = v);
			default -> null;
		};
	}

	private record DyeOption(Item dye, String name, int rgb) {
	}

	private static final List<DyeOption> DYE_OPTIONS = List.of(
			new DyeOption(Items.WHITE_DYE, "White", 0xFFFFFF),
			new DyeOption(Items.ORANGE_DYE, "Orange", 0xD87F33),
			new DyeOption(Items.MAGENTA_DYE, "Magenta", 0xB24CD8),
			new DyeOption(Items.LIGHT_BLUE_DYE, "Light Blue", 0x6699D8),
			new DyeOption(Items.YELLOW_DYE, "Yellow", 0xE5E533),
			new DyeOption(Items.LIME_DYE, "Lime", 0x7FCC19),
			new DyeOption(Items.PINK_DYE, "Pink", 0xF27FA5),
			new DyeOption(Items.GRAY_DYE, "Gray", 0x4C4C4C),
			new DyeOption(Items.LIGHT_GRAY_DYE, "Light Gray", 0x999999),
			new DyeOption(Items.CYAN_DYE, "Cyan", 0x4C7F99),
			new DyeOption(Items.PURPLE_DYE, "Purple", 0x7F3FB2),
			new DyeOption(Items.BLUE_DYE, "Blue", 0x334CB2),
			new DyeOption(Items.BROWN_DYE, "Brown", 0x664C33),
			new DyeOption(Items.GREEN_DYE, "Green", 0x667F33),
			new DyeOption(Items.RED_DYE, "Red", 0x993333),
			new DyeOption(Items.BLACK_DYE, "Black", 0x191919)
	);

	/** All 16 vanilla dye colors as clickable items - picking one sets the cosmetic's color and returns to its detail page. */
	private static final class ColorPickerPage implements Page {
		private final CosmeticDef def;

		ColorPickerPage(CosmeticDef def) {
			this.def = def;
		}

		@Override
		public String title() {
			return def.name() + " Color";
		}

		@Override
		public List<MenuAction> buildActions(SkyMellooMenuScreen screen) {
			List<MenuAction> list = new ArrayList<>();
			ParticleKindSupport kindSupport = particleKindSupportFor(def);
			boolean usingKind = kindSupport != null && !"DUST".equals(kindSupport.getter().get());
			int currentRgb = def.colorGetter().get().getRGB() & 0xFFFFFF;
			for (DyeOption dye : DYE_OPTIONS) {
				// Clicking any plain color always switches back to normal colored-dust mode, even if a
				// particle kind was selected before.
				boolean selected = !usingKind && dye.rgb() == currentRgb;
				ItemStack icon = named(dye.dye(), (selected ? "§a✓ " : "§f") + dye.name(),
						List.of("§7Click to use this color", "", selected ? "§aCurrently selected" : ""));
				list.add(new MenuAction(icon, () -> {
					def.colorSetter().accept(new Color(0xFF000000 | dye.rgb(), true));
					if (kindSupport != null) {
						kindSupport.setter().accept("DUST");
					}
					SkyMellooConfig.HANDLER.save();
					screen.goBack();
				}));
			}
			if (kindSupport != null) {
				for (ParticleKindChoice choice : PARTICLE_KIND_CHOICES) {
					boolean selected = choice.kindName().equals(kindSupport.getter().get());
					ItemStack icon = named(choice.icon(), (selected ? "§a✓ " : "§f") + choice.label(),
							List.of("§7Use this particle instead of a color", "", selected ? "§aCurrently selected" : ""));
					list.add(new MenuAction(icon, () -> {
						kindSupport.setter().accept(choice.kindName());
						SkyMellooConfig.HANDLER.save();
						screen.goBack();
					}));
				}
			}
			return list;
		}
	}
}
