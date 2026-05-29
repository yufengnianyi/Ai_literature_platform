<template>
  <div class="scope-panel">
    <div class="scope-header">
      <div>
        <h3>{{ mode === 'analysis' ? labels.analysisTitle : labels.candidateTitle }}</h3>
        <p>
          {{ mode === 'analysis'
            ? labels.analysisDescription
            : labels.candidateDescription }}
        </p>
      </div>
      <a-tag color="blue">{{ selectedQuestionIds.size }} {{ labels.questions }}</a-tag>
      <a-tag color="green">{{ selectedEntityIds.size }} {{ labels.entities }}</a-tag>
      <a-tag color="purple">{{ selectedDocumentIds.size }} {{ labels.papers }}</a-tag>
    </div>

    <div class="scope-stack">
      <section class="scope-section questions-section">
        <div class="column-title">
          <QuestionCircleOutlined />
          <span>{{ labels.userQuestions }}</span>
        </div>
        <div class="option-list">
          <label
            v-for="question in preview.questions"
            :key="question.id"
            class="option-row"
            :class="{ selected: selectedQuestionIds.has(question.id) }"
          >
            <a-checkbox
              :checked="selectedQuestionIds.has(question.id)"
              @change="(event: any) => toggleQuestion(question.id, event.target.checked)"
            />
            <span>{{ question.displayText || question.canonicalText }}</span>
          </label>
        </div>
        <a-input
          v-if="mode === 'analysis'"
          v-model:value="customQuestion"
          :placeholder="labels.addQuestion"
          @pressEnter="addCustomQuestion"
        >
          <template #suffix>
            <a-button type="link" size="small" :disabled="!customQuestion.trim()" @click="addCustomQuestion">
              <PlusOutlined />
            </a-button>
          </template>
        </a-input>
      </section>

      <section class="scope-section entities-section">
        <div class="column-title">
          <ExperimentOutlined />
          <span>{{ labels.relatedEntities }}</span>
        </div>
        <div class="tag-cloud">
          <a-checkable-tag
            v-for="entity in filteredEntities"
            :key="entity.id"
            :checked="selectedEntityIds.has(entity.id)"
            @change="(checked: boolean) => toggleEntity(entity.id, checked)"
          >
            {{ entity.displayName || entity.canonicalName }}
            <span class="tag-kind">{{ categoryLabel(entity.category) }}</span>
          </a-checkable-tag>
        </div>
      </section>

      <section class="scope-section document-section">
        <div class="document-section-head">
          <div class="column-title">
            <FileSearchOutlined />
            <span>{{ labels.literature }}</span>
          </div>
          <div class="document-toolbar">
            <a-select v-model:value="sortBy" size="small" style="width: 150px">
              <a-select-option value="score">{{ labels.score }}</a-select-option>
              <a-select-option value="relevance">{{ labels.relevance }}</a-select-option>
              <a-select-option value="title">{{ labels.title }}</a-select-option>
            </a-select>
            <a-button size="small" @click="selectVisibleDocuments">{{ labels.selectVisible }}</a-button>
          </div>
        </div>
        <div v-if="mode === 'candidate' && hasScoredDocuments" class="score-threshold-panel">
          <div class="threshold-control">
            <span>{{ labels.minimumScore }}</span>
            <a-input-number
              v-model:value="minimumScore"
              :min="scoreRange.min"
              :max="scoreRange.max"
              :step="scoreStep"
              size="small"
            />
            <a-button size="small" @click="selectThresholdDocuments">{{ labels.selectByScore }}</a-button>
            <a-tag color="blue">{{ labels.matchingPapers }} {{ thresholdDocumentCount }}</a-tag>
          </div>
          <div class="score-histogram" :aria-label="labels.scoreDistribution">
            <div
              v-for="bucket in scoreBuckets"
              :key="bucket.label"
              class="score-bucket"
            >
              <div class="bucket-meta">
                <span class="bucket-label">{{ bucket.label }}</span>
                <span class="bucket-count">{{ bucket.count }}</span>
              </div>
              <div class="bucket-bar-shell">
                <div class="bucket-bar" :style="{ width: `${bucket.height}%` }"></div>
              </div>
            </div>
          </div>
        </div>
        <div class="document-list">
          <article
            v-for="document in filteredDocuments"
            :key="document.id"
            class="document-card"
            :class="{ selected: selectedDocumentIds.has(document.id) }"
          >
            <div class="document-head">
              <a-checkbox
                :checked="selectedDocumentIds.has(document.id)"
                @change="(event: any) => toggleDocument(document.id, event.target.checked)"
              />
              <h4>{{ document.title || labels.untitled }}</h4>
              <a-tag :color="relevanceColor(document.relevance)">{{ relevanceLabel(document.relevance) }}</a-tag>
            </div>
            <div class="document-meta">
              <span v-if="document.score !== null">{{ labels.score }} {{ document.score.toFixed(2) }}</span>
              <a-tag :color="knowledgeColor(document.knowledgeStatus)" class="knowledge-tag">
                {{ knowledgeLabel(document.knowledgeStatus) }}
              </a-tag>
              <span v-if="document.relatedEntities.length">{{ document.relatedEntities.slice(0, 4).join(', ') }}</span>
            </div>
            <div v-if="document.innovationPoints.length" class="mini-section">
              <strong>{{ labels.innovation }}</strong>
              <ul>
                <li v-for="item in document.innovationPoints.slice(0, 3)" :key="item">{{ item }}</li>
              </ul>
            </div>
            <div v-if="document.keyFindings.length" class="mini-section">
              <strong>{{ labels.keyFindings }}</strong>
              <ul>
                <li v-for="item in document.keyFindings.slice(0, 3)" :key="item">{{ item }}</li>
              </ul>
            </div>
            <a-collapse v-if="document.previewText" :bordered="false" class="preview-collapse">
              <a-collapse-panel key="preview" :header="labels.previewText">
                <p>{{ document.previewText }}</p>
              </a-collapse-panel>
            </a-collapse>
          </article>
          <a-empty v-if="filteredDocuments.length === 0" :description="labels.noPapers" />
        </div>
      </section>
    </div>

    <a-divider style="margin: 16px 0" />

    <div class="panel-actions">
      <a-button @click="$emit('cancel')">{{ mode === 'analysis' ? labels.cancel : labels.back }}</a-button>
      <div class="spacer" />
      <a-button @click="selectAll">{{ labels.selectAll }}</a-button>
      <a-button @click="clearAll">{{ labels.clear }}</a-button>
      <a-button type="primary" :disabled="!canConfirm" @click="confirm">
        <ThunderboltOutlined />
        {{ mode === 'analysis' ? labels.confirmRetrieve : labels.confirmExtract }}
      </a-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type {
  CandidateReviewRequest,
  ReviewGenerateRequest,
  ReviewScopePreview,
} from '@/services/review';
import { detectReviewLanguage } from '@/utils/reviewPresentation';
import {
  ExperimentOutlined,
  FileSearchOutlined,
  PlusOutlined,
  QuestionCircleOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons-vue';

const props = defineProps<{
  preview: ReviewScopePreview;
  originalQuestion: string;
  mode: 'analysis' | 'candidate';
  languageCode?: string;
}>();

const emit = defineEmits<{
  confirmAnalysis: [request: ReviewGenerateRequest];
  confirmCandidates: [request: CandidateReviewRequest];
  cancel: [];
}>();

const selectedQuestionIds = ref(new Set<string>());
const selectedEntityIds = ref(new Set<string>());
const selectedDocumentIds = ref(new Set<string>());
const customQuestions = ref<string[]>([]);
const customQuestion = ref('');
const sortBy = ref<'score' | 'relevance' | 'title'>('score');
const minimumScore = ref(0.6);

const isChinese = computed(() =>
  detectReviewLanguage(props.languageCode ?? props.preview.analysis.languageCode, props.originalQuestion) === 'zh',
);

const labels = computed(() => isChinese.value ? {
  analysisTitle: '研究范围预览',
  candidateTitle: '文献范围审阅',
  analysisDescription: '检索前请审阅子问题、实体和早期文献信号。',
  candidateDescription: '选择要进入多篇解读的文献；超过 5 篇时生成时间可能较长。',
  questions: '个问题',
  entities: '个实体',
  papers: '篇文献',
  userQuestions: '扩展的问题',
  addQuestion: '添加一个聚焦子问题',
  relatedEntities: '问题关联实体',
  literature: '文献预览',
  score: '评分',
  minimumScore: '最低评分筛选',
  matchingPapers: '达标文献',
  selectByScore: '选择达标文献',
  scoreDistribution: '评分分布',
  relevance: '相关性',
  title: '标题',
  selectVisible: '全选',
  untitled: '未命名文献',
  innovation: '创新点',
  keyFindings: '关键发现',
  previewText: '预览文本',
  noPapers: '没有符合当前筛选的文献',
  cancel: '取消',
  back: '返回',
  selectAll: '全选',
  clear: '清空',
  confirmRetrieve: '确认并检索',
  confirmExtract: '确认并总结文献',
} : {
  analysisTitle: 'Research Scope Preview',
  candidateTitle: 'Literature Scope Review',
  analysisDescription: 'Review the questions, entities, and early paper signals before retrieval.',
  candidateDescription: 'Select the papers to include in the multi-paper review. More than 5 papers may take longer.',
  questions: 'questions',
  entities: 'entities',
  papers: 'papers',
  userQuestions: 'User Questions',
  addQuestion: 'Add a focused sub-question',
  relatedEntities: 'Related Entities',
  literature: 'Literature Preview',
  score: 'Score',
  minimumScore: 'Minimum score filter',
  matchingPapers: 'Matching papers',
  selectByScore: 'Select by score',
  scoreDistribution: 'Score distribution',
  relevance: 'Relevance',
  title: 'Title',
  selectVisible: 'Select all',
  untitled: 'Untitled document',
  innovation: 'Innovation',
  keyFindings: 'Key findings',
  previewText: 'Preview text',
  noPapers: 'No papers match the current selections',
  cancel: 'Cancel',
  back: 'Back',
  selectAll: 'Select all',
  clear: 'Clear',
  confirmRetrieve: 'Confirm & Retrieve',
  confirmExtract: 'Confirm & Summarize',
});

const canConfirm = computed(() => {
  if (selectedQuestionIds.value.size === 0) return false;
  if (props.mode === 'candidate') {
    return selectedDocumentIds.value.size > 0;
  }
  return true;
});

const scoredDocuments = computed(() =>
  props.preview.documents
    .map(document => document.score)
    .filter((score): score is number => typeof score === 'number' && Number.isFinite(score))
);

const hasScoredDocuments = computed(() => scoredDocuments.value.length > 0);

const scoreRange = computed(() => {
  if (!hasScoredDocuments.value) {
    return { min: 0, max: 0 };
  }
  return {
    min: Math.min(...scoredDocuments.value),
    max: Math.max(...scoredDocuments.value),
  };
});

const scoreStep = computed(() => {
  const span = scoreRange.value.max - scoreRange.value.min;
  if (span >= 10) return 1;
  if (span >= 1) return 0.1;
  return 0.01;
});

const formatScore = (value: number) => {
  const span = scoreRange.value.max - scoreRange.value.min;
  if (span >= 10) return value.toFixed(0);
  if (span >= 1) return value.toFixed(1);
  return value.toFixed(2);
};

const scoreBuckets = computed(() => {
  if (!hasScoredDocuments.value) return [];
  const bucketCount = Math.min(6, Math.max(1, scoredDocuments.value.length));
  const min = scoreRange.value.min;
  const max = scoreRange.value.max;
  const span = Math.max(max - min, 1);
  const buckets = Array.from({ length: bucketCount }, (_, index) => {
    const start = min + (span * index) / bucketCount;
    const end = index === bucketCount - 1 ? max : min + (span * (index + 1)) / bucketCount;
    return {
      start,
      end,
      count: 0,
      label: `${formatScore(start)}-${formatScore(end)}`,
      height: 0,
    };
  });
  for (const score of scoredDocuments.value) {
    const index = max === min
      ? 0
      : Math.min(bucketCount - 1, Math.floor(((score - min) / span) * bucketCount));
    const bucket = buckets[index];
    if (bucket) {
      bucket.count += 1;
    }
  }
  const maxCount = Math.max(...buckets.map(bucket => bucket.count), 1);
  return buckets.map(bucket => ({
    ...bucket,
    height: bucket.count === 0 ? 0 : Math.max(12, (bucket.count / maxCount) * 100),
  }));
});

const resetSelections = () => {
  selectedQuestionIds.value = new Set(props.preview.questions.filter(q => q.selected).map(q => q.id));
  selectedEntityIds.value = new Set(props.preview.entities.filter(e => e.selected).map(e => e.id));
  selectedDocumentIds.value = new Set(props.preview.documents.filter(d => d.selected).map(d => d.id));
  minimumScore.value = scoreRange.value.min;
  customQuestions.value = [];
  customQuestion.value = '';
};

watch(() => props.preview, resetSelections, { immediate: true });

const selectedQuestions = computed(() =>
  props.preview.questions.filter(q => selectedQuestionIds.value.has(q.id))
);

const filteredEntities = computed(() => {
  if (selectedQuestionIds.value.size === 0) return props.preview.entities;
  return props.preview.entities.filter(entity =>
    entity.relatedQuestionIds.length === 0 ||
    entity.relatedQuestionIds.some(id => selectedQuestionIds.value.has(id))
  );
});

const filteredDocuments = computed(() => {
  const relevanceOrder: Record<string, number> = { HIGH: 0, MEDIUM: 1, LOW: 2, IRRELEVANT: 3 };
  const selectedEntityNames = props.preview.entities
    .filter(entity => selectedEntityIds.value.has(entity.id))
    .map(entity => entity.canonicalName);

  const list = props.preview.documents.filter(document => {
    const questionMatch = selectedQuestionIds.value.size === 0 ||
      document.relatedQuestionIds.length === 0 ||
      document.relatedQuestionIds.some(id => selectedQuestionIds.value.has(id));
    const entityMatch = selectedEntityNames.length === 0 ||
      document.relatedEntities.length === 0 ||
      document.relatedEntities.some(entity => selectedEntityNames.includes(entity));
    const scoreMatch = props.mode !== 'candidate' || !hasScoredDocuments.value ||
      scoreValue(document.score) >= minimumScore.value;
    return questionMatch && entityMatch && scoreMatch;
  });

  return [...list].sort((a, b) => {
    if (sortBy.value === 'relevance') {
      return (relevanceOrder[a.relevance ?? 'IRRELEVANT'] ?? 4) -
        (relevanceOrder[b.relevance ?? 'IRRELEVANT'] ?? 4);
    }
    if (sortBy.value === 'title') {
      return (a.title ?? '').localeCompare(b.title ?? '');
    }
    return (b.score ?? 0) - (a.score ?? 0);
  });
});

const scoreValue = (score: number | null | undefined) => score ?? -1;

const thresholdDocumentCount = computed(() =>
  props.preview.documents.filter(document => scoreValue(document.score) >= minimumScore.value).length
);

const setChecked = (source: Set<string>, id: string, checked: boolean) => {
  const next = new Set(source);
  if (checked) next.add(id);
  else next.delete(id);
  return next;
};

const toggleQuestion = (id: string, checked: boolean) => {
  selectedQuestionIds.value = setChecked(selectedQuestionIds.value, id, checked);
};

const toggleEntity = (id: string, checked: boolean) => {
  selectedEntityIds.value = setChecked(selectedEntityIds.value, id, checked);
};

const toggleDocument = (id: string, checked: boolean) => {
  selectedDocumentIds.value = setChecked(selectedDocumentIds.value, id, checked);
};

const selectThresholdDocuments = () => {
  selectedDocumentIds.value = new Set(
    props.preview.documents
      .filter(document => document.documentId && scoreValue(document.score) >= minimumScore.value)
      .map(document => document.id)
  );
};

watch(minimumScore, () => {
  if (props.mode === 'candidate') {
    selectThresholdDocuments();
  }
});

const addCustomQuestion = () => {
  const value = customQuestion.value.trim();
  if (!value || customQuestions.value.includes(value)) return;
  customQuestions.value.push(value);
  customQuestion.value = '';
};

const selectVisibleDocuments = () => {
  selectedDocumentIds.value = new Set([
    ...selectedDocumentIds.value,
    ...filteredDocuments.value.map(document => document.id),
  ]);
};

const selectAll = () => {
  selectedQuestionIds.value = new Set(props.preview.questions.map(q => q.id));
  selectedEntityIds.value = new Set(props.preview.entities.map(e => e.id));
  selectedDocumentIds.value = new Set(props.preview.documents.map(d => d.id));
};

const clearAll = () => {
  selectedQuestionIds.value = new Set();
  selectedEntityIds.value = new Set();
  selectedDocumentIds.value = new Set();
};

const confirm = () => {
  if (props.mode === 'analysis') {
    const selectedSubQuestions = selectedQuestions.value.map(q => q.canonicalText);
    const selectedEntities = props.preview.entities
      .filter(entity => selectedEntityIds.value.has(entity.id) && entity.category === 'ENTITY')
      .map(entity => entity.canonicalName);
    const selectedConcepts = props.preview.entities
      .filter(entity => selectedEntityIds.value.has(entity.id) && entity.category !== 'ENTITY')
      .map(entity => entity.canonicalName);
    emit('confirmAnalysis', {
      question: props.originalQuestion,
      mainQuestion: props.preview.analysis.mainQuestion,
      selectedSubQuestions,
      selectedEntities,
      selectedConcepts,
      customSubQuestions: customQuestions.value,
      languageCode: props.preview.analysis.languageCode,
      displayMainQuestion: props.preview.analysis.displayMainQuestion,
      displaySubQuestions: props.preview.analysis.displaySubQuestions,
    });
    return;
  }

  const selectedDocuments = props.preview.documents
    .filter(document => selectedDocumentIds.value.has(document.id) && document.documentId)
    .map(document => document.documentId as string);
  const excludedChunkIds = props.preview.documents
    .filter(document => !selectedDocumentIds.value.has(document.id))
    .flatMap(document => document.chunkIds);
  emit('confirmCandidates', {
    excludedChunkIds,
    prioritizedChunkIds: [],
    selectedDocumentIds: selectedDocuments,
    selectedQuestionIds: [...selectedQuestionIds.value],
    selectedEntityIds: [...selectedEntityIds.value],
  });
};

const relevanceColor = (relevance: string | null) => {
  switch (relevance) {
    case 'HIGH': return 'green';
    case 'MEDIUM': return 'blue';
    case 'LOW': return 'orange';
    case 'IRRELEVANT': return 'red';
    default: return 'default';
  }
};

const knowledgeColor = (status?: string | null) => {
  switch (status) {
    case 'HIT': return 'green';
    case 'PARTIAL': return 'gold';
    case 'STALE': return 'orange';
    case 'MISS': return 'default';
    default: return 'default';
  }
};

const relevanceLabel = (relevance: string | null) => {
  if (!isChinese.value) return relevance || 'N/A';
  switch (relevance) {
    case 'HIGH': return '高相关';
    case 'MEDIUM': return '中等相关';
    case 'LOW': return '低相关';
    case 'IRRELEVANT': return '不相关';
    default: return '暂无';
  }
};

const knowledgeLabel = (status?: string | null) => {
  if (!isChinese.value) return status || 'MISS';
  switch (status) {
    case 'HIT': return '已命中';
    case 'PARTIAL': return '部分命中';
    case 'STALE': return '需更新';
    case 'MISS':
    case null:
    case undefined:
      return '未命中';
    default:
      return status;
  }
};

const categoryLabel = (category: string) => {
  if (!isChinese.value) return category;
  return category === 'ENTITY' ? '实体' : '概念';
};
</script>

<style scoped>
.scope-panel {
  background: #fff;
  border: 1px solid #d9d9d9;
  border-radius: 8px;
  padding: 20px;
}

.scope-header {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 16px;
}

.scope-header > div:first-child {
  flex: 1;
}

.scope-header h3 {
  margin: 0 0 4px;
  font-size: 18px;
}

.scope-header p {
  margin: 0;
  color: #667085;
}

.scope-stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.scope-section {
  min-width: 0;
  padding: 14px;
  border: 1px solid #edf0f3;
  border-radius: 8px;
  background: #fbfcfe;
}

.column-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  font-weight: 600;
}

