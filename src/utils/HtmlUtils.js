import DOMPurify from "dompurify";

export const purifyHTML = html => {
    return DOMPurify.sanitize(html);
};

import linkifyHtml from "linkify-html";

// Prevent reverse tabnabbing and referrer leakage on external links from user descriptions/comments
const linkifyOptions = {
    target: {
        url: "_blank",
    },
    rel: "noopener noreferrer nofollow",
};

// Ensure URLs only use safe protocols to prevent XSS via javascript: or data: URIs
export const sanitizeExternalUrl = url => {
    if (!url || typeof url !== "string") return "#";
    try {
        const parsed = new URL(url, "https://piped.video");
        if (parsed.protocol === "http:" || parsed.protocol === "https:") {
            return url;
        }
    } catch {
        return "#";
    }
    return "#";
};

export const rewriteDescription = text => {
    return linkifyHtml(text, linkifyOptions)
        .replaceAll(/(?:http(?:s)?:\/\/)?(?:www\.)?youtube\.com(\/[/a-zA-Z0-9_?=&-]*)/gm, "$1")
        .replaceAll(/(?:http(?:s)?:\/\/)?(?:www\.)?youtu\.be\/(?:watch\?v=)?([/a-zA-Z0-9_?=&-]*)/gm, "/watch?v=$1")
        .replaceAll("\n", "<br>");
};
