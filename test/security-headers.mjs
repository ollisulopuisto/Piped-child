import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

// 1. Check docker/nginx.conf
const nginxConf = readFileSync("docker/nginx.conf", "utf8");

assert.ok(
    nginxConf.includes('add_header X-Frame-Options "SAMEORIGIN" always;'),
    "docker/nginx.conf must contain X-Frame-Options SAMEORIGIN header",
);
assert.ok(
    nginxConf.includes('add_header X-Content-Type-Options "nosniff" always;'),
    "docker/nginx.conf must contain X-Content-Type-Options nosniff header",
);
assert.ok(
    nginxConf.includes('add_header Referrer-Policy "strict-origin-when-cross-origin" always;'),
    "docker/nginx.conf must contain Referrer-Policy strict-origin-when-cross-origin header",
);
assert.ok(
    nginxConf.includes('add_header Permissions-Policy "camera=(), microphone=(), geolocation=()" always;'),
    "docker/nginx.conf must contain Permissions-Policy header",
);

// 2. Check vercel.json
const vercelJson = JSON.parse(readFileSync("vercel.json", "utf8"));
assert.ok(Array.isArray(vercelJson.routes), "vercel.json must have a routes array");
assert.ok(vercelJson.routes.length > 0, "vercel.json routes array must not be empty");

const headerRoute = vercelJson.routes.find(r => r.src === "/(.*)" && r.headers);
assert.ok(headerRoute, "vercel.json must have a route matching /(.*) with headers");
assert.equal(headerRoute.continue, true, "vercel.json header route must specify continue: true");

const headers = headerRoute.headers;
assert.equal(headers["X-Frame-Options"], "SAMEORIGIN");
assert.equal(headers["X-Content-Type-Options"], "nosniff");
assert.equal(headers["Referrer-Policy"], "strict-origin-when-cross-origin");
assert.equal(headers["Permissions-Policy"], "camera=(), microphone=(), geolocation=()");

console.log("All security header tests passed successfully!");