.option-list,
.document-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.questions-section .option-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.option-row {
  display: grid;
  grid-template-columns: 20px 1fr;
  gap: 8px;
  padding: 8px;
  border: 1px solid #edf0f3;
  border-radius: 6px;
  cursor: pointer;
}

.option-row.selected,
.document-card.selected {
  border-color: #1677ff;
  background: #f3f8ff;
}

.tag-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tag-kind {
  margin-left: 4px;
  color: #8c8c8c;
  font-size: 11px;
}

.document-section-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 10px;
}

.document-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.score-threshold-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 10px;
  padding: 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
}

.threshold-control,
.threshold-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.threshold-control {
  color: #475569;
  font-size: 13px;
}

.threshold-control :deep(.ant-input-number) {
  width: 92px;
}

.score-histogram {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px 12px;
  align-items: center;
}

.score-bucket {
  display: grid;
  grid-template-columns: 58px minmax(80px, 1fr);
  gap: 8px;
  align-items: center;
  min-width: 0;
  color: #475569;
  font-size: 11px;
}

.bucket-meta {
  display: grid;
  min-width: 0;
}

.bucket-bar-shell {
  position: relative;
  min-width: 0;
  height: 8px;
  border-radius: 999px;
  background: #e8edf3;
  overflow: hidden;
}

