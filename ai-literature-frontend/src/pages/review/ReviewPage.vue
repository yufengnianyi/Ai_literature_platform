<template>
  <div class="review-page">
    <div class="review-header">
      <h2>Systematic Review Report</h2>
      <p class="subtitle">Enter a research question to generate a comprehensive systematic review report</p>
    </div>

    <!-- Stage: Input -->
    <div class="input-section" v-if="stage === 'inputting'">
      <a-textarea
        v-model:value="question"
        placeholder="Enter your research question, e.g.: What is the evolutionary origin and diversification pattern of the LRR-RLK gene family across land plants?"
        :rows="4"
        :maxlength="2000"
        show-count
        class="question-input"
      />
      <div class="action-bar">
        <a-button
          type="primary"
          size="large"
          :disabled="!question.trim()"
          :loading="isAnalyzing"
          @click="handleAnalyze"
        >
          <ThunderboltOutlined />
          Analyze & Configure
        </a-button>
        <a-button size="large" @click="handleDirectSubmit" :disabled="!question.trim()" :loading="isAnalyzing">
          Quick Generate (Skip Config)
        </a-button>
        <a-button size="large" @click="handleAsyncSubmit" :disabled="!question.trim()" :loading="isAnalyzing">
          Submit (Background)
        </a-button>
      </div>
    </div>

    <!-- Stage: Analyzing -->
    <div class="progress-section" v-if="stage === 'analyzing'">
      <a-card class="progress-card">
        <div class="progress-info">
          <a-spin size="large" />
          <div class="progress-text">
            <h3>Analyzing Question...</h3>
            <p class="stage-text">Decomposing your question into sub-questions, entities, and concepts</p>
          </div>
        </div>
        <a-button danger @click="handleBackToInput" style="margin-top: 16px">Cancel</a-button>
      </a-card>
    </div>

    <!-- Stage: Confirming (Interactive Analysis Panel) -->
    <div class="confirming-section" v-if="stage === 'confirming' && analysisResult">
      <ReviewAnalysisPanel
        :analysis="analysisResult"
        :original-question="question"
        @confirm="handleConfirmAndRetrieve"
        @cancel="handleBackToInput"
      />
    </div>

    <!-- Stage: Retrieving (Segment A running) -->
    <div class="progress-section" v-if="stage === 'retrieving'">
      <a-card class="progress-card">
        <div class="progress-info">
          <a-spin size="large" />
          <div class="progress-text">
            <h3>Retrieving & Reranking Literature...</h3>
            <p class="stage-text">{{ stageLabel }}</p>
          </div>
        </div>
        <a-button danger @click="handleBackToInput" style="margin-top: 16px">Cancel</a-button>
      </a-card>
    </div>

    <!-- Stage: Reviewing Candidates (Checkpoint 1) -->
    <div class="confirming-section" v-if="stage === 'reviewingCandidates' && candidateList.length > 0">
      <CandidateReviewPanel
        :candidates="candidateList"
        @confirm="handleConfirmCandidates"
        @cancel="handleBackToInput"
      />
    </div>

    <!-- Stage: Extracting (Segment B running) -->
    <div class="progress-section" v-if="stage === 'extracting'">
      <a-card class="progress-card">
        <div class="progress-info">
          <a-spin size="large" />
          <div class="progress-text">
            <h3>Extracting & Fusing Evidence...</h3>
            <p class="stage-text">{{ stageLabel }}</p>
          </div>
        </div>
        <a-button danger @click="handleBackToInput" style="margin-top: 16px">Cancel</a-button>
      </a-card>
    </div>

    <!-- Stage: Reviewing Evidence (Checkpoint 2) -->
    <div class="confirming-section" v-if="stage === 'reviewingEvidence' && evidenceList.length > 0">
      <EvidenceReviewPanel
        :evidence="evidenceList"
        @confirm="handleConfirmEvidence"
        @cancel="handleBackToInput"
      />
    </div>

    <!-- Stage: Generating -->
    <div class="progress-section" v-if="stage === 'generating' && !reportContent">
      <a-card class="progress-card">
        <div class="progress-info">
          <a-spin size="large" />
          <div class="progress-text">
            <h3>Generating Report...</h3>
            <p class="stage-text">{{ stageLabel }}</p>
          </div>
        </div>
        <a-button danger @click="handleCancel" style="margin-top: 16px">Cancel</a-button>
      </a-card>
    </div>

    <!-- Stage: Completed / Report Display -->
    <div class="report-section" v-if="reportContent">
      <div class="report-toolbar">
        <a-button @click="handleNewReport">New Report</a-button>
        <a-button @click="handleCopyReport">
          <CopyOutlined />
          Copy Markdown
        </a-button>
        <a-button v-if="xlsxDownloadUrl" type="primary" ghost @click="handleDownloadXlsx">
          <FileExcelOutlined />
          Download Summary Table (xlsx)
        </a-button>
      </div>
      <a-card class="report-card">
        <div class="report-content markdown-body" v-html="renderedReport"></div>
      </a-card>
    </div>

    <!-- Task History -->
    <div class="history-section" v-if="stage === 'inputting'">
      <h3>Recent Tasks</h3>
      <a-list
        :data-source="taskHistory"
        :loading="loadingHistory"
        :locale="{ emptyText: 'No review tasks yet. Submit a question above to get started.' }"
      >
        <template #renderItem="{ item }">
          <a-list-item class="task-item">
            <a-list-item-meta @click="loadTask(item.taskId)">
              <template #title>
                <span class="task-question">{{ item.question }}</span>
              </template>
              <template #description>
                <a-space>
                  <a-tag :color="statusColor(item.status)">{{ statusLabel(item) }}</a-tag>
                  <span v-if="item.metrics?.totalMs">{{ (item.metrics.totalMs / 1000).toFixed(1) }}s</span>
                  <span>{{ formatTime(item.createdAt) }}</span>
                </a-space>
              </template>
            </a-list-item-meta>
            <template #actions>
              <a-tooltip v-if="item.status === 'FAILED'" title="Retry">
                <a-button type="text" size="small" @click.stop="handleRetry(item)">
                  <template #icon><ReloadOutlined /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip title="Delete">
                <a-button type="text" danger size="small" @click.stop="handleDelete(item)">
                  <template #icon><DeleteOutlined /></template>
                </a-button>
              </a-tooltip>
            </template>
          </a-list-item>
        </template>
      </a-list>
    </div>

    <!-- Error display -->
    <a-alert v-if="errorMessage" type="error" :message="errorMessage" closable
             @close="errorMessage = ''" style="margin-top: 16px" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import { message, Modal } from 'ant-design-vue';
