package com.melloo.skymelloo.client.gui;

import com.melloo.skymelloo.client.api.SkyMellooApiClient;
import com.melloo.skymelloo.client.party.PartyHudManager;
import com.melloo.skymelloo.client.party.PartyTracker;
import com.melloo.skymelloo.client.social.FriendsManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Social" menu (default key G) - SkyMelloo friends (separate from real Hypixel friends, see
 * {@link FriendsManager}) and the current party, side by side, so managing both doesn't mean
 * memorizing a pile of "/sm friend"/"/party" chat commands. Buttons are {@link SkyMellooButtonWidget}
 * (shared across every SkyMelloo screen, see that class) instead of plain vanilla
 * {@link net.minecraft.client.gui.components.Button} styling - only the text input ({@link EditBox})
 * stays vanilla, since a custom cursor/selection reimplementation isn't worth the risk for one input box.
 * <p>
 * Every text label's (x, y) is computed exactly once, alongside its matching button, into
 * {@link #labels} during {@link #rebuild()} - {@link #extractRenderState} then just draws from that
 * list rather than recomputing the same layout a second time, so a label can never silently drift
 * out of alignment with the button next to it. Face icons ({@link #faces}) are the same idea, keyed
 * by UUID and resolved lazily via {@link RemoteFaceTextureCache} - which works over HTTP regardless
 * of whether that player is anywhere nearby, unlike the local tab-list skin blit PartyHud uses, since
 * a friend here can be offline or on a completely different part of the network. Every list/action
 * (friends, requests, party, invites) still comes straight from FriendsManager/PartyTracker/
 * PartyHudManager, which only ever call out to sky.melloo.me's real API - this screen just lays out
 * whatever they already fetched, it never invents or caches any of that data itself.
 */
public class SocialMenuScreen extends Screen {
	private record Label(String text, int x, int y) {
	}

	private record Face(UUID uuid, int x, int y) {
	}

	private static final int ROW_HEIGHT = 26;
	private static final int COLUMN_GAP = 20;
	private static final int COLUMN_WIDTH = 240;
	private static final int FACE_SIZE = 18;
	private static final int FACE_TEXT_GAP = 6;
	private static final int TEXT_X_OFFSET = FACE_SIZE + FACE_TEXT_GAP;
	private static final int PINK = 0xFFFF6EC7;
	private static final int GREEN = 0xFF55FF88;
	private static final int RED = 0xFFFF5555;

	private EditBox addFriendBox;
	private final List<Label> labels = new ArrayList<>();
	private final List<Face> faces = new ArrayList<>();
	private int lastFriendsHash;
	private int lastRequestsHash;
	private int lastPartyHash;
	private int lastBlockedHash;

	public SocialMenuScreen() {
		super(Component.literal("SkyMelloo Social"));
	}

	@Override
	protected void init() {
		FriendsManager.refresh(Minecraft.getInstance());
		rebuild();
	}

	@Override
	public void tick() {
		super.tick();
		// Friend requests can arrive, and party membership can change, while this screen is sitting
		// open - re-checking every tick against a cheap hash is simpler than wiring a proper
		// change-listener into two independently-polling managers this screen doesn't own.
		int friendsHash = FriendsManager.getFriends().hashCode();
		int requestsHash = FriendsManager.getIncomingRequests().hashCode();
		int partyHash = PartyTracker.getMembers().hashCode();
		int blockedHash = com.melloo.skymelloo.client.social.BlockedUsersManager.getBlocked().hashCode();
		if (friendsHash != lastFriendsHash || requestsHash != lastRequestsHash || partyHash != lastPartyHash || blockedHash != lastBlockedHash) {
			rebuild();
		}
	}

	/** Lenient parse of a Mojang UUID string in either dashed or dashless form - null (no face icon) rather than throwing if it's ever something unexpected. */
	private static UUID parseUuid(String raw) {
		if (raw == null || raw.isEmpty()) {
			return null;
		}
		try {
			if (raw.length() == 32) {
				return UUID.fromString(raw.replaceFirst(
						"(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
			}
			return UUID.fromString(raw);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private void rebuild() {
		clearWidgets();
		labels.clear();
		faces.clear();
		lastFriendsHash = FriendsManager.getFriends().hashCode();
		lastRequestsHash = FriendsManager.getIncomingRequests().hashCode();
		lastPartyHash = PartyTracker.getMembers().hashCode();
		lastBlockedHash = com.melloo.skymelloo.client.social.BlockedUsersManager.getBlocked().hashCode();

		int leftX = this.width / 2 - COLUMN_WIDTH - COLUMN_GAP / 2;
		int rightX = this.width / 2 + COLUMN_GAP / 2;
		int top = 46;

		buildFriendsColumn(leftX, top);
		buildPartyColumn(rightX, top);

		addRenderableWidget(new SkyMellooButtonWidget(this.width / 2 - 95, this.height - 28, 90, 20, "Report a Bug", RED, SkyMellooMenuScreen::openReportBug));
		addRenderableWidget(new SkyMellooButtonWidget(this.width / 2 + 5, this.height - 28, 90, 20, "Done", PINK, this::onClose));
	}

	private void buildFriendsColumn(int x, int top) {
		int y = top;
		addFriendBox = new EditBox(this.font, x, y, COLUMN_WIDTH - 60, 18, Component.literal("Add friend"));
		addFriendBox.setHint(Component.literal("Add friend..."));
		addFriendBox.setTextColor(0xFFFFB6E6);
		addRenderableWidget(addFriendBox);
		addRenderableWidget(new SkyMellooButtonWidget(x + COLUMN_WIDTH - 55, y, 55, 18, "Add", PINK, () -> {
			String name = addFriendBox.getValue().trim();
			if (!name.isEmpty()) {
				FriendsManager.sendRequest(Minecraft.getInstance(), name);
				addFriendBox.setValue("");
			}
		}));
		y += ROW_HEIGHT;

		List<SkyMellooApiClient.FriendRequestEntry> requests = FriendsManager.getIncomingRequests();
		for (SkyMellooApiClient.FriendRequestEntry request : requests) {
			UUID uuid = parseUuid(request.uuid());
			if (uuid != null) {
				faces.add(new Face(uuid, x, y));
			}
			addRenderableWidget(new SkyMellooButtonWidget(x + COLUMN_WIDTH - 44, y, 20, 18, "✓", GREEN, () -> FriendsManager.accept(Minecraft.getInstance(), request.username())));
			addRenderableWidget(new SkyMellooButtonWidget(x + COLUMN_WIDTH - 22, y, 20, 18, "✕", RED, () -> FriendsManager.decline(Minecraft.getInstance(), request.username())));
			labels.add(new Label("§b" + request.username() + " §7(request)", x + TEXT_X_OFFSET, y + (FACE_SIZE - 8) / 2));
			y += ROW_HEIGHT;
		}

		List<SkyMellooApiClient.FriendEntry> friends = FriendsManager.getFriends();
		for (SkyMellooApiClient.FriendEntry friend : friends) {
			UUID uuid = parseUuid(friend.uuid());
			if (uuid != null) {
				faces.add(new Face(uuid, x, y));
			}
			addRenderableWidget(new SkyMellooButtonWidget(x + COLUMN_WIDTH - 100, y, 45, 18, "Chat", PINK, () ->
					Minecraft.getInstance().setScreen(new ChatScreen("/sm chat " + friend.username() + " ", true))));
			addRenderableWidget(new SkyMellooButtonWidget(x + COLUMN_WIDTH - 52, y, 52, 18, "Remove", RED, () -> FriendsManager.remove(Minecraft.getInstance(), friend.username())));
			labels.add(new Label("§b" + friend.username(), x + TEXT_X_OFFSET, y + (FACE_SIZE - 8) / 2));
			y += ROW_HEIGHT;
		}

		if (friends.isEmpty() && requests.isEmpty()) {
			labels.add(new Label("§7No SkyMelloo friends yet.", x, y + 5));
		}

		List<String> blockedUsers = com.melloo.skymelloo.client.social.BlockedUsersManager.getBlocked();
		if (!blockedUsers.isEmpty()) {
			y += 4;
			labels.add(new Label("§7Blocked:", x, y + 3));
			y += ROW_HEIGHT - 4;
			for (String blockedUser : blockedUsers) {
				labels.add(new Label("§c" + blockedUser, x, y + 5));
				addRenderableWidget(new SkyMellooButtonWidget(x + COLUMN_WIDTH - 62, y, 62, 18, "Unblock", PINK, () ->
						com.melloo.skymelloo.client.social.BlockedUsersManager.unblock(blockedUser)));
				y += ROW_HEIGHT;
			}
		}
	}

	private void buildPartyColumn(int x, int top) {
		int y = top;
		if (!PartyTracker.isInParty()) {
			labels.add(new Label("§7Not in a party.", x, y + 5));
			return;
		}
		boolean isLeader = PartyTracker.isLocalPlayerLeader();
		Map<UUID, PartyHudManager.MemberInfo> members = PartyHudManager.getMembers();
		Minecraft client = Minecraft.getInstance();
		UUID selfUuid = client.player != null ? client.player.getUUID() : null;

		for (Map.Entry<UUID, PartyHudManager.MemberInfo> entry : members.entrySet()) {
			boolean isSelf = entry.getKey().equals(selfUuid);
			String memberName = entry.getValue().username();
			faces.add(new Face(entry.getKey(), x, y));
			labels.add(new Label("§b" + memberName + (isSelf ? " §7(you)" : ""), x + TEXT_X_OFFSET, y + (FACE_SIZE - 8) / 2));
			if (!isSelf) {
				// Block works regardless of current leadership - it only takes effect on a FUTURE join
				// once you happen to lead a party, so it's always offered even if Kick (leader-only,
				// would just fail server-side otherwise) isn't right now.
				addRenderableWidget(new SkyMellooButtonWidget(x + COLUMN_WIDTH - 52, y, 52, 18, "Block", RED, () ->
						com.melloo.skymelloo.client.social.BlockedUsersManager.blockAndKickIfPresent(client, memberName)));
				if (isLeader) {
					addRenderableWidget(new SkyMellooButtonWidget(x + COLUMN_WIDTH - 108, y, 52, 18, "Kick", RED, () ->
							client.player.connection.sendCommand("party kick " + memberName)));
				}
			}
			y += ROW_HEIGHT;
		}

		// Friends not currently in the party get an Invite button - "/party invite" works whether or
		// not you're already in a party (creates a new one if needed), so this isn't leader-gated.
		List<SkyMellooApiClient.FriendEntry> invitable = new ArrayList<>();
		for (SkyMellooApiClient.FriendEntry friend : FriendsManager.getFriends()) {
			boolean alreadyInParty = members.values().stream().anyMatch(m -> m.username().equalsIgnoreCase(friend.username()));
			if (!alreadyInParty) {
				invitable.add(friend);
			}
		}
		if (!invitable.isEmpty()) {
			y += 4;
			labels.add(new Label("§7Invite a friend:", x, y + 3));
			y += ROW_HEIGHT - 4;
			for (SkyMellooApiClient.FriendEntry friend : invitable) {
				UUID uuid = parseUuid(friend.uuid());
				if (uuid != null) {
					faces.add(new Face(uuid, x, y));
				}
				labels.add(new Label("§b" + friend.username(), x + TEXT_X_OFFSET, y + (FACE_SIZE - 8) / 2));
				addRenderableWidget(new SkyMellooButtonWidget(x + COLUMN_WIDTH - 55, y, 55, 18, "Invite", PINK, () ->
						client.player.connection.sendCommand("party invite " + friend.username())));
				y += ROW_HEIGHT;
			}
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick) {
		gg.fill(0, 0, this.width, this.height, 0xCC101018);

		int leftX = this.width / 2 - COLUMN_WIDTH - COLUMN_GAP / 2;
		int rightX = this.width / 2 + COLUMN_GAP / 2;
		int top = 46;

		gg.centeredText(this.font, "§6SkyMelloo Social", this.width / 2, 16, 0xFFFFFF);
		gg.text(this.font, "§dFriends", leftX, top - 14, 0xFFFFFF);
		gg.text(this.font, "§dParty", rightX, top - 14, 0xFFFFFF);

		for (Face face : faces) {
			Identifier texture = RemoteFaceTextureCache.get(face.uuid());
			if (texture != null) {
				gg.blit(texture, face.x(), face.y(), face.x() + FACE_SIZE, face.y() + FACE_SIZE, 0f, 1f, 0f, 1f);
			} else {
				gg.fill(face.x(), face.y(), face.x() + FACE_SIZE, face.y() + FACE_SIZE, 0x30FFFFFF);
			}
		}
		for (Label label : labels) {
			gg.text(this.font, label.text(), label.x(), label.y(), 0xFFFFFF);
		}

		super.extractRenderState(gg, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
