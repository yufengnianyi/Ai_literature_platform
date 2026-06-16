<template>
  <div class="attachment-panel">
    <div class="attachment-header">
      <div>
        <div class="attachment-title">报告附件</div>
        <div class="attachment-subtitle">当前会话生成的完整证据表</div>
      </div>
      <a-spin v-if="loading" size="small" />
    </div>

    <div v-if="runs.length === 0 && !loading" class="attachment-empty">
      提交 Report 问题后，将在这里提供 XLSX 证据表。
    </div>

    <div v-else class="attachment-list">
      <article v-for="run in runs" :key="run.reportId" class="attachment-item">
        <div class="attachment-question">{{ run.question }}</div>
        <div class="attachment-meta">
          <span class="status-pill" :class="`status-${run.status.toLowerCase()}`">
            {{ statusLabel(run.status) }}
          </span>
          <span>{{ run.evidenceCount }} 条证据</span>
          <span v-if="run.selectedDocumentCount > 0">
            {{ run.analyzedDocumentCount }}/{{ run.selectedDocumentCount }} 篇全文
          </span>
          <span>{{ formatDate(run.createdAt) }}</span>
        </div>
        <a-button
          block
          size="small"
          :disabled="!run.attachmentAvailable"
          @click="reportService.downloadAttachment(run.reportId)"
        >
          <template #icon><DownloadOutlined /></template>
          下载 XLSX
        </a-button>
      </article>
    </div>
  </div>
</template>

<script setup lang="ts">
import { DownloadOutlined } from '@ant-design/icons-vue';
import { reportService, type ReportRun } from '@/services/report';
import type { ReportStatus } from '@/types/chat';

defineProps<{
  runs: ReportRun[];
  loading?: boolean;
}>();

const formatter = new Intl.DateTimeFormat('zh-CN', {
  month: 'short',
  day: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
});

const formatDate = (value: string) => formatter.format(new Date(value));

const statusLabel = (status: ReportStatus) => {
  const labels: Record<ReportStatus, string> = {
    QUEUED: '排队中',
    REWRITING: '理解问题',
    MATCHING: '匹配证据',
    GENERATING: '生成综述',
    PLANNING: '规划报告',
    ANALYZING_EVIDENCE: '分析证据',
    RETRIEVING_LITERATURE: '检索文献',
    ANALYZING_LITERATURE: '全文分析',
    SYNTHESIZING: '跨文献综合',
    VALIDATING: '校验报告',
    COMPLETED: '已完成',
    PARTIAL_COMPLETED: '部分完成',
    FAILED: '失败',
  };
  return labels[status];
};
</script>

<style scoped>
.attachment-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #f8fafc;
}

.attachment-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 18px;
  border-bottom: 1px solid #e2e8f0;
}

.attachment-title {
  color: #0f172a;
  font-size: 15px;
  font-weight: 700;
}

.attachment-subtitle {
  margin-top: 4px;
  color: #64748b;
  font-size: 12px;
  line-height: 1.45;
}

.attachment-empty {
  padding: 24px 18px;
  color: #64748b;
  font-size: 13px;
  line-height: 1.6;
}

.attachment-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}

.attachment-item {
  padding: 14px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #fff;
}

.attachment-item + .attachment-item {
  margin-top: 10px;
}

.attachment-question {
  display: -webkit-box;
  overflow: hidden;
  color: #1e293b;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.5;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

.attachment-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 10px;
  margin: 10px 0;
  color: #64748b;
  font-size: 11px;
}

.status-pill {
  padding: 2px 7px;
  border-radius: 999px;
  background: #e2e8f0;
  color: #475569;
  font-weight: 700;
}

.status-completed {
  background: #dcfce7;
  color: #166534;
}

.status-failed {
  background: #fee2e2;
  color: #991b1b;
}

.status-rewriting,
.status-matching,
.status-generating,
.status-queued {
  background: #dbeafe;
  color: #1d4ed8;
}
</style>