import {
  DeleteOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
  CopyOutlined,
  FileExcelOutlined,
} from '@ant-design/icons-vue';
import {
  reviewService,
  type ReviewTaskRecord,
  type ReviewStreamHandle,
  type QueryAnalysis,
  type ReviewGenerateRequest,
  type ReviewCandidate,
  type ReviewEvidenceRecord,
  type CandidateReviewRequest,
  type EvidenceReviewRequest,
} from '@/services/review';
import { renderMarkdown } from '@/utils/markdown';
import ReviewAnalysisPanel from '@/components/review/ReviewAnalysisPanel.vue';
import CandidateReviewPanel from '@/components/review/CandidateReviewPanel.vue';
import EvidenceReviewPanel from '@/components/review/EvidenceReviewPanel.vue';

type ReviewStage =
  | 'inputting'
  | 'analyzing'
  | 'confirming'
  | 'retrieving'
  | 'reviewingCandidates'
  | 'extracting'
  | 'reviewingEvidence'
  | 'generating'
  | 'completed';

const stage = ref<ReviewStage>('inputting');
const question = ref('');
const isAnalyzing = ref(false);
const analysisResult = ref<QueryAnalysis | null>(null);
const currentTaskId = ref<string>('');
const candidateList = ref<ReviewCandidate[]>([]);
const evidenceList = ref<ReviewEvidenceRecord[]>([]);
const reportContent = ref('');
const stageLabel = ref('Initializing...');
const errorMessage = ref('');
const xlsxDownloadUrl = ref('');
const taskHistory = ref<ReviewTaskRecord[]>([]);
const loadingHistory = ref(false);
let streamHandle: ReviewStreamHandle | null = null;
let pollInterval: ReturnType<typeof setInterval> | null = null;

