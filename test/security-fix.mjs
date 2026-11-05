import { readFileSync } from "node:fs";
import assert from "node:assert/strict";

const LOCKFILE = "pnpm-lock.yaml";
const PATCHED = "11.4.18";

const lockfile = readFileSync(LOCKFILE, "utf8");

// Collect every resolved @intlify/shared version in the lockfile.
const versions = new Set();
const re = /@intlify\/shared@(\d+\.\d+\.\d+)/g;
let m;
while ((m = re.exec(lockfile)) !== null) {
    versions.add(m[1]);
}

// The vulnerable 11.4.14 must no longer be a resolved version.
assert.ok(!versions.has("11.4.14"), "@intlify/shared 11.4.14 is still present");
// The patched version must be resolved.
assert.ok(versions.has(PATCHED), `@intlify/shared ${PATCHED} is not resolved`);

// vue-i18n must be bumped to a version that depends on patched shared.
const vueI18nRe = /^ {2}vue-i18n@([\d.]+):/m;
const vueI18nMatch = lockfile.match(vueI18nRe);
const vueI18nVersion = vueI18nMatch ? vueI18nMatch[1] : null;
assert.equal(vueI18nVersion, PATCHED, `vue-i18n is ${vueI18nVersion}, expected ${PATCHED}`);

console.log(`OK: @intlify/shared resolved versions: ${[...versions].sort().join(", ")}`);
console.log(`OK: vue-i18n locked to ${vueI18nVersion}`);
