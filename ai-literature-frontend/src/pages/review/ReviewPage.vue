<template>
  <div class="review-page">
    <section class="review-shell">
      <header class="review-header">
        <div>
          <div class="eyebrow">Single-paper demo</div>
          <h2>Review 文献解读</h2>
          <p>问题命中 chunk 后自动选择最高分文献，回溯该文献全部 chunks，生成报告、模板表格和 xlsx。</p>
        </div>
        <a-tag color="blue">antimicrobial_compound</a-tag>
      </header>

      <div v-if="stage === 'inputting'" class="input-panel">
        <a-textarea
          v-model:value="question"
          :rows="5"
          :maxlength="2000"
          show-count
          placeholder="请帮我总结当前疫霉菌领域的抑菌化合物"
        />
        <div class="template-grid">
          <div
            v-for="field in templateFields"
            :key="field"
            class="template-chip"
          >
            {{ field }}
          </div>
        </div>
        <div class="action-bar">
          <a-button type="primary" size="large" :disabled="!question.trim()" @click="handleDirectSubmit">
            <ThunderboltOutlined />
            运行单篇 demo
          </a-button>
          <a-button size="large" :disabled="!question.trim()" :loading="isSubmitting" @click="handleAsyncSubmit">
            后台提交
          </a-button>
        </div>
      </div>

      <div v-if="stage === 'generating'" class="progress-panel">
        <a-spin />
        <div>
          <h3>{{ stageLabel }}</h3>
          <p>正在检索文献、选择最高分文献并生成单篇解读。</p>
        </div>
        <a-button danger @click="handleCancel">取消</a-button>
      </div>

      <div v-if="reportContent" class="result-panel">
        <div class="result-toolbar">
          <a-button @click="handleNewReport">新建解读</a-button>
          <a-button @click="handleCopyReport">
            <CopyOutlined />
            复制 Markdown
          </a-button>
          <a-button v-if="xlsxDownloadUrl" type="primary" ghost @click="handleDownloadXlsx">
            <FileExcelOutlined />
            下载 xlsx
          </a-button>
        </div>
        <ReviewTablePreview :tables="summaryTables" :language="activeLanguage" />
        <article class="report-content markdown-body" v-html="renderedReport"></article>
      </div>

      <a-alert
        v-if="errorMessage"
        type="error"
        :message="errorMessage"
        closable
        class="error-alert"
        @close="errorMessage = ''"
      />
    </section>

    <section v-if="stage === 'inputting'" class="history-section">
      <div class="history-title">最近任务</div>
      <a-list :data-source="taskHistory" :loading="loadingHistory" :locale="{ emptyText: '暂无 Review 任务' }">
        <template #renderItem="{ item }">
          <a-list-item class="task-item">
            <a-list-item-meta @click="loadTask(item.taskId)">
              <template #title>
                <span class="task-question">{{ item.question }}</span>
              </template>
              <template #description>
                <a-space>
                  <a-tag :color="statusColor(item.status)">{{ statusLabel(item) }}</a-tag>
                  <span v-if="item.selectedDocumentTitle">{{ item.selectedDocumentTitle }}</span>
                  <span>{{ formatTime(item.createdAt) }}</span>
                </a-space>
              </template>
            </a-list-item-meta>
            <template #actions>
              <a-tooltip v-if="item.status === 'FAILED'" title="重试">
                <a-button type="text" size="small" @click.stop="handleRetry(item)">
                  <template #icon><ReloadOutlined /></template>
                </a-button>
              </a-tooltip>
              <a-tooltip title="删除">
                <a-button type="text" danger size="small" @click.stop="handleDelete(item)">
                  <template #icon><DeleteOutlined /></template>
                </a-button>
              </a-tooltip>
            </template>
          </a-list-item>
        </template>
      </a-list>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { message, Modal } from 'ant-design-vue';
