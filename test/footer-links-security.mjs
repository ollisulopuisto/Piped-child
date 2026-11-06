import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { sanitizeExternalUrl } from "../src/utils/HtmlUtils.js";

// 1. Static check: Verify FooterComponent.vue imports and uses sanitizeExternalUrl
const vueFile = readFileSync("src/components/FooterComponent.vue", "utf8");
assert.ok(
    vueFile.includes('import { sanitizeExternalUrl } from "@/utils/HtmlUtils.js";'),
    "FooterComponent.vue must import sanitizeExternalUrl from HtmlUtils",
);
assert.ok(
    vueFile.includes("sanitizeConfigUrl"),
    "FooterComponent.vue must define a sanitizeConfigUrl helper function",
);
assert.ok(
    vueFile.includes("sanitizeConfigUrl(config.value?.donationUrl)"),
    "FooterComponent.vue must sanitize donationUrl",
);
assert.ok(
    vueFile.includes("sanitizeConfigUrl(config.value?.statusPageUrl)"),
    "FooterComponent.vue must sanitize statusPageUrl",
);
assert.ok(
    vueFile.includes("sanitizeConfigUrl(config.value?.privacyPolicyUrl)"),
    "FooterComponent.vue must sanitize privacyPolicyUrl",
);

// 2. Functional check: Verify sanitizeConfigUrl logic behavior
function sanitizeConfigUrl(url) {
    const sanitized = sanitizeExternalUrl(url);
    return sanitized !== "#" ? sanitized : null;
}

// Dangerous protocols must return null (so v-if doesn't render them and no script can run)
assert.equal(sanitizeConfigUrl("javascript:alert(document.domain)"), null);
assert.equal(sanitizeConfigUrl("javascript:/*--></title></style></textarea>*/<script>alert(1)</script>"), null);
assert.equal(sanitizeConfigUrl("data:text/html,<script>alert(1)</script>"), null);
assert.equal(sanitizeConfigUrl("vbscript:msgbox(1)"), null);
assert.equal(sanitizeConfigUrl(""), null);
assert.equal(sanitizeConfigUrl(null), null);
assert.equal(sanitizeConfigUrl(undefined), null);

// Safe HTTP and HTTPS protocols must pass through safely
assert.equal(sanitizeConfigUrl("https://status.piped.video"), "https://status.piped.video");
assert.equal(sanitizeConfigUrl("https://example.com/donate"), "https://example.com/donate");
assert.equal(sanitizeConfigUrl("http://localhost:8080/privacy"), "http://localhost:8080/privacy");

console.log("All footer config link security checks passed successfully!");
