<template>
  <div class="scope-panel">
    <div class="scope-header">
      <div>
        <h3>{{ mode === 'analysis' ? 'Research Scope Preview' : 'Literature Scope Review' }}</h3>
        <p>
          {{ mode === 'analysis'
            ? 'Review the questions, entities, and early paper signals before retrieval.'
            : 'Confirm the questions, entities, and papers that should move into evidence extraction.' }}
        </p>
      </div>
      <a-tag color="blue">{{ selectedQuestionIds.size }} questions</a-tag>
      <a-tag color="green">{{ selectedEntityIds.size }} entities</a-tag>
      <a-tag color="purple">{{ selectedDocumentIds.size }} papers</a-tag>
    </div>

    <div class="scope-grid">
      <section class="scope-column">
        <div class="column-title">
          <QuestionCircleOutlined />
          <span>User Questions</span>
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
          placeholder="Add a focused sub-question"
          @pressEnter="addCustomQuestion"
        >
          <template #suffix>
            <a-button type="link" size="small" :disabled="!customQuestion.trim()" @click="addCustomQuestion">
              <PlusOutlined />
            </a-button>
          </template>
        </a-input>
      </section>

      <section class="scope-column">
        <div class="column-title">
          <ExperimentOutlined />
          <span>Related Entities</span>
        </div>
        <div class="tag-cloud">
          <a-checkable-tag
            v-for="entity in filteredEntities"
            :key="entity.id"
            :checked="selectedEntityIds.has(entity.id)"
            @change="(checked: boolean) => toggleEntity(entity.id, checked)"
          >
            {{ entity.displayName || entity.canonicalName }}
            <span class="tag-kind">{{ entity.category }}</span>
          </a-checkable-tag>
        </div>
      </section>

      <section class="scope-column document-column">
        <div class="column-title">
          <FileSearchOutlined />
          <span>Literature and Novelty</span>
        </div>
        <div class="document-toolbar">
          <a-select v-model:value="sortBy" size="small" style="width: 150px">
            <a-select-option value="score">Score</a-select-option>
            <a-select-option value="relevance">Relevance</a-select-option>
            <a-select-option value="title">Title</a-select-option>
          </a-select>
          <a-button size="small" @click="selectVisibleDocuments">Select visible</a-button>
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
              <h4>{{ document.title || 'Untitled document' }}</h4>
              <a-tag :color="relevanceColor(document.relevance)">{{ document.relevance || 'N/A' }}</a-tag>
            </div>
            <div class="document-meta">
              <span v-if="document.score !== null">Score {{ document.score.toFixed(2) }}</span>
              <a-tag :color="knowledgeColor(document.knowledgeStatus)" class="knowledge-tag">
                {{ document.knowledgeStatus || 'MISS' }}
              </a-tag>
              <span v-if="document.relatedEntities.length">{{ document.relatedEntities.slice(0, 4).join(', ') }}</span>
            </div>
            <div v-if="document.compounds?.length" class="mini-section compound-section">
              <strong>Compounds</strong>
              <div class="compound-tags">
                <a-tag v-for="compound in document.compounds.slice(0, 6)" :key="compound">
                  {{ compound }}
                </a-tag>
              </div>
            </div>
            <div v-if="document.compoundAliases?.length" class="mini-section">
              <strong>Alias resolution</strong>
              <ul>
                <li v-for="alias in document.compoundAliases.slice(0, 4)" :key="`${alias.localAlias}-${alias.resolutionStatus}`">
                  <span>{{ alias.localAlias }}</span>
                  <span> -> {{ alias.resolvedName || 'unresolved local label' }}</span>
                  <a-tag :color="aliasColor(alias.resolutionStatus)">{{ alias.resolutionStatus }}</a-tag>
                </li>
              </ul>
            </div>
            <div v-if="document.innovationPoints.length" class="mini-section">
              <strong>Innovation</strong>
              <ul>
                <li v-for="item in document.innovationPoints.slice(0, 3)" :key="item">{{ item }}</li>
              </ul>
            </div>
            <div v-if="document.keyFindings.length" class="mini-section">
              <strong>Key findings</strong>
              <ul>
                <li v-for="item in document.keyFindings.slice(0, 3)" :key="item">{{ item }}</li>
              </ul>
            </div>
            <a-collapse v-if="document.previewText" :bordered="false" class="preview-collapse">
              <a-collapse-panel key="preview" header="Preview text">
                <p>{{ document.previewText }}</p>
              </a-collapse-panel>
            </a-collapse>
          </article>
          <a-empty v-if="filteredDocuments.length === 0" description="No papers match the current selections" />
        </div>
      </section>
    </div>

    <a-divider style="margin: 16px 0" />

    <div class="panel-actions">
      <a-button @click="$emit('cancel')">{{ mode === 'analysis' ? 'Cancel' : 'Back' }}</a-button>
      <div class="spacer" />
      <a-button @click="selectAll">Select all</a-button>
      <a-button @click="clearAll">Clear</a-button>
      <a-button type="primary" :disabled="selectedQuestionIds.size === 0" @click="confirm">
        <ThunderboltOutlined />
        {{ mode === 'analysis' ? 'Confirm & Retrieve' : 'Confirm & Extract Evidence' }}
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

const resetSelections = () => {
  selectedQuestionIds.value = new Set(props.preview.questions.filter(q => q.selected).map(q => q.id));
  selectedEntityIds.value = new Set(props.preview.entities.filter(e => e.selected).map(e => e.id));
  selectedDocumentIds.value = new Set(props.preview.documents.filter(d => d.selected).map(d => d.id));
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
    return questionMatch && entityMatch;
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

const aliasColor = (status?: string | null) => {
  switch (status) {
    case 'RESOLVED': return 'green';
    case 'AMBIGUOUS': return 'orange';
    case 'UNRESOLVED': return 'red';
    default: return 'default';
  }
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

.scope-grid {
  display: grid;
  grid-template-columns: minmax(220px, 0.85fr) minmax(220px, 0.85fr) minmax(360px, 1.4fr);
  gap: 16px;
}

.scope-column {
  min-width: 0;
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

.document-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
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

.compound-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
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
  .scope-grid {
    grid-template-columns: 1fr;
  }
}
</style>
