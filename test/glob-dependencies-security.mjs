import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { mkdtempSync, mkdirSync, rmSync, readdir, readdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { setTimeout as delay } from "node:timers/promises";

const require = createRequire(import.meta.url);
const workboxRequire = createRequire(require.resolve("workbox-build", { paths: [require.resolve("vite-plugin-pwa")] }));
const globRequire = createRequire(workboxRequire.resolve("glob"));
const { glob, globSync } = workboxRequire("glob");
const minimatchConsumers = [
    ["workbox/glob", globRequire],
    ["eslint", createRequire(require.resolve("eslint"))],
];

// GHSA-3g3m-2fjr-6pw4. The first directory read completes after abort;
// glob must not schedule more traversal from that callback.
test("aborting a glob walk stops filesystem traversal", async () => {
    const root = mkdtempSync(join(tmpdir(), "piped-glob-abort-"));
    const controller = new AbortController();
    let reads = 0;
    const tree = (path, depth) => {
        if (!depth) return;
        for (let i = 0; i < 3; i++) {
            const child = join(path, String(i));
            mkdirSync(child);
            tree(child, depth - 1);
        }
    };
    tree(root, 5);
    try {
        await assert.rejects(
            glob("**/*", {
                cwd: root,
                signal: controller.signal,
                fs: {
                    readdir(path, options, callback) {
                        reads++;
                        readdir(path, options, (error, entries) => {
                            controller.abort(new Error("test cancellation"));
                            callback(error, entries);
                        });
                    },
                    readdirSync,
                },
            }),
            /test cancellation/,
        );
        // Let queued callbacks drain before counting or removing the fixture.
        await delay(100);
        assert.equal(reads, 1, "cancelled glob continued reading directories");
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
});

// GHSA-g3hv-75wg-m249. Use a small explicit budget, not a huge pattern
// or a timing assertion. Distinct path segments prevent deduplication from
// concealing the exponential preprocessing amplification in the old version.
for (const [name, consumer] of minimatchConsumers) {
    test(`${name} bounds globstar-parent pattern amplification`, () => {
        const { Minimatch } = consumer("minimatch");
        const pattern = Array.from({ length: 8 }, (_, i) => `**/../a${i}/b${i}`).join("/") + "/leaf";
        const matcher = new Minimatch(pattern, { optimizationLevel: 2, braceExpandMax: 32 });
        assert.ok(matcher.globParts.length <= 32, "pattern expansion exceeded the caller's budget");
        assert.equal(new Minimatch("**/*.js").match("src/main.js"), true);
        assert.equal(new Minimatch("**/*.js").match("src/main.vue"), false);
    });
}

test("workbox's synchronous glob still handles ignores and nested assets", () => {
    const root = mkdtempSync(join(tmpdir(), "piped-glob-assets-"));
    try {
        mkdirSync(join(root, "assets"));
        writeFileSync(join(root, "index.html"), "fixture");
        writeFileSync(join(root, "assets", "app.js"), "fixture");
        writeFileSync(join(root, "assets", "app.js.map"), "fixture");
        assert.deepEqual(
            globSync("**/*", {
                cwd: root,
                nodir: true,
                ignore: ["**/*.map"],
            }).sort(),
            ["assets/app.js", "index.html"],
        );
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
});
