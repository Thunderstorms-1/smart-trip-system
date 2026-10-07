<script setup lang="ts">
import { ref } from "vue";
const failed = ref(false);
</script>
<template>
  <div class="journey-backdrop" aria-hidden="true">
    <img
      v-if="!failed"
      src="/design-v3/lake-journey.png"
      alt=""
      width="1536"
      height="1024"
      @error="failed = true"
    />
  </div>
  <small class="concept-caption">{{
    failed ? "旅行概念影像暂不可用" : "旅行概念影像 · 非目的地实拍"
  }}</small>
</template>
