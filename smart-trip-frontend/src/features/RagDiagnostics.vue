<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from "vue";
import { createRagDiagnostics, defaultRagRequirement, ragSourceUrl } from "./rag-diagnostics";

const diagnostics = createRagDiagnostics();
const { status, preview, checking, searching, statusError, previewError } = diagnostics;
const requirementText = ref(JSON.stringify(defaultRagRequirement(), null, 2));
const sourceGroups = computed(() => [
  { title: "实际选中的来源", note: "以下片段实际进入本次参考上下文。", sources: preview.value?.rag.sources ?? [] },
  { title: "候选来源", note: "候选命中仅供排查，不代表已选入上下文或被生成模型使用。", sources: preview.value?.rag.candidates ?? [] },
]);
onBeforeUnmount(diagnostics.dispose);
const display = (value: unknown) => value === null || value === undefined || value === "" ? "未返回" : String(value);
</script>

<template>
  <main class="rag-diagnostics">
    <header>
      <span class="dev-label">仅开发环境 · 只读</span>
      <h1>RAG 本机检索诊断</h1>
      <p>本次检索预览不会生成路书、扣除 AI 额度或调用付费模型。只读取公开知识，向量化使用本机 Ollama。</p>
      <p class="muted">此页面不执行知识导入、重建、删除或数据库迁移；检索成功不代表完整 AI 业务已经可用。</p>
    </header>

    <section class="diagnostic-panel" aria-labelledby="rag-status-title" :aria-busy="checking">
      <div class="panel-heading"><h2 id="rag-status-title">知识库状态</h2>
        <button type="button" :disabled="checking" @click="diagnostics.checkStatus()">{{ checking ? "正在读取索引状态…" : "检查知识库状态" }}</button>
      </div>
      <p v-if="statusError" class="error" role="alert">{{ statusError }}</p>
      <p v-if="checking" role="status">正在检查实际 Elasticsearch 别名、映射和公开知识计数。</p>
      <template v-if="status">
        <dl class="metrics">
          <div><dt>RAG 开关</dt><dd>{{ status.ragEnabled ? "已开启" : "未开启" }}</dd></div>
          <div><dt>索引别名</dt><dd>{{ display(status.indexAlias) }}</dd></div>
          <div><dt>索引状态</dt><dd>{{ display(status.indexStatus) }}</dd></div>
          <div><dt>Embedding 模型 / 版本</dt><dd>{{ status.embeddingModel }} / {{ status.embeddingVersion }}</dd></div>
          <div><dt>映射向量维度</dt><dd>{{ display(status.mappedVectorDimensions) }}</dd></div>
          <div><dt>公开切片数量</dt><dd>{{ display(status.publicChunkCount) }}</dd></div>
          <div><dt>公开文档数（估算）</dt><dd>{{ display(status.publicDocumentCountEstimate) }}</dd></div>
          <div><dt>检查耗时</dt><dd>{{ display(status.durationMs) }} ms</dd></div>
        </dl>
        <p :class="status.failureReason ? 'error' : 'muted'" role="status">{{ status.message }}<span v-if="status.failureReason"> · {{ status.failureReason }}</span></p>
        <p class="muted">Embedding 检查：{{ status.embeddingStatus }}。本按钮不调用 Ollama；使用下方真实预览验证向量化。</p>
      </template>
    </section>

    <section class="diagnostic-panel" aria-labelledby="rag-preview-title" :aria-busy="searching">
      <h2 id="rag-preview-title">真实混合检索预览</h2>
      <p class="muted">使用现有 TravelRequirement；可直接编辑结构化需求。预算和日期允许未知，不需要先调用模型理解需求。</p>
      <form @submit.prevent="diagnostics.search(requirementText)">
        <label for="rag-requirement">测试旅行需求（JSON）</label>
        <textarea id="rag-requirement" v-model="requirementText" rows="14" spellcheck="false" :readonly="searching" />
        <div class="actions">
          <button type="submit" :disabled="searching">{{ searching ? "正在执行本机混合检索…" : "运行只读检索预览" }}</button>
          <button v-if="searching" type="button" class="secondary" @click="diagnostics.cancelPreview()">停止等待</button>
          <button v-else type="button" class="secondary" @click="requirementText = JSON.stringify(defaultRagRequirement(), null, 2)">恢复默认需求</button>
        </div>
      </form>
      <p v-if="searching" role="status">正在读取真实 ES 与 BGE-M3 结果，本机冷启动可能需要等待。没有提交 AI 生成任务。</p>
      <p v-if="previewError" class="error" role="alert">{{ previewError }}</p>
      <template v-if="preview">
        <dl class="metrics">
          <div><dt>检索状态</dt><dd>{{ preview.rag.status }}</dd></div>
          <div><dt>BM25 命中</dt><dd>{{ display(preview.rag.bm25HitCount) }}</dd></div>
          <div><dt>向量检索命中</dt><dd>{{ display(preview.rag.vectorHitCount) }}</dd></div>
          <div><dt>RRF 合并数量</dt><dd>{{ display(preview.rag.mergedHitCount) }}</dd></div>
          <div><dt>最终上下文数量</dt><dd>{{ display(preview.rag.finalContextCount) }}</dd></div>
          <div><dt>Rerank 状态</dt><dd>{{ display(preview.rag.rerankProvider) }}</dd></div>
          <div><dt>检索耗时</dt><dd>{{ display(preview.rag.retrievalDurationMs) }} ms</dd></div>
          <div><dt>总耗时</dt><dd>{{ display(preview.rag.durationMs) }} ms</dd></div>
        </dl>
        <p :class="preview.rag.fallbackReason ? 'error' : 'muted'">降级原因：{{ preview.rag.fallbackReason || "无" }}</p>
        <div class="source-columns">
          <section v-for="group in sourceGroups" :key="group.title" class="source-group">
            <h3>{{ group.title }}（{{ group.sources.length }}）</h3><p class="muted">{{ group.note }}</p>
            <ol v-if="group.sources.length">
              <li v-for="source in group.sources" :key="source.chunkId">
                <strong v-if="source.ref">[{{ source.ref }}] </strong>
                <a v-if="ragSourceUrl(source)" :href="ragSourceUrl(source)" target="_blank" rel="noopener noreferrer">{{ source.title || source.sourceName }}</a>
                <strong v-else>{{ source.title || source.sourceName }}</strong>
                <small>{{ source.sourceName }} · 发布：{{ source.sourceUpdatedAt || "未注明" }}</small>
                <small>文档：{{ source.documentId }} · 切片：{{ source.chunkId }}</small>
                <small v-if="source.validFrom && source.validUntil">适用期：{{ source.validFrom }} 至 {{ source.validUntil }}</small>
                <p v-if="source.applicabilityNote">{{ source.applicabilityNote }}</p>
              </li>
            </ol>
            <p v-else>没有返回此类来源。</p>
          </section>
        </div>
        <section class="snippets">
          <h3>实际选中来源摘录（{{ preview.snippets.length }}）</h3>
          <article v-for="snippet in preview.snippets" :key="snippet.chunkId">
            <strong>{{ snippet.ref ? '[' + snippet.ref + '] ' : '' }}{{ snippet.title || snippet.sourceName }}</strong>
            <p>{{ snippet.excerpt }}</p><small v-if="snippet.excerptTruncated">摘录已截断，不代表文档全文。</small>
          </article>
          <p v-if="!preview.snippets.length">没有可靠摘录；不会补充模拟结果。</p>
        </section>
      </template>
    </section>
  </main>
