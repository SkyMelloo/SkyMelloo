#!/usr/bin/env node
// Friendly wrapper around gradlew (2026-07-28) - asks what kind of build this is instead of
// expecting -Pchangelog/-Ptest Gradle property syntax to already be known. Run with:
//   node scripts/build.js
//
// Three tiers ("dev etc nicht gebaut werden können weil die ja nen key und verifizierung
// brauchen... leute können nur test bauen dann brauchen sie gar nix und dev sowie public kann nur
// ich weil das per website geht"):
//   - test: anyone, zero requirements, never signed/registered, always shows as unofficial.
//   - dev:  a real signed+registered build - requires the maintainer's own private key. If you
//           don't have it, this script (and the Gradle build itself) refuses outright rather than
//           silently completing a build that never actually registered.
//   - public/official release: not something built here at all - an existing "dev" build gets
//           manually promoted via the sky.melloo.me admin panel, never via this script.
const { spawnSync } = require('child_process');
const readline = require('readline');
const fs = require('fs');
const os = require('os');
const path = require('path');

const SIGNING_KEY_PATH = path.join(os.homedir(), '.skymelloo-signing', 'private_key.pem');

const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
const ask = (question) => new Promise((resolve) => rl.question(question, resolve));

(async () => {
  console.log('SkyMelloo build helper\n');
  console.log('What kind of build?');
  console.log('  1) test - just for yourself, no key or verification needed');
  console.log('  2) dev  - registered as a trusted build on sky.melloo.me (requires the maintainer\'s signing key)');
  console.log('(public releases are never built here - an existing dev build gets promoted via the website admin panel)\n');
  const choice = (await ask('Choice [1]: ')).trim();
  const wantsDev = choice === '2';

  const gradlew = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
  const args = ['build'];

  if (!wantsDev) {
    args.push('-PtestBuild=true');
    console.log('\nBuilding a test build:');
    console.log('  - No changelog needed.');
    console.log('  - Never signed/registered - sky.melloo.me will always show it as unofficial (still fully functional).');
    console.log('\nQuestions about SkyMelloo? Reach hexedmaya:');
    console.log('  Discord: hexedmaya');
    console.log('  Email:   maja@melloo.me');
    console.log('  GitHub:  https://github.com/hexedmaya/SkyMelloo\n');
  } else {
    if (!fs.existsSync(SIGNING_KEY_PATH)) {
      console.error("\nCan't build \"dev\" - that requires the maintainer's own signing key, which isn't on this machine.");
      console.error('Run this again and choose "test" instead - that needs no key or verification at all.\n');
      rl.close();
      process.exit(1);
    }
    console.log('\nBuilding a dev release - this gets signed and registered with sky.melloo.me.');
    const changelog = await ask('Changelog for this build (Added:/Fixed:/Changed: style, end with a blank line):\n');
    const tmpFile = path.join(os.tmpdir(), `skymelloo-changelog-${Date.now()}.txt`);
    fs.writeFileSync(tmpFile, changelog);
    args.push(`-PchangelogFile=${tmpFile}`);
  }

  rl.close();
  console.log(`\nRunning: ${gradlew} ${args.join(' ')}\n`);
  const result = spawnSync(gradlew, args, { stdio: 'inherit', shell: true });
  process.exit(result.status == null ? 1 : result.status);
})();
