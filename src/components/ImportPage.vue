<template>
    <div class="text-center">
        <form>
            <div>
                <input ref="fileSelector" type="file" @change="fileChange" />
            </div>
            <div>
                <strong v-text="`${$t('info.selected_subscriptions')}: ${selectedSubscriptions}`" />
            </div>
            <div>
                <strong><span v-t="'actions.override'" />: <UiCheckbox v-model="override" /></strong>
            </div>
            <div>
                <Button v-t="'actions.import'" @click="handleImport" />
            </div>
        </form>
        <br />
        <strong>Importing Subscriptions from YouTube</strong>
        <br />
        <div>
            Open
            <a href="https://takeout.google.com/takeout/custom/youtube">takeout.google.com/takeout/custom/youtube</a>
            <br />
            In "Select data to include", click on "All YouTube data included" and select only "subscriptions".
            <br />
            Create the export and download the zip file.
            <br />
            Extract subscriptions.csv from the zip file.
            <br />
            Select and import the file above.
        </div>
        <br />
        <strong>Importing Subscriptions from Invidious</strong>
        <br />
        <div>
            Open
            <a href="https://invidio.us/data_control">invidiou.us/data_control</a>
            <br />
            Click on any of the export options.
            <br />
            Select and import the file above.
        </div>
        <br />
        <strong>Importing Subscriptions from NewPipe</strong>
        <br />
        <div>
            Go to the Feed tab.
            <br />
            Click on the arrow on where it says "Subscriptions".
            <br />
            Save the file somewhere.
            <br />
            Select and import the file above.
        </div>
    </div>
</template>

<script setup>
import { ref, computed, onActivated } from "vue";
import { useI18n } from "vue-i18n";
import Button from "./ui/Button.vue";
import UiCheckbox from "./ui/Checkbox.vue";
import { fetchJson, authApiUrl, getAuthToken, isAuthenticated } from "@/composables/useApi.js";
import { getLocalSubscriptions } from "@/composables/useSubscriptions.js";

const { t } = useI18n();

const fileSelector = ref(null);
const subscriptions = ref([]);
const override = ref(false);

const selectedSubscriptions = computed(() => subscriptions.value.length);

onActivated(() => {
    document.title = "Import - Piped";
});

function fileChange() {
    const file = fileSelector.value.files[0];
    file.text()
        .then(text => {
            const parsedSubs = [];
            const addChannelId = id => {
                // Prevent prototype pollution and malformed channel identifiers from untrusted files
                const forbiddenKeys = new Set(["__proto__", "constructor", "prototype"]);
                if (
                    typeof id === "string" &&
                    !forbiddenKeys.has(id.trim()) &&
                    /^[a-zA-Z0-9_-]{1,64}$/.test(id.trim())
                ) {
                    parsedSubs.push(id.trim());
                }
            };

            // Google Takeout CSV
            if (file.name.length >= 5 && file.name.slice(-4).toLowerCase() == ".csv") {
                const lines = text.split("\n");
                for (let i = 1; i < lines.length; i++) {
                    const line = lines[i];
                    const id = line.slice(0, line.indexOf(","));
                    if (id.length === 24) addChannelId(id);
                }
            }
            // Invidious OPML
            else if (text.includes("<opml") || text.includes("<outline")) {
                const parser = new DOMParser();
                const xmlDoc = parser.parseFromString(text, "text/xml");
                xmlDoc.querySelectorAll("outline[xmlUrl]").forEach(item => {
                    const url = item.getAttribute("xmlUrl");
                    if (url) addChannelId(url.slice(-24));
                });
            } else {
                // Try JSON formats based on structure and element shapes
                try {
                    const json = JSON.parse(text);
                    if (Array.isArray(json)) {
                        // Google Takeout JSON: array of items with snippet.resourceId.channelId
                        json.forEach(item => {
                            const id = item?.snippet?.resourceId?.channelId;
                            if (id) addChannelId(id);
                        });
                    } else if (Array.isArray(json?.subscriptions)) {
                        json.subscriptions.forEach(item => {
                            if (typeof item === "string") {
                                // Invidious JSON: channel ID strings
                                addChannelId(item);
                            } else if (
                                item &&
                                typeof item === "object" &&
                                (item.service_id == 0 || item.service_id == undefined)
                            ) {
                                // NewPipe / FreeTube JSON objects: prefer the explicit id, then the URL
                                if (typeof item.id === "string") addChannelId(item.id);
                                else if (typeof item.url === "string")
                                    addChannelId(item.url.replace(/\/+$/, "").slice(-24));
                            }
                        });
                    }
                } catch {
                    // Line-delimited JSON (FreeTube DB)
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

            subscriptions.value = parsedSubs;
        })
        .catch(() => {
            subscriptions.value = [];
        });
}

function handleImport() {
    if (isAuthenticated()) {
        fetchJson(
            authApiUrl() + "/import",
            {
                override: override.value,
            },
            {
                method: "POST",
                headers: {
                    Authorization: getAuthToken(),
                },
                body: JSON.stringify(subscriptions.value),
            },
        ).then(json => {
            if (json.message === "ok") window.location = "/feed";
        });
    } else {
        importSubscriptionsLocally(subscriptions.value);
        window.location = "/feed";
    }
}

function importSubscriptionsLocally(newChannels) {
    const subs = override.value
        ? [...new Set(newChannels)]
        : [...new Set((getLocalSubscriptions() ?? []).concat(newChannels))];
    // Sort for better cache hits
    subs.sort();
    try {
        localStorage.setItem("localSubscriptions", JSON.stringify(subs));
    } catch {
        alert(t("info.local_storage"));
    }
}
</script>