const renderedReport = computed(() => {
  return reportContent.value ? renderMarkdown(reportContent.value) : '';
});

const statusColor = (status: string) => {
  switch (status) {
    case 'COMPLETED': return 'green';
    case 'RUNNING': return 'blue';
    case 'QUEUED': return 'orange';
    case 'AWAITING_USER': return 'purple';
    case 'FAILED': return 'red';
    default: return 'default';
  }
};

const statusLabel = (task: ReviewTaskRecord) => {
  if (task.status === 'AWAITING_USER') return 'Awaiting your review';
  return task.status;
};

const formatTime = (iso: string) => {
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
};

const cleanupPolling = () => {
  if (pollInterval) {
    clearInterval(pollInterval);
    pollInterval = null;
  }
};

const resetState = () => {
  stage.value = 'inputting';
  analysisResult.value = null;
  currentTaskId.value = '';
  candidateList.value = [];
  evidenceList.value = [];
  reportContent.value = '';
  xlsxDownloadUrl.value = '';
  stageLabel.value = 'Initializing...';
  errorMessage.value = '';
  streamHandle?.close();
  streamHandle = null;
  cleanupPolling();
};

const loadTaskHistory = async () => {
  loadingHistory.value = true;
  try {
    taskHistory.value = await reviewService.listTasks();
  } catch {
    // ignore
  } finally {
    loadingHistory.value = false;
  }
};

// ── Checkpoint 0: Query Analysis ──

const handleAnalyze = async () => {
  if (!question.value.trim()) return;
  isAnalyzing.value = true;
  stage.value = 'analyzing';
  errorMessage.value = '';

  try {
    analysisResult.value = await reviewService.analyzeQuestion(question.value);
    stage.value = 'confirming';
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : 'Failed to analyze question';
    stage.value = 'inputting';
  } finally {
    isAnalyzing.value = false;
  }
};

// ── Segment A: Retrieval (after confirming analysis) ──

const handleConfirmAndRetrieve = async (request: ReviewGenerateRequest) => {
  stage.value = 'retrieving';
  stageLabel.value = 'Expanding queries & retrieving literature...';
  errorMessage.value = '';
  const taskId = crypto.randomUUID();
  currentTaskId.value = taskId;

  try {
    await reviewService.startRetrieval(taskId, request);
    pollForCheckpoint(taskId, 'RERANKING', 'reviewingCandidates', async () => {
      candidateList.value = await reviewService.getCandidates(taskId);
    });
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : 'Failed to start retrieval';
    stage.value = 'inputting';
  }
};

// ── Checkpoint 1: Candidate Review ──

const handleConfirmCandidates = async (request: CandidateReviewRequest) => {
  stage.value = 'extracting';
  stageLabel.value = 'Extracting evidence from selected literature...';
  errorMessage.value = '';

  try {
    await reviewService.startExtraction(currentTaskId.value, request);
    pollForCheckpoint(currentTaskId.value, 'EVIDENCE_FUSION', 'reviewingEvidence', async () => {
      evidenceList.value = await reviewService.getEvidence(currentTaskId.value);
    });
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : 'Failed to start extraction';
    stage.value = 'inputting';
  }
};

// ── Checkpoint 2: Evidence Review ──

const handleConfirmEvidence = (request: EvidenceReviewRequest) => {
  stage.value = 'generating';
  reportContent.value = '';
  xlsxDownloadUrl.value = '';
  stageLabel.value = 'Generating report with your guidance...';

  streamHandle = reviewService.startGeneration({
    taskId: currentTaskId.value,
    request,
    onMessage: (data: string) => {
      stageLabel.value = 'Generating report...';
      reportContent.value += data;
    },
    onXlsxReady: (downloadUrl: string) => {
      xlsxDownloadUrl.value = downloadUrl;
    },
    onError: (error: unknown) => {
      stage.value = reportContent.value ? 'completed' : 'inputting';
      errorMessage.value = error instanceof Error ? error.message : 'Report generation failed';
    },
    onComplete: () => {
      stage.value = 'completed';
      if (!reportContent.value) {
        errorMessage.value = 'No report content received';
      }
      loadTaskHistory();
    },
  });
};

