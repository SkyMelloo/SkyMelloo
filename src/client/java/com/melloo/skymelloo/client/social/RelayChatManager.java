package com.melloo.skymelloo.client.social;

import com.melloo.skymelloo.client.api.ModAuthManager;
import com.melloo.skymelloo.client.api.SkyMellooApiClient;
import com.melloo.skymelloo.client.party.PartyTracker;
import com.melloo.skymelloo.client.util.ChatUtil;
import com.melloo.skymelloo.client.util.DebugLog;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.List;
import java.util.UUID;

/**
 * Short message relay riding entirely on sky.melloo.me - a "/sm chat" DM to a confirmed SkyMelloo
 * friend, or a broadcast to whichever current party members are also running SkyMelloo - that never
 * touches real Hypixel chat at all (unlike the existing LOCAL/PARTY "/pc" delivery options
 * elsewhere in this mod). Since I (the operator) run the relay server myself, there's no need to
 * detect a specific in-game command to trigger it - callers just POST straight to sky.melloo.me and
 * it fans out to whichever other clients are polling their inbox below.
 * <p>
 * Polling (not push) since there's no persistent connection to the backend - {@link #POLL_INTERVAL_TICKS}
 * is deliberately tighter than {@link ModPresenceManager}'s 10s report cycle so a chat actually feels
 * like a chat, not a slow status update.
 */
public final class RelayChatManager {
	private static final int POLL_INTERVAL_TICKS = 60; // 3s
	private static int tickCounter = 0;
	private static boolean pollInFlight = false;

	private RelayChatManager() {
	}

	public static void tick(Minecraft client) {
		if (client.player == null) {
			return;
		}
		tickCounter++;
		if (tickCounter % POLL_INTERVAL_TICKS == 0) {
			poll(client);
		}
	}

	private static void poll(Minecraft client) {
		if (pollInFlight) {
			return;
		}
		pollInFlight = true;
		ModAuthManager.getIdentity(client)
				.thenCompose(SkyMellooApiClient::fetchRelayInbox)
				.whenComplete((messages, error) -> Minecraft.getInstance().execute(() -> {
					pollInFlight = false;
					if (error != null) {
						DebugLog.log(DebugLog.Category.PARTY, "Relay chat: inbox poll failed (" + error.getMessage() + ").");
						return;
					}
					for (SkyMellooApiClient.RelayMessage message : messages) {
						display(message);
					}
				}));
	}

