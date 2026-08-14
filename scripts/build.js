#!/usr/bin/env node
// Interactive wrapper around gradlew - asks what kind of build instead of requiring
// -Pchangelog/-PtestBuild flags to already be known. Run with: node scripts/build.js
//
// test: anyone, no key needed, never signed/registered. dev: requires the maintainer's signing
// key, gets signed+registered on sky.melloo.me. Public releases are never built here - an
// existing dev build gets promoted via the website admin panel.
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
    console.log('  GitHub:  https://github.com/SkyMelloo/SkyMelloo\n');
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