</template>

<style scoped>
.rag-diagnostics{max-width:1200px;margin:100px auto 40px;padding:24px;color:#16334d;font:15px/1.65 system-ui,-apple-system,"Microsoft YaHei",sans-serif;overflow-wrap:anywhere}
.rag-diagnostics header{margin-bottom:24px}.rag-diagnostics h1{font:700 clamp(25px,4vw,36px)/1.2 system-ui;margin:12px 0}.rag-diagnostics h2{font-size:21px;margin:0 0 12px}.rag-diagnostics h3{font-size:17px;margin:8px 0}
.dev-label{font-size:12px;letter-spacing:.08em;color:#315b7b}.muted,dt,small{color:#49657b}
.diagnostic-panel{padding:24px;margin-top:20px;background:#f6fbfff5;border:1px solid #d3e3ef;border-radius:18px;box-shadow:0 8px 30px #173d5810}
.panel-heading,.actions{display:flex;gap:12px;align-items:center;justify-content:space-between;flex-wrap:wrap}.actions{justify-content:flex-start;margin-top:12px}
button{font:600 14px/1.4 system-ui;color:#fff;background:#145c93;border:1px solid #145c93;border-radius:10px;padding:11px 16px;cursor:pointer;transition:background-color .16s,transform .16s}button:hover:enabled{background:#104b79}button:active:enabled{transform:translateY(1px)}button:disabled{opacity:.6;cursor:wait}button.secondary{background:#fff;color:#254c6a;border-color:#b9cddd}
button:focus-visible,textarea:focus-visible{outline:3px solid #ef873a;outline-offset:3px}label{display:block;font-weight:600;margin:12px 0 6px}textarea{box-sizing:border-box;width:100%;resize:vertical;min-height:240px;max-height:600px;border:1px solid #adc7db;border-radius:12px;padding:14px;color:#183b56;background:#fff;font:13px/1.65 ui-monospace,Consolas,monospace}
.metrics{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px}.metrics>div{background:#e9f2f8;border-radius:10px;padding:12px}dt{font-size:13px}dd{margin:4px 0 0;font-weight:650;font-size:16px}.error{padding:12px;border-radius:10px;background:#fff0eb;color:#942f12}
.source-columns{display:grid;grid-template-columns:1fr 1fr;gap:20px}.source-group{min-width:0}.source-group ol{padding-left:22px}.source-group li{padding:12px 0;border-bottom:1px solid #d4e2ed}small{display:block;font-size:12px}a{color:#125f98;text-decoration:underline;text-underline-offset:3px}.snippets article{background:#eaf3fa;border-radius:10px;padding:14px;margin:12px 0}.snippets p{white-space:pre-wrap}
@media(max-width:700px){.rag-diagnostics{margin-top:80px;padding:12px}.diagnostic-panel{padding:16px}.source-columns{grid-template-columns:1fr}.metrics{grid-template-columns:1fr 1fr}}
@media(prefers-reduced-motion:reduce){button{transition:none}button:active:enabled{transform:none}}
</style>
