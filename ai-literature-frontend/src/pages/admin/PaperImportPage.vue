<template>
  <div class="paper-import-page">
    <div class="import-header">
      <div>
        <span class="import-kicker">Admin Console</span>
        <h2 class="import-title">Paper import</h2>
      </div>
      <a-button :loading="statsRefreshing" @click="refreshDocumentStats">
        <template #icon><ReloadOutlined /></template>
        Refresh count
      </a-button>
    </div>

    <section class="stats-strip">
      <div class="stat-panel primary-stat">
        <span class="stat-label">Indexed literature</span>
        <strong>{{ documentStats?.canonicalCompletedDocuments ?? 0 }}</strong>
        <span class="stat-subtle">successfully loaded papers</span>
      </div>
      <div class="stat-panel">
        <span class="stat-label">Elapsed</span>
        <strong>{{ activeBatchRecord ? batchElapsedText(activeBatchRecord) : '--' }}</strong>
        <span class="stat-subtle">current batch runtime</span>
      </div>
      <div class="stat-panel">
        <span class="stat-label">Estimated finish</span>
        <strong class="time-value">{{ activeBatchRecord ? batchEtaText(activeBatchRecord) : '--' }}</strong>
        <span class="stat-subtle">based on processed PDFs</span>
      </div>
    </section>

    <div class="import-grid">
      <a-card class="import-card" :bordered="false">
        <template #title>
          <span class="card-title">
            <FilePdfOutlined />
            PDF batch upload
          </span>
        </template>
        <a-upload-dragger
          v-model:file-list="fileList"
          name="files"
          accept=".pdf,application/pdf"
          multiple
          :before-upload="beforeUpload"
          :disabled="uploadSubmitting"
          @remove="handleRemoveFile"
        >
          <p class="ant-upload-drag-icon">
            <InboxOutlined />
          </p>
          <p class="ant-upload-text">Drop PDFs here or choose multiple files</p>
          <p class="ant-upload-hint">Files are queued as one batch, then processed concurrently by the backend.</p>
        </a-upload-dragger>

        <div class="action-row">
          <a-button type="primary" :loading="uploadSubmitting" :disabled="selectedFiles.length === 0" @click="handleUploadDocuments">
            <template #icon><CloudUploadOutlined /></template>
            Import selected PDFs
          </a-button>
          <a-button :disabled="!lastUploadBatch" :loading="uploadBatchRefreshing" @click="refreshUploadBatch">
            Refresh upload batch
          </a-button>
        </div>

        <a-descriptions v-if="lastUploadBatch" class="result-panel" size="small" bordered :column="1">
          <a-descriptions-item label="Batch ID">{{ lastUploadBatch.batchId }}</a-descriptions-item>
          <a-descriptions-item label="Status">
            <a-tag :color="batchStatusColor(lastUploadBatch.status)">{{ lastUploadBatch.status }}</a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="Total files">{{ lastUploadBatch.totalFiles }}</a-descriptions-item>
        </a-descriptions>

        <div v-if="uploadBatchRecord" class="batch-panel">
          <div class="batch-progress-head">
            <span>Upload batch progress</span>
            <strong>{{ batchProgressText(uploadBatchRecord) }}</strong>
          </div>
          <a-progress :percent="batchProgressPercent(uploadBatchRecord)" :show-info="false" />
          <div class="batch-metrics">
            <span>Successful {{ uploadBatchRecord.completedFiles ?? 0 }}</span>
            <span>Elapsed {{ batchElapsedText(uploadBatchRecord) }}</span>
            <span>ETA {{ batchEtaText(uploadBatchRecord) }}</span>
          </div>
        </div>
      </a-card>

      <a-card class="import-card" :bordered="false">
        <template #title>
          <span class="card-title">
            <FolderOpenOutlined />
            Server folder batch
          </span>
        </template>
        <a-form layout="vertical" class="folder-form">
          <a-form-item label="Server folder path">
            <a-input
              v-model:value="folderPath"
              placeholder="D:\\papers\\phytophthora"
              :disabled="folderSubmitting"
              @pressEnter="handleFolderImport"
            />
          </a-form-item>
          <div class="action-row">
            <a-button type="primary" :loading="folderSubmitting" :disabled="!folderPath.trim()" @click="handleFolderImport">
              <template #icon><FolderOpenOutlined /></template>
              Import folder
            </a-button>
            <a-button :disabled="!lastFolderBatch" :loading="folderBatchRefreshing" @click="refreshFolderBatch">
              Refresh folder batch
            </a-button>
          </div>
        </a-form>

        <a-alert
          class="folder-note"
          type="info"
          show-icon
          message="Folder import reads PDFs from a path visible to the backend server."
        />

        <a-descriptions v-if="lastFolderBatch" class="result-panel" size="small" bordered :column="1">
          <a-descriptions-item label="Batch ID">{{ lastFolderBatch.batchId }}</a-descriptions-item>
          <a-descriptions-item label="Status">
            <a-tag :color="batchStatusColor(lastFolderBatch.status)">{{ lastFolderBatch.status }}</a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="Total files">{{ lastFolderBatch.totalFiles }}</a-descriptions-item>
        </a-descriptions>

        <div v-if="folderBatchRecord" class="batch-panel">
          <div class="batch-progress-head">
            <span>Folder batch progress</span>
            <strong>{{ batchProgressText(folderBatchRecord) }}</strong>
          </div>
          <a-progress :percent="batchProgressPercent(folderBatchRecord)" :show-info="false" />
          <div class="batch-metrics">
            <span>Successful {{ folderBatchRecord.completedFiles ?? 0 }}</span>
            <span>Elapsed {{ batchElapsedText(folderBatchRecord) }}</span>
            <span>ETA {{ batchEtaText(folderBatchRecord) }}</span>
          </div>
        </div>
      </a-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { message } from 'ant-design-vue';
