import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

// 1. Static check: Verify ImportHistoryModal.vue contains sanitization and validation logic
const vueFile = readFileSync("src/components/ImportHistoryModal.vue", "utf8");
assert.ok(
    vueFile.includes("forbiddenKeys"),
    "ImportHistoryModal.vue must check against forbidden prototype pollution keys",
);
assert.ok(
    vueFile.includes("forbiddenKeys.has(video.videoId)"),
    "ImportHistoryModal.vue must reject prototype pollution videoId values",
);
assert.ok(
    vueFile.includes("Array.isArray(json?.watchHistory)"),
    "ImportHistoryModal.vue must validate that json.watchHistory is an array",
);

// 2. Functional simulation of fileChange parsing logic
function simulateImportHistory(text) {
    let items = [];
    try {
        const json = JSON.parse(text);
        if (!Array.isArray(json?.watchHistory)) {
            return { items, error: false };
        }

        const forbiddenKeys = new Set(["__proto__", "constructor", "prototype"]);
        const parsed = [];
        for (const video of json.watchHistory) {
            if (!video || typeof video !== "object" || Array.isArray(video)) continue;
            if (forbiddenKeys.has(video.videoId) || typeof video.videoId !== "string" || !video.videoId.trim()) {
                continue;
            }

            parsed.push({
                videoId: video.videoId.trim(),
                title: typeof video.title === "string" ? video.title : "",
                duration: typeof video.duration === "number" ? video.duration : 0,
                thumbnail: typeof video.thumbnail === "string" ? video.thumbnail : "",
                uploaderUrl: typeof video.uploaderUrl === "string" ? video.uploaderUrl : "",
                uploaderName: typeof video.uploaderName === "string" ? video.uploaderName : "",
                watchedAt: typeof video.watchedAt === "number" ? video.watchedAt : 0,
                currentTime: typeof video.currentTime === "number" ? video.currentTime : 0,
            });
        }
        items = parsed.sort((a, b) => b.watchedAt - a.watchedAt);
        return { items, error: false };
    } catch {
        return { items: [], error: true };
    }
}

// Test case 1: Valid history payload import
const validPayload = JSON.stringify({
    format: "Piped",
    version: 1,
    watchHistory: [
        {
            videoId: "dQw4w9WgXcQ",
            title: "Never Gonna Give You Up",
            duration: 213,
            thumbnail: "https://example.com/thumb.jpg",
            uploaderUrl: "/channel/UCuAXFkgsw1L7xaCfnd5JJOw",
            uploaderName: "Rick Astley",
            watchedAt: 1700000000000,
            currentTime: 120,
        },
        {
            videoId: "9bZkp7q19f0",
            title: "Gangnam Style",
            duration: 252,
            thumbnail: "https://example.com/thumb2.jpg",
            uploaderUrl: "/channel/UCrDkAvwZum-UT44505WCoQg",
            uploaderName: "officialpsy",
            watchedAt: 1600000000000,
            currentTime: 60,
        },
    ],
});

const { items: validItems, error: validError } = simulateImportHistory(validPayload);
assert.equal(validError, false);
assert.equal(validItems.length, 2);
assert.equal(validItems[0].videoId, "dQw4w9WgXcQ");
assert.equal(validItems[0].title, "Never Gonna Give You Up");
assert.equal(validItems[1].videoId, "9bZkp7q19f0");

// Test case 2: Prototype pollution attempts and malformed objects
const maliciousPayload = JSON.stringify({
    format: "Piped",
    watchHistory: [
        {
            videoId: "__proto__",
            title: "Pollution attempt 1",
        },
        {
            videoId: "constructor",
            title: "Pollution attempt 2",
        },
        {
            videoId: "prototype",
            title: "Pollution attempt 3",
        },
        null,
        "not-an-object",
        [],
        {
            videoId: 12345, // invalid videoId type
            title: "Invalid ID type",
        },
        {
            videoId: "   ", // whitespace only
            title: "Whitespace ID",
        },
        {
            videoId: "safeVideoId123",
            title: "Clean Video",
            extraInjectedField: "should be ignored",
            duration: "not-a-number", // should default to 0
            watchedAt: 1710000000000,
            currentTime: 45,
        },
    ],
});

const { items: sanitizedItems, error: sanitizedError } = simulateImportHistory(maliciousPayload);
assert.equal(sanitizedError, false);
assert.equal(sanitizedItems.length, 1);
assert.equal(sanitizedItems[0].videoId, "safeVideoId123");
assert.equal(sanitizedItems[0].title, "Clean Video");
assert.equal(sanitizedItems[0].duration, 0);
assert.equal(sanitizedItems[0].extraInjectedField, undefined);
assert.equal(Object.prototype.polluted, undefined, "Object.prototype must not be polluted");

// Test case 3: Malformed JSON or invalid structures handle cleanly
assert.equal(simulateImportHistory("malformed json").error, true);
assert.equal(simulateImportHistory("malformed json").items.length, 0);
assert.equal(simulateImportHistory('{"format": "Piped"}').items.length, 0);
assert.equal(simulateImportHistory('{"watchHistory": "not-an-array"}').items.length, 0);
assert.equal(simulateImportHistory('{"watchHistory": []}').items.length, 0);

console.log("All watch history import security verification tests passed successfully!");
