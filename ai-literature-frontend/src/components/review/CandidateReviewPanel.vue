<template>
  <div class="candidate-review-panel">
    <div class="panel-header">
      <div class="panel-icon">
        <FileSearchOutlined />
      </div>
      <div class="panel-title">
        <h3>Candidate Literature Review</h3>
        <p class="panel-subtitle">
          Retrieved {{ candidates.length }} chunks,
          {{ includedCount }} included after AI reranking.
          Review and adjust before evidence extraction.
        </p>
      </div>
    </div>

    <a-divider style="margin: 16px 0" />

    <div class="filter-bar">
      <a-radio-group v-model:value="filterMode" button-style="solid" size="small">
        <a-radio-button value="all">All ({{ candidates.length }})</a-radio-button>
        <a-radio-button value="included">Included ({{ currentIncludedCount }})</a-radio-button>
        <a-radio-button value="excluded">Excluded ({{ currentExcludedCount }})</a-radio-button>
      </a-radio-group>
      <a-select v-model:value="sortBy" size="small" style="width: 160px; margin-left: 12px">
        <a-select-option value="relevance">Sort by Relevance</a-select-option>
        <a-select-option value="score">Sort by Score</a-select-option>
        <a-select-option value="title">Sort by Title</a-select-option>
      </a-select>
    </div>

    <div class="candidate-list">
      <div
        v-for="candidate in filteredCandidates"
        :key="candidate.chunkId"
        class="candidate-item"
        :class="{
          'is-excluded': excludedIds.has(candidate.chunkId),
          'is-prioritized': prioritizedIds.has(candidate.chunkId),
        }"
      >
        <div class="candidate-header">
          <a-checkbox
            :checked="!excludedIds.has(candidate.chunkId)"
            @change="(e: any) => toggleExclude(candidate.chunkId, !e.target.checked)"
          />
          <a-tag
            :color="relevanceColor(candidate.relevance)"
            class="relevance-tag"
          >
            {{ candidate.relevance || 'N/A' }}
          </a-tag>
          <span class="candidate-title">
            {{ candidate.documentTitle || 'Untitled' }}
          </span>
          <span class="candidate-score">
            Score: {{ (candidate.rerankScore ?? candidate.retrievalScore).toFixed(2) }}
          </span>
          <a-button
            v-if="!excludedIds.has(candidate.chunkId)"
            type="link"
            size="small"
            :class="{ 'priority-active': prioritizedIds.has(candidate.chunkId) }"
            @click="togglePrioritize(candidate.chunkId)"
          >
            <StarOutlined v-if="!prioritizedIds.has(candidate.chunkId)" />
            <StarFilled v-else />
            {{ prioritizedIds.has(candidate.chunkId) ? 'Prioritized' : 'Prioritize' }}
          </a-button>
        </div>
        <div class="candidate-reason" v-if="candidate.screeningReason">
          <span class="reason-label">AI Reason:</span> {{ candidate.screeningReason }}
        </div>
        <div class="candidate-source">
          Source: {{ candidate.retrievalSource }} | Chunk: {{ candidate.chunkId }}
        </div>
        <a-collapse :bordered="false" class="text-preview-collapse">
          <a-collapse-panel key="preview" header="Preview Text">
            <p class="chunk-text">{{ candidate.chunkText || 'No text available' }}</p>
          </a-collapse-panel>
        </a-collapse>
      </div>

      <a-empty v-if="filteredCandidates.length === 0" description="No candidates match current filter" />
    </div>

    <a-divider style="margin: 16px 0" />

    <div class="summary-bar">
      <span>
        <a-tag color="green">{{ currentIncludedCount }} included</a-tag>
        <a-tag color="red">{{ excludedIds.size }} user-excluded</a-tag>
        <a-tag color="gold">{{ prioritizedIds.size }} prioritized</a-tag>
      </span>
    </div>

    <div class="panel-actions">
      <a-button @click="$emit('cancel')">Back</a-button>
      <div class="spacer" />
      <a-button
        type="primary"
        :disabled="currentIncludedCount === 0"
        @click="handleConfirm"
      >
        <ExperimentOutlined />
        Confirm & Extract Evidence
      </a-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import type { ReviewCandidate, CandidateReviewRequest } from '@/services/review';
import {
  FileSearchOutlined,
  StarOutlined,
  StarFilled,
  ExperimentOutlined,
} from '@ant-design/icons-vue';

const props = defineProps<{
  candidates: ReviewCandidate[];
}>();

const emit = defineEmits<{
  confirm: [request: CandidateReviewRequest];
  cancel: [];
}>();

const filterMode = ref<'all' | 'included' | 'excluded'>('all');
const sortBy = ref<'relevance' | 'score' | 'title'>('relevance');

const excludedIds = ref<Set<string>>(new Set(
  props.candidates.filter(c => !c.included).map(c => c.chunkId)
));
const prioritizedIds = ref<Set<string>>(new Set());

