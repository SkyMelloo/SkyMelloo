# SkyMelloo Changelog

Internal dev version history - every entry below used to live as a giant stacked comment directly above `mod_version` in `gradle.properties`. Moved here since that file was getting absurd. See gradle.properties for the versioning scheme (PATCH/MINOR/MAJOR) and the still-separate PUBLIC_VERSIONING note.

> Versioning scheme (set 2026-07-26): PATCH (3rd number) for small bugfixes, MINOR (2nd number, patch reset to 0) for bigger added features, MAJOR (1st number) only ever bumped on explicit instruction. Bumped 0.0.0 -> 0.1.0 the same day after a whole batch of real features shipped (room grouping on the map, non-mod-user position reporting, redesigned cosmetics, the mod signing/integrity system, /sm info+version, etc.) without ever actually bumping the version - this scheme must be applied on every future change from here on, not just remembered once. The backend checks this against a minimum-compatible version on join (see ModVersionManager) and separately nudges (without disabling anything) if it's merely behind the latest release - see MIN_CLIENT_VERSION/LATEST_CLIENT_VERSION in server.js, which must stay in lockstep with whatever's released here.

## 0.39.7 (from 0.39.6) · patch

`/sm view <name>` now catches `Throwable`, not just `Exception` - a class-linking error (e.g. `NoSuchMethodError`) is an `Error`, not an `Exception`, and would have skipped the 0.39.6 catch entirely.

## 0.39.6 (from 0.39.5) · patch

`/sm view <name>` now reports failure to chat instead of silently doing nothing if opening the screen throws.

## 0.39.5 (from 0.39.4) · patch

`/sm search` now works in SkyBlock too, not just Hypixel lobbies - still Hypixel-only overall.

## 0.39.4 (from 0.39.3) · patch

Removed the account-link requirement from the Spell feature entirely - it's unlocked for everyone
now, same as every other feature. `PermissionsManager.has` always returns true; account-link status
is still tracked separately, but only Cloud Sync actually requires it now.

## 0.39.3 (from 0.39.2) · patch

Fixed the outdated Magic Missile "needs a linked account" chat message - it pointed to the old
code-based verification flow (`/mes verify <code>`), which was replaced by `/skymelloo link` a
while ago. No compiled-class change, so this build's signature hash matches 0.39.2's exactly.

## 0.39.2 (from 0.39.1) · patch

Repo maintenance pass: removed the unused `maven-publish` plugin/`publishing` block from build.gradle (dead config, nothing ever consumed it), trimmed verbose/historical comments across build.gradle and scripts/, and fixed a stale `github.com/hexedmaya/SkyMelloo` link in scripts/build.js to the real `github.com/SkyMelloo/SkyMelloo`. Synced `mellooessentials_version` pin to 0.13.2 (that side's own maintenance pass, including a real jar content change - LICENSE now embedded in its jar too). No functional change on this side.

## 0.39.1 (from 0.39.0) · patch

`cloudSyncEnabled` now defaults to off instead of on (T53). Previously, cloud settings sync started automatically the moment an account was linked, with no separate explicit step - now it needs its own opt-in in Settings, same privacy-first bar `presenceSharingEnabled` already had. Existing users who already had it on keep their current setting. Synced `mellooessentials_version` pin to 0.13.1 (same fix, MellooEssentials side).

## 0.39.0 (from 0.38.5) · minor

New "look clipboard": Ctrl+X saves the current look direction (yaw/pitch), Ctrl+F turns the player smoothly back to face it - one slot, overwritten on every save, not persisted across restarts. Both keys default to X/F (unbound in vanilla), gated on `Screen.hasControlDown()` since Fabric's `KeyMapping` has no native modifier-combo support - a bare press without Ctrl held is a no-op. The turn itself is a short eased lerp over a handful of ticks rather than an instant snap, both to feel like a real camera movement and to avoid an aim-bot-style instant snap on Hypixel. Only active with no screen open.

## 0.38.5 (from 0.38.4) · patch

`CODE_OF_CONDUCT.md` - added a no-politics rule to "Not okay", and made permanent ban the fixed, non-discretionary penalty specifically for political content and for sharing someone else's private information without consent (T49) - every other violation stays under the existing general "restriction at the maintainers' discretion" wording. Docs only, no code change.

## 0.38.4 (from 0.38.3) · patch

Added `.github/workflows/sync-shared-docs.yml` - SkyMelloo is now the canonical source for `SECURITY.md`/`CODE_OF_CONDUCT.md`, auto-pushed out to MellooEssentials, developer-api, and api-client on every change here (needs a `SYNC_TARGET_TOKEN` repo secret to actually run, not yet configured). Also genericized `CODE_OF_CONDUCT.md`'s wording - dropped the few SkyMelloo-specific illustrative examples (in-game block, friend's status message) so one canonical version reads correctly in the docs/library repos too, matching what developer-api's copy already independently said. No functional/code change.

## 0.38.3 (from 0.38.2) · patch

README's intro line no longer describes SkyMelloo as being for "Hypixel SkyBlock dungeons" specifically - now just "Hypixel SkyBlock", with dungeon tracking staying listed as one feature among several instead of the whole premise. Docs only, no code change.

## 0.38.2 (from 0.38.1) · patch

The position-history dedup above now reports how much it actually saved - once a run ends, DebugLog (Category.DUNGEON, off by default like every other debug category, same file+chat delivery as always) shows the total bytes saved vs. what would've been sent uncompacted, as a percentage and a formatted size (B/KB/MB/GB). Purely diagnostic, doesn't change what's sent.

## 0.38.1 (from 0.38.0) · patch

DungeonSyncManager now applies the same stand-still dedup to outgoing positionHistory that the website already applies on the storage side (`lib/dungeonRuns.js#compactPositionHistory`, T43) - a run of consecutive samples with identical mapX/mapY/yaw collapses to just its first and last tick before being sent, saving real upload bandwidth on long stand-stills, not just server storage. Lossless: never fewer than the run's first+last tick, and a yaw-only change (turning in place) is never collapsed - position/rotation stay independent signals, same as the website side.

## 0.38.0 (from 0.37.8) · minor

