<template>
  <div class="paper-import-page">
    <div class="import-header">
      <div>
        <span class="import-kicker">Admin Console</span>
        <h2 class="import-title">Paper import</h2>
      </div>
      <a-tag color="blue" class="admin-tag">Admin only</a-tag>
    </div>

    <div class="import-grid">
      <a-card class="import-card" :bordered="false">
        <template #title>
          <span class="card-title">
            <FilePdfOutlined />
            Single paper
          </span>
        </template>
        <a-upload-dragger
          v-model:file-list="fileList"
          name="file"
          accept=".pdf,application/pdf"
          :max-count="1"
          :before-upload="beforeUpload"
          :disabled="singleUploading"
          @remove="handleRemoveFile"
        >
          <p class="ant-upload-drag-icon">
            <InboxOutlined />
          </p>
          <p class="ant-upload-text">Drop a PDF here or choose one file</p>
          <p class="ant-upload-hint">The file enters preprocessing, deduplication, chunking, embedding, and RAG persistence.</p>
        </a-upload-dragger>

        <div class="action-row">
          <a-button type="primary" :loading="singleUploading" :disabled="!selectedFile" @click="handleUploadDocument">
            <template #icon><CloudUploadOutlined /></template>
            Import PDF
          </a-button>
          <a-button :disabled="!lastUpload" :loading="documentRefreshing" @click="refreshDocument">
            Refresh status
          </a-button>
        </div>

        <a-descriptions v-if="lastUpload" class="result-panel" size="small" bordered :column="1">
          <a-descriptions-item label="Document ID">{{ lastUpload.documentId }}</a-descriptions-item>
          <a-descriptions-item label="Job ID">{{ lastUpload.jobId }}</a-descriptions-item>
          <a-descriptions-item label="Status">
            <a-tag :color="jobStatusColor(lastUpload.status)">{{ lastUpload.status }}</a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="Stage">{{ lastUpload.stage }}</a-descriptions-item>
        </a-descriptions>

        <a-descriptions v-if="documentRecord" class="result-panel" size="small" bordered :column="1">
          <a-descriptions-item label="Title">{{ documentRecord.title || 'Untitled' }}</a-descriptions-item>
          <a-descriptions-item label="Source">{{ documentRecord.sourceFilename || '-' }}</a-descriptions-item>
          <a-descriptions-item label="DOI">{{ documentRecord.doiNormalized || documentRecord.doiRaw || '-' }}</a-descriptions-item>
          <a-descriptions-item label="Document status">
            <a-tag :color="documentStatusColor(documentRecord.status)">{{ documentRecord.status }}</a-tag>
          </a-descriptions-item>
        </a-descriptions>
      </a-card>

      <a-card class="import-card" :bordered="false">
        <template #title>
          <span class="card-title">
            <FolderOpenOutlined />
            Folder batch
          </span>
        </template>
        <a-form layout="vertical" class="folder-form">
          <a-form-item label="Server folder path">
            <a-input
              v-model:value="folderPath"
              placeholder="D:\\papers\\phytophthora"
              :disabled="batchSubmitting"
              @pressEnter="handleFolderImport"
            />
          </a-form-item>
          <div class="action-row">
            <a-button type="primary" :loading="batchSubmitting" :disabled="!folderPath.trim()" @click="handleFolderImport">
              <template #icon><FolderOpenOutlined /></template>
              Import folder
            </a-button>
            <a-button :disabled="!lastBatch" :loading="batchRefreshing" @click="refreshBatch">
              Refresh batch
            </a-button>
          </div>
        </a-form>

        <a-alert
          class="folder-note"
          type="info"
          show-icon
          message="Folder import reads PDFs from a path visible to the backend server."
        />

        <a-descriptions v-if="lastBatch" class="result-panel" size="small" bordered :column="1">
          <a-descriptions-item label="Batch ID">{{ lastBatch.batchId }}</a-descriptions-item>
          <a-descriptions-item label="Status">
            <a-tag :color="batchStatusColor(lastBatch.status)">{{ lastBatch.status }}</a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="Total files">{{ lastBatch.totalFiles }}</a-descriptions-item>
        </a-descriptions>

        <a-descriptions v-if="batchRecord" class="result-panel" size="small" bordered :column="1">
          <a-descriptions-item label="Source folder">{{ batchRecord.sourceFolder }}</a-descriptions-item>
          <a-descriptions-item label="Progress">
            {{ batchRecord.processedFiles ?? 0 }} / {{ batchRecord.totalFiles ?? 0 }}
          </a-descriptions-item>
          <a-descriptions-item label="Completed">{{ batchRecord.completedFiles ?? 0 }}</a-descriptions-item>
          <a-descriptions-item label="Duplicates">{{ batchRecord.duplicateFiles ?? 0 }}</a-descriptions-item>
          <a-descriptions-item label="Failed">{{ batchRecord.failedFiles ?? 0 }}</a-descriptions-item>
          <a-descriptions-item label="Chunks">{{ batchRecord.chunkCount ?? 0 }}</a-descriptions-item>
        </a-descriptions>
      </a-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { message } from 'ant-design-vue';