import type { UploadFile, UploadProps } from 'ant-design-vue';
import {
  CloudUploadOutlined,
  FilePdfOutlined,
  FolderOpenOutlined,
  InboxOutlined,
  ReloadOutlined,
} from '@ant-design/icons-vue';
import {
  paperImportService,
  type RagBatchAcceptedResponse,
  type RagDocumentStats,
  type RagIngestionBatchRecord,
} from '@/services/paperImport';

const fileList = ref<UploadFile[]>([]);
const selectedFiles = ref<File[]>([]);
const uploadSubmitting = ref(false);
const uploadBatchRefreshing = ref(false);
const folderSubmitting = ref(false);
const folderBatchRefreshing = ref(false);
const statsRefreshing = ref(false);
const folderPath = ref('');
const lastUploadBatch = ref<RagBatchAcceptedResponse | null>(null);
const uploadBatchRecord = ref<RagIngestionBatchRecord | null>(null);
const lastFolderBatch = ref<RagBatchAcceptedResponse | null>(null);
const folderBatchRecord = ref<RagIngestionBatchRecord | null>(null);
const documentStats = ref<RagDocumentStats | null>(null);
const nowMs = ref(Date.now());
let clockTimer: ReturnType<typeof setInterval> | undefined;

const activeBatchRecord = computed(() => {
  if (folderBatchRecord.value && uploadBatchRecord.value) {
    return Date.parse(folderBatchRecord.value.updatedAt ?? '') >= Date.parse(uploadBatchRecord.value.updatedAt ?? '')
      ? folderBatchRecord.value
      : uploadBatchRecord.value;
  }
  return folderBatchRecord.value ?? uploadBatchRecord.value;
});

const beforeUpload: UploadProps['beforeUpload'] = (file) => {
  const isPdf = file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf');
  if (!isPdf) {
    message.warning('Only PDF files can be imported');
    return false;
  }

  const alreadySelected = selectedFiles.value.some((item) => sameFile(item, file));
  if (!alreadySelected) {
    selectedFiles.value = [...selectedFiles.value, file];
  }
  return false;
};

const handleRemoveFile = (file: UploadFile) => {
  selectedFiles.value = selectedFiles.value.filter((item) => !sameFile(item, file));
  fileList.value = fileList.value.filter((item) => item.uid !== file.uid);
  return true;
};

const handleUploadDocuments = async () => {
  if (selectedFiles.value.length === 0) {
    message.warning('Choose at least one PDF first');
    return;
  }

  uploadSubmitting.value = true;
  try {
    const response = await paperImportService.uploadDocuments(selectedFiles.value);
    lastUploadBatch.value = response;
    uploadBatchRecord.value = null;
    message.success('PDF upload batch accepted');
    await refreshUploadBatch();
  } catch (error) {
    console.error(error);
    message.error('Failed to import selected PDFs');
  } finally {
    uploadSubmitting.value = false;
  }
};

const refreshUploadBatch = async () => {
  if (!lastUploadBatch.value) {
    return;
  }

  uploadBatchRefreshing.value = true;
  try {
    uploadBatchRecord.value = await paperImportService.getBatch(lastUploadBatch.value.batchId);
    await refreshDocumentStats();
  } catch (error) {
    console.error(error);
    message.error('Failed to refresh upload batch');
  } finally {
    uploadBatchRefreshing.value = false;
  }
};

const handleFolderImport = async () => {
  const normalizedPath = folderPath.value.trim();
  if (!normalizedPath) {
    message.warning('Enter a server folder path');
    return;
  }

  folderSubmitting.value = true;
  try {
    const response = await paperImportService.ingestFolder(normalizedPath);
    lastFolderBatch.value = response;
    folderBatchRecord.value = null;
    message.success('Folder import batch accepted');
    await refreshFolderBatch();
  } catch (error) {
    console.error(error);
    message.error('Failed to start folder import');
  } finally {
    folderSubmitting.value = false;
  }
};

const refreshFolderBatch = async () => {
  if (!lastFolderBatch.value) {
    return;
  }

  folderBatchRefreshing.value = true;
  try {
    folderBatchRecord.value = await paperImportService.getBatch(lastFolderBatch.value.batchId);
    await refreshDocumentStats();
  } catch (error) {
    console.error(error);
    message.error('Failed to refresh folder batch');
  } finally {
    folderBatchRefreshing.value = false;
  }
};