import {
  CopyOutlined,
  DeleteOutlined,
  FileExcelOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons-vue';
import {
  reviewService,
  type ReviewStreamHandle,
  type ReviewSummaryTable,
  type ReviewTaskRecord,
} from '@/services/review';
import { renderMarkdown } from '@/utils/markdown';
import {
  detectReviewLanguage,
  translateReviewStage,
  translateReviewStatus,
} from '@/utils/reviewPresentation';
import ReviewTablePreview from '@/components/review/ReviewTablePreview.vue';

type ReviewStage = 'inputting' | 'generating' | 'completed';

const TEMPLATE_ID = 'antimicrobial_compound';

const templateFields = [
  '化合物名称',
  '结构类型',
  '来源',
  '抑菌浓度',
  '作用病原菌',
  '试验方法',
  '靶标/机制',
  '安全性数据',
  '来源文献',
  '专利信息',
];

const stage = ref<ReviewStage>('inputting');
const question = ref('');
const reviewLanguageCode = ref<string>('zh');
const currentTaskId = ref('');
const reportContent = ref('');
const summaryTables = ref<ReviewSummaryTable[]>([]);
const stageLabel = ref('准备检索');
const errorMessage = ref('');
const xlsxDownloadUrl = ref('');
const taskHistory = ref<ReviewTaskRecord[]>([]);
const loadingHistory = ref(false);
const isSubmitting = ref(false);
let streamHandle: ReviewStreamHandle | null = null;
let pollInterval: ReturnType<typeof setInterval> | null = null;

const activeLanguage = computed(() => detectReviewLanguage(reviewLanguageCode.value, question.value));
const renderedReport = computed(() => reportContent.value ? renderMarkdown(reportContent.value) : '');

const statusColor = (status: string) => {
  if (status === 'COMPLETED') return 'green';
  if (status === 'RUNNING') return 'blue';
  if (status === 'QUEUED') return 'orange';
  if (status === 'FAILED') return 'red';
  return 'default';
};

const statusLabel = (task: ReviewTaskRecord) => translateReviewStatus(task.status, activeLanguage.value);

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

const resetResult = () => {
  reportContent.value = '';
  summaryTables.value = [];
  xlsxDownloadUrl.value = '';
  errorMessage.value = '';
};

const loadSummaryTables = async (taskId: string) => {
  try {
    summaryTables.value = await reviewService.getSummaryTables(taskId);
  } catch {
    summaryTables.value = [];
  }
};

const loadTaskHistory = async () => {
  loadingHistory.value = true;
  try {
    taskHistory.value = await reviewService.listTasks();
  } finally {
    loadingHistory.value = false;
  }
};

const handleDirectSubmit = () => {
  if (!question.value.trim()) return;
  cleanupPolling();
  streamHandle?.close();
  resetResult();
  stage.value = 'generating';
  stageLabel.value = '检索并生成报告';

  streamHandle = reviewService.streamReport({
    question: question.value,
    templateId: TEMPLATE_ID,
    onMessage: (data) => {
      stageLabel.value = '生成报告中';
      reportContent.value += data;
    },
    onError: (error) => {
      stage.value = reportContent.value ? 'completed' : 'inputting';
      errorMessage.value = error instanceof Error ? error.message : 'Review 生成失败';
    },
    onComplete: () => {
      stage.value = 'completed';
      if (streamHandle?.taskId) {
        currentTaskId.value = streamHandle.taskId;
        xlsxDownloadUrl.value = reviewService.getXlsxDownloadUrl(streamHandle.taskId);
        void loadSummaryTables(streamHandle.taskId);
      }
      void loadTaskHistory();
    },
  });
  currentTaskId.value = streamHandle.taskId ?? '';
};

const handleAsyncSubmit = async () => {
  if (!question.value.trim()) return;
  isSubmitting.value = true;
  errorMessage.value = '';
  try {
    const result = await reviewService.submitTask(question.value, TEMPLATE_ID);
    message.success(`已提交任务: ${result.taskId}`);
    await loadTaskHistory();
    pollTask(result.taskId);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '后台提交失败';
  } finally {
    isSubmitting.value = false;
  }
};

const handleCancel = () => {
  streamHandle?.close();
  streamHandle = null;
  cleanupPolling();
  stage.value = reportContent.value ? 'completed' : 'inputting';
};

const handleNewReport = () => {
  cleanupPolling();
  streamHandle?.close();
  streamHandle = null;
  stage.value = 'inputting';
  question.value = '';
  currentTaskId.value = '';
  resetResult();
};

const handleCopyReport = async () => {
  try {
    await navigator.clipboard.writeText(reportContent.value);
    message.success('已复制');
  } catch {
    message.error('复制失败');
  }
};

const handleDownloadXlsx = () => {
  if (xlsxDownloadUrl.value) {
    window.open(xlsxDownloadUrl.value, '_blank');
  }
};

const handleDelete = (task: ReviewTaskRecord) => {
  Modal.confirm({
    title: '删除任务',
    content: '删除后将无法在历史任务中查看该报告。',
    okText: '删除',
    okType: 'danger',
    async onOk() {
      await reviewService.deleteTask(task.taskId);
      await loadTaskHistory();
    },
  });
};

const handleRetry = async (task: ReviewTaskRecord) => {
  const result = await reviewService.retryTask(task.taskId);
  message.success(`已重新提交: ${result.taskId}`);
  await loadTaskHistory();
  pollTask(task.taskId);
};

const loadTask = async (taskId: string) => {
  try {
    const task = await reviewService.getTask(taskId);
    question.value = task.question;
    reviewLanguageCode.value = task.queryAnalysis?.languageCode ?? 'zh';
    currentTaskId.value = taskId;
    resetResult();
    if (task.reportMarkdown) {
      reportContent.value = task.reportMarkdown;
      xlsxDownloadUrl.value = reviewService.getXlsxDownloadUrl(taskId);
      stage.value = 'completed';
      await loadSummaryTables(taskId);
    } else if (task.status === 'RUNNING' || task.status === 'QUEUED') {
      pollTask(taskId);
    } else if (task.status === 'FAILED') {
      errorMessage.value = task.errorMessage ?? '任务失败';
    }
  } catch {
    message.error('任务加载失败');
  }
};

const pollTask = (taskId: string) => {
  cleanupPolling();
  stage.value = 'generating';
  stageLabel.value = '处理中';
  pollInterval = setInterval(async () => {
    try {
      const task = await reviewService.getTask(taskId);
      stageLabel.value = translateReviewStage(task.stage, activeLanguage.value);
      if (task.status === 'COMPLETED') {
        cleanupPolling();
        reportContent.value = task.reportMarkdown ?? '';
        question.value = task.question;
        xlsxDownloadUrl.value = reviewService.getXlsxDownloadUrl(taskId);
        stage.value = 'completed';
        await loadSummaryTables(taskId);
        await loadTaskHistory();
      } else if (task.status === 'FAILED') {
        cleanupPolling();
        stage.value = 'inputting';
        errorMessage.value = task.errorMessage ?? '任务失败';
      }
    } catch {
      cleanupPolling();
      stage.value = 'inputting';
      errorMessage.value = '连接中断';
    }
  }, 3000);
};

onMounted(() => {
  void loadTaskHistory();
});
</script>

<style scoped>
.review-page {
  width: min(1040px, calc(100% - 32px));
  margin: 0 auto;
  padding: 28px 0 40px;
}

.review-shell,
.history-section {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
}

.review-shell {
  padding: 24px;
}

.review-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  margin-bottom: 20px;
}