import type { UploadFile, UploadProps } from 'ant-design-vue';
import {
  CloudUploadOutlined,
  FilePdfOutlined,
  FolderOpenOutlined,
  InboxOutlined,
} from '@ant-design/icons-vue';
import {
  paperImportService,
  type RagBatchAcceptedResponse,
  type RagDocumentRecord,
  type RagIngestionBatchRecord,
  type RagUploadAcceptedResponse,
} from '@/services/paperImport';

const fileList = ref<UploadFile[]>([]);
const selectedFile = ref<File | null>(null);
const singleUploading = ref(false);
const documentRefreshing = ref(false);
const batchSubmitting = ref(false);
const batchRefreshing = ref(false);
const folderPath = ref('');
const lastUpload = ref<RagUploadAcceptedResponse | null>(null);
const documentRecord = ref<RagDocumentRecord | null>(null);
const lastBatch = ref<RagBatchAcceptedResponse | null>(null);
const batchRecord = ref<RagIngestionBatchRecord | null>(null);

const beforeUpload: UploadProps['beforeUpload'] = (file) => {
  const isPdf = file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf');
  if (!isPdf) {
    message.warning('Only PDF files can be imported');
    return false;
  }

  selectedFile.value = file;
  fileList.value = [file];
  return false;
};

const handleRemoveFile = () => {
  selectedFile.value = null;
  fileList.value = [];
  return true;
};

const handleUploadDocument = async () => {
  if (!selectedFile.value) {
    message.warning('Choose a PDF first');
    return;
  }

  singleUploading.value = true;
  try {
    const response = await paperImportService.uploadDocument(selectedFile.value);
    lastUpload.value = response;
    documentRecord.value = null;
    message.success('Paper import job accepted');
    await refreshDocument();
  } catch (error) {
    console.error(error);
    message.error('Failed to import paper');
  } finally {
    singleUploading.value = false;
  }
};

const refreshDocument = async () => {
  if (!lastUpload.value) {
    return;
  }

  documentRefreshing.value = true;
  try {
    documentRecord.value = await paperImportService.getDocument(lastUpload.value.documentId);
  } catch (error) {
    console.error(error);
    message.error('Failed to refresh document status');
  } finally {
    documentRefreshing.value = false;
  }
};

const handleFolderImport = async () => {
  const normalizedPath = folderPath.value.trim();
  if (!normalizedPath) {
    message.warning('Enter a server folder path');
    return;
  }

  batchSubmitting.value = true;
  try {
    const response = await paperImportService.ingestFolder(normalizedPath);
    lastBatch.value = response;
    batchRecord.value = null;
    message.success('Folder import batch accepted');
    await refreshBatch();
  } catch (error) {
    console.error(error);
    message.error('Failed to start folder import');
  } finally {
    batchSubmitting.value = false;
  }
};

const refreshBatch = async () => {
  if (!lastBatch.value) {
    return;
  }

  batchRefreshing.value = true;
  try {
    batchRecord.value = await paperImportService.getBatch(lastBatch.value.batchId);
  } catch (error) {
    console.error(error);
    message.error('Failed to refresh batch status');
  } finally {
    batchRefreshing.value = false;
  }
};

const jobStatusColor = (status: string) => {
  if (status === 'COMPLETED') return 'green';
  if (status === 'FAILED') return 'red';
  if (status === 'DUPLICATE_SKIPPED') return 'gold';
  return 'blue';
};

const documentStatusColor = (status: string) => {
  if (status === 'COMPLETED') return 'green';
  if (status === 'FAILED') return 'red';
  if (status === 'DUPLICATE_SKIPPED') return 'gold';
  return 'blue';
};

const batchStatusColor = (status: string) => {
  if (status === 'COMPLETED') return 'green';
  if (status === 'FAILED' || status === 'PARTIAL_FAILED') return 'red';
  return 'blue';
};
</script>

<style scoped>
.paper-import-page {
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 18px;
  background:
    linear-gradient(180deg, rgba(248, 250, 252, 0.96), rgba(255, 255, 255, 0.96)),
    radial-gradient(circle at top right, rgba(37, 99, 235, 0.08), transparent 26%);
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
  letter-spacing: 0.18em;
  color: #2563eb;
}

.import-title {
  margin: 10px 0 0;
  font-size: 30px;
  color: #0f172a;
}

.admin-tag {
  margin: 0;
}

.import-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.import-card {
  border-radius: 20px;
  box-shadow: 0 12px 36px rgba(15, 23, 42, 0.05);
}

.card-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #0f172a;
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
.result-panel {
  margin-top: 16px;
}

:deep(.ant-upload-wrapper .ant-upload-drag) {
  border-color: #cbd5e1;
  background: #f8fbff;
}

:deep(.ant-upload-wrapper .ant-upload-drag:hover) {
  border-color: #2563eb;
}

@media (max-width: 1100px) {
  .import-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 960px) {
  .import-header {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
