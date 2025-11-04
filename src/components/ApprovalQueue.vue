<template>
    <div class="p-4">
        <h1 class="mb-4 text-2xl font-bold">Approval Queue</h1>
        <ul>
            <li v-for="video in pendingVideos" :key="video.id" class="mb-2 flex items-center rounded border p-2">
                <img :src="video.thumbnailUrl" class="h-16 w-24 object-cover mr-4" />
                <div class="flex-grow">
                    <p class="font-bold">{{ video.title }}</p>
                    <p class="text-sm text-gray-500">{{ video.uploaderName }}</p>
                </div>
                <div class="mt-2">
                    <button class="btn btn-success mr-2" @click="approveVideo(video.id)">Approve</button>
                    <button class="btn btn-danger" @click="denyVideo(video.id)">Deny</button>
                </div>
            </li>
        </ul>
    </div>
</template>

<script>
export default {
    data() {
        return {
            pendingVideos: [],
        };
    },
    mounted() {
        this.fetchPendingVideos();
    },
    methods: {
    async fetchPendingVideos() {
            const pendingVideoIds = JSON.parse(localStorage.getItem("pending_videos")) || [];
      const videoDetails = await Promise.all(
        pendingVideoIds.map(videoId =>
          this.fetchJson(`${this.apiUrl()}/streams/${videoId}`).catch(() => null)
        )
      );
      this.pendingVideos = videoDetails.filter(Boolean);
        },
        approveVideo(videoId) {
            const pendingVideos = JSON.parse(localStorage.getItem("pending_videos")) || [];
            const approvedVideos = JSON.parse(localStorage.getItem("approved_videos")) || [];

            if (!approvedVideos.includes(videoId)) {
                approvedVideos.push(videoId);
                localStorage.setItem("approved_videos", JSON.stringify(approvedVideos));
            }

            const updatedPendingVideos = pendingVideos.filter(id => id !== videoId);
            localStorage.setItem("pending_videos", JSON.stringify(updatedPendingVideos));

            this.fetchPendingVideos();
        },
        denyVideo(videoId) {
            const pendingVideos = JSON.parse(localStorage.getItem("pending_videos")) || [];
            const updatedPendingVideos = pendingVideos.filter(id => id !== videoId);
            localStorage.setItem("pending_videos", JSON.stringify(updatedPendingVideos));
            this.fetchPendingVideos();
        },
    },
};
</script>