.review-header h2 {
  margin: 2px 0 6px;
  font-size: 24px;
  line-height: 1.25;
}

.review-header p {
  margin: 0;
  max-width: 720px;
  color: #64748b;
  line-height: 1.7;
}

.eyebrow {
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.input-panel :deep(textarea) {
  font-size: 15px;
  line-height: 1.7;
}

.template-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 14px 0 18px;
}

.template-chip {
  padding: 5px 10px;
  border-radius: 999px;
  background: #f3f4f6;
  color: #374151;
  font-size: 12px;
}

.action-bar,
.result-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.progress-panel {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 24px;
  border-radius: 8px;
  background: #f8fafc;
}

.progress-panel h3 {
  margin: 0 0 4px;
  font-size: 18px;
}

.progress-panel p {
  margin: 0;
  color: #64748b;
}

.progress-panel button {
  margin-left: auto;
}

.result-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.report-content {
  line-height: 1.8;
  font-size: 15px;
  overflow-x: auto;
}

.report-content :deep(h1) {
  font-size: 24px;
  margin: 10px 0 16px;
}

.report-content :deep(h2) {
  font-size: 19px;
  margin: 22px 0 10px;
}

.report-content :deep(table) {
  width: max-content;
  min-width: 100%;
  border-collapse: collapse;
  margin: 12px 0;
}

.report-content :deep(th),
.report-content :deep(td) {
  border: 1px solid #e5e7eb;
  padding: 8px 10px;
  text-align: left;
  vertical-align: top;
}

.report-content :deep(th) {
  background: #f8fafc;
}

.error-alert {
  margin-top: 16px;
}

.history-section {
  margin-top: 18px;
  padding: 18px 20px;
}

.history-title {
  margin-bottom: 12px;
  font-weight: 700;
}

.task-item {
  cursor: pointer;
}

.task-question {
  color: #111827;
}

@media (max-width: 720px) {
  .review-page {
    width: min(100% - 20px, 1040px);
    padding-top: 16px;
  }

  .review-shell {
    padding: 16px;
  }

  .review-header,
  .progress-panel {
    flex-direction: column;
  }

  .progress-panel button {
    margin-left: 0;
  }
}
</style>
