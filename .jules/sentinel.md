# Sentinel's Journal

## 2026-11-05 - Preference Backup Authentication Token Leakage
**Vulnerability:** In `PreferencesPage.vue`, `backupPreferences()` serialized `localStorage` directly via `JSON.stringify(localStorage)`. Because Piped stores authentication session tokens under `authToken${hash}` keys in `localStorage`, exporting settings dumped active session tokens into plain JSON files.
**Learning:** Browser client-side settings export patterns often blindly serialize `localStorage`, assuming only user display/config settings are stored there. However, frontend authentication credentials often share the same storage space.
**Prevention:** Always whitelist allowed keys or explicitly filter out credential prefixes (`authToken*`) and reject prototype pollution keys (`__proto__`, `constructor`, `prototype`) during settings export and import.
