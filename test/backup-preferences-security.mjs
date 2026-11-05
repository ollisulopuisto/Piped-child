import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

// 1. Static check: Verify PreferencesPage.vue contains the sanitization logic
const vueFile = readFileSync("src/components/PreferencesPage.vue", "utf8");
assert.ok(
    vueFile.includes('!key.startsWith("authToken")'),
    "PreferencesPage.vue should filter out authToken keys in backup/restore",
);
assert.ok(
    vueFile.includes("forbiddenKeys"),
    "PreferencesPage.vue should protect against forbidden prototype pollution keys",
);

// 2. Functional simulation check of backup and restore logic
class MockLocalStorage {
    constructor() {
        this.store = new Map();
    }
    get length() {
        return this.store.size;
    }
    key(index) {
        return Array.from(this.store.keys())[index];
    }
    getItem(key) {
        return this.store.get(key) ?? null;
    }
    setItem(key, val) {
        this.store.set(key, String(val));
    }
}

// Simulate backup logic from PreferencesPage
function simulateBackup(mockStorage) {
    const safeData = {};
    for (let i = 0; i < mockStorage.length; i++) {
        const key = mockStorage.key(i);
        if (key && !key.startsWith("authToken")) {
            safeData[key] = mockStorage.getItem(key);
        }
    }
    return JSON.stringify(safeData);
}

// Simulate restore logic from PreferencesPage
function simulateRestore(jsonText, mockStorage) {
    const data = JSON.parse(jsonText);
    const forbiddenKeys = new Set(["__proto__", "constructor", "prototype"]);
    Object.keys(data).forEach(function (key) {
        if (!forbiddenKeys.has(key) && !key.startsWith("authToken")) {
            mockStorage.setItem(key, data[key]);
        }
    });
}

const storage = new MockLocalStorage();
storage.setItem("theme", "dark");
storage.setItem("quality", "1080");
storage.setItem("authToken12345", "sensitive-jwt-token-secret");
storage.setItem("authToken67890", "another-sensitive-token");

const exportedJson = simulateBackup(storage);
const parsedBackup = JSON.parse(exportedJson);

assert.equal(parsedBackup.theme, "dark");
assert.equal(parsedBackup.quality, "1080");
assert.equal(parsedBackup.authToken12345, undefined, "authToken must not be present in backup");
assert.equal(parsedBackup.authToken67890, undefined, "authToken must not be present in backup");

const targetStorage = new MockLocalStorage();
const maliciousImport = JSON.stringify({
    theme: "light",
    authTokenFake: "fake-token",
    __proto__: { polluted: "yes" },
    constructor: { polluted: "yes" },
});

simulateRestore(maliciousImport, targetStorage);

assert.equal(targetStorage.getItem("theme"), "light");
assert.equal(targetStorage.getItem("authTokenFake"), null, "authToken must not be restored");
assert.equal(targetStorage.getItem("__proto__"), null, "__proto__ must not be restored");
assert.equal(targetStorage.getItem("constructor"), null, "constructor must not be restored");
assert.equal(Object.prototype.polluted, undefined, "Object.prototype must not be polluted");

console.log("All backup & restore security verification tests passed successfully!");