Deleted this mod's own entire ModVersionManager (version/integrity check) - MellooEssentials' own copy now checks this mod too (Fabric Loader's mod registry is global, no dependency-direction issue), fixing the double "unofficial build" chat notice both mods used to fire independently on join. "/sm version"/"/sm legal" now read MellooEssentials' new `getSkyMellooXxx()` getters instead. Synced dependency pin to 0.13.0.

## 0.37.8 (from 0.37.7) · patch

SECURITY.md scope now also covers developer-api/api-client (both just got the same community-health docs this repo already has). No functional change.

## 0.37.7 (from 0.37.6) · patch

Added GitHub issue templates (bug report, feature request) and a PR template under `.github/` - closes out most of the repo's Community Profile checklist. Synced mellooessentials_version pin to 0.12.4. No functional change.

## 0.37.6 (from 0.37.5) · patch

Fixed self-contradictory wording that slipped into the 0.37.5 entry below during the history rewrite (its own text got swept up in the same word-substitution rule it was describing). No functional change.

## 0.37.5 (from 0.37.4) · patch

Reworded every remaining mention of the old internal highlighting terminology in README/CHANGELOG.md, and rewrote git history (force-pushed) to scrub it out of every past commit's file content, paths, and messages too - never actually used in current code, just leftover history text. No functional change.

## 0.37.4 (from 0.37.3) · patch

CHANGELOG.md readability pass - each entry now shows its patch/minor/major badge next to the version heading instead of buried in the prose, and multi-part entries that were originally "(1) ... (2) ... (3) ..." or real bullet lists in the old gradle.properties comments render as actual markdown lists again (a few had gotten flattened into run-on paragraphs during the original extraction). Also synced mellooessentials_version pin to 0.12.3. No functional change.

## 0.37.3 (from 0.37.2) · patch

README banner - no public release planned yet, still full active development, Discord contact for anyone interested in helping. Also synced mellooessentials_version pin to 0.12.2. No functional change.

## 0.37.2 (from 0.37.1) · patch

Synced mellooessentials_version pin to 0.12.1 (same changelog-to-file cleanup as this mod just got). No SkyMelloo-side functional change.

## 0.37.1 (from 0.37.0) · patch

Moved the whole per-version changelog history out of gradle.properties (it had grown to 680+ lines) into this file. No functional change.

## 0.37.0 (from 0.36.1) · minor

Removed the whole Cloud tab. Account (connection-status display) and Cloud Sync's manual push/pull buttons were pure duplicates of MellooEssentials' own Cloud tab - gone, no replacement needed. SkyMelloo's OWN cloudSyncEnabled toggle (a genuinely separate sync process from MellooEssentials' - see CloudSyncManager) moved to the General tab instead of being removed. Presence Sharing moved to MellooEssentials as a new native setting (EssentialsConfig#presenceSharingEnabled, General tab) since it's not SkyBlock-specific - SkyMelloo no longer has its own copy (that copy was dead code, never actually wired to anything). Custom status text field removed entirely - status now comes from your linked sky.melloo.me account instead of being typed in the mod (website task filed for the server side). Dungeon Sync's toggle moved to the top of the Dungeons tab instead (stayed in SkyMelloo, not MellooEssentials - it's SkyBlock/dungeon-specific, doesn't belong in the general mod). Synced dependency pin to 0.12.0.

## 0.36.1 (from 0.36.0) · patch

Synced mellooessentials_version pin to 0.11.6 (settings-screen background fix + Cloud tab push/pull button removal). No SkyMelloo-side functional change.

## 0.36.0 (from 0.35.0) · minor

Several menu/settings cleanups.

- Dungeon room mob highlight default color changed red -> orange (0xFFFFA500) - only affects fresh installs, doesn't touch an already-saved custom color.
- New "Delivery (all at once)" section at the top of the Settings screen's Dungeons tab - two buttons that set every LOCAL/PARTY/PARTY SM delivery setting on that tab (18 fields) to LOCAL or PARTY in one click instead of one at a time.
- Removed the Fun tab entirely - its one row (Spell enabled/color) was a pure duplicate of what's already configurable from the item-menu (Spells page). Added a new "Spell Color" page there (12-color palette, same colors as the settings screen's own dropdown) so nothing was lost by removing it, alongside the existing Switch Spell page which already covered enabled/disabled and spell type.

## 0.35.0 (from 0.34.0) · minor

Boss-room 3D viewer prototype, two asks from the website session.

- BossRoomScanner's scan now genuinely expands outward from the player instead of re-sweeping a fixed box every pass - a frontier of discovered-but-unread neighbor positions, re-sorted by distance to the player's CURRENT position every pass (so it follows the player around the room), capped at a 50-block radius and a bounded per-pass read budget. Never rescans a position (unchanged invariant).
- New BossRoomScanner#getAnchorOffset - running min-corner of every non-air block found so far, relative to the same per-encounter origin bossRoomBlocks already uses - sent as a new "bossRoomAnchor" {dx,dy,dz} field so the website can re-align different encounters of the SAME boss room onto one common frame (the room's static geometry is identical every time, only where the player happened to start scanning differs run to run).
- New "bossRoomPlayers" dungeonSync field - self plus every visible roster member's real 3D position (x,y,z,yaw,pitch), relative to that same origin, sent whenever BossRoomScanner is active - lets the website place a player avatar/marker in its existing three.js boss-room scene. New debug line (/sm debug bossroom) showing frontier size.

## 0.34.0 (from 0.33.10) · minor