// ── Polling helper for segment completion ──

const pollForCheckpoint = (
  taskId: string,
  expectedStage: string,
  nextUiStage: ReviewStage,
  onReady: () => Promise<void>,
) => {
  cleanupPolling();
  pollInterval = setInterval(async () => {
    try {
      const task = await reviewService.getTask(taskId);
      stageLabel.value = task.stage ?? 'Processing...';

      if (task.status === 'AWAITING_USER' && task.stage === expectedStage) {
        cleanupPolling();
        await onReady();
        stage.value = nextUiStage;
      } else if (task.status === 'FAILED') {
        cleanupPolling();
        stage.value = 'inputting';
        errorMessage.value = task.errorMessage ?? 'Task failed';
      }
    } catch {
      cleanupPolling();
      stage.value = 'inputting';
      errorMessage.value = 'Lost connection to server';
    }
  }, 3000);
};

// ── Original flows (Quick Generate, Background Submit) ──

const handleDirectSubmit = () => {
  if (!question.value.trim()) return;
  stage.value = 'generating';
  reportContent.value = '';
  xlsxDownloadUrl.value = '';
  stageLabel.value = 'Analyzing question...';

  streamHandle = reviewService.streamReport({
    question: question.value,
    onMessage: (data: string) => {
      stageLabel.value = 'Generating report...';
      reportContent.value += data;
    },
    onError: (error: unknown) => {
      stage.value = reportContent.value ? 'completed' : 'inputting';
      errorMessage.value = error instanceof Error ? error.message : 'Report generation failed';
    },
    onComplete: () => {
      stage.value = 'completed';
      if (!reportContent.value) {
        errorMessage.value = 'No report content received';
      }
      loadTaskHistory();
    },
  });
};

const handleAsyncSubmit = async () => {
  if (!question.value.trim()) return;
  isAnalyzing.value = true;
  try {
    const result = await reviewService.submitTask(question.value);
    message.success(`Task submitted: ${result.taskId}`);
    await loadTaskHistory();
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : 'Submit failed';
  } finally {
    isAnalyzing.value = false;
  }
};

const handleCancel = () => {
  streamHandle?.close();
  streamHandle = null;
  cleanupPolling();
  if (reportContent.value) {
    stage.value = 'completed';
  } else {
    stage.value = 'inputting';
  }
};

const handleBackToInput = () => {
  resetState();
};

const handleNewReport = () => {
  resetState();
  question.value = '';
};

const handleCopyReport = async () => {
  try {
    await navigator.clipboard.writeText(reportContent.value);
    message.success('Report copied to clipboard');
  } catch {
    message.error('Copy failed');
  }
};

const handleDownloadXlsx = () => {
  if (xlsxDownloadUrl.value) {
    window.open(xlsxDownloadUrl.value, '_blank');
  }
};

const handleDelete = (task: ReviewTaskRecord) => {
  Modal.confirm({
    title: 'Delete Task',
    content: `Are you sure you want to delete this task? This action cannot be undone.`,
    okText: 'Delete',
    okType: 'danger',
    async onOk() {
      try {
        await reviewService.deleteTask(task.taskId);
        message.success('Task deleted');
        await loadTaskHistory();
      } catch {
        message.error('Failed to delete task');
      }
    },
  });
};

const handleRetry = async (task: ReviewTaskRecord) => {
  try {
    await reviewService.retryTask(task.taskId);
    message.success('Task resubmitted');
    await loadTaskHistory();
    pollTask(task.taskId);
  } catch (e) {
    message.error(e instanceof Error ? e.message : 'Failed to retry task');
  }
};

