// Regenerates dashutils_expected.mpd from dashutils_input.json using the web
// client's OWN src/utils/DashUtils.js (the exact code the browser runs).
//
// Repo-root run (package.json is type:module, so ESM resolves):
//   npm install fast-xml-parser@4 --no-save
//   node clients/contracts/golden/generate-dash-golden.mjs
//
// The golden in this directory was generated 2026-11-18 with
// fast-xml-parser@4 by running the unmodified DashUtils.js under node.
import { readFileSync, writeFileSync } from 'node:fs';
import { generate_dash_file_from_formats } from '../../../src/utils/DashUtils.js';

const here = new URL('.', import.meta.url).pathname;
const formats = JSON.parse(readFileSync(here + 'dashutils_input.json', 'utf8'));
writeFileSync(here + 'dashutils_expected.mpd', generate_dash_file_from_formats(formats, 212));
console.log('wrote dashutils_expected.mpd');
