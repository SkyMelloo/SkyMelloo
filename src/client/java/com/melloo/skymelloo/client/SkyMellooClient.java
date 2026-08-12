package com.melloo.skymelloo.client;

import com.melloo.skymelloo.client.api.ModAuthManager;
import com.melloo.skymelloo.client.api.SkyMellooApiClient;
import com.melloo.skymelloo.client.block.BlockHighlightRenderer;
import com.melloo.skymelloo.client.combat.PlayerKillTracker;
import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.cosmetics.MagicMissileManager;
import com.melloo.skymelloo.client.fishing.FishingHelper;
import com.melloo.skymelloo.client.fishing.FishingMinigameManager;
import com.melloo.skymelloo.client.party.PartyHud;
import com.melloo.skymelloo.client.party.PartyHudManager;
import com.melloo.skymelloo.client.party.PartyTracker;
import com.melloo.skymelloo.client.social.CloudSyncManager;
import com.melloo.skymelloo.client.social.ConnectionQualityMonitor;
import com.melloo.skymelloo.client.social.DungeonRoomTracker;
import com.melloo.skymelloo.client.social.DungeonDebugHud;
import com.melloo.skymelloo.client.social.DungeonRunTracker;
import com.melloo.skymelloo.client.social.DungeonScoreHud;
import com.melloo.skymelloo.client.social.DungeonTabList;
import com.melloo.skymelloo.client.social.ModPresenceManager;
import com.melloo.skymelloo.client.social.PartyJoinWatcher;
import com.melloo.skymelloo.client.social.PermissionsManager;
import com.melloo.skymelloo.client.social.ResourcePackStatus;
import com.melloo.skymelloo.client.social.SkyMellooPingMonitor;
import com.melloo.skymelloo.client.social.WhitelistManager;
import com.melloo.skymelloo.client.fishing.FishingScoreHud;
import com.melloo.skymelloo.client.util.ChatUtil;
import com.melloo.skymelloo.client.util.DebugLog;
import com.melloo.skymelloo.client.util.TickDelay;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public class SkyMellooClient implements ClientModInitializer {
	public static final String MOD_ID = "skymelloo";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath(MOD_ID, "main")
	);

	private static KeyMapping toggleMobHighlightKey;
	private static KeyMapping openConfigKey;
	private static KeyMapping mainMenuKey;

	/** So the settings screen itself can offer a "rebind" row without going out to vanilla's separate Controls screen. */
	public static KeyMapping getOpenConfigKey() {
		return openConfigKey;
	}

	@Override
	public void onInitializeClient() {
		SkyMellooConfig.HANDLER.load();
		// Lets essentials' own settings screen offer a "SkyMelloo Config" button back to this - H
		// always opens essentials' screen now (the single settings/status/player-info surface for
		// both mods), this button is the way to still reach SkyMelloo's own party/dungeon/etc tabs.
		com.melloo.mellooessentials.client.gui.SettingsScreen.setSkyMellooScreenOpener(() ->
				com.melloo.skymelloo.client.gui.SkyMellooSettingsScreen.open(com.melloo.skymelloo.client.gui.SkyMellooSettingsScreen.Tab.GENERAL));
		// These two extension points let essentials' ConnectionStatusHud surface extra info only
		// SkyMelloo has: whether this account is admin-linked, and the sky.melloo.me API ping.
		com.melloo.mellooessentials.client.social.ConnectionStatusHud.setAdminBadgeSupplier(WhitelistManager::isAdmin);
		com.melloo.mellooessentials.client.social.ConnectionStatusHud.setExtraLineProvider(() -> {
			int ms = com.melloo.skymelloo.client.social.SkyMellooPingMonitor.getLastPingMs();
			return ms >= 0 ? ms + "ms" : "--";
		});
		// The HUD layout editor (key J) moved into MellooEssentials entirely - it now natively handles
		// only the two HUD elements essentials itself renders, and this is the hook that lets it
		// supply the ones only SkyMelloo has (Fishing Combo, Party, Dungeon Score, etc.) without
		// essentials needing to know SkyMelloo exists. See SkyMellooHudElements/
		// HudLayoutEditorScreen's own doc comments.
		com.melloo.mellooessentials.client.gui.HudLayoutEditorScreen.setExtraElementsProvider(
				com.melloo.skymelloo.client.gui.SkyMellooHudElements::build);
		com.melloo.mellooessentials.client.gui.HudLayoutEditorScreen.setExtraSaveHandler(SkyMellooConfig.HANDLER::save);
		// Feeds live HP back into essentials' party glow decision - the one piece of data it can't
		// know on its own, for the low-HP blink. See HighlightManager#partyBlinkOverride's own doc comment.
		com.melloo.mellooessentials.client.highlight.HighlightManager.setPartyBlinkColorOverride(
				com.melloo.skymelloo.client.highlight.HighlightManager::partyBlinkOverride);
		PartyTracker.init();
		ModPresenceManager.init();
		PartyJoinWatcher.init();
		DungeonRunTracker.init();
		com.melloo.skymelloo.client.social.ActionBarTracker.init();
		com.melloo.skymelloo.client.social.ChatMentionHighlighter.init();
		com.melloo.skymelloo.client.social.AntiScamFilter.init();
		com.melloo.skymelloo.client.social.PartyGamesManager.init();
		com.melloo.skymelloo.client.util.PartyChatSender.init();
		com.melloo.skymelloo.client.gui.SkyMellooMenuItemManager.init();
		com.melloo.skymelloo.client.util.AutoReconnect.init();
		ResourcePackStatus.init();
		BlockHighlightRenderer.init();
		// INIT fires as soon as the play-protocol listener is set up, before the player entity/world exist.
		ClientPlayConnectionEvents.INIT.register((handler, client) -> {
			ConnectionQualityMonitor.reset();
			ConnectionQualityMonitor.start(client);
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			PlayerKillTracker.resetSession();
			// Whitelist/permissions/cloud-sync are account-level state on sky.melloo.me, not tied to a
			// TCP connection, so this event's own internal-server-hop firing (dungeon floor entry,
			// "/server X") doesn't reset them - each already has its own periodic 30s re-check. Party
			// membership IS connection-tied (per-connection HypixelModAPI packets), so it still resets.
			PartyHudManager.reset();
		});
		// Connection-status and Player-Info HUDs are MellooEssentials-only now (this mod's own
		// copies of both were byte-for-byte duplicates - see SkyMellooSettingsScreen's GENERAL tab
		// and MellooEssentials' ConnectionStatusHud/PlayerInfoHud, plus the extension points
		// registered below for the admin badge and sky.melloo.me ping line this mod still owns).
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "fishing_score"), FishingScoreHud.INSTANCE);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "party"), PartyHud.INSTANCE);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "party_mp_bar"), com.melloo.skymelloo.client.party.PartyMpBarHud.INSTANCE);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "dungeon_score"), DungeonScoreHud.INSTANCE);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "dungeon_debug"), DungeonDebugHud.INSTANCE);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "health_mana_bars"), com.melloo.skymelloo.client.gui.HealthManaBarsHud.INSTANCE);

		// Unbound by default (no reasonable universal default across keyboard layouts) -
		// bind it yourself under Controls > Key Binds > SkyMelloo, or toggle Mob Highlighting from
		// the settings screen (key H by default) instead. Repurposed to toggle the dungeon
		// current-room mob highlight, since that's the only mob highlighting left at all.
		toggleMobHighlightKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.skymelloo.toggle_mob_highlight",
				InputConstants.Type.KEYSYM,
				InputConstants.UNKNOWN.getValue(),
				CATEGORY
		));

		// Opens the SkyMelloo settings screen directly, no ModMenu/commands needed. Unbound by
		// default now (used to default to H, but that's MellooEssentials' key now - its settings
		// screen is the single H-menu for both mods, with a "SkyMelloo Config" button back to this
		// screen). Still fully rebindable for anyone who wants a direct hotkey to it.
		openConfigKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.skymelloo.open_config",
				InputConstants.Type.KEYSYM,
				InputConstants.UNKNOWN.getValue(),
				CATEGORY
		));

		// Opens the main SkyMelloo Menu item's screen (Credits/Spells/Cosmetics/Report a Bug nav row -
		// see SkyMellooMenuScreen/SkyMellooMenuItemManager). Defaults to K (free in vanilla).
		mainMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.skymelloo.main_menu",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_K,
				CATEGORY
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// Runs regardless of sky.melloo.me whitelist status - this is about the actual Minecraft
			// server connection, unrelated to any of the Hypixel-only SkyBlock features gated
			// below. ConnectionQualityMonitor specifically IS Hypixel-only despite sitting in this
			// block - its own start() refuses to ever go active off Hypixel (real bug fixed: it used
			// to fire its connection-quality chat messages on any server at all), so tick() here is
			// just a no-op everywhere else.
			ConnectionQualityMonitor.tick(client);
			com.melloo.skymelloo.client.util.SkyblockDetector.tick(client);
			SkyMellooPingMonitor.tick(client);
			com.melloo.skymelloo.client.util.AutoReconnect.tick(client);

			while (mainMenuKey.consumeClick()) {
				com.melloo.skymelloo.client.gui.SkyMellooMenuItemManager.openMenu(client);
			}
			while (openConfigKey.consumeClick()) {
				if (client.screen == null) {
					com.melloo.skymelloo.client.gui.SkyMellooSettingsScreen.open(com.melloo.skymelloo.client.gui.SkyMellooSettingsScreen.Tab.GENERAL);
				}
			}

			// Everything else is Hypixel-only - the whole rest of the mod (SkyBlock features, party,
			// friends, cloud sync, the whitelist/version/permission checks that gate them) has no
			// reason to run on any other server.
			if (!com.melloo.mellooessentials.client.util.HypixelDetector.isHypixel(client)) {
				TickDelay.tick();
				return;
			}

			PartyHudManager.tick(client);
			DungeonTabList.tick(client);
			DungeonRunTracker.tick(client);
			DungeonRoomTracker.tick(client);
			com.melloo.skymelloo.client.highlight.LobbySearchManager.tick(client);

			WhitelistManager.checkOnce(client);
			WhitelistManager.tickPeriodicRecheck(client);
			com.melloo.skymelloo.client.social.ModVersionManager.checkOnce(client);
			// ModVersionManager only ever sends an informational chat warning (see its own checkOnce),
			// never disables anything.
			PermissionsManager.fetchIfNeeded(client);
			PermissionsManager.tickPeriodicRecheck(client);
			CloudSyncManager.pullIfNeeded(client);
			while (toggleMobHighlightKey.consumeClick()) {
				boolean showFeedback = SkyMellooConfig.HANDLER.instance().debugMessagesEnabled && client.player != null;
				setMobHighlightEnabled(!SkyMellooConfig.HANDLER.instance().dungeonRoomMobHighlightEnabled,
						showFeedback ? client.player::sendSystemMessage : null);
			}
			FishingHelper.tick(client);
			FishingMinigameManager.tick(client);
			// Not gated on the highlight system anymore - the party HUD needs this too now. PartyTracker itself only
			// actually sends a request once on join and then on party-related chat lines, not every
			// tick, so this is cheap regardless.
			PartyTracker.tick();
			ResourcePackStatus.tick(client);
			PartyJoinWatcher.tick(client);
			BlockHighlightRenderer.tick(client);
			MagicMissileManager.tick(client);
			com.melloo.skymelloo.client.gui.SkyMellooMenuItemManager.tick(client);
			com.melloo.skymelloo.client.combat.DeathRecapManager.tick(client);
			com.melloo.skymelloo.client.social.DungeonSyncManager.sampleTick(client);
			com.melloo.skymelloo.client.social.BossRoomScanner.tick(client);
			TickDelay.tick();
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			var skymellooNode = dispatcher.register(
				ClientCommands.literal("skymelloo")
						.executes(ctx -> {
							sendHelp(ctx.getSource());
							return 1;
						})
						.then(ClientCommands.literal("help").executes(ctx -> {
							sendHelp(ctx.getSource());
							return 1;
						}))
						.then(ClientCommands.literal("sync")
								// Bare "/sm sync" == "/sm sync party" - the only thing left to sync.
								.executes(ctx -> {
									PartyTracker.requestRefreshNow();
									ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.sync.requested")));
									return 1;
								})
								.then(ClientCommands.literal("party").executes(ctx -> {
									PartyTracker.requestRefreshNow();
									ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.sync.requested")));
									return 1;
								})))
						.then(com.melloo.skymelloo.client.highlight.LobbySearchManager.buildSearchCommand())
						.then(buildGetDataCommand())
						.then(com.melloo.skymelloo.client.social.PartyGamesManager.buildRollCommand())
						.then(ClientCommands.literal("partyjoin")
								.executes(ctx -> {
									ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.usage.partyjoin_test")));
									return 1;
								})
								.then(ClientCommands.literal("test")
										.executes(ctx -> {
											ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.usage.partyjoin_test")));
											return 1;
										})
										.then(ClientCommands.argument("name", StringArgumentType.word())
												.suggests(SkyMellooClient::suggestOnlinePlayers)
												.executes(ctx -> {
													PartyJoinWatcher.lookupAndAnnounce(StringArgumentType.getString(ctx, "name"));
													return 1;
												}))))
						.then(ClientCommands.literal("kills").executes(ctx -> {
							ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.kills.summary",
									SkyMellooConfig.HANDLER.instance().totalSpellsCast, SkyMellooConfig.HANDLER.instance().totalPlayersKilled, SkyMellooConfig.HANDLER.instance().totalSpellEssenceCollected)
							));
							return 1;
						}))
						.then(ClientCommands.literal("session").executes(ctx -> {
							var stats = com.melloo.skymelloo.client.social.DungeonRunTracker.getSessionStats();
							if (stats.runsCompleted() == 0) {
								ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.session.none")));
								return 1;
							}
							int hours = stats.totalSeconds() / 3600;
							int minutes = (stats.totalSeconds() % 3600) / 60;
							String timeText = hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
							ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.session.header")));
							ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.session.runs",
									stats.runsCompleted(), stats.splusRuns(), String.format("%.0f", stats.averageScore()))));
							ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.session.deaths",
									stats.totalDeaths(), timeText)));
							return 1;
						}))
						// "/sm debug hm-bar" (renamed from "mana") - dumps the FULL pipeline to chat: the raw actionbar segments,
						// every "cur/max" fraction parsed out of them (labeled by position, health=0/mana=1
						// per ActionBarTracker's own positional matching), and then the actual on-screen
						// bar fill state computed via the exact same HealthManaBarsHud.compute*BarState()
						// methods the real renderer uses - so this can never drift from what's really drawn.
						.then(ClientCommands.literal("debug")
								.executes(ctx -> {
									ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.usage")));
									return 1;
								})
								.then(ClientCommands.literal("hm-bar").executes(ctx -> {
									long lastPacketMillis = com.melloo.skymelloo.client.social.ActionBarTracker.getLastPacketMillis();
									ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.hmbar.header")));
									if (lastPacketMillis == 0) {
										ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.hmbar.no_packet")));
										return 1;
									}
									long sincePacket = System.currentTimeMillis() - lastPacketMillis;
									var segments = com.melloo.skymelloo.client.social.ActionBarTracker.getLastSegments();
									ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.hmbar.raw_header", sincePacket, segments.size())));
									for (com.melloo.skymelloo.client.social.ActionBarTracker.Segment seg : segments) {
										ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.hmbar.segment", seg.colorHex(), seg.text())));
									}

									StringBuilder flattened = new StringBuilder();
									for (com.melloo.skymelloo.client.social.ActionBarTracker.Segment seg : segments) {
										flattened.append(seg.text());
									}
									var fractionMatcher = java.util.regex.Pattern.compile("([\\d,]+)\\s*/\\s*([\\d,]+)").matcher(flattened);
									ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.hmbar.fractions_header")));
									int fractionIndex = 0;
									while (fractionMatcher.find()) {
										Component label = fractionIndex == 0 ? Component.translatable("skymelloo.command.debug.hmbar.label_health")
												: fractionIndex == 1 ? Component.translatable("skymelloo.command.debug.hmbar.label_mana")
												: Component.translatable("skymelloo.command.debug.hmbar.label_unused");
										ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.hmbar.fraction", fractionIndex, fractionMatcher.group(), label)));
										fractionIndex++;
									}
									ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.hmbar.health_mana",
											com.melloo.skymelloo.client.social.ActionBarTracker.getCurrentHealth(),
											com.melloo.skymelloo.client.social.ActionBarTracker.getMaxHealth(),
											com.melloo.skymelloo.client.social.ActionBarTracker.getCurrentMana(),
											com.melloo.skymelloo.client.social.ActionBarTracker.getMaxMana())));

									Minecraft mc = Minecraft.getInstance();
									if (mc.player == null) {
										return 1;
									}
									var healthState = com.melloo.skymelloo.client.gui.HealthManaBarsHud.computeHealthBarState(mc.player);
									var manaState = com.melloo.skymelloo.client.gui.HealthManaBarsHud.computeManaBarState();
									var hud = com.melloo.skymelloo.client.gui.HealthManaBarsHud.INSTANCE;
									int barWidth = com.melloo.skymelloo.client.gui.HealthManaBarsHud.BAR_WIDTH;
									ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.hmbar.displayed_header",
											barWidth, com.melloo.skymelloo.client.gui.HealthManaBarsHud.BAR_HEIGHT)));
									Component healthSource = healthState.fromActionBar()
											? Component.translatable("skymelloo.command.debug.hmbar.source_actionbar")
											: Component.translatable("skymelloo.command.debug.hmbar.source_vanilla");
									net.minecraft.network.chat.MutableComponent healthBarLine = Component.translatable("skymelloo.command.debug.hmbar.health_bar",
											healthState.health(), healthState.maxHealth(), healthSource, healthState.healthPx(), barWidth,
											String.format("%.1f%%", healthState.healthFraction() * 100));
									if (healthState.absorptionPx() > 0) {
										healthBarLine.append(Component.translatable("skymelloo.command.debug.hmbar.absorption_suffix",
												healthState.absorptionPx(), barWidth, String.format("%.1f", healthState.absorption())));
									}
									ctx.getSource().sendFeedback(ChatUtil.prefixed(healthBarLine));
									ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.hmbar.trail_line",
											hud.isHealthTrailInitialized() ? Component.literal(Math.round(barWidth * hud.getDisplayedHealthFraction()) + "/" + barWidth + "px") : Component.translatable("skymelloo.command.debug.hmbar.not_initialized_long"),
											hud.isHealthTrailInitialized() ? Component.literal(Math.round(barWidth * hud.getRisingHealthFraction()) + "/" + healthState.healthPx() + "px") : Component.translatable("skymelloo.command.debug.hmbar.not_initialized_short"))));
									if (manaState.fraction() != null) {
										ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.hmbar.mana_bar",
												manaState.current(), manaState.max(), manaState.manaPx(), barWidth, String.format("%.1f%%", manaState.fraction() * 100))));
									} else {
										ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.hmbar.mana_none")));
									}
									ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.hmbar.trail_line",
											hud.isManaTrailInitialized() ? Component.literal(Math.round(barWidth * hud.getDisplayedManaFraction()) + "/" + barWidth + "px") : Component.translatable("skymelloo.command.debug.hmbar.not_initialized_long"),
											hud.isManaTrailInitialized() ? Component.literal(Math.round(barWidth * hud.getRisingManaFraction()) + "/" + manaState.manaPx() + "px") : Component.translatable("skymelloo.command.debug.hmbar.not_initialized_short"))));

									SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
									ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.hmbar.enabled_prefix")
											.append(Component.literal("healthManaBarsEnabled=" + config.healthManaBarsEnabled
													+ " healthBarEnabled=" + config.healthBarEnabled + " manaBarEnabled=" + config.manaBarEnabled
													+ " sideBySide=" + config.healthManaBarsSideBySide))));
									return 1;
								}))
								// "/sm debug bossroom" - diagnostic for the boss-room 3D scanner prototype
								// after a real report of it silently not working, with no error in the log -
								// this exposes whether it's even active and how many blocks it's found,
								// instead of it working (or not) completely silently.
								.then(ClientCommands.literal("bossroom").executes(ctx -> {
									ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.bossroom.header")));
									ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.bossroom.entered_cleared",
											com.melloo.skymelloo.client.social.DungeonRunTracker.isBossRoomEntered(), com.melloo.skymelloo.client.social.DungeonRunTracker.isBossRoomCleared())));
									ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.bossroom.scanner_active", com.melloo.skymelloo.client.social.BossRoomScanner.isActive())));
									if (com.melloo.skymelloo.client.social.BossRoomScanner.isActive()) {
										ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.bossroom.origin",
												com.melloo.skymelloo.client.social.BossRoomScanner.getOrigin(), com.melloo.skymelloo.client.social.BossRoomScanner.getScanId())));
										ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.bossroom.positions",
												com.melloo.skymelloo.client.social.BossRoomScanner.getSeenCount(), com.melloo.skymelloo.client.social.BossRoomScanner.getPendingCount())));
									} else {
										ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.bossroom.not_scanning")));
									}
									// "Queued, not yet sent: 0" above only proves the data was drained LOCALLY,
									// never that the HTTP report carrying it actually reached the server - these
									// counts answer whether it actually arrived, tracking only presence reports
									// that genuinely had ≥1 boss-room block in them.
									long attempts = com.melloo.skymelloo.client.social.ModPresenceManager.getBossRoomSendAttempts();
									long successes = com.melloo.skymelloo.client.social.ModPresenceManager.getBossRoomSendSuccesses();
									long failures = com.melloo.skymelloo.client.social.ModPresenceManager.getBossRoomSendFailures();
									ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.bossroom.reports", attempts, successes, failures)));
									if (failures > 0) {
										ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.bossroom.last_error", com.melloo.skymelloo.client.social.ModPresenceManager.getLastBossRoomSendError())));
									}
									return 1;
								}))
								// "/sm debug score" - real bugfix: the
								// live/recorded score stayed frozen at 120 for an entire run regardless of
								// real progress. Dumps every input the formula uses, PLUS a scan of every
								// reconstructed tab-list line (not just the fixed index 43 the formula
								// actually reads) so a live report shows exactly whether that index is wrong,
								// empty, or Skyblocker's own score just isn't being used - instead of guessing.
								.then(ClientCommands.literal("score").executes(ctx -> {
									var info = com.melloo.skymelloo.client.social.DungeonRunTracker.debugScoreInfo();
									ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.score.header")));
									ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.score.skyblocker", info.skyblockerAvailable(), info.skyblockerScore())));
									ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.score.rooms",
											info.totalRooms(), info.completedRooms(), info.extraCompletedRooms(), info.clearedPercentUsed())));
									ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.score.estimate",
											info.skill(), info.explore(), info.speed(), info.bonus(), info.total())));
									java.util.List<String> lines = com.melloo.skymelloo.client.social.DungeonTabList.getAllLines();
									Component line43 = 43 < lines.size() ? Component.literal(lines.get(43)) : Component.translatable("skymelloo.command.debug.score.out_of_range");
									ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.score.tablist", lines.size(), line43)));
									boolean foundElsewhere = false;
									for (int i = 0; i < lines.size(); i++) {
										if (lines.get(i) != null && lines.get(i).contains("Completed Rooms")) {
											ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.score.match", i, lines.get(i))));
											foundElsewhere = true;
										}
									}
									if (!foundElsewhere) {
										ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.score.not_found")));
									}
									return 1;
								})))
						// "/sm version" and "/sm info" merged into one command - always fires a FRESH check
						// against the server (see ModVersionManager#checkNow) rather than just showing
						// whatever the one join-time check happened to cache, so this always reflects the
						// real latest published version right now, cooldown-limited client-side against
						// accidental spam. Dropped the buildKind/"official"/"unofficial" trust framing
						// entirely - a self-reported build check can't actually prove anything to anyone but
						// yourself - plain informational, version numbers and a reminder of where the real
						// thing comes from, nothing more.
						.then(ClientCommands.literal("version").executes(ctx -> {
							String version = com.melloo.skymelloo.client.social.ModVersionManager.getLocalVersion();
							String publicVersion = com.melloo.skymelloo.client.social.ModVersionManager.getPublicVersion();
							String jarHash = com.melloo.skymelloo.client.social.ModVersionManager.getLocalJarHash();
							ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.version.header")));
							Component jarHashText = jarHash != null ? Component.literal(jarHash) : Component.translatable("skymelloo.command.version.jarhash_unknown");
							ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.version.running", publicVersion, version, jarHashText)));
							ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.version.checking")));
							com.melloo.skymelloo.client.social.ModVersionManager.checkNow(
									result -> {
										Minecraft c = Minecraft.getInstance();
										if (c.player == null) {
											return;
										}
										if (result == null) {
											c.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.command.version.unreachable")));
											return;
										}
										if (result.latestPublicVersion() != null) {
											c.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.command.version.latest_published", result.latestPublicVersion())));
										}
										if (result.upToDate()) {
											c.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.command.version.up_to_date")));
										} else {
											c.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.command.version.outdated")));
										}
										c.player.sendSystemMessage(legalLink(Component.translatable("skymelloo.command.version.get_from_official"), "https://sky.melloo.me/download"));
									},
									cooldownSeconds -> {
										Minecraft c = Minecraft.getInstance();
										if (c.player == null) {
											return;
										}
										c.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.command.version.cooldown", cooldownSeconds)));
										c.player.sendSystemMessage(legalLink(Component.translatable("skymelloo.command.version.get_from_official"), "https://sky.melloo.me/download"));
									}
							);
							return 1;
						}))
						// German data-protection law expects the legal pages to actually be reachable from
						// the mod itself, not just buried in the website's footer. Fetched fully server-side
						// (rather than hardcoded here) so the server can refuse to hand them out to a build
						// it can't verify as an official/dev SkyMelloo release, since a modified build
						// genuinely isn't legally covered by the maintainer's own imprint/privacy/terms.
						.then(ClientCommands.literal("legal").executes(ctx -> {
							String jarHash = com.melloo.skymelloo.client.social.ModVersionManager.getLocalJarHash();
							com.melloo.skymelloo.client.api.SkyMellooApiClient.fetchLegalInfo(jarHash).whenComplete((info, error) -> Minecraft.getInstance().execute(() -> {
								if (error != null || info == null) {
									// Addressed partly to whoever actually built this - a test/private build is
									// almost always someone's own compile, so it's worth telling them directly that
									// this command still points at the real maintainer's own legal pages and
									// probably shouldn't ship as-is in a real fork, not just a generic refusal.
									var lastResult = com.melloo.skymelloo.client.social.ModVersionManager.getLastResult();
									Component maintainer = lastResult != null && lastResult.maintainerUsername() != null
											? Component.literal(lastResult.maintainerUsername())
											: Component.translatable("skymelloo.command.legal.fallback_maintainer");
									ctx.getSource().sendFeedback(ChatUtil.prefixed(
											Component.translatable("skymelloo.command.legal.not_official")));
									ctx.getSource().sendFeedback(ChatUtil.prefixed(
											Component.translatable("skymelloo.command.legal.fork_reminder", maintainer)));
									return;
								}
								ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.legal.header")));
								ctx.getSource().sendFeedback(legalLink(Component.translatable("skymelloo.command.legal.label_imprint"), info.imprint()));
								ctx.getSource().sendFeedback(legalLink(Component.translatable("skymelloo.command.legal.label_privacy"), info.privacy()));
								ctx.getSource().sendFeedback(legalLink(Component.translatable("skymelloo.command.legal.label_terms"), info.terms()));
							}));
							return 1;
						}))
						.then(ClientCommands.literal("config").executes(ctx -> {
							// Always open, regardless of current screen - the chat/command screen
							// is technically still "open" when this executes, so a null-check here
							// would silently do nothing.
							com.melloo.skymelloo.client.gui.SkyMellooSettingsScreen.open(com.melloo.skymelloo.client.gui.SkyMellooSettingsScreen.Tab.GENERAL);
							return 1;
						}))
						.then(ClientCommands.literal("unlink").executes(ctx -> {
							Minecraft client = Minecraft.getInstance();
							if (client.player == null) {
								return 1;
							}
							ModAuthManager.getIdentity(client).thenCompose(SkyMellooApiClient::unlinkAccount)
									.whenComplete((result, error) ->
											Minecraft.getInstance().execute(() -> {
												Minecraft c = Minecraft.getInstance();
												if (c.player == null) {
													return;
												}
												if (error != null) {
													c.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.command.common.failed", ChatUtil.friendlyError(error))));
												} else if (result.ok()) {
													c.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.command.unlink.success")));
												} else {
													c.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.command.common.failed", result.error())));
												}
											})
									);
							return 1;
						}))
						// "/sm link" - the mirror image of MellooEssentials' "/me verify <code>": instead of
						// typing a website-generated code in-game, this generates a token in-game and opens
						// sky.melloo.me/link/<token> directly in the system browser, where it completes using
						// whatever Discord session is already there (or prompts a fresh login first) - no
						// code to type at all.
						.then(ClientCommands.literal("link").executes(ctx -> {
							Minecraft client = Minecraft.getInstance();
							if (client.player == null) {
								return 1;
							}
							ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.link.opening")));
							ModAuthManager.getIdentity(client).thenCompose(SkyMellooApiClient::startAccountLink)
									.whenComplete((result, error) ->
											Minecraft.getInstance().execute(() -> {
												Minecraft c = Minecraft.getInstance();
												if (c.player == null) {
													return;
												}
												if (error != null) {
													c.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.command.common.failed", ChatUtil.friendlyError(error))));
												} else if (result.ok()) {
													net.minecraft.util.Util.getPlatform().openUri(java.net.URI.create("https://sky.melloo.me/link/" + result.token()));
												} else {
													c.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.command.common.failed", result.error())));
												}
											})
									);
							return 1;
						}))
						.then(ClientCommands.literal("contact").executes(ctx -> {
							ctx.getSource().sendFeedback(legalLink(Component.translatable("skymelloo.command.contact.label"), "https://sky.melloo.me/contact"));
							return 1;
						}))
						.then(ClientCommands.literal("view")
								.then(ClientCommands.argument("name", StringArgumentType.word())
										.suggests(SkyMellooClient::suggestOnlinePlayers)
										.executes(ctx -> {
											com.melloo.skymelloo.client.gui.PlayerViewScreen.open(StringArgumentType.getString(ctx, "name"));
											return 1;
										})))
						// Fallback for anything that doesn't match a known subcommand above - Brigadier tries
						// literals first, so this only catches genuinely unknown input, replacing vanilla's
						// generic "Unknown command" with a SkyMelloo-branded pointer to /skymelloo help.
						.then(ClientCommands.argument("unknown", StringArgumentType.greedyString()).executes(ctx -> {
							ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.unknown_command")));
							return 1;
						}))
			);
			// "/sm" - a shorter alias for everything above, via Brigadier's own redirect mechanism
			// (the same one vanilla uses for its command aliases) rather than duplicating the whole
			// tree, so the two can never drift out of sync with each other.
			dispatcher.register(ClientCommands.literal("sm").redirect(skymellooNode));
		});

		LOGGER.info("SkyMelloo loaded. Open settings with 'H' (rebindable), or /skymelloo config");
	}

	/** Clickable "§dLabel: §fhttps://..." chat line - opens the URL in the system browser. Used by {@code /sm legal} and {@code /sm version}/{@code /sm info}'s download reminder. */
	private static net.minecraft.network.chat.MutableComponent legalLink(Component label, String url) {
		return Component.translatable("skymelloo.command.legal.link_line", label, url).withStyle(style -> style
				.withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(java.net.URI.create(url)))
				.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.translatable("skymelloo.command.legal.hover_open_browser"))));
	}

	private static void sendHelp(FabricClientCommandSource source) {
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.header")));
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.config")));
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.link")));
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.verify_note")));
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.unlink")));
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.view")));
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.getdata_player")));
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.getdata_party")));
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.version")));
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.contact")));
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.legal")));

		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.party_header")));
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.sync")));
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.social_note")));
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.roll")));

		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.dungeons_header")));
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.kills")));
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.session")));
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.partyjoin")));

		// Every feature is unlocked for everyone now except Spell (Magic Missile), which genuinely
		// still needs a linked sky.melloo.me account (the server has to know who's who to broadcast a
		// cast to nearby players). Particle cosmetics themselves moved to the separately-required
		// MellooEssentials mod and aren't account-gated at all anymore.
		java.util.List<String> unlockedFeatures = new java.util.ArrayList<>(java.util.List.of("Party", "Fishing", "Dungeons"));
		if (PermissionsManager.has("spell")) {
			unlockedFeatures.add("Spell");
		}
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.more_settings", String.join(", ", unlockedFeatures))));
	}

	public static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestOnlinePlayers(
			com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> ctx,
			com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
		Minecraft client = Minecraft.getInstance();
		if (client.getConnection() == null) {
			return builder.buildFuture();
		}
		return SharedSuggestionProvider.suggest(
				client.getConnection().getOnlinePlayers().stream()
						.map(info -> info.getProfile().name())
						// Hypixel NPCs commonly show up in the tab list with names starting with "!"
						// (e.g. "!Auctioneer") - not real players, so don't offer them as suggestions.
						.filter(name -> !name.startsWith("!")),
				builder
		);
	}

	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestPartyMembers(
			com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> ctx,
			com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
		Minecraft client = Minecraft.getInstance();
		if (client.getConnection() == null) {
			return builder.buildFuture();
		}
		return SharedSuggestionProvider.suggest(
				PartyTracker.getMembers().stream()
						.map(uuid -> client.getConnection().getPlayerInfo(uuid))
						.filter(java.util.Objects::nonNull)
						.map(info -> info.getProfile().name()),
				builder
		);
	}

	/** Autocompletes the "profile" argument with the SkyBlock profile names of whatever player was already typed as "name". */
	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestProfilesForPlayer(
			com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> ctx,
			com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
		String name;
		try {
			name = StringArgumentType.getString(ctx, "name");
		} catch (IllegalArgumentException e) {
			return builder.buildFuture();
		}
		return ModAuthManager.getIdentity(Minecraft.getInstance()).thenCompose(identity -> SkyMellooApiClient.fetchProfileNames(name, identity))
				.thenCompose(names -> {
					java.util.List<String> withNormal = new java.util.ArrayList<>(names);
					withNormal.add("normal");
					return SharedSuggestionProvider.suggest(withNormal, builder);
				})
				.exceptionally(e -> builder.build());
	}

	/**
	 * Every leaf stat name under getdata, in the order they were added. "all" and "mp" are handled
	 * specially in {@link #dispatchStat}/{@link #dispatchPartyStat} (different data source/shape
	 * than the plain summary-endpoint stats); everything else maps 1:1 to an {@link ExtraStat}.
	 */
	private static final java.util.List<String> GETDATA_STAT_NAMES = java.util.List.of(
			"all", "mp", "networth", "bank", "purse", "fairysouls", "guild", "rank",
			"skills", "slayer", "classes", "minions", "bestiary", "highestfloor", "firstjoin",
			"pets", "collections", "minionslots", "profiles", "dungeonruns"
	);

	/**
	 * Builds "/skymelloo getdata" with player/party (and the target name/profile) BEFORE the stat
	 * you want, e.g. "getdata player Foo Bar all" / "getdata player Foo mp" / "getdata party all" -
	 * so every stat shares one player-then-profile-then-stat shape instead of one command tree per stat.
	 */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildGetDataCommand() {
		var profileArg = ClientCommands.argument("profile", StringArgumentType.word())
				.suggests(SkyMellooClient::suggestProfilesForPlayer);
		for (String stat : GETDATA_STAT_NAMES) {
			var profileStatLiteral = ClientCommands.literal(stat).executes(ctx -> {
				String name = StringArgumentType.getString(ctx, "name");
				String profile = StringArgumentType.getString(ctx, "profile");
				dispatchStat(stat, name, profile, ctx.getSource(), false);
				return 1;
			});
			profileStatLiteral.then(ClientCommands.literal("announce").executes(ctx -> {
				String name = StringArgumentType.getString(ctx, "name");
				String profile = StringArgumentType.getString(ctx, "profile");
				dispatchStat(stat, name, profile, ctx.getSource(), true);
				return 1;
			}));
			profileArg.then(profileStatLiteral);
		}

		var playerNameArg = ClientCommands.argument("name", StringArgumentType.word())
				.suggests(SkyMellooClient::suggestOnlinePlayers)
				.then(profileArg);
		for (String stat : GETDATA_STAT_NAMES) {
			var statLiteral = ClientCommands.literal(stat).executes(ctx -> {
				String name = StringArgumentType.getString(ctx, "name");
				dispatchStat(stat, name, null, ctx.getSource(), false);
				return 1;
			});
			// Same idea as the party version below - "announce" only actually changes behavior for
			// "all"/"mp" right now (the only two that have a party-chat-safe combined format), harmless
			// no-op on the others - accepted everywhere for a consistent command shape.
			statLiteral.then(ClientCommands.literal("announce").executes(ctx -> {
				String name = StringArgumentType.getString(ctx, "name");
				dispatchStat(stat, name, null, ctx.getSource(), true);
				return 1;
			}));
			playerNameArg.then(statLiteral);
		}

		var partyNameArg = ClientCommands.argument("name", StringArgumentType.word())
				.suggests(SkyMellooClient::suggestPartyMembers);
		for (String stat : GETDATA_STAT_NAMES) {
			partyNameArg.then(ClientCommands.literal(stat).executes(ctx -> {
				String name = StringArgumentType.getString(ctx, "name");
				dispatchStat(stat, name, null, ctx.getSource(), false);
				return 1;
			}));
		}

		var partyLiteral = ClientCommands.literal("party").then(partyNameArg);
		for (String stat : GETDATA_STAT_NAMES) {
			var statLiteral = ClientCommands.literal(stat).executes(ctx -> {
				dispatchPartyStat(stat, ctx.getSource(), false);
				return 1;
			});
			// "announce" only actually changes behavior for "all" right now (see announcePartyAllStats)
			// - accepted on every stat for a consistent command shape, harmless no-op on the others.
			statLiteral.then(ClientCommands.literal("announce").executes(ctx -> {
				dispatchPartyStat(stat, ctx.getSource(), true);
				return 1;
			}));
			partyLiteral.then(statLiteral);
		}

		var getdataUsage = Component.translatable("skymelloo.command.usage.getdata");
		return ClientCommands.literal("getdata")
				.executes(ctx -> {
					ctx.getSource().sendFeedback(ChatUtil.prefixed(getdataUsage));
					return 1;
				})
				.then(ClientCommands.literal("player")
						.executes(ctx -> {
							ctx.getSource().sendFeedback(ChatUtil.prefixed(getdataUsage));
							return 1;
						})
						.then(playerNameArg))
				.then(partyLiteral.executes(ctx -> {
					ctx.getSource().sendFeedback(ChatUtil.prefixed(getdataUsage));
					return 1;
				}));
	}

	private static void dispatchStat(String stat, String name, String profile, FabricClientCommandSource source, boolean announce) {
		// "normal" is a stand-in for "whatever profile this player is currently playing" - same as
		// omitting the profile argument entirely, just spelled out for people who type the full
		// name -> profile -> stat shape out of habit instead of skipping straight to the stat.
		if (profile != null && profile.equalsIgnoreCase("normal")) {
			profile = null;
		}
		switch (stat) {
			case "all" -> {
				source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.getdata.loading_overview", name)));
				announceAllStats(name, profile, () -> {
				}, announce);
			}
			case "mp" -> {
				source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.getdata.looking_mp", name)));
				announceMagicalPower(name, profile, announce);
			}
			default -> announceExtraStat(ExtraStat.byCommandName(stat), name, profile, announce);
		}
	}

	private static void dispatchPartyStat(String stat, FabricClientCommandSource source, boolean announce) {
		switch (stat) {
			case "all" -> announcePartyAllStats(source, announce);
			case "mp" -> announcePartyMagicalPower(source, announce);
			default -> announcePartyExtraStat(ExtraStat.byCommandName(stat), source, announce);
		}
	}

	/**
	 * Stats beyond Magical Power that all live in the plain /player/:username summary endpoint
	 * (no extra API call needed per-stat) - networth/bank/purse/fairysouls/guild.
	 */
	private enum ExtraStat {
		NETWORTH("networth") {
			Component format(SkyMellooApiClient.SummaryResult s) {
				return Component.translatable("skymelloo.command.stat.networth", formatAmount(s.netWorth()));
			}
		},
		BANK("bank") {
			Component format(SkyMellooApiClient.SummaryResult s) {
				return Component.translatable("skymelloo.command.stat.bank", formatAmount(s.bank()));
			}
		},
		PURSE("purse") {
			Component format(SkyMellooApiClient.SummaryResult s) {
				return Component.translatable("skymelloo.command.stat.purse", formatAmount(s.purse()));
			}
		},
		FAIRYSOULS("fairysouls") {
			Component format(SkyMellooApiClient.SummaryResult s) {
				return Component.translatable("skymelloo.command.stat.fairysouls", s.fairySouls());
			}
		},
		GUILD("guild") {
			Component format(SkyMellooApiClient.SummaryResult s) {
				if (s.guildName() == null) {
					return Component.translatable("skymelloo.command.stat.guild_none");
				}
				Component tag = s.guildTag() != null ? Component.literal(" §7[" + s.guildTag() + "]") : Component.empty();
				Component members = s.guildMemberCount() > 0 ? Component.translatable("skymelloo.command.stat.guild_members", s.guildMemberCount()) : Component.empty();
				return Component.translatable("skymelloo.command.stat.guild", s.guildName(), tag, members);
			}
		},
		RANK("rank") {
			Component format(SkyMellooApiClient.SummaryResult s) {
				Component rank = s.rankLabel() != null ? Component.literal(s.rankLabel()) : Component.translatable("skymelloo.command.stat.rank_none");
				return Component.translatable("skymelloo.command.stat.rank", rank);
			}
		},
		SKILLS("skills") {
			Component format(SkyMellooApiClient.SummaryResult s) {
				return formatLevelMap(s.skillLevels());
			}
		},
		SLAYER("slayer") {
			Component format(SkyMellooApiClient.SummaryResult s) {
				return formatLevelMap(s.slayerLevels());
			}
		},
		CLASSES("classes") {
			Component format(SkyMellooApiClient.SummaryResult s) {
				return formatLevelMap(s.classLevels());
			}
		},
		MINIONS("minions") {
			Component format(SkyMellooApiClient.SummaryResult s) {
				return Component.translatable("skymelloo.command.stat.minions", s.minionUniqueCount(), s.minionUpgrades());
			}
		},
		BESTIARY("bestiary") {
			Component format(SkyMellooApiClient.SummaryResult s) {
				return Component.translatable("skymelloo.command.stat.bestiary", s.bestiaryKills());
			}
		},
		HIGHESTFLOOR("highestfloor") {
			Component format(SkyMellooApiClient.SummaryResult s) {
				Component floor = s.highestFloor() == 0 ? Component.translatable("skymelloo.command.stat.highestfloor_none") : Component.literal("F/M" + s.highestFloor());
				return Component.translatable("skymelloo.command.stat.highestfloor", floor);
			}
		},
		FIRSTJOIN("firstjoin") {
			Component format(SkyMellooApiClient.SummaryResult s) {
				if (s.firstJoin() <= 0) {
					return Component.translatable("skymelloo.command.stat.firstjoin_unknown");
				}
				long days = (System.currentTimeMillis() - s.firstJoin()) / 86_400_000L;
				return Component.translatable("skymelloo.command.stat.firstjoin", days);
			}
		},
		PETS("pets") {
			Component format(SkyMellooApiClient.SummaryResult s) {
				Component best = s.bestPetLabel() != null ? Component.translatable("skymelloo.command.stat.pets_best", s.bestPetLabel()) : Component.empty();
				return Component.translatable("skymelloo.command.stat.pets", s.petCount(), best);
			}
		},
		COLLECTIONS("collections") {
			Component format(SkyMellooApiClient.SummaryResult s) {
				return Component.translatable("skymelloo.command.stat.collections", s.collectionsStarted());
			}
		},
		MINIONSLOTS("minionslots") {
			Component format(SkyMellooApiClient.SummaryResult s) {
				return Component.translatable("skymelloo.command.stat.minionslots", s.minionSlots());
			}
		},
		PROFILES("profiles") {
			Component format(SkyMellooApiClient.SummaryResult s) {
				return Component.literal(s.profilesLabel());
			}
		},
		DUNGEONRUNS("dungeonruns") {
			Component format(SkyMellooApiClient.SummaryResult s) {
				return Component.translatable("skymelloo.command.stat.dungeonruns", s.dungeonCompletions());
			}
		};

		final String commandName;

		ExtraStat(String commandName) {
			this.commandName = commandName;
		}

		abstract Component format(SkyMellooApiClient.SummaryResult summary);

		static ExtraStat byCommandName(String commandName) {
			for (ExtraStat stat : values()) {
				if (stat.commandName.equals(commandName)) {
					return stat;
				}
			}
			throw new IllegalArgumentException("Unknown getdata stat: " + commandName);
		}
	}

	private static Component formatLevelMap(java.util.Map<String, Integer> levels) {
		if (levels.isEmpty()) {
			return Component.translatable("skymelloo.command.stat.no_data");
		}
		net.minecraft.network.chat.MutableComponent result = Component.empty();
		boolean first = true;
		for (var entry : levels.entrySet()) {
			if (!first) {
				result.append(Component.literal("§r, "));
			}
			first = false;
			String name = entry.getKey().substring(0, 1).toUpperCase() + entry.getKey().substring(1);
			result.append(Component.literal(name + " §d" + entry.getValue()));
		}
		return result;
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

	/**
	 * The website caches Hypixel data for ~45s so many requests only cost one upstream call - with
	 * debug messages on, show how stale the data actually is so it's clear why a fresh action in-game
	 * (like just picking up a fairy soul) might not show up immediately.
	 */
	private static String debugCacheAgeSuffix(long dataFetchedAt) {
		if (!SkyMellooConfig.HANDLER.instance().debugMessagesEnabled || dataFetchedAt <= 0) {
			return "";
		}
		long ageSeconds = Math.max(0, (System.currentTimeMillis() - dataFetchedAt) / 1000);
		return Component.translatable("skymelloo.command.common.cache_age_suffix", ageSeconds).getString();
	}

	private static void announceExtraStat(ExtraStat stat, String name, String profile, boolean announce) {
		ModAuthManager.getIdentity(Minecraft.getInstance()).thenCompose(identity -> SkyMellooApiClient.fetchSummary(name, profile, identity)).whenComplete((summary, error) ->
				Minecraft.getInstance().execute(() -> {
					Minecraft client = Minecraft.getInstance();
					if (client.player == null) {
						return;
					}
					if (error != null) {
						client.player.sendSystemMessage(ChatUtil.prefixed(ChatUtil.errorMessage(name, error)));
						return;
					}
					Component textComponent = Component.translatable("skymelloo.command.getdata.result_line", name, stat.format(summary), debugCacheAgeSuffix(summary.dataFetchedAt()));
					if (announce) {
						// §-codes above don't survive /pc (Hypixel strips them, see ChatUtil.partyPrefixed)
						// so they're harmlessly stripped for the party delivery, kept as-is for local.
						DungeonRunTracker.sendDungeonMessage(client, textComponent.getString(), "PARTY");
						return;
					}
					client.player.sendSystemMessage(ChatUtil.prefixed(textComponent));
				})
		);
	}

	private static void announcePartyExtraStat(ExtraStat stat, FabricClientCommandSource source, boolean announce) {
		java.util.List<String> names = resolvePartyMemberNames(false);
		if (names == null) {
			source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.common.no_party")));
			return;
		}
		if (names.isEmpty()) {
			source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.common.no_resolved_members")));
			return;
		}
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.getdata.checking_stat", stat.commandName, names.size())));
		announcePartyExtraStatSequentially(stat, names.iterator(), announce);
	}

	private static void announcePartyExtraStatSequentially(ExtraStat stat, java.util.Iterator<String> remaining, boolean announce) {
		if (!remaining.hasNext()) {
			return;
		}
		String name = remaining.next();
		Runnable next = () -> announcePartyExtraStatSequentially(stat, remaining, announce);
		// Staggered a full second apart when announcing to party, same reasoning as
		// announcePartyAllStatsSequentially - back-to-back /pc messages for a whole party risk
		// Hypixel's own chat rate limit silently swallowing some of them.
		Runnable onDone = announce ? () -> TickDelay.schedule(ANNOUNCE_STAGGER_TICKS, next) : next;
		ModAuthManager.getIdentity(Minecraft.getInstance()).thenCompose(identity -> SkyMellooApiClient.fetchSummary(name, identity)).whenComplete((summary, error) ->
				Minecraft.getInstance().execute(() -> {
					Minecraft client = Minecraft.getInstance();
					if (client.player != null) {
						if (error != null) {
							client.player.sendSystemMessage(ChatUtil.prefixed(ChatUtil.errorMessage(name, error)));
						} else {
							Component textComponent = Component.translatable("skymelloo.command.getdata.result_line", name, stat.format(summary), debugCacheAgeSuffix(summary.dataFetchedAt()));
							if (announce) {
								DungeonRunTracker.sendDungeonMessage(client, textComponent.getString(), "PARTY");
							} else {
								client.player.sendSystemMessage(ChatUtil.prefixed(textComponent));
							}
						}
					}
					onDone.run();
				})
		);
	}

	private static void announceMagicalPower(String name, String profile, boolean announce) {
		ModAuthManager.getIdentity(Minecraft.getInstance()).thenCompose(identity -> SkyMellooApiClient.fetchMagicalPower(name, profile, identity)).whenComplete((result, error) ->
				Minecraft.getInstance().execute(() -> {
					Minecraft client = Minecraft.getInstance();
					if (client.player == null) {
						return;
					}
					if (error != null) {
						client.player.sendSystemMessage(ChatUtil.prefixed(ChatUtil.errorMessage(name, error)));
						return;
					}
					if (result.magicalPower() < 0) {
						client.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.command.mp.not_found", name)));
						return;
					}
					Component activeSuffix = result.selectedPower() != null
							? Component.translatable("skymelloo.command.mp.active_suffix", result.selectedPower())
							: Component.empty();
					Component textComponent = Component.translatable("skymelloo.command.mp.result", name, result.magicalPower(), activeSuffix);
					if (announce) {
						DungeonRunTracker.sendDungeonMessage(client, textComponent.getString(), "PARTY");
						return;
					}
					client.player.sendSystemMessage(ChatUtil.prefixed(textComponent));
				})
		);
	}

	/**
	 * Combines the most important stats into one readable overview. Locally this is several English
	 * messages (one per stat group, easier to read); "announce" mode instead combines everything into
	 * ONE message sent via /pc if in a party, since firing 6 separate /pc messages per player would
	 * spam party chat badly across a whole party.
	 */
	private static void announceAllStats(String name, String profile, Runnable onDone, boolean announce) {
		ModAuthManager.getIdentity(Minecraft.getInstance()).thenAccept(identity ->
		SkyMellooApiClient.fetchSummary(name, profile, identity).whenComplete((summary, summaryError) ->
				SkyMellooApiClient.fetchMagicalPower(name, profile, identity).whenComplete((mp, mpError) ->
						Minecraft.getInstance().execute(() -> {
							try {
								Minecraft client = Minecraft.getInstance();
								if (client.player == null) {
									return;
								}
								if (summaryError != null) {
									client.player.sendSystemMessage(ChatUtil.prefixed(ChatUtil.errorMessage(name, summaryError)));
									return;
								}
								String mpValue = (mpError == null && mp.magicalPower() >= 0) ? String.valueOf(mp.magicalPower()) : "?";
								Component rank = summary.rankLabel() != null ? Component.literal(summary.rankLabel()) : Component.translatable("skymelloo.command.common.none");
								Component guild = summary.guildName() != null ? Component.literal(summary.guildName()) : Component.translatable("skymelloo.command.common.none");
								String classSuffix = summary.selectedClass() != null ? " (" + summary.selectedClass() + ")" : "";
								String avgSkillText = String.format("%.1f", summary.averageSkillLevel());
								if (announce) {
									// Previously trimmed down to just the "most important" handful of fields
									// specifically to avoid Minecraft's 256-char /pc command limit - now that
									// sendDungeonMessage auto-splits long party messages into multiple chunks,
									// there's no need to hold back the rest of what the LOCAL view already
									// shows.
									Component textComponent = Component.translatable("skymelloo.command.getdata.all.party_summary",
											name, summary.skyblockLevel(), rank, guild, summary.catacombsLevel(), classSuffix, mpValue, avgSkillText,
											formatAmount(summary.purse()), formatAmount(summary.bank()), formatAmount(summary.netWorth()),
											summary.fairySouls(), summary.petCount(), summary.minionSlots(), summary.dungeonCompletions(),
											debugCacheAgeSuffix(summary.dataFetchedAt()));
									DungeonRunTracker.sendDungeonMessage(client, textComponent.getString(), "PARTY");
									return;
								}
								String profileSuffix = profile != null ? " §7(" + profile + ")" : "";
								client.player.sendSystemMessage(ChatUtil.prefixed(
										Component.translatable("skymelloo.command.getdata.all.header", name, profileSuffix)
								));
								client.player.sendSystemMessage(ChatUtil.prefixed(
										Component.translatable("skymelloo.command.getdata.all.line1", summary.skyblockLevel(), rank, guild)
								));
								client.player.sendSystemMessage(ChatUtil.prefixed(
										Component.translatable("skymelloo.command.getdata.all.line2", formatAmount(summary.purse()), formatAmount(summary.bank()), formatAmount(summary.netWorth()))
								));
								client.player.sendSystemMessage(ChatUtil.prefixed(
										Component.translatable("skymelloo.command.getdata.all.line3", mpValue, summary.catacombsLevel(), classSuffix, avgSkillText)
								));
								client.player.sendSystemMessage(ChatUtil.prefixed(
										Component.translatable("skymelloo.command.getdata.all.line4", summary.fairySouls(), summary.petCount(), summary.minionSlots())
								));
								client.player.sendSystemMessage(ChatUtil.prefixed(
										Component.translatable("skymelloo.command.getdata.all.line5", summary.dungeonCompletions(), summary.bestiaryKills())
								));
								String debugSuffix = debugCacheAgeSuffix(summary.dataFetchedAt());
								if (!debugSuffix.isEmpty()) {
									client.player.sendSystemMessage(ChatUtil.prefixed(debugSuffix.trim()));
								}
							} finally {
								onDone.run();
							}
						})
				)
		)).exceptionally(error -> {
			Minecraft.getInstance().execute(() -> {
				Minecraft client = Minecraft.getInstance();
				if (client.player != null) {
					client.player.sendSystemMessage(ChatUtil.prefixed(ChatUtil.errorMessage(name, error)));
				}
				onDone.run();
			});
			return null;
		});
	}

	private static final int ANNOUNCE_STAGGER_TICKS = 20; // 1 second - avoids tripping Hypixel's own chat rate limit when /pc-announcing a whole party back to back

	private static void announcePartyAllStats(FabricClientCommandSource source, boolean announce) {
		java.util.List<String> names = resolvePartyMemberNames(false);
		if (names == null) {
			source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.common.no_party")));
			return;
		}
		if (names.isEmpty()) {
			source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.common.no_resolved_members")));
			return;
		}
		// The command-typing feedback always stays local (only you see it, same convention as every
		// other command reply) - "announce" additionally posts a heads-up to the party itself, since
		// everyone's about to see a string of stat messages land one by one.
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.party.loading_overview", names.size())));
		if (announce) {
			DungeonRunTracker.sendDungeonMessage(Minecraft.getInstance(),
					Component.translatable("skymelloo.command.party.loading_overview_announce", names.size()).getString(), "PARTY");
		}
		announcePartyAllStatsSequentially(names.iterator(), announce);
	}

	private static void announcePartyAllStatsSequentially(java.util.Iterator<String> remaining, boolean announce) {
		if (!remaining.hasNext()) {
			return;
		}
		String name = remaining.next();
		Runnable next = () -> announcePartyAllStatsSequentially(remaining, announce);
		// Announced results are staggered a full second apart rather than firing as fast as each
		// API response lands - back-to-back /pc messages for a whole party risk Hypixel's own chat
		// rate limit silently swallowing some of them.
		Runnable onDone = announce ? () -> TickDelay.schedule(ANNOUNCE_STAGGER_TICKS, next) : next;
		announceAllStats(name, null, onDone, announce);
	}

	/** Resolves the current party's members to usernames and checks their MP one at a time (not all at once). */
	private static void announcePartyMagicalPower(FabricClientCommandSource source, boolean announce) {
		java.util.List<String> names = resolvePartyMemberNames(false);
		if (names == null) {
			source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.common.no_party")));
			return;
		}
		if (names.isEmpty()) {
			source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.common.no_resolved_members")));
			return;
		}
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.party.checking_mp", names.size())));
		announcePartyMagicalPowerSequentially(names.iterator(), announce);
	}

	/**
	 * Called from {@link PartyJoinWatcher} right after detecting that YOU joined an existing
	 * Dungeon Finder party (not one you created) - checks everyone already in it, silently doing
	 * nothing if there's no party data yet (e.g. HypixelModAPI hasn't responded, or you made the
	 * party yourself and there's no one else in it).
	 */
	public static void checkPartyMagicalPowerAuto() {
		java.util.List<String> names = resolvePartyMemberNames(true);
		if (names == null || names.isEmpty()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			client.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.command.party.joined_checking_mp", names.size())));
		}
		announcePartyMagicalPowerSequentially(names.iterator(), false);
	}

	/** @return null if there's no party at all, otherwise the resolved (possibly empty) member name list. */
	/**
	 * Prefers {@link PartyHudManager}'s already-resolved, continuously-cached usernames over a fresh
	 * tab-list lookup - a real report showed this dropping a genuine party member entirely (loaded/
	 * announced "1 party member" when 2 were actually in the party) because that member simply wasn't
	 * in tab-list/render range at the EXACT moment the command ran, and the old code silently skipped
	 * anyone {@code getPlayerInfo} returned null for. PartyHudManager resolves names once (via tab
	 * list, falling back to a Mojang lookup) and keeps using that cached name afterward regardless of
	 * momentary tab-list gaps, so it still has the real name even when a fresh lookup here wouldn't.
	 */
	private static java.util.List<String> resolvePartyMemberNames(boolean excludeSelf) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.getConnection() == null) {
			return null;
		}
		java.util.Set<java.util.UUID> memberIds = PartyTracker.getMembers();
		if (memberIds.isEmpty()) {
			return null;
		}
		java.util.Map<java.util.UUID, PartyHudManager.MemberInfo> resolved = PartyHudManager.getMembers();
		java.util.List<String> names = new java.util.ArrayList<>();
		for (java.util.UUID uuid : memberIds) {
			if (excludeSelf && uuid.equals(client.player.getUUID())) {
				continue;
			}
			PartyHudManager.MemberInfo info = resolved.get(uuid);
			String username = info != null ? info.username() : null;
			if (username == null) {
				// PartyHudManager hasn't caught up yet (e.g. command run right after joining) - fall
				// back to a direct tab-list lookup rather than dropping this member entirely.
				var tabInfo = client.getConnection().getPlayerInfo(uuid);
				username = tabInfo != null ? tabInfo.getProfile().name() : null;
			}
			if (username == null || username.equals(uuid.toString().substring(0, 8))) {
				// Still just the UUID-fragment placeholder, not a real name - log why this member is
				// missing instead of silently dropping them with no explanation.
				DebugLog.log(DebugLog.Category.PARTY, "getdata party: couldn't resolve a username for " + uuid + " yet, skipping.");
				continue;
			}
			names.add(username);
		}
		return names;
	}

	private static void announcePartyMagicalPowerSequentially(java.util.Iterator<String> remaining, boolean announce) {
		if (!remaining.hasNext()) {
			return;
		}
		String name = remaining.next();
		Runnable next = () -> announcePartyMagicalPowerSequentially(remaining, announce);
		Runnable onDone = announce ? () -> TickDelay.schedule(ANNOUNCE_STAGGER_TICKS, next) : next;
		ModAuthManager.getIdentity(Minecraft.getInstance()).thenCompose(identity -> SkyMellooApiClient.fetchMagicalPower(name, identity)).whenComplete((result, error) ->
				Minecraft.getInstance().execute(() -> {
					Minecraft client = Minecraft.getInstance();
					if (client.player != null) {
						if (error != null) {
							client.player.sendSystemMessage(ChatUtil.prefixed(ChatUtil.errorMessage(name, error)));
						} else if (result.magicalPower() < 0) {
							client.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.command.mp.no_data", name)));
						} else {
							String text = Component.translatable("skymelloo.command.mp.result_colon", name, result.magicalPower()).getString()
									+ (result.selectedPower() != null ? Component.translatable("skymelloo.command.mp.active_suffix", result.selectedPower()).getString() : "");
							if (announce) {
								DungeonRunTracker.sendDungeonMessage(client, text, "PARTY");
							} else {
								client.player.sendSystemMessage(ChatUtil.prefixed(text));
							}
						}
					}
					// Only move to the next member once this one's result has actually come back -
					// keeps them appearing one-by-one in chat instead of firing all requests at once.
					onDone.run();
				})
		);
	}

	private static void setMobHighlightEnabled(boolean enabled, Consumer<Component> feedback) {
		SkyMellooConfig.HANDLER.instance().dungeonRoomMobHighlightEnabled = enabled;
		SkyMellooConfig.HANDLER.save();
		if (feedback != null) {
			feedback.accept(ChatUtil.prefixed(Component.translatable("skymelloo.command.mob_highlighting.toggled",
					enabled ? Component.translatable("skymelloo.command.common.state_on") : Component.translatable("skymelloo.command.common.state_off"))));
		}
	}
}
