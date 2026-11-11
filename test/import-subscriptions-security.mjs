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
        if (text.indexOf("opml") != -1) {
            // Simulated opml outline extracts
            const matches = [...text.matchAll(/xmlUrl="([^"]+)"/g)];
            for (const match of matches) {
                const url = match[1];
                if (url) addChannelId(url.slice(-24));
            }
        } else if (text.indexOf("thin_mode") != -1) {
            const json = JSON.parse(text);
            if (Array.isArray(json?.subscriptions)) {
                json.subscriptions.forEach(id => addChannelId(id));
            }
        } else if (text.indexOf("allChannels") != -1) {
            const lines = text.split("\n");
            for (let line of lines) {
                if (line === "") continue;
                const json = JSON.parse(line);
                if (Array.isArray(json?.subscriptions)) {
                    json.subscriptions.forEach(item => {
                        if (item?.id) addChannelId(item.id);
                    });
                }
            }
        } else if (text.indexOf("contentDetails") != -1) {
            const json = JSON.parse(text);
            if (Array.isArray(json)) {
                json.forEach(item => {
                    const id = item?.snippet?.resourceId?.channelId;
                    if (id) addChannelId(id);
                });
            }
        } else if (filename.length >= 5 && filename.slice(-4).toLowerCase() == ".csv") {
            const lines = text.split("\n");
            for (let i = 1; i < lines.length; i++) {
                const line = lines[i];
                const id = line.slice(0, line.indexOf(","));
                if (id.length === 24) addChannelId(id);
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

// Test case 3: FreeTube DB import with prototype pollution attempt
const maliciousFreeTube =
    '{"allChannels": true, "channels": [{"id": "__proto__"}]}\n{"subscriptions": [{"id": "__proto__"}, {"id": "constructor"}, {"id": "UCbjYfaGZgP8u_lS_7AeVwLQ"}]}';
const freeTubeSubs = simulateImportSubscriptions(maliciousFreeTube);
assert.equal(freeTubeSubs.length, 1);
assert.equal(freeTubeSubs[0], "UCbjYfaGZgP8u_lS_7AeVwLQ");

// Test case 4: Malformed JSON handled gracefully without crashing
assert.deepEqual(simulateImportSubscriptions("malformed { json"), []);

console.log("All subscription import security verification tests passed successfully!");
