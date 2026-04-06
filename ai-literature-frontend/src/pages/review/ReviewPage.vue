<template>
  <div class="review-page">
    <div class="review-header">
      <h2>Systematic Review Report</h2>
      <p class="subtitle">Enter a research question to generate a comprehensive systematic review report</p>
    </div>

    <!-- Input Section -->
    <div class="input-section" v-if="!isGenerating && !reportContent">
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
          :loading="isSubmitting"
          @click="handleSubmit"
        >
          Generate Report
        </a-button>
        <a-button size="large" @click="handleAsyncSubmit" :disabled="!question.trim()" :loading="isSubmitting">
          Submit (Background)
        </a-button>
      </div>
    </div>

    <!-- Progress Section -->
    <div class="progress-section" v-if="isGenerating">
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

    <!-- Report Section -->
    <div class="report-section" v-if="reportContent">
      <div class="report-toolbar">
        <a-button @click="handleNewReport">New Report</a-button>
        <a-button @click="handleCopyReport">Copy Markdown</a-button>
      </div>
      <a-card class="report-card">
        <div class="report-content markdown-body" v-html="renderedReport"></div>
      </a-card>
    </div>

    <!-- Task History -->
    <div class="history-section" v-if="!isGenerating && !reportContent">
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
                  <a-tag :color="statusColor(item.status)">{{ item.status }}</a-tag>
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
import { reviewService, type ReviewTaskRecord, type ReviewStreamHandle } from '@/services/review';
import { renderMarkdown } from '@/utils/markdown';
import { DeleteOutlined, ReloadOutlined } from '@ant-design/icons-vue';

const question = ref('');
const isSubmitting = ref(false);
const isGenerating = ref(false);
const reportContent = ref('');
const stageLabel = ref('Initializing...');
const errorMessage = ref('');
const taskHistory = ref<ReviewTaskRecord[]>([]);
const loadingHistory = ref(false);
let streamHandle: ReviewStreamHandle | null = null;

const renderedReport = computed(() => {
  return reportContent.value ? renderMarkdown(reportContent.value) : '';
});

const statusColor = (status: string) => {
  switch (status) {
    case 'COMPLETED': return 'green';
    case 'RUNNING': return 'blue';
    case 'QUEUED': return 'orange';
    case 'FAILED': return 'red';
    default: return 'default';
  }
};

const formatTime = (iso: string) => {
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
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

const handleSubmit = () => {
  if (!question.value.trim()) return;
  isSubmitting.value = true;
  isGenerating.value = true;
  reportContent.value = '';
  stageLabel.value = 'Analyzing question...';

  streamHandle = reviewService.streamReport({
    question: question.value,
    onMessage: (data: string) => {
      isSubmitting.value = false;
      stageLabel.value = 'Generating report...';
      reportContent.value += data;
      isGenerating.value = false;
    },
    onError: (error: unknown) => {
      isSubmitting.value = false;
      isGenerating.value = false;
      errorMessage.value = error instanceof Error ? error.message : 'Report generation failed';
    },
    onComplete: () => {
      isSubmitting.value = false;
      isGenerating.value = false;
      if (!reportContent.value) {
        errorMessage.value = 'No report content received';
      }
      loadTaskHistory();
    },
  });
};

const handleAsyncSubmit = async () => {
  if (!question.value.trim()) return;
  isSubmitting.value = true;
  try {
    const result = await reviewService.submitTask(question.value);
    message.success(`Task submitted: ${result.taskId}`);
    await loadTaskHistory();
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : 'Submit failed';
  } finally {
    isSubmitting.value = false;
  }
};

const handleCancel = () => {
  streamHandle?.close();
  isGenerating.value = false;
  isSubmitting.value = false;
};

const handleNewReport = () => {
  reportContent.value = '';
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
    if (task.reportMarkdown) {
      reportContent.value = task.reportMarkdown;
      question.value = task.question;
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
  isGenerating.value = true;
  const interval = setInterval(async () => {
    try {
      const task = await reviewService.getTask(taskId);
      stageLabel.value = task.stage ?? 'Processing...';
      if (task.status === 'COMPLETED') {
        clearInterval(interval);
        isGenerating.value = false;
        if (task.reportMarkdown) {
          reportContent.value = task.reportMarkdown;
          question.value = task.question;
        }
      } else if (task.status === 'FAILED') {
        clearInterval(interval);
        isGenerating.value = false;
        errorMessage.value = task.errorMessage ?? 'Task failed';
      }
    } catch {
      clearInterval(interval);
      isGenerating.value = false;
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