.bucket-bar {
  height: 100%;
  border-radius: inherit;
  background: #64748b;
}

.bucket-count {
  color: #0f172a;
  font-weight: 700;
  line-height: 1.2;
}

.bucket-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #64748b;
  line-height: 1.2;
}

.document-list {
  max-height: 620px;
  overflow-y: auto;
}

.document-card {
  border: 1px solid #edf0f3;
  border-radius: 8px;
  padding: 12px;
}

.document-head {
  display: grid;
  grid-template-columns: 20px 1fr auto;
  gap: 8px;
  align-items: start;
}

.document-head h4 {
  margin: 0;
  font-size: 14px;
  line-height: 1.4;
}

.document-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin: 6px 0;
  color: #667085;
  font-size: 12px;
}

.knowledge-tag {
  margin-inline-end: 0;
}

.mini-section {
  margin-top: 8px;
}

.mini-section strong {
  display: block;
  margin-bottom: 4px;
  font-size: 12px;
}

.mini-section ul {
  margin: 0;
  padding-left: 18px;
  color: #344054;
}

.mini-section li {
  margin-bottom: 2px;
  font-size: 12px;
  line-height: 1.45;
}

.preview-collapse {
  margin-top: 8px;
}

.preview-collapse p {
  margin: 0;
  white-space: pre-wrap;
  font-size: 12px;
  line-height: 1.6;
}

.panel-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.spacer {
  flex: 1;
}

@media (max-width: 1100px) {
  .questions-section .option-list {
    grid-template-columns: 1fr;
  }

  .document-section-head {
    flex-direction: column;
  }

  .score-histogram {
    grid-template-columns: 1fr;
  }
}
</style>
