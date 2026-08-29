import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useAIStore = defineStore('ai', () => {
  // State
  const isGenerating = ref(false)     // 是否正在生成中
  const streamOutput = ref('')        // 实时打字机缓冲区文本
  const isFinished = ref(false)       // 是否生成完毕
  const generatedTripId = ref(null)   // 生成完毕后后端返回的路书 ID

  // Actions
  const startGenerating = () => {
    isGenerating.value = true
    isFinished.value = false
    streamOutput.value = ''
    generatedTripId.value = null
  }

  const appendChunk = (chunkText) => {
    streamOutput.value += chunkText
  }

  const finishGenerating = (tripId) => {
    isGenerating.value = false
    isFinished.value = true
    generatedTripId.value = tripId
  }

  const resetState = () => {
    isGenerating.value = false
    isFinished.value = false
    streamOutput.value = ''
    generatedTripId.value = null
  }

  return {
    isGenerating,
    streamOutput,
    isFinished,
    generatedTripId,
    startGenerating,
    appendChunk,
    finishGenerating,
    resetState
  }
})