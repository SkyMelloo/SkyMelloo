# Security Policy

## Supported versions

Only the latest official public release (see [sky.melloo.me/download](https://sky.melloo.me/download))
and the current `main` branch are supported. Older releases and self-built/test versions are not.

## Reporting a vulnerability

**Please do not open a public GitHub issue for a security vulnerability.** A public issue discloses
the problem to everyone, including anyone who might exploit it, before a fix is ready.

Instead, report it privately through one of these:

- Preferred: the website's [Report a Bug](https://sky.melloo.me/report-bug) form, with the "This is
  a security issue" box checked - these reports are private (visible only to admins/moderators) and
  are prioritized over regular bug reports. Doesn't fit that form? See
  [Contact](https://sky.melloo.me/contact) for other ways to reach the developer.
- Urgent: Discord DM **HexedMaya** - the fastest way to reach me directly.
- Email also works - see [Contact](https://sky.melloo.me/contact) for the address - but can take
  longer to get a reply than Discord.

Please include:

- What the vulnerability is and where it lives (mod client, website, API endpoint, etc.).
- Steps to reproduce, or a proof of concept if you have one.
- The potential impact, as best you understand it.

### Not in scope

Testing how rate limits behave, or intentionally trying to overload an endpoint, is not in scope for
responsible disclosure under this policy - it's ordinary abuse, not a vulnerability report. A genuine
bypass (the rate limit doesn't actually work) is a real security issue and should be reported here as
normal.

## What happens next

Reports are acknowledged as soon as possible and treated as priority. Once a fix is confirmed, it's
shipped through the normal signed-release process (see the mod's build pipeline) and, for anything
affecting other users, a note is added to the changelog once it's safe to disclose.

## Scope

This covers the SkyMelloo mod (this repository), [MellooEssentials](https://github.com/SkyMelloo/MellooEssentials)
(the shared core mod SkyMelloo depends on - same policy, no separate copy), the sky.melloo.me
website/API, and the [developer-api](https://github.com/SkyMelloo/developer-api)/[api-client](https://github.com/SkyMelloo/api-client)
repos (same policy, no separate copy). Third-party dependencies (Fabric, Minecraft itself, Hypixel's
own systems) are out of scope - please report those to their respective maintainers.
