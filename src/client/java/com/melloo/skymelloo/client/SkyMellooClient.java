package com.melloo.skymelloo.client;

import com.melloo.skymelloo.client.api.ModAuthManager;
import com.melloo.skymelloo.client.api.SkyMellooApiClient;
import com.melloo.skymelloo.client.block.BlockHighlightRenderer;
import com.melloo.skymelloo.client.combat.PlayerKillTracker;
import com.melloo.skymelloo.client.config.SkyMellooConfig;
import com.melloo.skymelloo.client.cosmetics.CosmeticsRenderer;
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
import com.melloo.skymelloo.client.social.WhitelistStatusHud;
import com.melloo.skymelloo.client.fishing.FishingScoreHud;
import com.melloo.skymelloo.client.gui.HudLayoutEditorScreen;
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

	private static KeyMapping toggleHighlightKey;
	private static KeyMapping openConfigKey;
	private static KeyMapping hudEditorKey;
	private static KeyMapping socialMenuKey;
	private static KeyMapping mainMenuKey;

	/** So the settings screen itself can offer a "rebind" row without going out to vanilla's separate Controls screen. */
	public static KeyMapping getOpenConfigKey() {
		return openConfigKey;
	}

	@Override
	public void onInitializeClient() {
		SkyMellooConfig.HANDLER.load();
		PartyTracker.init();
		com.melloo.skymelloo.client.social.HypixelLocationTracker.init();
		PartyJoinWatcher.init();
		DungeonRunTracker.init();
		com.melloo.skymelloo.client.social.ActionBarTracker.init();
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
			// Whitelist/permissions/cloud-sync are account-level state on sky.melloo.me, not tied to
			// a specific TCP connection - deliberately NOT reset here anymore. This event also fires
			// on Hypixel's own internal server-hops (dungeon floor entry, "/server X"), which used to
			// make the status HUD flash back to "connecting" and re-run every check on every single
			// hop for no reason (each already has its own periodic 30s re-check in the background for
			// staying up to date). Party membership IS connection-tied (comes from per-connection
			// HypixelModAPI packets), so that one still resets normally.
			PartyHudManager.reset();
		});
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "connection_status"), WhitelistStatusHud.INSTANCE);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "fishing_score"), FishingScoreHud.INSTANCE);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "party"), PartyHud.INSTANCE);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "party_mp_bar"), com.melloo.skymelloo.client.party.PartyMpBarHud.INSTANCE);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "dungeon_score"), DungeonScoreHud.INSTANCE);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "dungeon_debug"), DungeonDebugHud.INSTANCE);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "health_mana_bars"), com.melloo.skymelloo.client.gui.HealthManaBarsHud.INSTANCE);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "player_info"), com.melloo.skymelloo.client.gui.PlayerInfoHud.INSTANCE);

		// Unbound by default (no reasonable universal default across keyboard layouts) -
		// bind it yourself under Controls > Key Binds > SkyMelloo, or toggle Mob Highlighting from
		// the settings screen (key H by default) instead. Repurposed (2026-07-27) to toggle the
		// dungeon current-room mob highlight, since that's the only mob highlighting left at all.
		toggleHighlightKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.skymelloo.toggle_highlight",
				InputConstants.Type.KEYSYM,
				InputConstants.UNKNOWN.getValue(),
				CATEGORY
		));

		// Opens the SkyMelloo settings screen directly, no ModMenu/commands needed.
		// Defaults to H (free in vanilla, unaffected by keyboard layout). Rebindable as usual.
		openConfigKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.skymelloo.open_config",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_H,
				CATEGORY
		));

		// Opens the HUD layout editor (drag SkyMelloo's own HUD elements around), like Lunar
		// Client's own HUD editor. Defaults to J (free in vanilla).
		hudEditorKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.skymelloo.hud_editor",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_J,
				CATEGORY
		));

		// Opens the Social menu (SkyMelloo friends + party, see SocialMenuScreen). Defaults to G
		// (free in vanilla).
		socialMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.skymelloo.social_menu",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_G,
				CATEGORY
		));

		// Opens the main SkyMelloo Menu item's screen (Credits/Spells/Cosmetics/Report a Bug nav row -
		// see SkyMellooMenuScreen/SkyMellooMenuItemManager) - previously only reachable by right-clicking
		// the fake hotbar item, no keybind at all (2026-07-29, "kleiner fix hotkeys für die anderen
		// menus noch adden momentan nur h und normales menu"). Defaults to K (free in vanilla).
		mainMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.skymelloo.main_menu",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_K,
				CATEGORY
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// Runs regardless of sky.melloo.me whitelist status - this is about the actual
			// Minecraft server connection, unrelated to any of our own gated features below.
			ConnectionQualityMonitor.tick(client);
			com.melloo.skymelloo.client.util.SkyblockDetector.tick(client);
			com.melloo.skymelloo.client.util.AfkDetector.tick(client);
			SkyMellooPingMonitor.tick(client);
			com.melloo.skymelloo.client.util.ServerPingMonitor.tick(client);
			com.melloo.skymelloo.client.gui.FpsMonitor.tick(client);
			com.melloo.skymelloo.client.util.AutoReconnect.tick(client);
			PartyHudManager.tick(client);
			DungeonTabList.tick(client);
			DungeonRunTracker.tick(client);
			DungeonRoomTracker.tick(client);

			while (hudEditorKey.consumeClick()) {
				if (client.screen == null) {
					client.setScreen(new HudLayoutEditorScreen());
				}
			}
			while (socialMenuKey.consumeClick()) {
				if (client.screen == null) {
					client.setScreen(new com.melloo.skymelloo.client.gui.SocialMenuScreen());
				}
			}
			while (mainMenuKey.consumeClick()) {
				com.melloo.skymelloo.client.gui.SkyMellooMenuItemManager.openMenu(client);
			}

			WhitelistManager.checkOnce(client);
			WhitelistManager.tickPeriodicRecheck(client);
			com.melloo.skymelloo.client.social.ModVersionManager.checkOnce(client);
			// Used to hard-stop the ENTIRE mod here for an unverified/outdated build, same as the old
			// whitelist gate - removed 2026-07-28 ("generell nix mehr blocken über permissions das
			// komplett ausbauen"). ModVersionManager now only ever sends an informational chat warning
			// (see its own checkOnce), never disables anything.
			PermissionsManager.fetchIfNeeded(client);
			PermissionsManager.tickPeriodicRecheck(client);
			CloudSyncManager.pullIfNeeded(client);
			while (toggleHighlightKey.consumeClick()) {
				boolean showFeedback = SkyMellooConfig.HANDLER.instance().debugMessagesEnabled && client.player != null;
				setHighlightEnabled(!SkyMellooConfig.HANDLER.instance().dungeonRoomMobHighlightEnabled,
						showFeedback ? client.player::sendSystemMessage : null);
			}
			while (openConfigKey.consumeClick()) {
				if (client.screen == null) {
					com.melloo.skymelloo.client.gui.SkyMellooSettingsScreen.open(com.melloo.skymelloo.client.gui.SkyMellooSettingsScreen.Tab.GENERAL);
				}
			}
			FishingHelper.tick(client);
			FishingMinigameManager.tick(client);
			// Not gated on Highlighting anymore - the party HUD needs this too now. PartyTracker itself only
			// actually sends a request once on join and then on party-related chat lines, not every
			// tick, so this is cheap regardless.
			PartyTracker.tick();
			ResourcePackStatus.tick(client);
			PartyJoinWatcher.tick(client);
			BlockHighlightRenderer.tick(client);
			CosmeticsRenderer.tick(client);
			MagicMissileManager.tick(client);
			com.melloo.skymelloo.client.gui.SkyMellooMenuItemManager.tick(client);
			com.melloo.skymelloo.client.combat.DeathRecapManager.tick(client);
			ModPresenceManager.tick(client);
			com.melloo.skymelloo.client.social.DungeonSyncManager.sampleTick(client);
			com.melloo.skymelloo.client.social.BossRoomScanner.tick(client);
			com.melloo.skymelloo.client.social.FriendsManager.tick(client);
			com.melloo.skymelloo.client.social.RelayChatManager.tick(client);
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
								// Bare "/sm sync" == "/sm sync party" now - friend-list sync was removed
								// entirely (2026-07-27) along with Friend highlighting, the only thing it
								// ever fed (see FriendListSync.java's deletion). Party sync is the only
								// thing left to sync.
								.executes(ctx -> {
									PartyTracker.requestRefreshNow();
									ctx.getSource().sendFeedback(ChatUtil.prefixed("Party-Sync angefragt..."));
									return 1;
								})
								.then(ClientCommands.literal("party").executes(ctx -> {
									PartyTracker.requestRefreshNow();
									ctx.getSource().sendFeedback(ChatUtil.prefixed("Party-Sync angefragt..."));
									return 1;
								})))
						.then(com.melloo.skymelloo.client.social.FriendsManager.buildFriendCommand())
						.then(com.melloo.skymelloo.client.social.BlockedUsersManager.buildBlockCommand())
						.then(com.melloo.skymelloo.client.social.BlockedUsersManager.buildUnblockCommand())
						.then(com.melloo.skymelloo.client.social.RelayChatManager.buildChatCommand())
						.then(buildGetDataCommand())
						.then(com.melloo.skymelloo.client.social.PartyGamesManager.buildRollCommand())
						.then(com.melloo.skymelloo.client.social.PartyGamesManager.buildPollCommand())
						.then(ClientCommands.literal("partyjoin")
								.executes(ctx -> {
									ctx.getSource().sendFeedback(ChatUtil.prefixed("§cBenutzung: §f/sm partyjoin test <name>"));
									return 1;
								})
								.then(ClientCommands.literal("test")
										.executes(ctx -> {
											ctx.getSource().sendFeedback(ChatUtil.prefixed("§cBenutzung: §f/sm partyjoin test <name>"));
											return 1;
										})
										.then(ClientCommands.argument("name", StringArgumentType.word())
												.suggests(SkyMellooClient::suggestOnlinePlayers)
												.executes(ctx -> {
													PartyJoinWatcher.lookupAndAnnounce(StringArgumentType.getString(ctx, "name"));
													return 1;
												}))))
						.then(ClientCommands.literal("kills").executes(ctx -> {
							ctx.getSource().sendFeedback(ChatUtil.prefixed(
									"Spells Cast: §e" + SkyMellooConfig.HANDLER.instance().totalSpellsCast + "§r  ·  §dKills: §e" + SkyMellooConfig.HANDLER.instance().totalPlayersKilled + "§r  ·  §dSpell Essence: §e" + SkyMellooConfig.HANDLER.instance().totalSpellEssenceCollected
							));
							return 1;
						}))
						.then(ClientCommands.literal("session").executes(ctx -> {
							var stats = com.melloo.skymelloo.client.social.DungeonRunTracker.getSessionStats();
							if (stats.runsCompleted() == 0) {
								ctx.getSource().sendFeedback(ChatUtil.prefixed("§7Noch keine abgeschlossenen Runs diese Session."));
								return 1;
							}
							int hours = stats.totalSeconds() / 3600;
							int minutes = (stats.totalSeconds() % 3600) / 60;
							String timeText = hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
							ctx.getSource().sendFeedback(ChatUtil.prefixed("§6=== Session (Dungeons) ==="));
							ctx.getSource().sendFeedback(ChatUtil.prefixed("§dRuns: §f" + stats.runsCompleted()
									+ "§7  ·  §dS+: §f" + stats.splusRuns()
									+ "§7  ·  §dØ Score: §f" + String.format("%.0f", stats.averageScore())));
							ctx.getSource().sendFeedback(ChatUtil.prefixed("§dDeaths: §f" + stats.totalDeaths()
									+ "§7  ·  §dZeit in Dungeons: §f" + timeText));
							return 1;
						}))
						// "/sm debug hm-bar" (renamed from "mana", 2026-07-27 - "nenne mana zu hm-bar um
						// health mana bar") - dumps the FULL pipeline to chat: the raw actionbar segments,
						// every "cur/max" fraction parsed out of them (labeled by position, health=0/mana=1
						// per ActionBarTracker's own positional matching), and then the actual on-screen
						// bar fill state computed via the exact same HealthManaBarsHud.compute*BarState()
						// methods the real renderer uses - so this can never drift from what's really drawn.
						.then(ClientCommands.literal("debug")
								.executes(ctx -> {
									ctx.getSource().sendFeedback(ChatUtil.prefixed("§cBenutzung: §f/sm debug hm-bar§7, §f/sm debug bossroom§7, oder §f/sm debug score"));
									return 1;
								})
								.then(ClientCommands.literal("hm-bar").executes(ctx -> {
									long lastPacketMillis = com.melloo.skymelloo.client.social.ActionBarTracker.getLastPacketMillis();
									ctx.getSource().sendFeedback(ChatUtil.prefixed("§6=== HM-Bar Debug (Health/Mana Bar) ==="));
									if (lastPacketMillis == 0) {
										ctx.getSource().sendFeedback(ChatUtil.prefixed("§cno actionbar packet seen yet"));
										return 1;
									}
									long sincePacket = System.currentTimeMillis() - lastPacketMillis;
									var segments = com.melloo.skymelloo.client.social.ActionBarTracker.getLastSegments();
									ctx.getSource().sendFeedback(ChatUtil.prefixed("§7--- Raw actionbar (last packet " + sincePacket + "ms ago, " + segments.size() + " segment(s)) ---"));
									for (com.melloo.skymelloo.client.social.ActionBarTracker.Segment seg : segments) {
										ctx.getSource().sendFeedback(ChatUtil.prefixed("§7[" + seg.colorHex() + "] \"" + seg.text() + "\""));
									}

									StringBuilder flattened = new StringBuilder();
									for (com.melloo.skymelloo.client.social.ActionBarTracker.Segment seg : segments) {
										flattened.append(seg.text());
									}
									var fractionMatcher = java.util.regex.Pattern.compile("([\\d,]+)\\s*/\\s*([\\d,]+)").matcher(flattened);
									ctx.getSource().sendFeedback(ChatUtil.prefixed("§7--- Parsed fractions (0=health, 1=mana, positional) ---"));
									int fractionIndex = 0;
									while (fractionMatcher.find()) {
										String label = fractionIndex == 0 ? "§ahealth" : fractionIndex == 1 ? "§bmana" : "§7(unused)";
										ctx.getSource().sendFeedback(ChatUtil.prefixed("§7[" + fractionIndex + "] §f" + fractionMatcher.group() + " §7- " + label));
										fractionIndex++;
									}
									ctx.getSource().sendFeedback(ChatUtil.prefixed("§dHealth: §fcur=" + com.melloo.skymelloo.client.social.ActionBarTracker.getCurrentHealth()
											+ " max=" + com.melloo.skymelloo.client.social.ActionBarTracker.getMaxHealth()
											+ "§7  ·  §dMana: §fcur=" + com.melloo.skymelloo.client.social.ActionBarTracker.getCurrentMana()
											+ " max=" + com.melloo.skymelloo.client.social.ActionBarTracker.getMaxMana()));

									Minecraft mc = Minecraft.getInstance();
									if (mc.player == null) {
										return 1;
									}
									var healthState = com.melloo.skymelloo.client.gui.HealthManaBarsHud.computeHealthBarState(mc.player);
									var manaState = com.melloo.skymelloo.client.gui.HealthManaBarsHud.computeManaBarState();
									var hud = com.melloo.skymelloo.client.gui.HealthManaBarsHud.INSTANCE;
									int barWidth = com.melloo.skymelloo.client.gui.HealthManaBarsHud.BAR_WIDTH;
									ctx.getSource().sendFeedback(ChatUtil.prefixed("§7--- Displayed (bar is " + barWidth + "x" + com.melloo.skymelloo.client.gui.HealthManaBarsHud.BAR_HEIGHT + "px) ---"));
									ctx.getSource().sendFeedback(ChatUtil.prefixed("§dHealth bar: §f" + healthState.health() + "/" + healthState.maxHealth()
											+ " §7(source: " + (healthState.fromActionBar() ? "§aactionbar" : "§evanilla fallback") + "§7)"
											+ "  §7- §agreen " + healthState.healthPx() + "/" + barWidth + "px §7(" + String.format("%.1f%%", healthState.healthFraction() * 100) + ")"
											+ (healthState.absorptionPx() > 0 ? "  §7+ §6gold " + healthState.absorptionPx() + "/" + barWidth + "px §7(absorption " + String.format("%.1f", healthState.absorption()) + ")" : "")));
									ctx.getSource().sendFeedback(ChatUtil.prefixed("§7white trail (\"just lost\"): §f"
											+ (hud.isHealthTrailInitialized() ? Math.round(barWidth * hud.getDisplayedHealthFraction()) + "/" + barWidth + "px" : "not initialized yet (HUD hasn't rendered this session)")
											+ "  §7·§c red gain-highlight (\"just gained, catching up\"): §f"
											+ (hud.isHealthTrailInitialized() ? Math.round(barWidth * hud.getRisingHealthFraction()) + "/" + healthState.healthPx() + "px" : "not initialized yet")));
									if (manaState.fraction() != null) {
										ctx.getSource().sendFeedback(ChatUtil.prefixed("§dMana bar: §f" + manaState.current() + "/" + manaState.max()
												+ "  §7- §bfilled " + manaState.manaPx() + "/" + barWidth + "px §7(" + String.format("%.1f%%", manaState.fraction() * 100) + ")"));
									} else {
										ctx.getSource().sendFeedback(ChatUtil.prefixed("§dMana bar: §7no reading yet this session"));
									}
									ctx.getSource().sendFeedback(ChatUtil.prefixed("§7white trail (\"just lost\"): §f"
											+ (hud.isManaTrailInitialized() ? Math.round(barWidth * hud.getDisplayedManaFraction()) + "/" + barWidth + "px" : "not initialized yet (HUD hasn't rendered this session)")
											+ "  §7·§c red gain-highlight (\"just gained, catching up\"): §f"
											+ (hud.isManaTrailInitialized() ? Math.round(barWidth * hud.getRisingManaFraction()) + "/" + manaState.manaPx() + "px" : "not initialized yet")));

									SkyMellooConfig config = SkyMellooConfig.HANDLER.instance();
									ctx.getSource().sendFeedback(ChatUtil.prefixed("§7Enabled: healthManaBarsEnabled=" + config.healthManaBarsEnabled
											+ " healthBarEnabled=" + config.healthBarEnabled + " manaBarEnabled=" + config.manaBarEnabled
											+ " sideBySide=" + config.healthManaBarsSideBySide));
									return 1;
								}))
								// "/sm debug bossroom" - diagnostic for the boss-room 3D scanner prototype
								// (2026-07-27) after a real report that it "doesn't work" with no error in the
								// log - this exposes whether it's even active and how many blocks it's found,
								// instead of it working (or not) completely silently.
								.then(ClientCommands.literal("bossroom").executes(ctx -> {
									ctx.getSource().sendFeedback(ChatUtil.prefixed("§6=== Boss Room Scanner Debug ==="));
									ctx.getSource().sendFeedback(ChatUtil.prefixed("§dbossRoomEntered: §f" + com.melloo.skymelloo.client.social.DungeonRunTracker.isBossRoomEntered()
											+ "§7  ·  §dbossRoomCleared: §f" + com.melloo.skymelloo.client.social.DungeonRunTracker.isBossRoomCleared()));
									ctx.getSource().sendFeedback(ChatUtil.prefixed("§dScanner active: §f" + com.melloo.skymelloo.client.social.BossRoomScanner.isActive()));
									if (com.melloo.skymelloo.client.social.BossRoomScanner.isActive()) {
										ctx.getSource().sendFeedback(ChatUtil.prefixed("§dOrigin: §f" + com.melloo.skymelloo.client.social.BossRoomScanner.getOrigin()
												+ "§7  ·  §dscanId: §f" + com.melloo.skymelloo.client.social.BossRoomScanner.getScanId()));
										ctx.getSource().sendFeedback(ChatUtil.prefixed("§dPositions checked so far: §f" + com.melloo.skymelloo.client.social.BossRoomScanner.getSeenCount()
												+ "§7  ·  §dQueued, not yet sent: §f" + com.melloo.skymelloo.client.social.BossRoomScanner.getPendingCount()));
									} else {
										ctx.getSource().sendFeedback(ChatUtil.prefixed("§7Not scanning - walk into an active (not yet cleared) boss room first."));
									}
									// "der sendet halt nix ... adde im debug sent dazu ... successful sent ned
									// nur sent und gegenteil von successful" (2026-07-27) - "queued: 0" above only
									// proves the data was drained LOCALLY, never that the HTTP report carrying
									// it actually reached the server. This is what answers "did it arrive" -
									// counts only presence reports that genuinely had ≥1 boss-room block in them.
									long attempts = com.melloo.skymelloo.client.social.ModPresenceManager.getBossRoomSendAttempts();
									long successes = com.melloo.skymelloo.client.social.ModPresenceManager.getBossRoomSendSuccesses();
									long failures = com.melloo.skymelloo.client.social.ModPresenceManager.getBossRoomSendFailures();
									ctx.getSource().sendFeedback(ChatUtil.prefixed("§dReports with boss-room data sent: §f" + attempts
											+ " §7- §asucceeded: §f" + successes + "§7  ·  §cfailed: §f" + failures));
									if (failures > 0) {
										ctx.getSource().sendFeedback(ChatUtil.prefixed("§cLast send error: §f" + com.melloo.skymelloo.client.social.ModPresenceManager.getLastBossRoomSendError()));
									}
									return 1;
								}))
								// "/sm debug score" - real bugfix (2026-07-27): the
								// live/recorded score stayed frozen at 120 for an entire run regardless of
								// real progress. Dumps every input the formula uses, PLUS a scan of every
								// reconstructed tab-list line (not just the fixed index 43 the formula
								// actually reads) so a live report shows exactly whether that index is wrong,
								// empty, or Skyblocker's own score just isn't being used - instead of guessing.
								.then(ClientCommands.literal("score").executes(ctx -> {
									var info = com.melloo.skymelloo.client.social.DungeonRunTracker.debugScoreInfo();
									ctx.getSource().sendFeedback(ChatUtil.prefixed("§6=== Score Debug ==="));
									ctx.getSource().sendFeedback(ChatUtil.prefixed("§dSkyblocker available: §f" + info.skyblockerAvailable()
											+ "§7  ·  §dSkyblocker score: §f" + info.skyblockerScore()));
									ctx.getSource().sendFeedback(ChatUtil.prefixed("§dtotalRooms: §f" + info.totalRooms()
											+ "§7  ·  §dcompletedRooms: §f" + info.completedRooms()
											+ "§7  ·  §dextraCompletedRooms: §f" + info.extraCompletedRooms()
											+ "§7  ·  §dclearedPercentUsed: §f" + info.clearedPercentUsed()));
									ctx.getSource().sendFeedback(ChatUtil.prefixed("§dOur estimate: §fSkill=" + info.skill() + " Explore=" + info.explore()
											+ " Speed=" + info.speed() + " Bonus=" + info.bonus() + " §7-> §fTotal=" + info.total()));
									java.util.List<String> lines = com.melloo.skymelloo.client.social.DungeonTabList.getAllLines();
									ctx.getSource().sendFeedback(ChatUtil.prefixed("§dTab list: §f" + lines.size() + " lines §7- line[43]: §f\"" + (43 < lines.size() ? lines.get(43) : "(out of range)") + "\""));
									boolean foundElsewhere = false;
									for (int i = 0; i < lines.size(); i++) {
										if (lines.get(i) != null && lines.get(i).contains("Completed Rooms")) {
											ctx.getSource().sendFeedback(ChatUtil.prefixed("§7  match at line[" + i + "]: \"" + lines.get(i) + "\""));
											foundElsewhere = true;
										}
									}
									if (!foundElsewhere) {
										ctx.getSource().sendFeedback(ChatUtil.prefixed("§c\"Completed Rooms\" not found on ANY tab-list line - our reconstructed tab list may be missing Hypixel's dungeon-stat entries entirely."));
									}
									return 1;
								})))
						// "/sm version" and "/sm info" merged into one command (2026-07-29, "/sm info bzw
						// version zusammen machen") - always fires a FRESH check against the server (see
						// ModVersionManager#checkNow) rather than just showing whatever the one join-time
						// check happened to cache, so this always reflects the real latest published
						// version right now, cooldown-limited client-side against accidental spam. Dropped
						// the buildKind/"official"/"unofficial" trust framing entirely (2026-07-28, see the
						// extended back-and-forth on why a self-reported build check can't actually prove
						// anything to anyone but yourself) - plain informational, version numbers and a
						// reminder of where the real thing comes from, nothing more.
						.then(ClientCommands.literal("version").executes(ctx -> {
							String version = com.melloo.skymelloo.client.social.ModVersionManager.getLocalVersion();
							String publicVersion = com.melloo.skymelloo.client.social.ModVersionManager.getPublicVersion();
							String jarHash = com.melloo.skymelloo.client.social.ModVersionManager.getLocalJarHash();
							ctx.getSource().sendFeedback(ChatUtil.prefixed("§6=== SkyMelloo Version ==="));
							ctx.getSource().sendFeedback(ChatUtil.prefixed("§dRunning: §fv" + publicVersion + " §7(dev " + version + ")"
									+ "§7  ·  §djarHash: §f" + (jarHash != null ? jarHash : "unknown (dev/exploded classpath)")));
							ctx.getSource().sendFeedback(ChatUtil.prefixed("§7Checking for the latest version…"));
							com.melloo.skymelloo.client.social.ModVersionManager.checkNow(
									result -> {
										Minecraft c = Minecraft.getInstance();
										if (c.player == null) {
											return;
										}
										if (result == null) {
											c.player.sendSystemMessage(ChatUtil.prefixed("§cCouldn't reach sky.melloo.me to check for updates - try again shortly."));
											return;
										}
										if (result.latestVersion() != null) {
											c.player.sendSystemMessage(ChatUtil.prefixed("§dLatest published: §fv" + result.latestVersion()));
										}
										if (result.upToDate()) {
											c.player.sendSystemMessage(ChatUtil.prefixed("§aYou're on the latest version."));
										} else {
											c.player.sendSystemMessage(ChatUtil.prefixed("§eYou're NOT on the latest version - make sure to get it from our official site: §fsky.melloo.me"));
										}
										c.player.sendSystemMessage(legalLink("Always get SkyMelloo from", "https://sky.melloo.me/download"));
									},
									cooldownSeconds -> {
										Minecraft c = Minecraft.getInstance();
										if (c.player == null) {
											return;
										}
										c.player.sendSystemMessage(ChatUtil.prefixed("§7Already checked recently - try again in " + cooldownSeconds + "s."));
										c.player.sendSystemMessage(legalLink("Always get SkyMelloo from", "https://sky.melloo.me/download"));
									}
							);
							return 1;
						}))
						// German data-protection law expects the legal pages to actually be reachable from
						// the mod itself, not just buried in the website's footer (2026-07-27, "legal part
						// zur mod... /sm legal ein der alle links zu den ganzen legal seiten schickt").
						// Moved fully server-side (2026-07-28, "/sm legal in private builds entfernt werden
						// muss weil es nicht durch mich gecovered ist... einen api anruf... der überprüft
						// erst ob das überhaupt unser build ist") - the URLs are no longer hardcoded here at
						// all, and the server refuses to hand them out to a build it can't verify as an
						// official/dev SkyMelloo release, since a modified build genuinely isn't legally
						// covered by the maintainer's own imprint/privacy/terms.
						.then(ClientCommands.literal("legal").executes(ctx -> {
							String jarHash = com.melloo.skymelloo.client.social.ModVersionManager.getLocalJarHash();
							com.melloo.skymelloo.client.api.SkyMellooApiClient.fetchLegalInfo(jarHash).whenComplete((info, error) -> Minecraft.getInstance().execute(() -> {
								if (error != null || info == null) {
									// Addressed partly to whoever actually built this (2026-07-28, "sagt der you
									// forgot to remove this from your private version") - a test/private build is
									// almost always someone's own compile, so it's worth telling them directly that
									// this command still points at the MAINTAINER's own legal pages and probably
									// shouldn't ship as-is in a real fork, not just a generic refusal.
									ctx.getSource().sendFeedback(ChatUtil.prefixed(
											"§cThis isn't an official SkyMelloo version, so legal info isn't shown here."));
									ctx.getSource().sendFeedback(ChatUtil.prefixed(
											"§7If you built this yourself: you forgot to remove/customize \"/sm legal\" - it points to hexedmaya's own legal pages, not yours."));
									return;
								}
								ctx.getSource().sendFeedback(ChatUtil.prefixed("§6=== SkyMelloo Legal ==="));
								ctx.getSource().sendFeedback(legalLink("Imprint", info.imprint()));
								ctx.getSource().sendFeedback(legalLink("Privacy Policy", info.privacy()));
								ctx.getSource().sendFeedback(legalLink("Terms of Service", info.terms()));
							}));
							return 1;
						}))
						// "/sm trust" removed entirely (2026-07-28) - a self-reported build-hash check can
						// never actually prove anything to anyone but yourself (a modified client can just
						// lie about which hash it reports), so it was giving a false sense of security
						// rather than a real one. See "/sm version" above for what's left:
						// version numbers and a reminder of where the real thing comes from, no trust claims.
						.then(ClientCommands.literal("config").executes(ctx -> {
							// Always open, regardless of current screen - the chat/command screen
							// is technically still "open" when this executes, so a null-check here
							// would silently do nothing.
							com.melloo.skymelloo.client.gui.SkyMellooSettingsScreen.open(com.melloo.skymelloo.client.gui.SkyMellooSettingsScreen.Tab.GENERAL);
							return 1;
						}))
						.then(ClientCommands.literal("verify")
								.executes(ctx -> {
									ctx.getSource().sendFeedback(ChatUtil.prefixed("§cBenutzung: §f/skymelloo verify <code>"));
									return 1;
								})
								.then(ClientCommands.argument("code", StringArgumentType.word()).executes(ctx -> {
									String code = StringArgumentType.getString(ctx, "code");
									Minecraft client = Minecraft.getInstance();
									if (client.player == null) {
										return 1;
									}
									ctx.getSource().sendFeedback(ChatUtil.prefixed("Prüfe Code..."));
									ModAuthManager.getIdentity(client).thenCompose(identity -> SkyMellooApiClient.verifyAccount(code, identity))
											.whenComplete((result, error) ->
													Minecraft.getInstance().execute(() -> {
														Minecraft c = Minecraft.getInstance();
														if (c.player == null) {
															return;
														}
														if (error != null) {
															c.player.sendSystemMessage(ChatUtil.prefixed("§cVerbindung fehlgeschlagen: " + ChatUtil.friendlyError(error)));
														} else if (result.ok()) {
															c.player.sendSystemMessage(ChatUtil.prefixed("§aAccount verbunden! Du bist jetzt Admin auf sky.melloo.me."));
														} else {
															c.player.sendSystemMessage(ChatUtil.prefixed("§cVerbindung fehlgeschlagen: " + result.error()));
														}
													})
											);
									return 1;
								})))
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
													c.player.sendSystemMessage(ChatUtil.prefixed("§cFehlgeschlagen: " + ChatUtil.friendlyError(error)));
												} else if (result.ok()) {
													c.player.sendSystemMessage(ChatUtil.prefixed("§aAccount getrennt."));
												} else {
													c.player.sendSystemMessage(ChatUtil.prefixed("§cFehlgeschlagen: " + result.error()));
												}
											})
									);
							return 1;
						}))
						.then(ClientCommands.literal("contact").executes(ctx -> {
							ctx.getSource().sendFeedback(legalLink("Contact", "https://sky.melloo.me/contact"));
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
							ctx.getSource().sendFeedback(ChatUtil.prefixed("§cUnbekannter Befehl. Nutze §f/skymelloo help§c für eine Übersicht."));
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
	private static net.minecraft.network.chat.MutableComponent legalLink(String label, String url) {
		return Component.literal("§d" + label + ": §f§n" + url).withStyle(style -> style
				.withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(java.net.URI.create(url)))
				.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal("Click to open in your browser"))));
	}

	private static void sendHelp(FabricClientCommandSource source) {
		source.sendFeedback(ChatUtil.prefixed("§6=== SkyMelloo Befehle §7(auch als §f/sm§7 verfügbar) ==="));
		source.sendFeedback(ChatUtil.prefixed("§a/skymelloo config §7- Einstellungen öffnen (auch Taste H)"));
		source.sendFeedback(ChatUtil.prefixed("§a/skymelloo verify <code> §7- Account mit sky.melloo.me verbinden"));
		source.sendFeedback(ChatUtil.prefixed("§a/skymelloo unlink §7- Verbundenen Account trennen"));
		source.sendFeedback(ChatUtil.prefixed("§a/skymelloo view <name> §7- Spieler-Stats als Fenster öffnen (Tabs, scrollbar)"));
		source.sendFeedback(ChatUtil.prefixed("§a/skymelloo getdata player <name> <stat> §7- Spieler-Stats abrufen"));
		source.sendFeedback(ChatUtil.prefixed("§a/skymelloo getdata party <stat> §7- Party-Stats abrufen"));
		source.sendFeedback(ChatUtil.prefixed("§a/skymelloo version §7- Mod-Version & Update-Status (live geprüft)"));
		source.sendFeedback(ChatUtil.prefixed("§a/skymelloo contact §7- Kontaktseite (sky.melloo.me/contact)"));
		source.sendFeedback(ChatUtil.prefixed("§a/skymelloo legal §7- Impressum, Datenschutz, AGB (Links)"));

		source.sendFeedback(ChatUtil.prefixed("§6--- Freunde & Party ---"));
		source.sendFeedback(ChatUtil.prefixed("§a/skymelloo sync §7- Party-Sync manuell anstoßen"));
		source.sendFeedback(ChatUtil.prefixed("§a/skymelloo friend <name>§7/§aaccept§7/§adecline§7/§aremove§7/§alist §7- SkyMelloo-Freunde verwalten"));
		source.sendFeedback(ChatUtil.prefixed("§a/skymelloo block <name>§7/§aunblock <name> §7- Party-Mitglied blockieren (Auto-Kick)"));
		source.sendFeedback(ChatUtil.prefixed("§a/skymelloo chat party <msg>§7/§achat <name> <msg> §7- Relay-Chat (an Party oder direkt)"));
		source.sendFeedback(ChatUtil.prefixed("§a/skymelloo roll <amount>§7/§aroll party§7/§aroll <word> <secs> §7- Zufallsauswahl in der Party"));
		source.sendFeedback(ChatUtil.prefixed("§a/skymelloo poll start <Frage;Antwort1;...>§7/§apoll close §7- Party-Umfrage"));

		// Only advertise commands/menu categories tied to a specific feature permission if the
		// account actually has it - matching the same PermissionsManager checks SkyMellooSettingsScreen
		// already uses to hide whole tabs (visibleTabs()). Advertising a locked feature by name here
		// was misleading (e.g. always mentioning "Highlighting" even for accounts without highlight/chestHighlight).
		if (PermissionsManager.has("killTracker")) {
			source.sendFeedback(ChatUtil.prefixed("§6--- Dungeons ---"));
			source.sendFeedback(ChatUtil.prefixed("§a/skymelloo kills §7- Spell-Kills anzeigen"));
			source.sendFeedback(ChatUtil.prefixed("§a/skymelloo session §7- Dungeon-Session-Stats anzeigen (Runs, Ø Score, Deaths, Zeit)"));
		}
		if (PermissionsManager.has("dungeonInfo")) {
			source.sendFeedback(ChatUtil.prefixed("§a/skymelloo partyjoin test <name> §7- Party-Join-Nachricht testen"));
		}

		java.util.List<String> unlockedFeatures = new java.util.ArrayList<>();
		// No standalone "Highlighting" entry (2026-07-27, never write "Highlighting" in anything user-facing) - Mob
		// Highlighting (the "highlight" permission) already surfaces under "Dungeons" below, since that's
		// where it actually lives in the menu now.
		if (PermissionsManager.has("playerHighlight")) {
			unlockedFeatures.add("Party");
		}
		if (PermissionsManager.has("cosmetics")) {
			unlockedFeatures.add("Cosmetics");
		}
		if (PermissionsManager.has("fishingHelper")) {
			unlockedFeatures.add("Fishing");
		}
		if (PermissionsManager.has("dungeonInfo") || PermissionsManager.has("chestHighlight") || PermissionsManager.has("itemHighlight") || PermissionsManager.has("mobHighlight")) {
			unlockedFeatures.add("Dungeons");
		}
		if (!unlockedFeatures.isEmpty()) {
			source.sendFeedback(ChatUtil.prefixed("§7Weitere Einstellungen (" + String.join(", ", unlockedFeatures) + ") laufen über das Menü (Taste H)."));
		} else {
			source.sendFeedback(ChatUtil.prefixed("§7Für deinen Account sind noch keine Extra-Features freigeschaltet - siehe §f/skymelloo contact§7."));
		}
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

		var getdataUsage = "§cBenutzung: §f/sm getdata player <name> <stat>§7 oder §f/sm getdata party <stat>";
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
				source.sendFeedback(ChatUtil.prefixed("Loading overview for §a" + name + "§r..."));
				announceAllStats(name, profile, () -> {
				}, announce);
			}
			case "mp" -> {
				source.sendFeedback(ChatUtil.prefixed("Looking up Magical Power for §a" + name + "§r..."));
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
			String format(SkyMellooApiClient.SummaryResult s) {
				return "Networth §6" + formatAmount(s.netWorth());
			}
		},
		BANK("bank") {
			String format(SkyMellooApiClient.SummaryResult s) {
				return "Bank §6" + formatAmount(s.bank());
			}
		},
		PURSE("purse") {
			String format(SkyMellooApiClient.SummaryResult s) {
				return "Purse §6" + formatAmount(s.purse());
			}
		},
		FAIRYSOULS("fairysouls") {
			String format(SkyMellooApiClient.SummaryResult s) {
				return "§d" + s.fairySouls() + " Fairy Souls";
			}
		},
		GUILD("guild") {
			String format(SkyMellooApiClient.SummaryResult s) {
				if (s.guildName() == null) {
					return "Guild §7None";
				}
				String tag = s.guildTag() != null ? " §7[" + s.guildTag() + "]" : "";
				String members = s.guildMemberCount() > 0 ? " §7(" + s.guildMemberCount() + " members)" : "";
				return "Guild §b" + s.guildName() + tag + members;
			}
		},
		RANK("rank") {
			String format(SkyMellooApiClient.SummaryResult s) {
				return "Rank §b" + (s.rankLabel() != null ? s.rankLabel() : "§7None");
			}
		},
		SKILLS("skills") {
			String format(SkyMellooApiClient.SummaryResult s) {
				return formatLevelMap(s.skillLevels());
			}
		},
		SLAYER("slayer") {
			String format(SkyMellooApiClient.SummaryResult s) {
				return formatLevelMap(s.slayerLevels());
			}
		},
		CLASSES("classes") {
			String format(SkyMellooApiClient.SummaryResult s) {
				return formatLevelMap(s.classLevels());
			}
		},
		MINIONS("minions") {
			String format(SkyMellooApiClient.SummaryResult s) {
				return "§d" + s.minionUniqueCount() + "§r unique minions, §d" + s.minionUpgrades() + "§r upgrades";
			}
		},
		BESTIARY("bestiary") {
			String format(SkyMellooApiClient.SummaryResult s) {
				return "§d" + s.bestiaryKills() + "§r Bestiary kills";
			}
		},
		HIGHESTFLOOR("highestfloor") {
			String format(SkyMellooApiClient.SummaryResult s) {
				return "Highest dungeon floor: §d" + (s.highestFloor() == 0 ? "none" : "F/M" + s.highestFloor());
			}
		},
		FIRSTJOIN("firstjoin") {
			String format(SkyMellooApiClient.SummaryResult s) {
				if (s.firstJoin() <= 0) {
					return "Join date unknown";
				}
				long days = (System.currentTimeMillis() - s.firstJoin()) / 86_400_000L;
				return "On SkyBlock for §d" + days + "§r days";
			}
		},
		PETS("pets") {
			String format(SkyMellooApiClient.SummaryResult s) {
				return "§d" + s.petCount() + "§r pets" + (s.bestPetLabel() != null ? ", best: §b" + s.bestPetLabel() + "§r" : "");
			}
		},
		COLLECTIONS("collections") {
			String format(SkyMellooApiClient.SummaryResult s) {
				return "§d" + s.collectionsStarted() + "§r collections started";
			}
		},
		MINIONSLOTS("minionslots") {
			String format(SkyMellooApiClient.SummaryResult s) {
				return "§d" + s.minionSlots() + "§r minion slots";
			}
		},
		PROFILES("profiles") {
			String format(SkyMellooApiClient.SummaryResult s) {
				return s.profilesLabel();
			}
		},
		DUNGEONRUNS("dungeonruns") {
			String format(SkyMellooApiClient.SummaryResult s) {
				return "§d" + s.dungeonCompletions() + "§r dungeon runs total (all floors)";
			}
		};

		final String commandName;

		ExtraStat(String commandName) {
			this.commandName = commandName;
		}

		abstract String format(SkyMellooApiClient.SummaryResult summary);

		static ExtraStat byCommandName(String commandName) {
			for (ExtraStat stat : values()) {
				if (stat.commandName.equals(commandName)) {
					return stat;
				}
			}
			throw new IllegalArgumentException("Unknown getdata stat: " + commandName);
		}
	}

	private static String formatLevelMap(java.util.Map<String, Integer> levels) {
		if (levels.isEmpty()) {
			return "§7No data";
		}
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (var entry : levels.entrySet()) {
			if (!first) {
				sb.append("§r, ");
			}
			first = false;
			String name = entry.getKey().substring(0, 1).toUpperCase() + entry.getKey().substring(1);
			sb.append(name).append(" §d").append(entry.getValue());
		}
		return sb.toString();
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
		return " §8(data from " + ageSeconds + "s ago, just requested)";
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
					String text = "§a" + name + "§r: " + stat.format(summary) + debugCacheAgeSuffix(summary.dataFetchedAt());
					if (announce) {
						// §-codes above don't survive /pc (Hypixel strips them, see ChatUtil.partyPrefixed)
						// so they're harmlessly stripped for the party delivery, kept as-is for local.
						DungeonRunTracker.sendDungeonMessage(client, text, "PARTY");
						return;
					}
					client.player.sendSystemMessage(ChatUtil.prefixed(text));
				})
		);
	}

	private static void announcePartyExtraStat(ExtraStat stat, FabricClientCommandSource source, boolean announce) {
		java.util.List<String> names = resolvePartyMemberNames(false);
		if (names == null) {
			source.sendFeedback(ChatUtil.prefixed("§cNo party found (or Hypixel Mod API not connected)."));
			return;
		}
		if (names.isEmpty()) {
			source.sendFeedback(ChatUtil.prefixed("§cCouldn't resolve any party members (not in view range/tab list?)."));
			return;
		}
		source.sendFeedback(ChatUtil.prefixed("Checking " + stat.commandName + " for §a" + names.size() + "§r party members, one by one..."));
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
							String text = "§a" + name + "§r: " + stat.format(summary) + debugCacheAgeSuffix(summary.dataFetchedAt());
							if (announce) {
								DungeonRunTracker.sendDungeonMessage(client, text, "PARTY");
							} else {
								client.player.sendSystemMessage(ChatUtil.prefixed(text));
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
						client.player.sendSystemMessage(ChatUtil.prefixed("§cNo Magical Power data found for §f" + name + "§c."));
						return;
					}
					String text = "§a" + name + "§r has §d" + result.magicalPower() + " Magical Power§r"
							+ (result.selectedPower() != null ? " (active: §b" + result.selectedPower() + "§r)" : "");
					if (announce) {
						DungeonRunTracker.sendDungeonMessage(client, name + " has " + result.magicalPower() + " Magical Power"
								+ (result.selectedPower() != null ? " (active: " + result.selectedPower() + ")" : ""), "PARTY");
						return;
					}
					client.player.sendSystemMessage(ChatUtil.prefixed(text));
				})
		);
	}

	/**
	 * Combines the most important stats into one readable overview. Locally this is several English
	 * messages (one per stat group, easier to read - used to be German-language here specifically, but
	 * there's no good reason for it to differ from the "announce" wording below); "announce" mode
	 * instead combines everything into ONE message sent via /pc if in a party, since firing 6 separate
	 * /pc messages per player would spam party chat badly across a whole party.
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
								if (announce) {
									// Previously trimmed down to just the "most important" handful of fields
									// specifically to avoid Minecraft's 256-char /pc command limit - now that
									// sendDungeonMessage auto-splits long party messages into multiple chunks,
									// there's no need to hold back the rest of what the LOCAL view already
									// shows.
									String text = name + ": SB Level " + summary.skyblockLevel()
											+ ", Rank " + (summary.rankLabel() != null ? summary.rankLabel() : "None")
											+ ", Guild " + (summary.guildName() != null ? summary.guildName() : "None")
											+ ", Cata " + summary.catacombsLevel() + (summary.selectedClass() != null ? " (" + summary.selectedClass() + ")" : "")
											+ ", MP " + mpValue
											+ ", Skill Avg " + String.format("%.1f", summary.averageSkillLevel())
											+ ", Purse " + formatAmount(summary.purse())
											+ ", Bank " + formatAmount(summary.bank())
											+ ", Networth " + formatAmount(summary.netWorth())
											+ ", " + summary.fairySouls() + " Fairy Souls"
											+ ", " + summary.petCount() + " Pets"
											+ ", " + summary.minionSlots() + " Minion Slots"
											+ ", " + summary.dungeonCompletions() + " Dungeon Runs"
											+ debugCacheAgeSuffix(summary.dataFetchedAt());
									DungeonRunTracker.sendDungeonMessage(client, text, "PARTY");
									return;
								}
								String mpText = "§d" + mpValue;
								client.player.sendSystemMessage(ChatUtil.prefixed(
										"§6=== §a" + name + "§r" + (profile != null ? " §7(" + profile + ")" : "") + " §6==="
								));
								client.player.sendSystemMessage(ChatUtil.prefixed(
										"SB Level §d" + summary.skyblockLevel()
												+ "§r, Rank §b" + (summary.rankLabel() != null ? summary.rankLabel() : "None")
												+ "§r, Guild §b" + (summary.guildName() != null ? summary.guildName() : "None")
								));
								client.player.sendSystemMessage(ChatUtil.prefixed(
										"Purse §6" + formatAmount(summary.purse())
												+ "§r, Bank §6" + formatAmount(summary.bank())
												+ "§r, Networth §6" + formatAmount(summary.netWorth())
								));
								client.player.sendSystemMessage(ChatUtil.prefixed(
										"MP " + mpText + "§r, Cata §b" + summary.catacombsLevel()
												+ (summary.selectedClass() != null ? " (" + summary.selectedClass() + ")" : "")
												+ "§r, Skill Avg §e" + String.format("%.1f", summary.averageSkillLevel())
								));
								client.player.sendSystemMessage(ChatUtil.prefixed(
										"§d" + summary.fairySouls() + "§r Fairy Souls"
												+ "§r, §d" + summary.petCount() + "§r Pets"
												+ "§r, §d" + summary.minionSlots() + "§r Minion Slots"
								));
								client.player.sendSystemMessage(ChatUtil.prefixed(
										"§d" + summary.dungeonCompletions() + "§r Dungeon Runs"
												+ "§r, §d" + summary.bestiaryKills() + "§r Bestiary Kills"
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
			source.sendFeedback(ChatUtil.prefixed("§cNo party found (or Hypixel Mod API not connected)."));
			return;
		}
		if (names.isEmpty()) {
			source.sendFeedback(ChatUtil.prefixed("§cCouldn't resolve any party members (not in view range/tab list?)."));
			return;
		}
		// The command-typing feedback always stays local (only you see it, same convention as every
		// other command reply) - "announce" additionally posts a heads-up to the party itself, since
		// everyone's about to see a string of stat messages land one by one.
		source.sendFeedback(ChatUtil.prefixed("Loading overview for §a" + names.size() + "§r party members, one by one..."));
		if (announce) {
			DungeonRunTracker.sendDungeonMessage(Minecraft.getInstance(), "Loading overview for " + names.size() + " party members...", "PARTY");
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
			source.sendFeedback(ChatUtil.prefixed("§cNo party found (or Hypixel Mod API not connected)."));
			return;
		}
		if (names.isEmpty()) {
			source.sendFeedback(ChatUtil.prefixed("§cCouldn't resolve any party members (not in view range/tab list?)."));
			return;
		}
		source.sendFeedback(ChatUtil.prefixed("Checking Magical Power for §a" + names.size() + "§r party members, one by one..."));
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
			client.player.sendSystemMessage(ChatUtil.prefixed("Joined party - checking Magical Power for §a" + names.size() + "§r members..."));
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
							client.player.sendSystemMessage(ChatUtil.prefixed("§cNo MP data for §f" + name + "§c."));
						} else {
							String text = "§a" + name + "§r: §d" + result.magicalPower() + " Magical Power§r"
									+ (result.selectedPower() != null ? " (active: §b" + result.selectedPower() + "§r)" : "");
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

	private static void setHighlightEnabled(boolean enabled, Consumer<Component> feedback) {
		SkyMellooConfig.HANDLER.instance().dungeonRoomMobHighlightEnabled = enabled;
		SkyMellooConfig.HANDLER.save();
		if (feedback != null) {
			feedback.accept(ChatUtil.prefixed("Mob Highlighting " + (enabled ? "§aan" : "§caus")));
		}
	}
}