const refreshDocumentStats = async () => {
  statsRefreshing.value = true;
  try {
    documentStats.value = await paperImportService.getDocumentStats();
  } catch (error) {
    console.error(error);
    message.error('Failed to refresh literature count');
  } finally {
    statsRefreshing.value = false;
  }
};

const batchStatusColor = (status: string) => {
  if (status === 'COMPLETED') return 'green';
  if (status === 'FAILED' || status === 'PARTIAL_FAILED') return 'red';
  return 'blue';
};

const batchProgressText = (record: RagIngestionBatchRecord) => {
  return `${record.processedFiles ?? 0} / ${record.totalFiles ?? 0}`;
};

const batchProgressPercent = (record: RagIngestionBatchRecord) => {
  const total = record.totalFiles ?? 0;
  if (total <= 0) {
    return 0;
  }
  return Math.min(100, Math.round(((record.processedFiles ?? 0) / total) * 100));
};

const batchElapsedText = (record: RagIngestionBatchRecord) => {
  return formatDuration(batchElapsedMs(record));
};

const batchEtaText = (record: RagIngestionBatchRecord) => {
  if (isBatchFinished(record.status)) {
    return record.finishedAt ? formatDateTime(record.finishedAt) : 'Complete';
  }

  const processed = record.processedFiles ?? 0;
  const total = record.totalFiles ?? 0;
  const elapsedMs = batchElapsedMs(record);
  if (processed <= 0 || total <= 0 || elapsedMs <= 0) {
    return '--';
  }

  const remainingFiles = Math.max(total - processed, 0);
  const remainingMs = Math.round((elapsedMs / processed) * remainingFiles);
  return formatDateTime(new Date(nowMs.value + remainingMs).toISOString());
};

const batchElapsedMs = (record: RagIngestionBatchRecord) => {
  if (record.totalElapsedMs != null) {
    return record.totalElapsedMs;
  }

  const startMs = Date.parse(record.startedAt ?? record.createdAt ?? '');
  if (Number.isNaN(startMs)) {
    return 0;
  }
  return Math.max(0, nowMs.value - startMs);
};

const isBatchFinished = (status: string) => {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'PARTIAL_FAILED';
};

const formatDuration = (milliseconds: number) => {
  const totalSeconds = Math.max(0, Math.floor(milliseconds / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;

  if (hours > 0) {
    return `${hours}h ${minutes}m`;
  }
  if (minutes > 0) {
    return `${minutes}m ${seconds.toString().padStart(2, '0')}s`;
  }
  return `${seconds}s`;
};

const formatDateTime = (value: string) => {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '--';
  }
  return date.toLocaleString(undefined, {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
};

const sameFile = (left: File | UploadFile, right: File | UploadFile) => {
  return left.name === right.name && left.size === right.size;
};

onMounted(() => {
  clockTimer = setInterval(() => {
    nowMs.value = Date.now();
  }, 1000);
  void refreshDocumentStats();
});

onUnmounted(() => {
  if (clockTimer) {
    clearInterval(clockTimer);
  }
});
</script>

<style scoped>
.paper-import-page {
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 18px;
  background: #f6f8fb;
}

.import-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
}

.import-kicker {
  display: inline-block;
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.12em;
  color: #2563eb;
}

.import-title {
  margin: 8px 0 0;
  font-size: 28px;
  color: #111827;
}

.stats-strip {
  display: grid;
  grid-template-columns: minmax(240px, 1.2fr) repeat(2, minmax(180px, 1fr));
  gap: 12px;
  align-items: stretch;
}

.stat-panel,
.import-card {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
}

.stat-panel {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 18px;
  min-width: 0;
}

.primary-stat strong {
  line-height: 1;
  font-size: 42px;
  color: #0f766e;
}

.stat-panel:not(.primary-stat) strong {
  line-height: 1.15;
  font-size: 24px;
  color: #111827;
}

.time-value {
  overflow-wrap: anywhere;
}

.stat-label,
.stat-subtle {
  color: #64748b;
}

.stat-label {
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.stat-subtle {
  margin-top: 8px;
  font-size: 13px;
}

.import-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.import-card {
  box-shadow: 0 10px 30px rgba(15, 23, 42, 0.05);
}

.card-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #111827;
}

.action-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 16px;
}

.folder-form {
  margin-top: 2px;
}

.folder-note,
.result-panel,
.batch-panel {
  margin-top: 16px;
}

.batch-panel {
  padding: 14px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
}

.batch-progress-head,
.batch-metrics {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.batch-progress-head {
  margin-bottom: 8px;
  color: #334155;
}

.batch-metrics {
  margin-top: 10px;
  color: #64748b;
  font-size: 13px;
}

:deep(.ant-upload-wrapper .ant-upload-drag) {
  border-color: #cbd5e1;
  background: #f8fbff;
}

:deep(.ant-upload-wrapper .ant-upload-drag:hover) {
  border-color: #2563eb;
}

@media (max-width: 1100px) {
  .import-grid,
  .stats-strip {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .import-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .stat-panel:not(.primary-stat) strong {
    font-size: 20px;
  }
}
</style>
