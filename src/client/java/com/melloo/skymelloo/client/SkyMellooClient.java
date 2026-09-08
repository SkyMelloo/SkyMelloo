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
	private static KeyMapping saveLookKey;
	private static KeyMapping loadLookKey;

	public static KeyMapping getOpenConfigKey() {
		return openConfigKey;
	}

	private static boolean isControlDown(Minecraft client) {
		return InputConstants.isKeyDown(client.getWindow(), InputConstants.KEY_LCONTROL)
				|| InputConstants.isKeyDown(client.getWindow(), InputConstants.KEY_RCONTROL);
	}

	@Override
	public void onInitializeClient() {
		SkyMellooConfig.HANDLER.load();
		// Lets essentials' settings screen (H) offer a button back into SkyMelloo's own tabs.
		com.melloo.mellooessentials.client.gui.SettingsScreen.setSkyMellooScreenOpener(() ->
				com.melloo.skymelloo.client.gui.SkyMellooSettingsScreen.open(com.melloo.skymelloo.client.gui.SkyMellooSettingsScreen.Tab.GENERAL));
		com.melloo.mellooessentials.client.social.ConnectionStatusHud.setAdminBadgeSupplier(WhitelistManager::getAdminBadgeText);
		com.melloo.mellooessentials.client.social.ConnectionStatusHud.setExtraLineProvider(() -> {
			int ms = com.melloo.skymelloo.client.social.SkyMellooPingMonitor.getLastPingMs();
			return ms >= 0 ? ms + "ms" : "--";
		});
		// Supplies this mod's own HUD elements to essentials' layout editor (key J).
		com.melloo.mellooessentials.client.gui.HudLayoutEditorScreen.setExtraElementsProvider(
				com.melloo.skymelloo.client.gui.SkyMellooHudElements::build);
		com.melloo.mellooessentials.client.gui.HudLayoutEditorScreen.setExtraSaveHandler(SkyMellooConfig.HANDLER::save);
		com.melloo.mellooessentials.client.highlight.HighlightManager.setPartyBlinkColorOverride(
				com.melloo.skymelloo.client.highlight.HighlightManager::partyBlinkOverride);
		PartyTracker.init();
		ModPresenceManager.init();
		PartyJoinWatcher.init();
		DungeonRunTracker.init();
		com.melloo.skymelloo.client.social.ActionBarTracker.init();
		com.melloo.skymelloo.client.social.ChatMentionHighlighter.init();
		com.melloo.skymelloo.client.social.AntiScamFilter.init();
		com.melloo.skymelloo.client.util.PartyChatSender.init();
		com.melloo.skymelloo.client.gui.SkyMellooMenuItemManager.init();
		com.melloo.skymelloo.client.util.AutoReconnect.init();
		BlockHighlightRenderer.init();
		ClientPlayConnectionEvents.INIT.register((handler, client) -> {
			ConnectionQualityMonitor.reset();
			ConnectionQualityMonitor.start(client);
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			PlayerKillTracker.resetSession();
			// Party membership is connection-tied; whitelist/permissions/cloud-sync have their own recheck timers.
			PartyHudManager.reset();
		});
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "fishing_score"), FishingScoreHud.INSTANCE);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "party"), PartyHud.INSTANCE);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "party_mp_bar"), com.melloo.skymelloo.client.party.PartyApBarHud.INSTANCE);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "dungeon_score"), DungeonScoreHud.INSTANCE);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "dungeon_debug"), DungeonDebugHud.INSTANCE);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "health_mana_bars"), com.melloo.skymelloo.client.gui.HealthManaBarsHud.INSTANCE);

		// Unbound by default - bind under Controls > Key Binds > SkyMelloo.
		toggleMobHighlightKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.skymelloo.toggle_mob_highlight",
				InputConstants.Type.KEYSYM,
				InputConstants.UNKNOWN.getValue(),
				CATEGORY
		));

		// Unbound by default - H opens MellooEssentials' settings screen instead.
		openConfigKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.skymelloo.open_config",
				InputConstants.Type.KEYSYM,
				InputConstants.UNKNOWN.getValue(),
				CATEGORY
		));

		mainMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.skymelloo.main_menu",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_K,
				CATEGORY
		));

		// Ctrl+X/Ctrl+F "look clipboard" - fabric's KeyMapping has no modifier-combo support, so callers check isControlDown() themselves.
		saveLookKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.skymelloo.save_look",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_X,
				CATEGORY
		));
		loadLookKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.skymelloo.load_look",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_F,
				CATEGORY
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			ConnectionQualityMonitor.tick(client);
			com.melloo.skymelloo.client.util.SkyblockDetector.tick(client);
			SkyMellooPingMonitor.tick(client);
			com.melloo.skymelloo.client.util.AutoReconnect.tick(client);
			com.melloo.skymelloo.client.util.LunarPackCacheCleaner.tick(client);

			while (mainMenuKey.consumeClick()) {
				com.melloo.skymelloo.client.gui.SkyMellooMenuItemManager.openMenu(client);
			}
			while (openConfigKey.consumeClick()) {
				if (client.screen == null) {
					com.melloo.skymelloo.client.gui.SkyMellooSettingsScreen.open(com.melloo.skymelloo.client.gui.SkyMellooSettingsScreen.Tab.GENERAL);
				}
			}
			while (saveLookKey.consumeClick()) {
				if (client.screen == null && client.player != null && isControlDown(client)) {
					com.melloo.skymelloo.client.util.LookClipboardManager.save(client.player);
					client.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.chat.look_clipboard.saved")));
				}
			}
			while (loadLookKey.consumeClick()) {
				if (client.screen == null && client.player != null && isControlDown(client)) {
					boolean restored = com.melloo.skymelloo.client.util.LookClipboardManager.startRestore(client.player);
					client.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable(
							restored ? "skymelloo.chat.look_clipboard.loaded" : "skymelloo.chat.look_clipboard.empty")));
				}
			}
			if (client.player != null) {
				com.melloo.skymelloo.client.util.LookClipboardManager.tick(client.player);
			}

			// Everything else is Hypixel-only.
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
			PartyTracker.tick();
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
								// Bare "/sm sync" == "/sm sync party".
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
										ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.bossroom.frontier",
												com.melloo.skymelloo.client.social.BossRoomScanner.getFrontierSize())));
									} else {
										ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.bossroom.not_scanning")));
									}
									long attempts = com.melloo.skymelloo.client.social.ModPresenceManager.getBossRoomSendAttempts();
									long successes = com.melloo.skymelloo.client.social.ModPresenceManager.getBossRoomSendSuccesses();
									long failures = com.melloo.skymelloo.client.social.ModPresenceManager.getBossRoomSendFailures();
									ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.bossroom.reports", attempts, successes, failures)));
									if (failures > 0) {
										ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.debug.bossroom.last_error", com.melloo.skymelloo.client.social.ModPresenceManager.getLastBossRoomSendError())));
									}
									return 1;
								}))
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
								}))
								.then(ClientCommands.literal("items").executes(ctx -> {
									boolean on = com.melloo.skymelloo.client.gui.SkyblockItemIcons.toggleDebug();
									ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable(
											on ? "skymelloo.command.debug.items.on" : "skymelloo.command.debug.items.off")));
									return 1;
								})))
						.then(ClientCommands.literal("version").executes(ctx -> {
							String version = com.melloo.mellooessentials.client.util.ModVersionManager.getSkyMellooLocalVersion();
							String publicVersion = com.melloo.mellooessentials.client.util.ModVersionManager.getSkyMellooPublicVersion();
							String jarHash = com.melloo.mellooessentials.client.util.ModVersionManager.getSkyMellooJarHash();
							ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.version.header")));
							Component jarHashText = jarHash != null ? Component.literal(jarHash) : Component.translatable("skymelloo.command.version.jarhash_unknown");
							ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.version.running", publicVersion, version, jarHashText)));
							if (com.melloo.skymelloo.client.api.SiteConfig.isCustomSite()) {
								ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.version.custom_site", com.melloo.skymelloo.client.api.SiteConfig.root())));
							}
							ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.version.checking")));
							com.melloo.mellooessentials.client.util.ModVersionManager.checkSkyMellooNow(
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
										c.player.sendSystemMessage(legalLink(Component.translatable("skymelloo.command.version.get_from_official"), com.melloo.skymelloo.client.api.SiteConfig.PRODUCTION + "/download"));
									},
									cooldownSeconds -> {
										Minecraft c = Minecraft.getInstance();
										if (c.player == null) {
											return;
										}
										c.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.command.version.cooldown", cooldownSeconds)));
										c.player.sendSystemMessage(legalLink(Component.translatable("skymelloo.command.version.get_from_official"), com.melloo.skymelloo.client.api.SiteConfig.PRODUCTION + "/download"));
									}
							);
							return 1;
						}))
						// Fetched server-side so an unverified build can be refused the real legal pages.
						.then(ClientCommands.literal("legal").executes(ctx -> {
							String jarHash = com.melloo.mellooessentials.client.util.ModVersionManager.getSkyMellooJarHash();
							com.melloo.skymelloo.client.api.SkyMellooApiClient.fetchLegalInfo(jarHash).whenComplete((info, error) -> Minecraft.getInstance().execute(() -> {
								if (error != null || info == null) {
									var lastResult = com.melloo.mellooessentials.client.util.ModVersionManager.getSkyMellooLastResult();
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
						// Opens sky.melloo.me/link/<token> in the system browser to complete via existing Discord session.
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
													net.minecraft.util.Util.getPlatform().openUri(java.net.URI.create(com.melloo.skymelloo.client.api.SiteConfig.url("/link/") + result.token()));
												} else {
													c.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.command.common.failed", result.error())));
												}
											})
									);
							return 1;
						}))
						.then(ClientCommands.literal("contact").executes(ctx -> {
							ctx.getSource().sendFeedback(legalLink(Component.translatable("skymelloo.command.contact.label"), com.melloo.skymelloo.client.api.SiteConfig.url("/contact")));
							return 1;
						}))
						.then(ClientCommands.literal("view")
								.then(ClientCommands.argument("name", StringArgumentType.word())
										.suggests(SkyMellooClient::suggestOnlinePlayers)
										.executes(ctx -> {
											try {
												com.melloo.skymelloo.client.gui.PlayerViewScreen.open(StringArgumentType.getString(ctx, "name"));
											} catch (Throwable t) {
												ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.common.failed", ChatUtil.friendlyError(t))));
											}
											return 1;
										})))
						.then(ClientCommands.argument("unknown", StringArgumentType.greedyString()).executes(ctx -> {
							ctx.getSource().sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.unknown_command")));
							return 1;
						}))
			);
			dispatcher.register(ClientCommands.literal("sm").redirect(skymellooNode));
		});

		LOGGER.info("SkyMelloo loaded. Open settings with 'H' (rebindable), or /skymelloo config");
	}

	/** Clickable chat line that opens {@code url} in the system browser. */
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

		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.dungeons_header")));
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.kills")));
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.session")));
		source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.help.partyjoin")));

		// Every feature is unlocked for everyone, no account link required.
		java.util.List<String> unlockedFeatures = java.util.List.of("Party", "Fishing", "Dungeons", "Spell");
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
						// Hypixel NPCs show up in the tab list with names starting with "!" (e.g. "!Auctioneer").
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

	// "all" and "ap" are handled specially; everything else maps 1:1 to an ExtraStat.
	private static final java.util.List<String> GETDATA_STAT_NAMES = java.util.List.of(
			"all", "ap", "networth", "bank", "purse", "fairysouls", "guild", "rank",
			"skills", "slayer", "classes", "minions", "bestiary", "highestfloor", "firstjoin",
			"pets", "collections", "minionslots", "profiles", "dungeonruns"
	);

	/** e.g. "getdata player Foo Bar all" / "getdata player Foo ap" / "getdata party all". */
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
		// "normal" is a stand-in for omitting the profile argument entirely.
		if (profile != null && profile.equalsIgnoreCase("normal")) {
			profile = null;
		}
		switch (stat) {
			case "all" -> {
				source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.getdata.loading_overview", name)));
				announceAllStats(name, profile, () -> {
				}, announce);
			}
			case "ap" -> {
				source.sendFeedback(ChatUtil.prefixed(Component.translatable("skymelloo.command.getdata.looking_mp", name)));
				announceAccessoryPower(name, profile, announce);
			}
			default -> announceExtraStat(ExtraStat.byCommandName(stat), name, profile, announce);
		}
	}

	private static void dispatchPartyStat(String stat, FabricClientCommandSource source, boolean announce) {
		switch (stat) {
			case "all" -> announcePartyAllStats(source, announce);
			case "ap" -> announcePartyAccessoryPower(source, announce);
			default -> announcePartyExtraStat(ExtraStat.byCommandName(stat), source, announce);
		}
	}

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

	/** With debug messages on, shows how stale the ~45s-cached data is. */
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
		// Staggered a second apart to avoid tripping Hypixel's chat rate limit.
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

	private static void announceAccessoryPower(String name, String profile, boolean announce) {
		ModAuthManager.getIdentity(Minecraft.getInstance()).thenCompose(identity -> SkyMellooApiClient.fetchAccessoryPower(name, profile, identity)).whenComplete((result, error) ->
				Minecraft.getInstance().execute(() -> {
					Minecraft client = Minecraft.getInstance();
					if (client.player == null) {
						return;
					}
					if (error != null) {
						client.player.sendSystemMessage(ChatUtil.prefixed(ChatUtil.errorMessage(name, error)));
						return;
					}
					if (result.accessoryPower() < 0) {
						client.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.command.mp.not_found", name)));
						return;
					}
					Component activeSuffix = result.selectedPower() != null
							? Component.translatable("skymelloo.command.mp.active_suffix", result.selectedPower())
							: Component.empty();
					Component textComponent = Component.translatable("skymelloo.command.mp.result", name, result.accessoryPower(), activeSuffix);
					if (announce) {
						DungeonRunTracker.sendDungeonMessage(client, textComponent.getString(), "PARTY");
						return;
					}
					client.player.sendSystemMessage(ChatUtil.prefixed(textComponent));
				})
		);
	}

	/** Locally: several separate messages. Announce mode: one combined /pc message, to avoid spamming party chat. */
	private static void announceAllStats(String name, String profile, Runnable onDone, boolean announce) {
		ModAuthManager.getIdentity(Minecraft.getInstance()).thenAccept(identity ->
		SkyMellooApiClient.fetchSummary(name, profile, identity).whenComplete((summary, summaryError) ->
				SkyMellooApiClient.fetchAccessoryPower(name, profile, identity).whenComplete((ap, apError) ->
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
								String apValue = (apError == null && ap.accessoryPower() >= 0) ? String.valueOf(ap.accessoryPower()) : "?";
								Component rank = summary.rankLabel() != null ? Component.literal(summary.rankLabel()) : Component.translatable("skymelloo.command.common.none");
								Component guild = summary.guildName() != null ? Component.literal(summary.guildName()) : Component.translatable("skymelloo.command.common.none");
								String classSuffix = summary.selectedClass() != null ? " (" + summary.selectedClass() + ")" : "";
								String avgSkillText = String.format("%.1f", summary.averageSkillLevel());
								if (announce) {
									Component textComponent = Component.translatable("skymelloo.command.getdata.all.party_summary",
											name, summary.skyblockLevel(), rank, guild, summary.catacombsLevel(), classSuffix, apValue, avgSkillText,
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
										Component.translatable("skymelloo.command.getdata.all.line3", apValue, summary.catacombsLevel(), classSuffix, avgSkillText)
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

	// Delay between staggered /pc announcements to a whole party.
	private static final int ANNOUNCE_STAGGER_TICKS = 20;

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
		Runnable onDone = announce ? () -> TickDelay.schedule(ANNOUNCE_STAGGER_TICKS, next) : next;
		announceAllStats(name, null, onDone, announce);
	}

	private static void announcePartyAccessoryPower(FabricClientCommandSource source, boolean announce) {
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
		announcePartyAccessoryPowerSequentially(names.iterator(), announce);
	}

	/** Called after joining an existing Dungeon Finder party; no-ops if there's no party data yet. */
	public static void checkPartyAccessoryPowerAuto() {
		java.util.List<String> names = resolvePartyMemberNames(true);
		if (names == null || names.isEmpty()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			client.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.command.party.joined_checking_mp", names.size())));
		}
		announcePartyAccessoryPowerSequentially(names.iterator(), false);
	}

	/** Null if there's no party; prefers PartyHudManager's cached usernames over a fresh tab-list lookup. */
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
				var tabInfo = client.getConnection().getPlayerInfo(uuid);
				username = tabInfo != null ? tabInfo.getProfile().name() : null;
			}
			if (username == null || username.equals(uuid.toString().substring(0, 8))) {
				DebugLog.log(DebugLog.Category.PARTY, "getdata party: couldn't resolve a username for " + uuid + " yet, skipping.");
				continue;
			}
			names.add(username);
		}
		return names;
	}

	private static void announcePartyAccessoryPowerSequentially(java.util.Iterator<String> remaining, boolean announce) {
		if (!remaining.hasNext()) {
			return;
		}
		String name = remaining.next();
		Runnable next = () -> announcePartyAccessoryPowerSequentially(remaining, announce);
		Runnable onDone = announce ? () -> TickDelay.schedule(ANNOUNCE_STAGGER_TICKS, next) : next;
		ModAuthManager.getIdentity(Minecraft.getInstance()).thenCompose(identity -> SkyMellooApiClient.fetchAccessoryPower(name, identity)).whenComplete((result, error) ->
				Minecraft.getInstance().execute(() -> {
					Minecraft client = Minecraft.getInstance();
					if (client.player != null) {
						if (error != null) {
							client.player.sendSystemMessage(ChatUtil.prefixed(ChatUtil.errorMessage(name, error)));
						} else if (result.accessoryPower() < 0) {
							client.player.sendSystemMessage(ChatUtil.prefixed(Component.translatable("skymelloo.command.mp.no_data", name)));
						} else {
							String text = Component.translatable("skymelloo.command.mp.result_colon", name, result.accessoryPower()).getString()
									+ (result.selectedPower() != null ? Component.translatable("skymelloo.command.mp.active_suffix", result.selectedPower()).getString() : "");
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

	private static void setMobHighlightEnabled(boolean enabled, Consumer<Component> feedback) {
		SkyMellooConfig.HANDLER.instance().dungeonRoomMobHighlightEnabled = enabled;
		SkyMellooConfig.HANDLER.save();
		if (feedback != null) {
			feedback.accept(ChatUtil.prefixed(Component.translatable("skymelloo.command.mob_highlighting.toggled",
					enabled ? Component.translatable("skymelloo.command.common.state_on") : Component.translatable("skymelloo.command.common.state_off"))));
		}
	}
}
