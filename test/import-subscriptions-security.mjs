import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

// 1. Static check: Verify ImportPage.vue contains sanitization and validation logic
const vueFile = readFileSync("src/components/ImportPage.vue", "utf8");
const idsFile = readFileSync("src/utils/ImportIds.js", "utf8");
assert.ok(idsFile.includes("FORBIDDEN_KEYS"), "ImportIds.js must check against forbidden prototype pollution keys");
assert.ok(
    idsFile.includes("FORBIDDEN_KEYS.has(id.trim())"),
    "ImportIds.js must reject prototype pollution channel ID values",
);
assert.ok(
    vueFile.includes("isValidChannelId") && vueFile.includes("channelIdFromEntry"),
    "ImportPage.vue must use the shared validators",
);
const { isValidChannelId, channelIdFromEntry } = await import("../src/utils/ImportIds.js");

// 2. Functional simulation of fileChange parsing logic in ImportPage.vue
function simulateImportSubscriptions(text, filename = "subscriptions.json") {
    const parsedSubs = [];
    const addChannelId = id => {
        if (isValidChannelId(id)) parsedSubs.push(id.trim());
    };
    const addEntry = (entry, idFromUrl) => {
        const id = channelIdFromEntry(entry, idFromUrl);
        if (id !== null) parsedSubs.push(id);
    };

    try {
        if (filename.length >= 5 && filename.slice(-4).toLowerCase() == ".csv") {
            const lines = text.split("\n");
            for (let i = 1; i < lines.length; i++) {
                const line = lines[i];
                const id = line.slice(0, line.indexOf(","));
                if (id.length === 24) addChannelId(id);
            }
        } else if (text.includes("<opml") || text.includes("<outline")) {
            const matches = [...text.matchAll(/xmlUrl="([^"]+)"/g)];
            for (const match of matches) {
                const url = match[1];
                if (url) addChannelId(url.slice(-24));
            }
        } else {
            try {
                const json = JSON.parse(text);
                if (Array.isArray(json)) {
                    json.forEach(item => {
                        const id = item?.snippet?.resourceId?.channelId;
                        if (id) addChannelId(id);
                    });
                } else if (Array.isArray(json?.subscriptions)) {
                    json.subscriptions.forEach(item => {
                        if (typeof item === "string") {
                            addChannelId(item);
                        } else if (
                            item &&
                            typeof item === "object" &&
                            (item.service_id == 0 || item.service_id == undefined)
                        ) {
                            addEntry(item, url => url.replace(/\/+$/, "").slice(-24));
                        }
                    });
                }
            } catch {
                const lines = text.split("\n");
                for (let line of lines) {
                    if (line.trim() === "") continue;
                    try {
                        const lineJson = JSON.parse(line);
                        if (Array.isArray(lineJson?.subscriptions)) {
                            lineJson.subscriptions.forEach(item => {
                                addEntry(item, url => url.slice(-24));
                            });
                        }
                    } catch {
                        // ignore malformed lines
                    }
                }
            }
        }
    } catch {
        return [];
    }

    return parsedSubs;
}

// Test case 1: Valid Invidious JSON (thin_mode) import
const validThinMode = JSON.stringify({
    thin_mode: true,
    subscriptions: ["UC_x5XG1OV2P6uZZ5FSM9Ttw", "UCbjYfaGZgP8u_lS_7AeVwLQ"],
});
const validSubs = simulateImportSubscriptions(validThinMode);
assert.equal(validSubs.length, 2);
assert.equal(validSubs[0], "UC_x5XG1OV2P6uZZ5FSM9Ttw");
assert.equal(validSubs[1], "UCbjYfaGZgP8u_lS_7AeVwLQ");

// Test case 2: Prototype pollution attempts in thin_mode
const maliciousThinMode = JSON.stringify({
    thin_mode: true,
    subscriptions: [
        "__proto__",
        "constructor",
        "prototype",
        null,
        12345,
        "   ",
        "invalid channel with spaces",
        "<script>alert(1)</script>",
        "UC_x5XG1OV2P6uZZ5FSM9Ttw",
    ],
});
const sanitizedSubs = simulateImportSubscriptions(maliciousThinMode);
assert.equal(sanitizedSubs.length, 1);
assert.equal(sanitizedSubs[0], "UC_x5XG1OV2P6uZZ5FSM9Ttw");
assert.equal(Object.prototype.polluted, undefined, "Object.prototype must not be polluted");

