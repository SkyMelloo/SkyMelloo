#!/usr/bin/env node
// Uploads this build's actual jar file to sky.melloo.me right after every build - a Dev build
// already passes the integrity check, it's just not a public release,
// it just isn't the one everyone's required to run, so there was no reason its jar shouldn't also be
// sitting on the server for the admin to grab and test without a manual pscp step every time).
//
// Same shared build-report token as report-build.js/SignAndRegister.java, read from the same
// outside-the-repo file. Never fails the build itself - a network hiccup or missing token file here
// is logged and swallowed, not fatal.
const fs = require('fs');
const https = require('https');
const os = require('os');
const path = require('path');

const [, , version, jarPath] = process.argv;
if (!version || !jarPath) {
  console.error('Usage: node upload-jar.js <version> <path-to-jar>');
  process.exit(0);
}

const TOKEN_FILE = path.join(os.homedir(), '.skymelloo-signing', 'build_report_token.txt');

let token;
try {
  token = fs.readFileSync(TOKEN_FILE, 'utf8').trim();
} catch {
  console.warn(`[upload-jar] No token file at ${TOKEN_FILE} - skipping jar upload (non-fatal).`);
  process.exit(0);
}

let jarBytes;
try {
  jarBytes = fs.readFileSync(jarPath);
} catch (e) {
  console.warn(`[upload-jar] Could not read jar at ${jarPath} - skipping jar upload (non-fatal): ${e.message}`);
  process.exit(0);
}

const req = https.request(
  `https://sky.melloo.me/api/mod/releases/${encodeURIComponent(version)}/jar`,
  {
    method: 'POST',
    headers: {
      'Content-Type': 'application/java-archive',
      'Content-Length': jarBytes.length,
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
        console.log(`[upload-jar] Uploaded ${version}'s jar (${jarBytes.length} bytes) to sky.melloo.me.`);
      } else {
        console.warn(`[upload-jar] Server rejected the upload (${res.statusCode}): ${data}`);
      }
    });
  }
);
req.on('error', (err) => {
  console.warn(`[upload-jar] Request failed (non-fatal): ${err.message}`);
});
req.write(jarBytes);
req.end();
