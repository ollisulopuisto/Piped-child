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

export const rewriteDescription = text => {
    return linkifyHtml(text, linkifyOptions)
        .replaceAll(/(?:http(?:s)?:\/\/)?(?:www\.)?youtube\.com(\/[/a-zA-Z0-9_?=&-]*)/gm, "$1")
        .replaceAll(/(?:http(?:s)?:\/\/)?(?:www\.)?youtu\.be\/(?:watch\?v=)?([/a-zA-Z0-9_?=&-]*)/gm, "/watch?v=$1")
        .replaceAll("\n", "<br>");
};
