# SkyMelloo

A [Fabric](https://fabricmc.net/) client mod for Hypixel SkyBlock, paired with a companion website
at [sky.melloo.me](https://sky.melloo.me). Dungeon tracking, party tools, cosmetics, and
highlighting, plus a live/replay dungeon map, friends system, and account dashboard on the website.

Not an official Minecraft product. Not approved by or associated with Mojang, Microsoft, or
Hypixel Inc.

## Features

- **Dungeon tracking** - live Score HUD (Skill/Explore/Speed/Bonus, matching Skyblocker's own
  calculation when it's installed), death recap, kill tracker, room/secret tracking, and a
  boss-room 3D preview.
- **Party tools** - party HUD with per-member stats, auto-kick rules (level/floor requirement,
  floor completion, AFK), join watcher.
- **Highlighting** - party members, SkyMelloo staff, SkyMelloo Friends, and current-room dungeon
  mobs each get their own fixed color. No general-purpose ESP.
- **Cosmetics** - spell effects, kill announcements, and other visual extras shared with nearby
  players also running the mod.
- **Sync** - reports your online status, current world/dungeon floor, and (opt-in, separately)
  detailed live dungeon-run data to sky.melloo.me, so party members and friends can see what
  you're doing and the website can render a live/replay dungeon map.
- **SkyMelloo Friends** - a mod-native friends list (separate from Hypixel's own), with relay
  chat and status.

## Download

The official, signed build is only ever distributed from **[sky.melloo.me/download](https://sky.melloo.me/download)**.
If you got a jar from anywhere else, it isn't an official release - see Building below to make
your own from this source instead.

## Building

Requires JDK 25.

```
node scripts/build.js
```

Three tiers:
- **test** - anyone, zero requirements. Never signed/registered; sky.melloo.me always shows it as
  unofficial (still fully functional, just a one-time chat notice on join).
- **dev** - a real signed+registered build. Requires the maintainer's own private key, which isn't
  in this repo and never will be - the build refuses outright if you pick this without it, rather
  than silently completing a build that never actually registered.
- **public release** - not a build-time concept at all. An existing "dev" build gets manually
  promoted via the sky.melloo.me admin panel; nothing you run locally produces one.

`node scripts/build.js` asks which of the first two you want and handles the Gradle flags. Prefer
raw Gradle? That still works:

```
./gradlew build -PtestBuild=true                # test build, no key or changelog needed
./gradlew build -PchangelogFile=path.txt   # dev build, needs the signing key + a changelog
```

The build also runs a couple of automated tasks (`reportBuild`, `uploadJar`) that talk to
sky.melloo.me for release tracking - these fail silently/non-fatally if you don't have the
maintainer's own tokens (which live outside this repo entirely and are never distributed with it).
You're free to share a test build with others too, by the way - this is AGPL-3.0, same as the rest
of the project.

## License

[GNU Affero General Public License v3.0](LICENSE). Copyright (C) 2026 Maja Bekurdts (hexedmaya).

## Contact

maja@melloo.me