const loadTask = async (taskId: string) => {
  try {
    const task = await reviewService.getTask(taskId);
    question.value = task.question;
    currentTaskId.value = taskId;

    if (task.reportMarkdown) {
      reportContent.value = task.reportMarkdown;
      stage.value = 'completed';
      xlsxDownloadUrl.value = reviewService.getXlsxDownloadUrl(taskId);
    } else if (task.status === 'AWAITING_USER') {
      if (task.stage === 'RERANKING') {
        candidateList.value = await reviewService.getCandidates(taskId);
        stage.value = 'reviewingCandidates';
      } else if (task.stage === 'EVIDENCE_FUSION') {
        evidenceList.value = await reviewService.getEvidence(taskId);
        stage.value = 'reviewingEvidence';
      }
    } else if (task.status === 'RUNNING' || task.status === 'QUEUED') {
      message.info('Task is still running, please wait...');
      pollTask(taskId);
    } else {
      message.warning('Report not available');
    }
  } catch {
    message.error('Failed to load task');
  }
};

const pollTask = (taskId: string) => {
  stage.value = 'generating';
  stageLabel.value = 'Processing...';
  cleanupPolling();
  pollInterval = setInterval(async () => {
    try {
      const task = await reviewService.getTask(taskId);
      stageLabel.value = task.stage ?? 'Processing...';
      if (task.status === 'COMPLETED') {
        cleanupPolling();
        if (task.reportMarkdown) {
          reportContent.value = task.reportMarkdown;
          question.value = task.question;
          xlsxDownloadUrl.value = reviewService.getXlsxDownloadUrl(taskId);
        }
        stage.value = 'completed';
      } else if (task.status === 'AWAITING_USER') {
        cleanupPolling();
        currentTaskId.value = taskId;
        question.value = task.question;
        if (task.stage === 'RERANKING') {
          candidateList.value = await reviewService.getCandidates(taskId);
          stage.value = 'reviewingCandidates';
        } else if (task.stage === 'EVIDENCE_FUSION') {
          evidenceList.value = await reviewService.getEvidence(taskId);
          stage.value = 'reviewingEvidence';
        }
      } else if (task.status === 'FAILED') {
        cleanupPolling();
        stage.value = 'inputting';
        errorMessage.value = task.errorMessage ?? 'Task failed';
      }
    } catch {
      cleanupPolling();
      stage.value = 'inputting';
    }
  }, 3000);
};

onMounted(() => {
  loadTaskHistory();
});
</script>

<style scoped>
.review-page {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px;
}

.review-header {
  margin-bottom: 24px;
}

.review-header h2 {
  margin-bottom: 4px;
}

.subtitle {
  color: #666;
  font-size: 14px;
}

.input-section {
  margin-bottom: 24px;
}

.question-input {
  margin-bottom: 12px;
}

.action-bar {
  display: flex;
  gap: 12px;
}

.progress-section {
  margin-bottom: 24px;
}

.progress-card {
  text-align: center;
}

.progress-info {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
}

.progress-text h3 {
  margin: 0;
}

.stage-text {
  color: #888;
  margin: 4px 0 0;
}

.confirming-section {
  margin-bottom: 24px;
}

.report-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.report-card {
  margin-bottom: 24px;
}

.report-content {
  line-height: 1.8;
  font-size: 15px;
}

.report-content :deep(h1) {
  font-size: 24px;
  border-bottom: 1px solid #eee;
  padding-bottom: 8px;
  margin-top: 24px;
}

.report-content :deep(h2) {
  font-size: 20px;
  margin-top: 20px;
}

.report-content :deep(h3) {
  font-size: 17px;
  margin-top: 16px;
}

.report-content :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 12px 0;
}

.report-content :deep(th),
.report-content :deep(td) {
  border: 1px solid #ddd;
  padding: 8px 12px;
  text-align: left;
}

.report-content :deep(th) {
  background: #f5f5f5;
}

.report-content :deep(blockquote) {
  border-left: 4px solid #1890ff;
  padding: 8px 16px;
  margin: 12px 0;
  background: #f6f8fa;
}

.history-section {
  margin-top: 32px;
}

.history-section h3 {
  margin-bottom: 12px;
}

.task-item {
  cursor: pointer;
  transition: background-color 0.2s;
}

.task-item:hover {
  background-color: #f5f5f5;
}

.task-question {
  color: #1890ff;
}
</style>
