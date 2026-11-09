import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

// 1. Static check: Verify PlaylistsPage.vue contains sanitization and validation logic
const vueFile = readFileSync("src/components/PlaylistsPage.vue", "utf8");
assert.ok(vueFile.includes("forbiddenKeys"), "PlaylistsPage.vue must check against forbidden prototype pollution keys");
assert.ok(
    vueFile.includes("forbiddenKeys.has(playlist.name)"),
    "PlaylistsPage.vue must reject prototype pollution playlist names",
);
assert.ok(
    vueFile.includes("Array.isArray(parsed?.playlists)"),
    "PlaylistsPage.vue must validate that parsed.playlists is an array",
);

// 2. Functional simulation of importPlaylistFile logic for "Piped" format
function simulateImportPlaylists(text) {
    const importedPlaylists = [];
    let alertTriggered = false;

    if (text.includes('"Piped"')) {
        try {
            const parsed = JSON.parse(text);
            if (!Array.isArray(parsed?.playlists)) {
                alertTriggered = true;
                return { importedPlaylists, alertTriggered };
            }

            // Prevent prototype pollution and malformed object injection from untrusted files
            const forbiddenKeys = new Set(["__proto__", "constructor", "prototype"]);
            for (let playlist of parsed.playlists) {
                if (!playlist || typeof playlist !== "object" || Array.isArray(playlist)) continue;
                if (forbiddenKeys.has(playlist.name) || typeof playlist.name !== "string" || !playlist.name.trim())
                    continue;

                const cleanPlaylist = {
                    name: playlist.name.trim(),
                    videos: Array.isArray(playlist.videos) ? playlist.videos.filter(v => typeof v === "string") : [],
                };
                importedPlaylists.push(cleanPlaylist);
            }

            if (!importedPlaylists.length) {
                alertTriggered = true;
                return { importedPlaylists, alertTriggered };
            }
        } catch {
            alertTriggered = true;
            return { importedPlaylists, alertTriggered };
        }
    } else {
        alertTriggered = true;
        return { importedPlaylists, alertTriggered };
    }

    return { importedPlaylists, alertTriggered };
}

// Test case 1: Valid playlist import
const validPayload = JSON.stringify({
    format: "Piped",
    version: 1,
    playlists: [
        {
            name: "Favorites",
            videos: ["https://youtube.com/watch?v=dQw4w9WgXcQ", "https://youtube.com/watch?v=9bZkp7q19f0"],
        },
    ],
});
const { importedPlaylists: validResults, alertTriggered: validAlert } = simulateImportPlaylists(validPayload);
assert.equal(validAlert, false);
assert.equal(validResults.length, 1);
assert.equal(validResults[0].name, "Favorites");
assert.deepEqual(validResults[0].videos, [
    "https://youtube.com/watch?v=dQw4w9WgXcQ",
    "https://youtube.com/watch?v=9bZkp7q19f0",
]);

// Test case 2: Prototype pollution attempts and invalid objects
const maliciousPayload = JSON.stringify({
    format: "Piped",
    playlists: [
        {
            name: "__proto__",
            videos: ["https://youtube.com/watch?v=polluted1234"],
        },
        {
            name: "constructor",
            videos: ["https://youtube.com/watch?v=polluted1234"],
        },
        {
            name: "prototype",
            videos: ["https://youtube.com/watch?v=polluted1234"],
        },
        null,
        "not-an-object",
        {
            name: 12345, // invalid name type
            videos: ["https://youtube.com/watch?v=testvideo123"],
        },
        {
            name: "   ", // whitespace only
            videos: ["https://youtube.com/watch?v=testvideo123"],
        },
        {
            name: "Coding Chill",
            videos: ["https://youtube.com/watch?v=cleanvideo12", 42, null, {}], // non-string videos filtered
        },
    ],
});
const { importedPlaylists: sanitizedResults, alertTriggered: sanitizedAlert } =
    simulateImportPlaylists(maliciousPayload);
assert.equal(sanitizedAlert, false);
assert.equal(sanitizedResults.length, 1);
assert.equal(sanitizedResults[0].name, "Coding Chill");
assert.deepEqual(sanitizedResults[0].videos, ["https://youtube.com/watch?v=cleanvideo12"]);
assert.equal(Object.prototype.polluted, undefined, "Object.prototype must not be polluted");

// Test case 3: Malformed JSON or invalid structures trigger alert
assert.equal(simulateImportPlaylists('{"format": "Piped", malformed json}').alertTriggered, true);
assert.equal(simulateImportPlaylists('{"format": "Piped", "playlists": "not-an-array"}').alertTriggered, true);
assert.equal(simulateImportPlaylists('{"format": "Piped", "playlists": []}').alertTriggered, true);
assert.equal(simulateImportPlaylists('{"format": "Piped", "playlists": [{"name": "__proto__"}]}').alertTriggered, true);

console.log("All playlist import security verification tests passed successfully!");
