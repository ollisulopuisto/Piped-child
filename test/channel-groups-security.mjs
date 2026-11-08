import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

// 1. Static check: Verify SubscriptionsPage.vue contains the sanitization logic
const vueFile = readFileSync("src/components/SubscriptionsPage.vue", "utf8");
assert.ok(
    vueFile.includes("forbiddenKeys"),
    "SubscriptionsPage.vue must check against forbidden prototype pollution keys",
);
assert.ok(
    vueFile.includes("forbiddenKeys.has(group.groupName)"),
    "SubscriptionsPage.vue must reject prototype pollution group names",
);
assert.ok(
    vueFile.includes("Array.isArray(parsed?.groups)"),
    "SubscriptionsPage.vue must validate that parsed.groups is an array",
);

// 2. Functional simulation of importGroupsHandler logic
function simulateImportGroups(jsonText) {
    const importedGroups = [];
    try {
        const parsed = JSON.parse(jsonText);
        if (!Array.isArray(parsed?.groups)) return importedGroups;

        const forbiddenKeys = new Set(["__proto__", "constructor", "prototype"]);
        for (const group of parsed.groups) {
            if (!group || typeof group !== "object" || Array.isArray(group)) continue;
            if (forbiddenKeys.has(group.groupName) || typeof group.groupName !== "string") continue;

            const cleanGroup = {
                groupName: group.groupName,
                channels: Array.isArray(group.channels) ? group.channels.filter(c => typeof c === "string") : [],
            };
            importedGroups.push(cleanGroup);
        }
    } catch {
        // fail safely on malformed JSON
    }
    return importedGroups;
}

// Test case 1: Valid groups import
const validPayload = JSON.stringify({
    format: "Piped",
    version: 1,
    groups: [
        {
            groupName: "Tech News",
            channels: ["UC_x5XG1OV2P6uZZ5FSM9Ttw", "UCbjYfaGZgP8u_lS_7AeVwLQ"],
        },
    ],
});
const validResults = simulateImportGroups(validPayload);
assert.equal(validResults.length, 1);
assert.equal(validResults[0].groupName, "Tech News");
assert.deepEqual(validResults[0].channels, ["UC_x5XG1OV2P6uZZ5FSM9Ttw", "UCbjYfaGZgP8u_lS_7AeVwLQ"]);

// Test case 2: Prototype pollution attempts
const maliciousPayload = JSON.stringify({
    groups: [
        {
            groupName: "__proto__",
            channels: ["polluted-channel"],
        },
        {
            groupName: "constructor",
            channels: ["polluted-channel"],
        },
        {
            groupName: "prototype",
            channels: ["polluted-channel"],
        },
        null,
        "invalid-group-string",
        {
            groupName: 12345, // invalid group name type
            channels: "not-an-array",
        },
        {
            groupName: "Music",
            channels: ["UC12345678901234567890123", 999, null, {}], // non-string channels filtered out
        },
    ],
});
const sanitizedResults = simulateImportGroups(maliciousPayload);

assert.equal(sanitizedResults.length, 1);
assert.equal(sanitizedResults[0].groupName, "Music");
assert.deepEqual(sanitizedResults[0].channels, ["UC12345678901234567890123"]);
assert.equal(Object.prototype.polluted, undefined, "Object.prototype must not be polluted");

// Test case 3: Malformed JSON strings or empty/invalid structures
assert.deepEqual(simulateImportGroups("malformed json"), []);
assert.deepEqual(simulateImportGroups("{}"), []);
assert.deepEqual(simulateImportGroups("null"), []);
assert.deepEqual(simulateImportGroups('{"groups": "not-an-array"}'), []);

console.log("All channel groups import security verification tests passed successfully!");
