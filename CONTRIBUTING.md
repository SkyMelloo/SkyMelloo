# Contributing to SkyMelloo

## Before you start

- Bug reports and feature requests: [Report a Bug](https://sky.melloo.me/report-bug) or GitHub Issues - not a PR.
- Security issues: see [SECURITY.md](SECURITY.md) - never a public issue or PR.
- Translations: see [TRANSLATING.md](TRANSLATING.md) - not a code PR.

## Code contributions

1. Fork and branch off `main`.
2. Build with `node scripts/build.js`, or plain Gradle with `-PtestBuild=true` (see [README](README.md#building)) - no signing key needed for a test build.
3. Keep comments short and about current behavior only, not a running commentary of past attempts - see the existing code for the style.
4. Open a PR against `main` with a clear description of what changed and why.

## License

By contributing, you agree your changes are licensed under this repo's [AGPL-3.0](LICENSE), same as the rest of the project.