const relevanceOrder: Record<string, number> = { HIGH: 0, MEDIUM: 1, LOW: 2, IRRELEVANT: 3 };

const includedCount = computed(() => props.candidates.filter(c => c.included).length);

const currentIncludedCount = computed(() =>
  props.candidates.filter(c => !excludedIds.value.has(c.chunkId)).length
);

const currentExcludedCount = computed(() => excludedIds.value.size);

const filteredCandidates = computed(() => {
  let list = [...props.candidates];

  if (filterMode.value === 'included') {
    list = list.filter(c => !excludedIds.value.has(c.chunkId));
  } else if (filterMode.value === 'excluded') {
    list = list.filter(c => excludedIds.value.has(c.chunkId));
  }

  list.sort((a, b) => {
    if (sortBy.value === 'relevance') {
      const aOrd = relevanceOrder[a.relevance ?? 'IRRELEVANT'] ?? 4;
      const bOrd = relevanceOrder[b.relevance ?? 'IRRELEVANT'] ?? 4;
      return aOrd - bOrd;
    }
    if (sortBy.value === 'score') {
      return (b.rerankScore ?? b.retrievalScore) - (a.rerankScore ?? a.retrievalScore);
    }
    return (a.documentTitle ?? '').localeCompare(b.documentTitle ?? '');
  });

  return list;
});

const relevanceColor = (relevance: string | null) => {
  switch (relevance) {
    case 'HIGH': return 'green';
    case 'MEDIUM': return 'blue';
    case 'LOW': return 'orange';
    case 'IRRELEVANT': return 'red';
    default: return 'default';
  }
};

const toggleExclude = (chunkId: string, excluded: boolean) => {
  const next = new Set(excludedIds.value);
  if (excluded) {
    next.add(chunkId);
    prioritizedIds.value.delete(chunkId);
  } else {
    next.delete(chunkId);
  }
  excludedIds.value = next;
};

const togglePrioritize = (chunkId: string) => {
  const next = new Set(prioritizedIds.value);
  if (next.has(chunkId)) {
    next.delete(chunkId);
  } else {
    next.add(chunkId);
  }
  prioritizedIds.value = next;
};

const handleConfirm = () => {
  const originalExcluded = new Set(props.candidates.filter(c => !c.included).map(c => c.chunkId));
  const userExcluded = [...excludedIds.value].filter(id => !originalExcluded.has(id));
  const userReIncluded = [...originalExcluded].filter(id => !excludedIds.value.has(id));

  const allExcluded = [...excludedIds.value].filter(id =>
    props.candidates.some(c => c.chunkId === id && c.included)
  );

  const request: CandidateReviewRequest = {
    excludedChunkIds: allExcluded,
    prioritizedChunkIds: [...prioritizedIds.value],
  };
  emit('confirm', request);
};
</script>

<style scoped>
.candidate-review-panel {
  background: #fff;
  border: 1px solid #d9d9d9;
  border-radius: 12px;
  padding: 24px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.panel-header {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.panel-icon {
  font-size: 24px;
  color: #1890ff;
  flex-shrink: 0;
  margin-top: 2px;
}

.panel-title h3 {
  margin: 0 0 4px;
  font-size: 18px;
  font-weight: 600;
}

.panel-subtitle {
  margin: 0;
  color: #8c8c8c;
  font-size: 13px;
}

.filter-bar {
  display: flex;
  align-items: center;
  margin-bottom: 16px;
}

.candidate-list {
  max-height: 500px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.candidate-item {
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  padding: 12px;
  transition: all 0.2s;
}

.candidate-item:hover {
  border-color: #d9d9d9;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.candidate-item.is-excluded {
  opacity: 0.5;
  background: #fafafa;
}

.candidate-item.is-prioritized {
  border-color: #faad14;
  background: #fffbe6;
}

.candidate-header {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.relevance-tag {
  font-size: 11px;
}

.candidate-title {
  font-weight: 500;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.candidate-score {
  color: #8c8c8c;
  font-size: 12px;
  flex-shrink: 0;
}

.priority-active {
  color: #faad14 !important;
}

.candidate-reason {
  margin-top: 4px;
  font-size: 12px;
  color: #595959;
}

.reason-label {
  color: #1890ff;
  font-weight: 500;
}

.candidate-source {
  margin-top: 2px;
  font-size: 11px;
  color: #bfbfbf;
}

.text-preview-collapse {
  margin-top: 8px;
}

.text-preview-collapse :deep(.ant-collapse-header) {
  padding: 4px 8px !important;
  font-size: 12px;
  color: #1890ff !important;
}

.text-preview-collapse :deep(.ant-collapse-content-box) {
  padding: 8px !important;
}

.chunk-text {
  font-size: 12px;
  line-height: 1.6;
  color: #595959;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 200px;
  overflow-y: auto;
  margin: 0;
}

.summary-bar {
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.panel-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.spacer {
  flex: 1;
}
</style>
