const FORBIDDEN_KEYS = new Set(["__proto__", "constructor", "prototype"]);

// Prevent prototype pollution and malformed channel identifiers from untrusted files.
export function isValidChannelId(id) {
    return typeof id === "string" && !FORBIDDEN_KEYS.has(id.trim()) && /^[a-zA-Z0-9_-]{1,64}$/.test(id.trim());
}

// An export entry may carry an explicit `id` and/or a channel `url`. The explicit id wins
// when it is valid; an invalid id must not hide a valid url, so the url is tried next.
export function channelIdFromEntry(entry, idFromUrl) {
    if (isValidChannelId(entry?.id)) return entry.id.trim();
    if (typeof entry?.url === "string") {
        const fromUrl = idFromUrl(entry.url);
        if (isValidChannelId(fromUrl)) return fromUrl.trim();
    }
    return null;
}
