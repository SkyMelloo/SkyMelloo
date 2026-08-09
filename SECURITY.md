# Security Policy

## Supported versions

Only the latest official public release (see [sky.melloo.me/download](https://sky.melloo.me/download))
and the current `main` branch are supported. Older releases and self-built/test versions are not.

## Reporting a vulnerability

**Please do not open a public GitHub issue for a security vulnerability.** A public issue discloses
the problem to everyone, including anyone who might exploit it, before a fix is ready.

Instead, report it privately through one of these:

- Email **maja@melloo.me** with details and, if possible, steps to reproduce.
- Discord: **HexedMaya**
- The website's [Report a Bug](https://sky.melloo.me/report-bug) form, with the "This is a security
  issue" box checked - these reports are private (visible only to admins/moderators) and are
  always triaged first, ahead of regular bug reports.

Please include:

- What the vulnerability is and where it lives (mod client, website, API endpoint, etc.).
- Steps to reproduce, or a proof of concept if you have one.
- The potential impact, as best you understand it.

## What happens next

Reports are acknowledged as soon as possible and treated as priority. Once a fix is confirmed, it's
shipped through the normal signed-release process (see the mod's build pipeline) and, for anything
affecting other users, a note is added to the changelog once it's safe to disclose.

## Scope

This covers the SkyMelloo mod (this repository), [MellooEssentials](https://github.com/SkyMelloo/MellooEssentials)
(the shared core mod SkyMelloo depends on - same policy, no separate copy), and the sky.melloo.me
website/API. Third-party dependencies (Fabric, Minecraft itself, Hypixel's own systems) are out of
scope - please report those to their respective maintainers.
