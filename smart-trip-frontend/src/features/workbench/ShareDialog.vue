<script setup lang="ts">
import QrcodeVue from "qrcode.vue";
import type { Share } from "../types";
defineProps<{ shares: Share[]; shareUrl: string; busy: boolean }>();
const emit = defineEmits<{ create: []; revoke: [id: number] }>();
</script>
<template>
  <p>分享的是此刻的路书快照。之后的私密修改不会同步到链接。</p>
  <button class="button" :disabled="busy" @click="emit('create')">
    创建分享链接</button
  ><template v-if="shareUrl"
    ><a class="share-url" :href="shareUrl" target="_blank" rel="noopener">{{
      shareUrl
    }}</a
    ><QrcodeVue :value="shareUrl" :size="140" level="M"
  /></template>
  <div v-for="i in shares" :key="i.id" class="packing-row">
    <small>分享 #{{ i.id }} · {{ i.createdAt }}</small
    ><button
      v-if="!i.revoked"
      class="text-button"
      :disabled="busy"
      @click="emit('revoke', i.id)"
    >
      撤销</button
    ><small v-else>已撤销</small>
  </div>
</template>
