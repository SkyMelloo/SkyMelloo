# Translating SkyMelloo / MellooEssentials

Both mods use Minecraft's standard language-file system - each translatable piece of text has a key, and each language is a flat JSON file mapping keys to translated strings.

## Files that need translating

| Mod | English source (reference) |
|---|---|
| SkyMelloo | [`src/main/resources/assets/skymelloo/lang/en_us.json`](https://github.com/SkyMelloo/SkyMelloo/blob/main/src/main/resources/assets/skymelloo/lang/en_us.json) |
| MellooEssentials | [`src/client/resources/assets/mellooessentials/lang/en_us.json`](https://github.com/SkyMelloo/MellooEssentials/blob/main/src/client/resources/assets/mellooessentials/lang/en_us.json) |

To add a language, copy the English file to `<language_code>.json` in the same folder (e.g. `de_de.json` for German) and translate the values - never the keys. [Minecraft's language code list](https://minecraft.wiki/w/Language) has the exact codes the game expects.

## How to contribute

1. Fork the relevant repo.
2. Add or update a language file as above.
3. Open a pull request. Partial translations are fine - an incomplete file just falls back to English for whatever's missing.

## A note on completeness

Nearly all user-facing text is already routed through the language-file system. If you do find text that doesn't seem to come from a lang file (i.e. it never changes no matter what your Minecraft language is set to), please report it via [Report a Bug](https://sky.melloo.me/report-bug) rather than guessing at a fix - a hardcoded string needs a small code change (moving it into the lang file with a new key) before it can be translated at all.