	private static void display(SkyMellooApiClient.RelayMessage message) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}
		boolean isParty = "party".equalsIgnoreCase(message.scope());
		String tag = isParty ? "§d[SM Party] " : "§d[SM DM] ";
		MutableComponent name = Component.literal("§b" + message.fromUsername());
		if (!isParty) {
			// Click a DM sender's name to pre-fill a reply, same convenience as clicking a real
			// player's name in vanilla chat to /msg them.
			name.setStyle(Style.EMPTY
					.withClickEvent(new ClickEvent.SuggestCommand("/sm chat " + message.fromUsername() + " "))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to reply"))));
		}
		client.player.sendSystemMessage(ChatUtil.prefixed(Component.literal(tag).append(name).append(Component.literal("§7: §f" + message.text()))));
	}

	/** Sends a DM - {@code toUsername} must already be a confirmed SkyMelloo friend (checked both here for a fast local error, and again server-side regardless). */
	public static void sendDirect(Minecraft client, String toUsername, String text) {
		if (!FriendsManager.isFriend(toUsername)) {
			client.player.sendSystemMessage(ChatUtil.prefixed("§cNot a SkyMelloo friend yet - §f/sm friend " + toUsername + " §cfirst."));
			return;
		}
		ModAuthManager.getIdentity(client)
				.thenCompose(identity -> SkyMellooApiClient.sendRelayMessage(toUsername, text, identity))
				.whenComplete((ok, error) -> Minecraft.getInstance().execute(() -> {
					if (client.player == null) {
						return;
					}
					if (error != null || !Boolean.TRUE.equals(ok)) {
						client.player.sendSystemMessage(ChatUtil.prefixed("§cCouldn't send - they may not be online right now."));
						return;
					}
					client.player.sendSystemMessage(ChatUtil.prefixed("§d[SM DM → " + toUsername + "] §7: §f" + text));
				}));
	}

	/**
	 * Broadcasts to whichever current party members are also running SkyMelloo (detected via
	 * {@link ModPresenceManager#isModUser}) - unlike a DM, party membership itself is the trust
	 * boundary here, no separate friend requirement. Silently does nothing if nobody else in the
	 * party is running SkyMelloo, rather than posting a message nobody else will ever see.
	 */
	public static void sendPartyBroadcast(Minecraft client, String text) {
		List<String> recipients = PartyTracker.getMembers().stream()
				.filter(uuid -> !uuid.equals(client.player.getUUID()))
				.filter(ModPresenceManager::isModUser)
				.map(UUID::toString)
				.toList();
		if (recipients.isEmpty()) {
			client.player.sendSystemMessage(ChatUtil.prefixed("§7Nobody else in your party is running SkyMelloo right now."));
			return;
		}
		String selfName = client.player.getGameProfile().name();
		ModAuthManager.getIdentity(client)
				.thenCompose(identity -> SkyMellooApiClient.sendRelayPartyMessage(recipients, text, identity))
				.whenComplete((ok, error) -> Minecraft.getInstance().execute(() -> {
					if (client.player == null) {
						return;
					}
					if (error != null || !Boolean.TRUE.equals(ok)) {
						client.player.sendSystemMessage(ChatUtil.prefixed("§cCouldn't send to party."));
						return;
					}
					client.player.sendSystemMessage(ChatUtil.prefixed("§d[SM Party] §b" + selfName + "§7: §f" + text));
				}));
	}

	/**
	 * Fire-and-forget party ANNOUNCEMENT relay, deliberately quiet (no "sent"/"nobody online" chat
	 * line - see {@link #sendPartyBroadcast} for the user-initiated equivalent that DOES confirm) -
	 * used for automatic background announcements (see {@code DungeonRunTracker#sendDungeonMessage}'s
	 * "PARTY SM" delivery option and {@link DebugLog}'s own PARTY SM support), which already print
	 * their own message text locally through their normal LOCAL-delivery code path, so echoing it
	 * again here would just double it up. Any leader-only restriction is the CALLER's responsibility
	 * (see sendDungeonMessage's {@code leaderOnlyForRelay} doc comment) - this method itself always
	 * just sends, since some callers (e.g. debug logs, a death recap) are inherently personal/never
	 * duplicated and don't need that restriction at all.
	 */
	public static void sendPartyAnnouncement(Minecraft client, String text) {
		if (client.player == null) {
			return;
		}
		List<String> recipients = PartyTracker.getMembers().stream()
				.filter(uuid -> !uuid.equals(client.player.getUUID()))
				.filter(ModPresenceManager::isModUser)
				.map(UUID::toString)
				.toList();
		if (recipients.isEmpty()) {
			return;
		}
		ModAuthManager.getIdentity(client)
				.thenCompose(identity -> SkyMellooApiClient.sendRelayPartyMessage(recipients, text, identity))
				.exceptionally(error -> {
					DebugLog.log(DebugLog.Category.DUNGEON, "Party SM announcement failed: " + error.getMessage());
					return null;
				});
	}

	// ---- command tree ----

	public static LiteralArgumentBuilder<FabricClientCommandSource> buildChatCommand() {
		return ClientCommands.literal("chat")
				.executes(ctx -> {
					ctx.getSource().sendFeedback(ChatUtil.prefixed("§cBenutzung: §f/sm chat party <message>§7 oder §f/sm chat <name> <message>"));
					return 1;
				})
				.then(ClientCommands.literal("party")
						.then(ClientCommands.argument("message", StringArgumentType.greedyString())
								.executes(ctx -> {
									sendPartyBroadcast(Minecraft.getInstance(), StringArgumentType.getString(ctx, "message"));
									return 1;
								})))
				.then(ClientCommands.argument("name", StringArgumentType.word())
						.then(ClientCommands.argument("message", StringArgumentType.greedyString())
								.executes(ctx -> {
									sendDirect(Minecraft.getInstance(), StringArgumentType.getString(ctx, "name"), StringArgumentType.getString(ctx, "message"));
									return 1;
								})));
	}
}