// Test case 3: NewPipe export containing channel names with format markers ("thin_mode", "allChannels")
const newPipeWithMarkers = JSON.stringify({
    app_version: "0.27.0",
    app_version_int: 999,
    subscriptions: [
        {
            service_id: 0,
            url: "https://www.youtube.com/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw",
            name: "thin_mode",
        },
        {
            service_id: 0,
            url: "https://www.youtube.com/channel/UCbjYfaGZgP8u_lS_7AeVwLQ",
            name: "allChannels",
        },
        {
            service_id: 0,
            url: "https://www.youtube.com/channel/constructor", // prototype pollution test in URL
            name: "Malicious Channel",
        },
        {
            service_id: 1, // non-youtube service should be skipped
            url: "https://soundcloud.com/ignored",
            name: "SoundCloud",
        },
    ],
});
const newPipeSubs = simulateImportSubscriptions(newPipeWithMarkers);
assert.equal(newPipeSubs.length, 2, "Both NewPipe channels with marker names must be imported");
assert.equal(newPipeSubs[0], "UC_x5XG1OV2P6uZZ5FSM9Ttw");
assert.equal(newPipeSubs[1], "UCbjYfaGZgP8u_lS_7AeVwLQ");

// Test case 4: FreeTube DB (line-delimited JSON) import with prototype pollution attempt
const maliciousFreeTube =
    '{"_id":"settings","allChannels":true}\n{"_id":"subs","subscriptions":[{"id":"__proto__"},{"id":"constructor"},{"id":"UCbjYfaGZgP8u_lS_7AeVwLQ"}]}';
const freeTubeSubs = simulateImportSubscriptions(maliciousFreeTube);
assert.equal(freeTubeSubs.length, 1);
assert.equal(freeTubeSubs[0], "UCbjYfaGZgP8u_lS_7AeVwLQ");

// Test case 5: Google Takeout JSON import
const googleTakeoutJson = JSON.stringify([
    {
        snippet: {
            resourceId: {
                channelId: "UC_x5XG1OV2P6uZZ5FSM9Ttw",
            },
        },
    },
    {
        snippet: {
            resourceId: {
                channelId: "__proto__",
            },
        },
    },
]);
const takeoutSubs = simulateImportSubscriptions(googleTakeoutJson);
assert.equal(takeoutSubs.length, 1);
assert.equal(takeoutSubs[0], "UC_x5XG1OV2P6uZZ5FSM9Ttw");

// Test case 6: Invidious JSON with a null first entry still imports the valid channel IDs
const nullFirstInvidious = JSON.stringify({
    subscriptions: [null, "UC_x5XG1OV2P6uZZ5FSM9Ttw"],
});
const nullFirstSubs = simulateImportSubscriptions(nullFirstInvidious);
assert.equal(nullFirstSubs.length, 1, "A null first entry must not drop later valid channel ID strings");
assert.equal(nullFirstSubs[0], "UC_x5XG1OV2P6uZZ5FSM9Ttw");

// Test case 7: FreeTube JSON object with both id and a trailing-slash URL keeps the valid id
const freeTubeSuffixedUrl = JSON.stringify({
    allChannels: true,
    subscriptions: [
        {
            id: "UC_x5XG1OV2P6uZZ5FSM9Ttw",
            url: "https://www.youtube.com/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw/",
        },
    ],
});
const suffixedSubs = simulateImportSubscriptions(freeTubeSuffixedUrl);
assert.equal(suffixedSubs.length, 1, "A record with an id and a URL ending in / must keep the id");
assert.equal(suffixedSubs[0], "UC_x5XG1OV2P6uZZ5FSM9Ttw");

// Test case 8: Malformed JSON handled gracefully without crashing
assert.deepEqual(simulateImportSubscriptions("malformed { json"), []);

console.log("All subscription import security verification tests passed successfully!");

// Devin finding: an invalid explicit id must not hide a valid channel url.
const urlFallback = simulateImportSubscriptions(
    JSON.stringify({
        subscriptions: [
            { service_id: 0, id: "__proto__", url: "https://www.youtube.com/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw" },
            { service_id: 0, id: "bad id!", url: "https://www.youtube.com/channel/UCbjYfaGZgP8u_lS_7AeVwLQ/" },
            {
                service_id: 0,
                id: "UCvalidIdOnly_000000000AA",
                url: "https://www.youtube.com/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw",
            },
            { service_id: 0, id: "__proto__", url: "https://example.com/__proto__" },
            { service_id: 0, id: "__proto__" },
        ],
    }),
);
assert.deepEqual(urlFallback, ["UC_x5XG1OV2P6uZZ5FSM9Ttw", "UCbjYfaGZgP8u_lS_7AeVwLQ", "UCvalidIdOnly_000000000AA"]);
const freeTubeFallback = simulateImportSubscriptions(
    JSON.stringify({
        subscriptions: [{ id: "constructor", url: "https://www.youtube.com/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw" }],
    }) + "\n{bad",
);
assert.deepEqual(freeTubeFallback, ["UC_x5XG1OV2P6uZZ5FSM9Ttw"]);
assert.equal(
    channelIdFromEntry({ id: "__proto__", url: "x" }, () => "prototype"),
    null,
);
console.log("import-subscriptions-security: ok");
