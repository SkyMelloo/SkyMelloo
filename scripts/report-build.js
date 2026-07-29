#!/usr/bin/env node
// Reports this build's version + jar hash to sky.melloo.me for admin-visible build tracking - run
// automatically by Gradle's "reportBuild" task right after every build (2026-07-26), so builds and
// their versions can be managed from the admin panel.
//
// Purely informational/for admin visibility - does NOT grant runtime trust by itself. The hash
// computed here is a plain SHA-256 of the built jar file, which is NOT necessarily what Lunar
// Client's own loader reports at runtime (see ModVersionManager's own doc comment on that mismatch,
// confirmed directly from a live report). Actually trusting a build for the integrity check still
// needs the mod's own real runtime-reported hash, registered separately via /api/mod/dev-whitelist
// or /api/mod/releases.
//
// Never fails the build itself - a network hiccup or a missing local token file here is logged and
// swallowed, not fatal.
const crypto = require('crypto');
const fs = require('fs');
const https = require('https');
const os = require('os');
const path = require('path');

const [, , version, jarPath] = process.argv;
if (!version || !jarPath) {
  console.error('Usage: node report-build.js <version> <path-to-jar>');
  process.exit(0); // non-fatal even here - never block the actual build over this script's own usage
}

// Lives OUTSIDE this repo on purpose, alongside the release-signing private key - never checked into
// or deployed with either the mod or website repo. See sign-release.js's own doc comment for the
// same convention.
const TOKEN_FILE = path.join(os.homedir(), '.skymelloo-signing', 'build_report_token.txt');

let token;
try {
  token = fs.readFileSync(TOKEN_FILE, 'utf8').trim();
} catch {
  console.warn(`[report-build] No token file at ${TOKEN_FILE} - skipping build report (non-fatal).`);
  process.exit(0);
}

let jarBytes;
try {
  jarBytes = fs.readFileSync(jarPath);
} catch (e) {
  console.warn(`[report-build] Could not read jar at ${jarPath} - skipping build report (non-fatal): ${e.message}`);
  process.exit(0);
}

const hash = crypto.createHash('sha256').update(jarBytes).digest('hex');
const body = JSON.stringify({ version, hash, builtAt: Date.now() });

const req = https.request(
  'https://sky.melloo.me/api/mod/build-report',
  {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Content-Length': Buffer.byteLength(body),
      'X-Build-Report-Token': token,
    },
  },
  (res) => {
    let data = '';
    res.on('data', (chunk) => {
      data += chunk;
    });
    res.on('end', () => {
      if (res.statusCode === 200) {
        console.log(`[report-build] Reported build ${version} (${hash}) to sky.melloo.me.`);
      } else {
        console.warn(`[report-build] Server rejected the report (${res.statusCode}): ${data}`);
      }
    });
  }
);
req.on('error', (err) => {
  console.warn(`[report-build] Request failed (non-fatal): ${err.message}`);
});
req.write(body);
req.end();
