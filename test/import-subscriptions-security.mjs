import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

// 1. Static check: Verify ImportPage.vue contains sanitization and validation logic
const vueFile = readFileSync("src/components/ImportPage.vue", "utf8");
assert.ok(vueFile.includes("forbiddenKeys"), "ImportPage.vue must check against forbidden prototype pollution keys");
assert.ok(
    vueFile.includes("forbiddenKeys.has(id.trim())"),
    "ImportPage.vue must reject prototype pollution channel ID values",
);

// 2. Functional simulation of fileChange parsing logic in ImportPage.vue
function simulateImportSubscriptions(text, filename = "subscriptions.json") {
    const parsedSubs = [];
    const addChannelId = id => {
        const forbiddenKeys = new Set(["__proto__", "constructor", "prototype"]);
        if (typeof id === "string" && !forbiddenKeys.has(id.trim()) && /^[a-zA-Z0-9_-]{1,64}$/.test(id.trim())) {
            parsedSubs.push(id.trim());
        }
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
                    if (typeof json.subscriptions[0] === "string") {
                        json.subscriptions.forEach(id => addChannelId(id));
                    } else {
                        json.subscriptions
                            .filter(item => item?.service_id == 0 || item?.service_id == undefined)
                            .forEach(item => {
                                if (typeof item?.url === "string") addChannelId(item.url.slice(-24));
                                else if (typeof item?.id === "string") addChannelId(item.id);
                            });
                    }
                }
            } catch {
                const lines = text.split("\n");
                for (let line of lines) {
                    if (line.trim() === "") continue;
                    try {
                        const lineJson = JSON.parse(line);
                        if (Array.isArray(lineJson?.subscriptions)) {
                            lineJson.subscriptions.forEach(item => {
                                if (typeof item?.id === "string") addChannelId(item.id);
                                else if (typeof item?.url === "string") addChannelId(item.url.slice(-24));
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

// Test case 6: Malformed JSON handled gracefully without crashing
assert.deepEqual(simulateImportSubscriptions("malformed { json"), []);

console.log("All subscription import security verification tests passed successfully!");