Magical Power renamed to Accessory Power everywhere (Hypixel's own in-game rename), MP -> AP in every chat message, GUI label/tooltip, HUD text (Party HUD, the new PartyApBarHud, replacing PartyMpBarHud), and command output. "/sm getdata ... mp" -> "... ap". Internal identifiers renamed to match (SkyMellooApiClient#fetchAccessoryPower, DungeonReadiness#accessoryPowerTierLabel, etc.) - config field names and lang keys deliberately left alone to avoid resetting anyone's saved settings. dungeonAutoKickStat/dungeonAutoKickMaxStat now default to "AP" but still accept a legacy "MP" value from old configs (anything not "LEVEL" means AP). Message-template placeholders {mp}/{mptier}/{mpscore} still work in already-saved custom templates (same values as the new {ap}/{aptier}/{apscore}). SkyMellooApiClient now parses the website's new "accessoryPower" JSON field, falling back to the legacy "magicalPower" field name until the website's own rename ships (T41, filed for the website session). Synced dependency pin to 0.11.5.

## 0.33.10 (from 0.33.9) · patch

Dungeon current-room mob highlight now also catches Hypixel mobs disguised as non-Enemy entities (ArmorStand reskins etc.), not just real vanilla hostile mobs - HighlightManager#isDungeonMobEntity excludes only Players and decorative (marker) ArmorStands.

## 0.33.9 (from 0.33.8) · patch

T37 (filed by the website session) - new DungeonRunTracker#getRunEndReason ("completed"/"wiped"/"left"), set right before each runActive=false from real signals already tracked (finishRun() having genuinely run, isEntirePartyDead()), sent as DungeonSyncManager's new runEndReason field so the website's run-history can show why a run ended instead of just guessing from runActive going false. Also registers PresenceManager#setDungeonSyncEnabledSupplier (T38, MellooEssentials 0.11.4) from the dungeonSyncEnabled config field. Synced dependency pin to 0.11.4.

## 0.33.8 (from 0.33.7) · patch

Real bug - the same leading-^ anchor issue from 0.33.7 also broke PUZZLE_SOLVED_PATTERN/PUZZLE_FAIL_PATTERN (confirmed on a real "PUZZLE SOLVED!" line, which is why the Score HUD wrongly showed "solved on retry" for a normal single-attempt solve - that's a fallback path for when chat detection genuinely misses, which it did here). Also relaxed the same way as a preventive measure on the same-shaped BOSS_CHAT_PATTERN, WATCHER_PATTERN, WATCHER_CLEARED_PATTERN, BLOOD_DOOR_OPENED_PATTERN, WITHER_KEY_PICKED_GENERIC_PATTERN, and READY_PATTERN, switching their matches() calls to find() where needed.

## 0.33.7 (from 0.33.6) · patch

Two real bugs from a live report.

- DOOR_OPENED_PATTERN was anchored to the start of the chat line, but a chat-icon mod (chat_heads) can prepend "[Name head]" before the real name, so a genuinely opened Wither/Blood door never registered - anchor removed, find() now lands on whichever name directly precedes "opened a ... door!".
- The Dungeon Debug HUD's "Blood key obtained" line was hardcoded to always show red until the Blood Room was entered, based on a wrong assumption that Hypixel sends no real pickup message - it does, already tracked via BLOOD_KEY_OBTAINED_PATTERN but never read by the HUD. New DungeonRunTracker#isBloodKeyObtained getter, HUD now reads it instead.

## 0.33.6 (from 0.33.5) · patch

Real bug - right after switching to a genuinely different room, Skyblocker's own room match can briefly still point at the PREVIOUS room, so the old room's mobs kept highlighting. getCurrentRoomSegments' result is now verified to actually contain the current physical room position before being trusted.

## 0.33.5 (from 0.33.4) · patch

Real improvement - the flood-fill from 0.33.2 only merges cells once already revealed on the map, so a multi-cell room the player hasn't fully walked into yet still split. New SkyblockerBridge#getCurrentRoomSegments reads Skyblocker's own shape/door match (Room#getSegments) when installed - correct even for a partially-explored room - and now takes priority over the flood-fill the moment it's ready, same reset/recheck pattern as the existing type-confirmation flag.

## 0.33.4 (from 0.33.3) · patch

Comment cleanup - trimmed several long comments down to 1-2 lines. No functional change. Synced dependency pin to 0.11.3.

## 0.33.3 (from 0.33.2) · patch

Real bug, root-caused by the website session inspecting an actual stored dungeon replay - the presence report interval was 2s (see MellooEssentials 0.11.2) while DungeonSyncManager's position-history send window assumed 1s, leaving a real ~1s gap with zero recorded samples between every pair of consecutive reports, throughout every run. HISTORY_SEND_ WINDOW_MS widened 1000ms -> 2000ms (0.5s overlap padded onto both edges of the now-real 1s interval) and HISTORY_RETAIN_MS 2000ms -> 3000ms to match. Also fixed two stale doc comments pointing at ModPresenceManager#reportSelf (moved to MellooEssentials' PresenceManager during the presence consolidation) and the wrong 1s/5s interval figures. Synced dependency pin to 0.11.2.

## 0.33.2 (from 0.33.1) · patch

Real bug - multi-cell dungeon rooms (1x2/2x1/2x2/L-shaped) got treated as several separate rooms for the current-room mob highlight, since room tracking only ever reasoned about the single 32x32 grid cell the player physically stands in. New findConnectedRoomCells flood-fills through adjacent same-room-type cells (capped at 4, the largest known real room shape) once per room entry, and getCurrentRoomBounds now unions all of them instead of just the one cell.

## 0.33.1 (from 0.33.0) · patch

Real report - the Dungeon Debug HUD's "Run failed (you died)" line fires the instant the local player dies, even while the rest of the party is still alive and could still finish the floor (already tracked as its own flag, separate from bossRoomCleared/etc., specifically because that can happen - only the wording was misleading). Reworded to "You died (party may still finish)" - "Run failed" is reserved for an actual confirmed full party wipe now.

## 0.33.0 (from 0.32.11) · minor

"/sm roll" moved to MellooEssentials' "/mes roll" (never actually SkyBlock-specific) - removed here, help text points to /mes roll instead. Fixed a real bug: the Magic Missile "needs a linked account" message told the user to run the no-longer-existing "/skymelloo verify" (verification moved to MellooEssentials' /mes verify a while back) - a linked account following that stale instruction would silently fail on an unknown command. Trimmed the unrelated "Cosmetics don't need this" aside too. Synced dependency pin to 0.11.0.

## 0.32.11 (from 0.32.10) · patch

Swapped in the banner's no-subtitle version. No functional change.

## 0.32.10 (from 0.32.9) · patch

Swapped in an updated banner image. No functional change.

## 0.32.9 (from 0.32.8) · patch

README banner image (.github/banner.png). No functional change.

## 0.32.8 (from 0.32.7) · patch

README polish - badge row (license/Minecraft/loader/website), and a "Requires MellooEssentials" note that was missing entirely. No functional change.

## 0.32.7 (from 0.32.6) · patch

Three real reports at once.

- Lunar Client's own resource-pack download cache can still have a locked file at the exact moment ResourcePackFailureToastMixin tries to wipe it (the just-failed pack's zip handle hasn't released yet) - new LunarPackCacheCleaner now retries the wipe once more 3s later, not just a single best-effort pass.
- WhitelistManager now tracks the account's actual role label (not just an isAdmin bool) via checkIsAdmin's new roleLabel field, feeding MellooEssentials 0.9.3's "Connected as <Role>" HUD text.
- Synced dependency pin to 0.9.3.

## 0.32.6 (from 0.32.5) · patch

Removed the "auto-decline Hypixel resource packs" feature entirely (ResourcePackAutoDeclineMixin, ResourcePackStatus, the config toggle) - a real report that it never actually worked, even set manually via Edit Server, not just via the mixin. Strong suspicion: Hypixel's pack is server-marked "required", which no client-side preference (mixin or manual) can ever override - that's an intentional, unbypassable Minecraft protocol guarantee, not something fixable without much more invasive protocol spoofing this project won't do. Per direct instruction: remove rather than leave a toggle that does nothing. ResourcePackFailureToastMixin (the unrelated pack-load-crash recovery toast) is untouched, still fully in place.

## 0.32.5 (from 0.32.4) · patch

Auto-Reconnect's delay before rejoining raised from 5s to 12s - a real report of constant disconnect/reconnect cycling with Auto-Reconnect enabled, most likely our own reconnect attempt racing/conflicting with Hypixel's own internal server-to-server transfers (island/dungeon/lobby hops), which can briefly look like a disconnect and complete on their own within a few seconds. Not a 100% confirmed root cause without live logs - flagged as a reasoned mitigation, report back if it's still happening after this.

## 0.32.4 (from 0.32.3) · patch

Real bugfix - registers into MellooEssentials' new setSkyMellooInstalled so the mod-user marker's pink-vs-light-blue distinction works again (see MellooEssentials 0.9.2's own changelog entry for the root cause). Synced dependency pin to 0.9.2.

## 0.32.3 (from 0.32.2) · patch

Real bugfix - the sky.melloo.me ping shown on MellooEssentials' Connection Status HUD had silently shown "--" since the v1 API migration (0.30.6), since ping() hit /health unauthenticated but v1's /health requires mod auth (unlike the old internal route). SkyMellooPingMonitor now signs the request. Also removed "/sm poll" entirely (command + backing vote-tally logic) - roll is unaffected.

## 0.32.2 (from 0.32.1) · patch

Comment cleanup - trimmed several long doc comments down to 1-2 lines. No functional change. Synced mellooessentials_version pin to 0.9.1.

## 0.32.1 (from 0.32.0) · patch

Simplified per follow-up - no longer requires the same sender to repeat the offer 3+ times before flagging it. Any trade-offer-shaped public chat message from a stranger is flagged immediately.

## 0.32.0 (from 0.31.0) · minor

New Anti-Scam Chat Filter (AntiScamFilter) - flags repeated trade-offer-shaped public chat messages from the same sender (lowball spam), detected by repetition rather than judging whether a price is actually low (needs live market data this mod doesn't have). Public chat only, never party/guild/whisper, never SkyMelloo Friends. Deliberately no link/phishing detection - Hypixel's own chat filter already blocks links in public chat. Lives here rather than MellooEssentials since trade/lowball scams are a SkyBlock-economy concept specifically, unlike the generic cross-gamemode features already consolidated there. Configurable in Settings (Chat section): on/off and hide-entirely vs warn-and-leave-visible.

## 0.31.0 (from 0.30.6) · minor

ModPresenceManager no longer runs its own report/query loop - it registers into MellooEssentials' PresenceManager extension points instead (status text, dungeonSync, the "magicMissile" cosmetic, and a dungeon-sync-received listener), same consolidation pattern STAFF/PARTY/Friends already went through. Fixes a real bug this uncovered: SkyMelloo's own richer presence report (status/dungeonSync/afk/accountLinked/location) was being silently overwritten by MellooEssentials' own separate, always-empty report on its next tick, since both hit the exact same server endpoint uncoordinated. AfkDetector and HypixelLocationTracker (both byte-for-byte duplicates of MellooEssentials' own) are deleted from this mod entirely - PartyHud/DungeonDebugHud now read MellooEssentials' copies directly. SkyMellooApiClient's own now-dead reportPresence/queryPresence/PresenceEntry removed (repo-wide grep confirmed zero remaining callers before deleting).

## 0.30.6 (from 0.30.5) · patch

Migrated every SkyMellooApiClient call from the internal /api/mod/* path to the versioned public /api/public/mod/v1/* surface (see DEVELOPER_API.md) - the internal path is now official-mod-only. Signing unchanged (still Ed25519), only the base URL and signed path prefix moved. /credits stays on the old base URL (no v1 equivalent, shared with the website's own credits page). No behavior/response-shape change otherwise.

## 0.30.5 (from 0.30.4) · patch

I18n sweep follow-up - 2 more German lang-file values found during merge/cross-check (settings preview-drag hint, empty-value placeholder) fixed to English, and a keybind-capture lang value corrected to not double up its own "[ ]" bracket wrapping.

## 0.30.4 (from 0.30.3) · patch

Full i18n sweep (T24) - every hardcoded player-facing string (chat, command feedback, GUI labels/tooltips/titles, toasts) now routes through en_us.json translation keys instead of Component.literal/raw strings. Also fixed 3 hardcoded German strings found along the way (fishing highscore message, mob-highlighting on/off, Save/Cancel buttons) - English-only source text restored, translatable per this same change. No other functional change.

## 0.30.3 (from 0.30.2) · patch

Dead-code sweep (T16) - removed 3 confirmed-unused public methods (ModVersionManager#isCompatible, ActionBarTracker#getLastUpdateMillis + its now-fully-orphaned backing field, DungeonRunTracker#getBloodRoomEnteredMillis), each verified with a repo-wide grep before removal, not just within-file. Same-named classes shared with MellooEssentials (ChatUtil/PartyTracker/etc.) were checked and confirmed intentionally divergent, not duplicates - left untouched. No functional change.

## 0.30.2 (from 0.30.1) · patch

README Building section trimmed further - removed maintainer-only release-process details (admin panel promotion, reportBuild/uploadJar, tokens) that don't matter to someone building the mod themselves. No functional change.

## 0.30.1 (from 0.30.0) · patch

Comment/README cleanup pass - trimmed long comments that narrated implementation history/past bugs down to 1-2 lines of current behavior, and shortened the README's Build section. No functional change.

## 0.30.0 (from 0.29.1) · minor

Friend Highlighting moved to MellooEssentials, completing the player-highlighting consolidation staff/party already went through - this mod's own PARTY tab lost the "Player Highlighting"/"SkyMelloo Friend Color"/"Player Glow Outline" rows (now in MellooEssentials' General tab instead) and its highlight.HighlightManager lost the PlayerCategory/classifyPlayer machinery entirely; /sm search is the only player highlight this mod still decides on its own. Removed the now-dead playerHighlightEnabled/partyHighlightColor/ friendHighlightColor/playerGlowOutlineEnabled config fields (partyHighlightColor was already unreferenced dead weight left over from the earlier PARTY move, not newly orphaned by this one). Real bugfix found along the way: MissileHitInvisibilityMixin's "Show Invisible Players" reveal was silently gated behind BOTH showInvisiblePlayersEnabled AND the now-removed playerHighlightEnabled - its own doc comment and settings tooltip only ever documented the first, so the second was either a leftover coupling or a real bug; now gated on showInvisiblePlayersEnabled alone, matching its own documented behavior.

## 0.29.1 (from 0.29.0) · patch

"/skymelloo verify <code>" moved to MellooEssentials as "/me verify <code>" - the server-side check was already mod-agnostic, so this was a pure client-side command move; SkyMelloo hard-depends on MellooEssentials anyway, no reason to keep a duplicate. Help text now points at the new command. Also fixed leftover German text in the unlink/link commands ("Fehlgeschlagen" -> "Failed", "Account getrennt" -> "Account unlinked").

## 0.29.0 (from 0.28.0) · minor

Dropped the YACL dependency entirely. It was only ever providing (de)serialization for SkyMellooConfig and a secondary Mod Menu config screen - the real in-game settings screen (key H via MellooEssentials) has been fully custom for a long time and never used YACL's own generated UI. SkyMellooConfig is now plain Gson-persisted (same pattern as MellooEssentials' own EssentialsConfig), keeping the exact same HANDLER.instance()/.save()/.load() API shape so none of the ~30 files calling it needed to change, and the same skymelloo.json5 file path/format so existing installs keep their settings (verified directly: Gson's default lenient Reader parsing handles the real JSON5-with-comments-and-unquoted-keys file YACL wrote just fine, not just a guess). ModMenuIntegration now opens the same custom SkyMellooSettingsScreen instead of YACL's generated one (that screen gained an optional parent-screen constructor + a real onClose() for this, so its "back" button correctly returns to Mod Menu's mod list, not straight to the game world like every other way of opening it). Removed ~110 now-dead yacl3.config.* translation keys from en_us.json (leftover from before cosmetics moved to MellooEssentials).

## 0.28.0 (from 0.27.4) · minor

Took over full ownership of party glow/marking AND the party block/kick system by MellooEssentials (same treatment STAFF got in 0.27.0) - this mod's own PARTY highlight branch, BlockedUsersManager, and PartyJoinWatcher's kick-queue/join-notification are all deleted; /sm block and /sm unblock are gone, redirected to /me block and /me unblock. The low-HP party blink is preserved via a new partyBlinkOverride(uuid, normalColor) hook registered into MellooEssentials' HighlightManager.setPartyBlinkColorOverride at init, instead of duplicating a whole second glow-decision system. PartyJoinWatcher now keeps only its 6 threshold-based auto-kick rules, calling MellooEssentials' PartyKickQueue.queueKick instead of its own deleted queue. mellooessentials_version dependency pin bumped to 0.5.0 to match. Also fixed several leftover German user-facing strings (party-sync feedback, a few command usage messages, the stats-fetch error prefix) missed by the 0.27.2 English-only pass.

## 0.27.4 (from 0.27.3) · minor

The HUD layout editor (key J) moved to MellooEssentials entirely - deleted this mod's own HudLayoutEditorScreen and its J-keybind. New SkyMellooHudElements#build supplies this mod's own HUD elements (Fishing Combo, Party, Party MP Bar, Dungeon Score, Dungeon Debug, Health/Mana Bars) to essentials' editor via its new setExtraElementsProvider/setExtraSaveHandler extension points, registered once at init.

## 0.27.3 (from 0.27.2) · patch

Real bugfix - Skyblocker was wrongly listed as a required dependency on MellooEssentials' manifest (that mod has no Skyblocker integration at all). It's now declared here instead, where it actually belongs (see SkyblockerBridge). Also synced the local mellooessentials_version dependency pin to 0.4.4.

## 0.27.2 (from 0.27.1) · patch

Translated every remaining German user-facing string to English (the whole /skymelloo help output, a few chat-feedback lines, a resource-pack-error toast, and the HTTP-timeout error message) - all in-game text is meant to be English only, no exceptions.

## 0.27.1 (from 0.27.0) · patch

Real bug fix - removed the mod-user marker's setSpriteOverride hook (registered in onInitializeClient) entirely. It only ever saw this client's own SkyMelloo presence data, not the server's, so a real MellooEssentials-only player always showed pink to a SkyMelloo client (both mods report presence to the same endpoint - "is a mod user" can't tell them apart). MellooEssentials now resolves pink-vs-light-blue itself from a proper server-provided signal - see its own 0.4.2 changelog entry.

## 0.27.0 (from 0.26.2) · minor

Synced MellooEssentials 0.4.0. Retired this mod's own duplicate STAFF glow highlighting (HighlightManager's STAFF branch + ModPresenceManager's role tracking) - it was racing MellooEssentials' own independent staff-glow mixin for the same vanilla methods with no defined winner, which is what made staff highlighting look broken. Party/Friend highlighting are unchanged (still this mod's own, including the low-HP party blink MellooEssentials doesn't have). The entire Friends system (friend list, relay chat, the Social menu/key G) and the "encountered staff" tracker/"/sm hitstaff" command moved to MellooEssentials entirely (now "/me friend"/"/me chat"/"/me hitstaff") - removed from this mod, not kept as a duplicate. HighlightManager's FRIEND check now reads MellooEssentials' moved FriendsManager instead of this mod's own (deleted).

## 0.26.2 (from 0.26.1) · patch

Synced MellooEssentials 0.3.1 - the sky.melloo.me ping reading this mod feeds into essentials' redesigned 2-line ConnectionStatusHud (setExtraLineProvider) no longer includes the "sky.melloo.me" prefix itself, matching that HUD's new combined detail line.

## 0.26.1 (from 0.26.0) · patch

Renamed the "cosmetics" account-link permission key to "spell" - it only ever gated the Magic Missile spell/kill-announce feature (MellooEssentials' own particle cosmetics need no account link at all since the split), so the old key name and its chat message ("Cosmetics need a linked account...") were actively misleading once that happened. Message reworded to name the Spell specifically and clarify cosmetics don't need this.

## 0.26.0 (from 0.25.2) · minor

Fixed a real bug found this session - both mods authenticating the same Minecraft account concurrently silently stole each other's server-side session (fixed server-side in lib/modAuth.js, no mod change needed for that part, but explains why "Connection failed" showed here while MellooEssentials worked fine). Reversed the H-key deferral from 0.25.2 - this mod's own H binding is now unbound by default; MellooEssentials' H always wins, one settings screen for both mods, reachable from here via the "SkyMelloo Config" button. This mod's own WhitelistStatusHud and PlayerInfoHud (both byte-for-byte duplicates of MellooEssentials' own) are deleted - MellooEssentials' single copies of each are now the only ones, with this mod's admin-link badge and sky.melloo.me ping reading fed into MellooEssentials' status HUD via its new extension points instead of a second HUD box. HUD layout editor updated to match (one "Connection Status" and one "Player Info" draggable instead of two of each).

## 0.25.2 (from 0.25.1) · patch

The HUD layout editor (key J) now also covers MellooEssentials' own HUD elements (Player Info, the new connection-status HUD) - it's the only positioning UI either mod has. The "sky.melloo.me Status" HUD gained a ticking "for Xh Ym Zs" connected-duration line. Also: registered a SkyMelloo-Config-screen opener with MellooEssentials' SettingsScreen (its own H keybind now defers to SkyMelloo's when both are installed, since they collided on the same key).

## 0.25.1 (from 0.25.0) · patch

The H-menu's Cosmetics link no longer opens a duplicate cosmetics UI of its own - it now opens MellooEssentials' own settings screen directly (straight to its Cosmetics tab, via the new SettingsScreen(parent, openToCosmetics) constructor), and the whole CosmeticDef/CosmeticsListPage/CosmeticDetailPage/ColorPickerPage/FavoriteColorPickerPage block in SkyMellooMenuScreen is deleted - one cosmetics UI instead of two reading/writing the same data.

## 0.25.0 (from 0.24.10) · minor

MellooEssentials (renamed/promoted from the internal hypixel-essentials prototype) is now a hard dependency (see fabric.mod.json's depends block). Particle cosmetics (CosmeticsRenderer/ParticleKind) and the byte-identical utility classes (HypixelDetector/RollingStats/ServerPingMonitor/FpsMonitor/TpsEstimator) are deleted from SkyMelloo entirely - it now reads/ticks essentials' copies instead of duplicating them. PlayerInfoHud stays (a genuine superset, not a duplicate), just repointed to essentials' data. The particle-cosmetics account-link gate is dropped (cosmetics are free once essentials is installed) - the "cosmetics" permission itself stays, now gating only Magic Missile/the FUN tab. The H-menu's Cosmetics page stays as a second GUI on essentials' own config (not deleted), so SkyMelloo's own menu remains the single UI. The nametag mod-user marker moved to essentials (light-blue default for any essentials user) with a new override hook (ModMarkerManager) that SkyMelloo uses to upgrade it to pink for confirmed SkyMelloo users - AccountLinkedMarkerManager is gone, replaced by that hook registered once at startup.

## 0.24.1 (from 0.24.0) · patch

Renamed the whole old internal naming scheme away entirely - every package/class/config field/keybind/lang key describing this feature now reads "highlight" instead of the old abbreviated term (e.g. the manager class, the block renderer, `partyHighlightColor`, `itemHighlightEnabled`, the toggle keybind). Purely a naming change, no behavior difference - except that existing players' saved values for these specific renamed fields reset to default once on this update, since the saved settings file keys them by name.

## 0.24.0 (from 0.23.0) · minor

Two new features. "/sm search <name>" (tab-completed from the current tab list) highlights that player green (LobbySearchManager) - lobby-only, since SkyBlock already has its own party/staff/friend highlighting; auto-clears on entering SkyBlock or leaving Hypixel entirely. Chat messages that mention your own username (whole-word, case-insensitive) now get bolded with a colored marker plus a short bell sound (ChatMentionHighlighter, via Fabric API's ClientReceiveMessageEvents.MODIFY_GAME) - both configurable (toggle + color) under Party ("Lobby Player Search") and General ("Chat") respectively.

## 0.23.0 (from 0.22.0) · minor

The Cloud Sync conflict screen now only ever asks once per device (cloudSyncConflictResolved, a new local-only config field excluded from the push/pull payload) - after either choice, every later launch is back to plain "whichever side is newer" comparison within a small tolerance, never asking again. Its own screen text is now English and shows each side's saved time as a caption above the button instead of inside the label. Fixed a real bug in Auto-Reconnect: it read the current server from the DISCONNECT event itself, which the client had frequently already cleared by the time that event fired, silently skipping every reconnect regardless of how the disconnect happened - now cached continuously every tick while connected instead. Added a Cosmetics master switch under General - off hides the Cosmetics tab in both the settings screen and the SkyMelloo Menu item, and stops rendering any cosmetic effect at all, yours or anyone else's.

## 0.22.0 (from 0.21.0) · minor

Reworked Cloud Sync again after a data-protection concern - re-added the explicit opt-in toggle (cloudSyncEnabled, off by default) alongside account linking, since linking alone isn't real consent to sync settings data. Sync direction is now timestamp-based (this device's settings-file mtime vs. the cloud copy's own updatedAt, both now returned by GET /api/mod/settings) instead of always preferring one side. When both a local file and a cloud copy genuinely exist, it no longer guesses at all - a new CloudSyncChoiceScreen pops up showing exactly when each was last saved and lets the player pick Local or Cloud explicitly.

## 0.21.0 (from 0.20.0) · minor

Cloud Sync is now automatic once the account is linked, no separate toggle anymore. Once per launch it checks link status directly, pulls whatever's already saved in the cloud if anything is, or - if nothing's been saved yet - pushes this device's current settings up so whichever device links first doesn't lose what it already had. From then on it behaves as before: push on every settings-screen close, pull on the next launch/device. Removed the old "skip if this device already has a local config" safety check - account-linking is now the only gate that matters.

## 0.20.0 (from 0.19.1) · minor

The mod is now Hypixel-only in general - the whole tick loop (party/friends/cloud sync/dungeons/cosmetics/whitelist+version+permission checks/etc.) is gated behind the new HypixelDetector.isHypixel(client) (extracted from PartyTracker's own internal check) and simply doesn't run at all when connected to any other server. The one exception is the new StaffEncounterTracker, which keeps scanning the tab list everywhere, specifically so real SkyMelloo staff/owner members are still recognized wherever encountered. New command "/sm hitstaff" lists every staff/owner member you've ever been seen alongside, most recently seen first (server-side log, see the website's new lib/staffEncounters.js and the two new /api/mod/staff-encounters routes).

## 0.19.1 (from 0.19.0) · patch

Real bugfix - the highlight manager's target-check method (used for the colored nametag/distance-display path, separate from shouldGlow's outline path) was missing the same SkyBlock-only restriction shouldGlow already got, so a real vanilla-invisible party/staff member could still be revealed on non-SkyBlock game modes. Now gated the same way in both places.

## 0.19.0 (from 0.18.0) · minor

Chest Highlight and Item Highlighting no longer show through walls - both now require an actual clear line of sight (real block raycast, see the new VisibilityUtil#hasLineOfSight, gated in the highlight manager's shouldGlow), so a chest/item is only highlighted once the marker is actually in view. Party/staff player highlighting is now scoped to SkyBlock only (previously applied on any Hypixel game mode). Removed the last of the dead per-feature permission gating (PermissionsManager.has() is a no-op returning true for everything except "cosmetics") - every remaining call site that still wrapped a feature behind it now runs unconditionally. The maintainer name shown in a couple of chat messages (/sm legal failure, unofficial-build notice) is now fetched live from the server instead of hardcoded.

## 0.18.0 (from 0.17.6) · minor

2026-07-30: real new feature, not a bugfix - new "/sm link" command, the mirror image of "/skymelloo verify <code>". Instead of generating a code on the website and typing it in-game, this generates a token in-game and opens sky.melloo.me/link/<token> directly in the system browser, where it completes automatically using whatever Discord session is already there (or prompts a fresh login first) - no code to type at all. New server endpoints: POST /api/mod/link/start, GET /api/account/link/token/:token, POST /api/account/link/token/:token/complete (see website's new lib/modLink.js).

## 0.17.6 (from 0.17.5) · patch

2026-07-29: "/sm info" and "/sm version" merged into one "/sm version" command that always fires a fresh check against the server instead of just showing the cached join-time result - shows the real latest published version and, if you're behind it, tells you to get it from sky.melloo.me. Also: SocialMenuScreen's private button-widget class was extracted into a new shared SkyMellooButtonWidget (com.melloo.skymelloo.client.gui) so every screen can use the same pink-glow custom button - the Settings screen's Report a Bug button (added in 0.17.4) was accidentally the one place still using vanilla Minecraft's grey button.

## 0.17.5 (from 0.17.4) · patch

2026-07-29: new keybind (default K, rebindable) opens the main SkyMelloo Menu item's screen directly (Credits/Spells/Cosmetics/Report a Bug nav row) - previously only reachable by right-clicking the fake hotbar item, no keybind at all.

## 0.17.4 (from 0.17.3) · patch

2026-07-29: "Report a Bug" (opens sky.melloo.me/report-bug in the system browser) is now also a button in the Settings screen (SkyMellooSettingsScreen, opened via key H) - it previously only lived in the Social menu and the main SkyMelloo Menu item's nav row, missing from the single most-used entry point into the mod's UI.

## 0.17.3 (from 0.17.2) · patch

2026-07-29: real bugfix - many "/sm" subcommands that are pure namespaces with children ("getdata", "getdata player", "getdata party", "debug", "partyjoin", "unblock", "chat") had no .executes() of their own, so running them bare fell through to Brigadier's raw usage-syntax dump instead of a SkyMelloo-branded message. All now show a proper "§cBenutzung: ..." line, matching the pattern "/sm roll"/"/sm poll" already used. Also rewrote "/sm help" (SkyMellooClient#sendHelp), which was missing an entire batch of commands added over many versions since it was last touched (friend, block/unblock, chat, roll, poll, view, info/version) and still described "/sm contact" by its old hardcoded-Discord-line behavior instead of the website link it now sends (see 0.17.2).

## 0.17.2 (from 0.17.1) · patch

2026-07-29: two real bugfixes. "/sm contact" now sends a clickable link to sky.melloo.me/contact (via the existing legalLink helper) instead of a hardcoded plain "Contact: hexedmaya on Discord" line. And every genuinely user-facing chat error ("/sm verify", "/sm unlink", friend request send/accept/decline/remove, the dungeon-party-join stats fetch) no longer leaks "java.lang.RuntimeException: <message>" - CompletionException(cause)'s own getMessage() is cause.toString(), which prepends the wrapped exception's class name. ChatUtil's existing CompletionException-unwrapping (previously only used by the stats-lookup errorMessage() helper) is now exposed as a public friendlyError(Throwable) and applied at every one of those sites.

## 0.17.1 (from 0.17.0) · patch

2026-07-30: Minecraft Brand and Asset Usage Guidelines compliance pass - README.md and fabric.mod.json's description now carry the required "not an official Minecraft product, not affiliated with Mojang/Microsoft/Hypixel" disclaimer, and the in-game Credits page (SkyMellooMenuScreen) gained a static, non-clickable "About SkyMelloo" entry with the same disclaimer - the one place in the actual mod UI a player would see it, alongside "who made this". No behavior change otherwise.

## 0.17.0 (from 0.16.0) · minor

2026-07-29: Social menu redesign - friends/party members/requests/invites now show a real player-face icon next to their name (new RemoteFaceTextureCache, fetched over HTTP from mc-heads.net by UUID so it works regardless of whether that player is anywhere nearby, unlike PartyHud's local tab-list skin blit). Wider layout to fit the icons. Also added a "Report a Bug" button/entry, always present, to both the Social menu and the main SkyMelloo Menu item's nav row - opens sky.melloo.me/report-bug directly in the system browser.

## 0.16.0 (from 0.15.1) · minor

2026-07-29: real new feature - a personal, client-side block list for party members (BlockedUsersManager), completely separate from the SkyMelloo Friends system and never synced to sky.melloo.me. "/sm block <name>"/"/sm unblock <name>" manage it, and blocking someone auto-kicks them from any party you lead the moment they join (immediately if they're already in your party when you block them). Any new party member joining now also gets a plain "X joined your party [Kick] [Block]" chat prompt (PartyJoinWatcher#handlePartyMemberJoined), independent of the existing Dungeon Party Finder stats/auto-kick message - and the Social menu's Party column gained a Block button next to Kick, with a Blocked section under Friends to unblock.

## 0.15.1 (from 0.15.0) · patch

2026-07-28: "/sm legal" now tells a self-built/test build clearly why it refuses instead of a generic error, stating plainly that it isn't an official version so legal info isn't shown. Website side: Imprint/Privacy/Terms pages now show a disclaimer at the top explaining that since SkyMelloo is open source, these legal terms only cover OFFICIAL builds downloaded from sky.melloo.me or the official GitHub repo, not self-built/third-party copies.

## 0.15.0 (from 0.14.1) · minor

2026-07-28: "/sm trust" removed entirely, and "/sm version"/"/sm info" dropped their buildKind "official"/"unofficial" framing - a self-reported build-hash check can never actually prove anything to anyone but yourself (a modified client can just lie about which hash it reports), so displaying it as a trust/security signal was giving a false sense of security rather than a real one. Both commands are now plain informational (version numbers, up-to-date status) plus a reminder that SkyMelloo only ever comes from sky.melloo.me/download or the official GitHub repo - that's the actual mitigation (distribution control), not an in-game trust check.

## 0.14.1 (from 0.14.0) · patch

2026-07-28: real correction, not just wording - "/sm trust" no longer posts any chat message with a clickable link at all - it opens the verify-build page directly in the system browser instead. Client commands never actually broadcast to other players in the first place (FabricClientCommandSource#sendFeedback is local-only), but this removes any ambiguity - there's no chat message to misread as more visible than it was.

## 0.14.0 (from 0.13.0) · minor

2026-07-28: new "/sm trust" command - sends a clickable link to sky.melloo.me/verify-build?hash=<this build's own class hash>, a public page anyone can open (not just the player themselves) that checks it against the same integrity check the mod already does and shows a plain "official"/"not official" verdict.

## 0.13.0 (from 0.12.0) · minor

2026-07-28: "/sm legal" moved fully server-side and out of the (now public/open-source) mod source - it's now a live GET /mod/legal call, gated by the same build-verification the integrity check already does, so a build that can't be verified as official/dev doesn't get to represent itself as legally covered by the maintainer's own imprint/privacy/terms. "/sm version" now also shows the server-verified official/unofficial build label (from the cached once-per-join version-check), matching "/sm info".

## 0.12.0 (from 0.11.0) · minor

2026-07-28: major behavioral change, now that the mod is open source - the entire admin-whitelist + per-feature-permissions gating system is GONE: WhitelistManager/ PermissionsManager no longer make the mod (or any feature) inert for anyone - every feature works for everyone. Build-integrity verification is unaffected and still exists, but its consequence changed too: an unverified build (unsigned, including a legitimate personal "-Pprivate" build - see build.gradle/scripts/build.js) now gets a one-time informational chat notice on join instead of having every feature hard-disabled. Website side: /api/mod/check/:uuid, /api/admin/whitelist*, /api/admin/permissions/defaults all removed; /api/mod/permissions now only reports account-link status (still required for cosmetics specifically, a technical necessity, not an admin-configurable permission).

## 0.11.0 (from 0.10.1) · minor

2026-07-28: real new wire-protocol addition - the presence report now includes a "location" field (which world/island/dungeon floor, from Hypixel's own Mod API location event) alongside the existing cosmetics/status/afk/dungeonSync, so the website's friends list can show what a friend is actually doing instead of just "online". Relabeled "Presence Sharing" to "Sync" in the settings screen too, presented as a lightweight "general sync" peer to the already-separate Dungeon Sync toggle - same underlying presenceSharingEnabled field, not renamed/split at the config level since dungeonSyncEnabled was already its own independent switch.

## 0.10.1 (from 0.10.0) · patch

2026-07-28: real addition that was accidentally built/deployed under the 0.10.0 hash without a version bump first (the standing "always bump" rule was missed for one build) - SkyMelloo Friends (see FriendsManager) now get their own aqua/light-blue glow color (PlayerCategory.FRIEND) alongside the existing party/staff highlights.

## 0.10.0 (from 0.9.0) · minor

2026-07-27: massive simplification, not a small change - general-purpose highlighting-of-everything is removed and rebuilt from scratch. The fully customizable old system (mob name filters/friendly-mob toggle/default+named mob colors, self/friend/other-player/NPC player colors, auto-sync-for-friends) is gone entirely, replaced with exactly three fixed, semantic highlights: party members (green), SkyMelloo staff - owner/admin/developer (pink, works everywhere now, not dungeon-restricted), and hostile mobs in your CURRENT dungeon room only (red, inherently dungeon-only). Regular other players and NPCs get no highlight at all anymore. FriendListSync.java deleted outright (only ever fed the now-removed Friend highlighting). Permission keys renamed away from the old abbreviated terminology too, to plainly describe what they gate instead (also renamed on sky.melloo.me's side - lib/permissions.js, the admin Permissions page).

## 0.9.0 (from 0.8.2) · minor

2026-07-27: real new feature - a new "Show Invisible Players" toggle (Party tab, off by default) makes another player's REAL vanilla invisibility (Invisibility Potion) not apply to how this client renders them, showing their nametag too. Deliberately NOT a glow-outline highlight effect, just plain visibility - overrides Entity#isInvisible() itself (MissileHitInvisibilityMixin, alongside its existing missile-hit fake-invisibility check, kept in the same method so their priority is explicit) so they render as a completely normal, visible player - still blocked by walls/line-of-sight like anyone else.

## 0.8.2 (from 0.8.1) · patch

2026-07-27: real regression fix - the live dungeon map's player marker had gone back to visibly snapping instead of gliding smoothly. Root cause: the position-history report cadence had drifted to a 1s "drain only what's new since last report" send, so a single delayed/dropped report left a genuine gap the website's render pointer could fall into. Restored the original working design: reports now go out every 0.5s (ModPresenceManager#REPORT_INTERVAL_TICKS) and each one resends the last 1s of samples (DungeonSyncManager#HISTORY_SEND_WINDOW_MS), overlapping with the previous report instead of only sending the delta - the server/website already dedupe by exact sample timestamp, so the overlap is free.

## 0.8.1 (from 0.8.0) · patch

2026-07-27: Spell Essence's on/off toggle is gone, it's always dropped on a Spell kill now (still requires "cosmetics" permission, same as before) - magicMissileEssenceEnabled removed from config entirely.

## 0.8.0 (from 0.7.1) · minor

2026-07-27: big real-feature/removal batch, not a bugfix - the old highlight-everything category is removed entirely, several sync/debug/HUD settings switch to OFF by default, the spell cosmetic moves to a menu button, kill tracker becomes always-local, death double is removed, and death recap moves into the Dungeons tab. Specifically:

- The old highlight-everything tab is gone entirely, including HP Armor Stand highlighting (hpDisplayEnabled/hpArmorStandColor removed, not relocated - EntityDisplayNameMixin/the old highlight manager no longer touch ArmorStand at all).
- New defaults (existing installs keep whatever they already had saved, only fresh installs see this): Connection Quality Check, Cloud Sync, Presence Sharing, and both Health/Mana HUD bars all default OFF now instead of ON. Debug tab toggles and Auto-Reconnect were already OFF by default.
- Casting the Spell cosmetic no longer happens by punching empty air - it's now the "Cast Spell" button in the SkyMelloo Menu item's Spells page (SkyMellooMenuScreen), which manually swings the caster's arm so remote SkyMelloo users can still see it via RemoteMissileTriggerMixin.
- Kill Tracker's on/off toggle and delivery choice are gone - always on, always LOCAL, hardcoded at MagicMissileManager#announceMissileKill instead of read from config.
- Death Double and Death Double on Spell Kill are gone entirely (combat/DeathDoubleManager.java deleted, all 3 mixins that referenced it updated).
- Death Recap (+ Party Announce) moved from the Fun tab into Dungeons, same "killTracker" permission gate as before.

## 0.7.1 (from 0.7.0) · patch

2026-07-27: Ore highlighting removed entirely, not just reordered - its config fields, settings-screen rows, the old block renderer's ore-marker tracking, and its permission key are all gone from both the mod and sky.melloo.me. Chest Highlight is unaffected and keeps using the same Block Scan Range setting on its own now.

## 0.7.0 (from 0.6.4) · minor

2026-07-27: real feature, not a bugfix - settings-screen reorg: Chest/Item/Mob highlighting all moved into the Dungeons tab and renamed ("Chest Highlight"/"Item Highlighting"/"Mob Highlighting"), Player highlighting moved into a brand new Party tab (Tab.ITEMS removed, now empty). Party-highlighted SkyMelloo users now default to pink, with a new separate gold color for players whose linked account is the owner/an admin/a developer (resolved server-side via /api/presence/query's new role field - never self-reported, see ModPresenceManager#isAdminOrOwner). Player highlighting also got its own permission, split off from Mob highlighting's own (previously granting one silently granted the other). Wire-protocol change: PresenceEntry gained a role field, so this is a minor bump, not a patch.

## 0.6.4 (from 0.6.3) · patch

2026-07-27: new "/sm legal" command sends clickable links to the website's Imprint/Privacy/Terms pages in chat - German data-protection law wants the mod itself to make these reachable, not just buried in the website footer.

## 0.6.3 (from 0.6.2) · patch

2026-07-27: build pipeline now also uploads this build's own jar to sky.melloo.me automatically (new uploadJar Gradle task, scripts/upload-jar.js) - a Dev build is already "Trusted" (passes integrity) the moment it's registered, but previously had no jar sitting on the server for the admin to actually download and test, only whatever was last uploaded by hand.

## 0.6.2 (from 0.6.1) · patch

2026-07-27: fabric.mod.json's description no longer leads with the old highlight-everything terminology - that whole approach was being phased out (planned removal, repurposed into cosmetic effects and dungeon-mob related tooling instead) so it shouldn't be the headline feature description anymore. No behavior change, metadata only.

## 0.6.1 (from 0.6.0) · patch

2026-07-27: auto-kick `/party kick` commands are now queued and spaced ~1.1s apart instead of fired instantly - Hypixel's own per-command cooldown was silently swallowing every kick after the first whenever two checks fired close together (screenshot: "Command Failed: This command is on cooldown!"). A failed kick (cooldown message detected) is requeued and retried after another full interval, not dropped. See PartyJoinWatcher.java.

## 0.6.0 (from 0.5.6) · minor

Same day: real new feature, not a bugfix - tracks dungeon completion, not just the current catacombs level. A new independent "Floor Completion" Auto-Kick pair (min + max, mirroring the existing Floor Requirement pair) checks SummaryResult#highestFloor() - Hypixel's own REAL completion record - as opposed to the existing Floor Requirement check, which only ever verified current level-ELIGIBILITY, never actual past completion. Each of MP/Level, Floor Requirement, and Floor Completion (min and max each) is its own independent toggle with its own threshold/message/ delivery, same established pattern - a party can enable any combination.

## 0.5.6 (from 0.5.5) · patch

Same day: "/sm debug bossroom" showed "Queued, not yet sent: 0" which only proves BossRoomScanner drained its LOCAL pending list into a report payload - never that the HTTP request carrying it actually reached the server (drainPendingJson doesn't put anything back on a failed send, so a failed report loses that batch silently either way). ModPresenceManager now tracks attempts/successes/failures specifically for reports that had ≥1 boss-room block, plus the last error message, and /sm debug bossroom now shows a distinct successful-sent count alongside the existing sent count.

## 0.5.5 (from 0.5.4) · patch

Same day: removed the "Prefer Skyblocker Score" toggle entirely, Skyblocker's live score is now ALWAYS used when installed (unconditional), not opt-in. Our own calculateScore() estimate remains only as the fallback when Skyblocker genuinely isn't installed, and as the sole source of the Skill/Explore/Speed/Bonus breakdown (Skyblocker doesn't expose those individually) - NOT deleted outright, since removing it entirely would break both of those. Also removed the "[Skyblocker]" tag from the Score HUD line - redundant now that it's always the source when available.

## 0.5.4 (from 0.5.3) · patch

Same day: real bugfix - the linked-account dye icon rendered with the wrong colour. The icon component itself had no explicit style, only its (normally unused) fallback glyph did, so a preceding colour code in the name (e.g. a Hypixel rank prefix) visibly bled into the icon's render. Both the icon and the connecting space now get an explicit style of their own, breaking that inheritance regardless of exactly which rendering path let it through.

## 0.5.3 (from 0.5.2) · patch

Same day: real bugfix - the linked-account marker never showed on the local player's own nametag. AccountLinkedMarkerManager gated on ModPresenceManager#isAccountLinked(uuid), which only ever tracks OTHER nearby players reporting in via presence - it never contains the LOCAL player's own uuid, so your own nametag (visible to yourself in third person) could never show the pink-dye marker even with a linked account, regardless of it already showing correctly to OTHER SkyMelloo users looking at you. Now uses PermissionsManager#isAccountLinked() (a direct self-status check, no presence system involved) specifically when the entity being named is the local player.

## 0.5.2 (from 0.5.1) · patch

Same day: real bugfix - the live/recorded score was frozen at exactly 20+0+100+0=120 for ENTIRE runs regardless of real progress, across every tracked run/floor. Root cause not yet fixed (needs a live report to see which piece is actually wrong - see the new debugScoreInfo()/DungeonTabList#getAllLines() doc comments for the full reasoning) - this ships the DIAGNOSTICS to find out: SkyblockerBridge#ensureChecked's failure path now actually logs why (was completely silent before), and a new "/sm debug score" command dumps every input calculateScore() uses plus a scan of the WHOLE reconstructed tab list for "Completed Rooms" (not just the fixed line index 43 the real formula reads), so the next report shows exactly whether that index is wrong, empty, or Skyblocker's own score just isn't being used. Also fixed a smaller, confirmed website bug alongside this: the Blood Room tile/badge showed a confusing "Not yet" even for runs that never got anywhere near it - now hidden entirely until actually reached, matching how the Boss Room tile already worked.

## 0.5.1 (from 0.5.0) · patch

Same day: the boss-room 3D prototype failed silently with nothing in the log to diagnose from - added DebugLog output to BossRoomScanner (start/stop, periodic new-block counts) and a new "/sm debug bossroom" command (active state, origin, scanId, positions checked/queued) so the next attempt is actually diagnosable instead of silently failing. Also sends more of what the local Dungeon Debug/Score/Party HUDs already track to the website: per-door wither state (not just the aggregate count), the local-death/party-wipe "run failed" flags, and client fps + Hypixel ping (real server TPS has no reliable client-visible source, so that's deliberately NOT included - see DungeonSyncManager's own comment on why).

## 0.5.0 (from 0.4.4) · minor

Same day: real new feature, first prototype - the mod now scans real world blocks while in a boss room (the dungeon map item doesn't cover boss rooms at all, Hypixel swaps it for a Nether Star on entry) and delta-encodes them (BossRoomScanner, only ever sends a given block position once per encounter) into the dungeonSync report. The website accumulates this into a real navigable 3D voxel scene (Three.js, WASD+mouselook, polls for updates) at /dungeon/:username/boss-room - a first prototype of a navigable 3D environment for boss rooms. Blocks render as flat colours (Minecraft's own MapColor id/RGB values, decompiled from the game's own code - NOT real textures, deliberately avoiding bundling/redistributing Mojang's copyrighted texture files).

## 0.4.4 (from 0.4.3) · patch

Same day: real bugfix - the "hurry or S+ may no longer be possible!" time-limit checkpoint warnings fired purely off the floor's raw nominal par time, completely ignoring the run's REAL S+ margin (getExtraSecondsForSPlus) - confirmed from a real report: S+ was already comfortably secured (Skill/Explore/Bonus alone nearly covered 300) and the mod warned anyway at 60/30/15/10s remaining on the floor's own clock, directly contradicting its own Score HUD "extra time" display at the same moment. Checkpoints now gate on the real S+ margin instead, and a sudden drop in that margin (e.g. right after a puzzle fail) only announces the single most urgent checkpoint crossed instead of spamming one message per checkpoint.

## 0.4.3 (from 0.4.2) · patch

Same day: mirrored the existing "damage trail" animation for GAINS too. A heal/mana-regen now shows the full new amount instantly as a red highlight block, while the real green/blue fill animates up to meet it at the same speed the loss-trail already fades down at (HealthManaBarsHud#risingHealthFraction/risingManaFraction, mirroring displayedHealthFraction/displayedManaFraction). /sm debug hm-bar reports this too.

## 0.4.2 (from 0.4.1) · patch

Same day: renamed "/sm debug mana" to "/sm debug hm-bar" and expanded its output to cover the whole pipeline, not just the raw actionbar segments - every parsed cur/max fraction labeled by position (health=0/mana=1), and the actual on-screen bar fill state (px filled out of the 120px bar, source used, absorption overflow, white "just lost" trail) read via new shared HealthManaBarsHud#computeHealthBarState/computeManaBarState methods, which the real renderer was refactored to call too - so the debug output can never drift from what's actually drawn.

## 0.4.1 (from 0.4.0) · patch

Same day: small addition + a real bugfix - Score HUD now shows how many points are needed for the next grade (DungeonRunTracker#nextGrade, SkyMellooConfig#dungeonScoreShowNextGrade), and puzzle rooms solved on a retry (Hypixel doesn't always re-send "PUZZLE SOLVED!" for those - confirmed missing from a real log) no longer get stuck showing FAILED forever on the Score HUD/Run Report - SkyblockerBridge#getCurrentRoomClearState now reads the real dungeon-map checkmark icon as a fallback completion signal (DungeonRunTracker# checkPuzzleClearedViaSkyblocker, ticked from DungeonRoomTracker). Doesn't touch the permanent Skill-score fail penalty, which stays correct/unchanged - only the displayed outcome is corrected.

## 0.4.0 (from 0.3.1) · minor

Same day: real features - presence/dungeonSync report interval is now fixed at 1s (removed the "Sync Interval (ticks)" setting entirely, see SkyMellooConfig/ModPresenceManager's own comments on why a fixed interval fixed a real live/replay sync bug on the website side too), and death events now record a position + death number (DungeonRunTracker#DeathMarker) for the website's new death "X" markers on both the live map and replay.

## 0.3.1 (from 0.3.0) · patch

Same day: cleanup only - removed the now-dead warn-only integrity branch in ModVersionManager now that the server actually hard-blocks an unverified build again (see server.js's matching comment). The enforcement itself is server-side and was already live against 0.3.0; this just removes unreachable code and clarifies the log/chat message.

## 0.3.0 (from 0.2.3) · minor

Same day: real feature, not a bugfix - the mod now samples its exact dungeon position every client tick (20/s) and bundles the whole buffer into each presence report instead of one point per report, so the website can draw a real recorded path instead of smoothing/guessing between sparse points (see DungeonSyncManager#sampleTick, the server's lib/presence.js#mergePositionHistory accumulation, and app.js's markerHistory rendering).

## 0.2.3 (from 0.2.2) · patch

Same day: "Sync Interval (ticks)" in the Cloud tab was an intStepRow (4-100 in steps of 4 - up to 24 clicks to reach the high end), switched to intTextRow (click to type a value directly), matching the pattern other wide-range int settings already use.

## 0.2.2 (from 0.2.1) · patch

Same day: fixed a real health-bar bug (ActionBarTracker matched component.getString() instead of its own already-correct segment text, corrupting the health number - confirmed from a live "62901/2526" report), extended "Run failed" to also cover a full party wipe (not just the local player's own death), and fixed the website's room-merge/movement-smoothing issues (no mod-side change needed for those).

## 0.2.1 (from 0.2.0) · patch

For two dungeon-tracker bugfixes: a "Run failed" debug HUD line for the local-death case, and a fallback that sends the end-of-run report when Hypixel's "Click HERE to re-queue" line never shows up (confirmed missing from a real log even after a genuine floor completion).

## 0.2.0 (from 0.1.1) · minor

For the ephemeral-keypair + per-request-signing mod-auth rewrite - a full wire-protocol replacement of ModAuthManager/SkyMellooApiClient's auth handshake, not a small bugfix.
