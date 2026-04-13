<template>
  <div class="evidence-review-panel">
    <div class="panel-header">
      <div class="panel-icon">
        <AuditOutlined />
      </div>
      <div class="panel-title">
        <h3>Evidence Synthesis Review</h3>
        <p class="panel-subtitle">
          {{ evidence.length }} evidence items extracted, grouped into {{ subQuestionGroups.length }} sub-questions.
          Review and adjust before report generation.
        </p>
      </div>
    </div>

    <a-divider style="margin: 16px 0" />

    <div class="evidence-groups">
      <div
        v-for="(group, gi) in subQuestionGroups"
        :key="gi"
        class="evidence-group"
      >
        <div class="group-header">
          <div class="group-title">
            <span class="group-number">Sub-question {{ gi + 1 }}:</span>
            {{ group.subQuestion }}
          </div>
          <a-button
            :type="focusedSubQuestions.has(group.subQuestion) ? 'primary' : 'default'"
            size="small"
            @click="toggleFocus(group.subQuestion)"
          >
            <AimOutlined />
            {{ focusedSubQuestions.has(group.subQuestion) ? 'Focused' : 'Focus' }}
          </a-button>
        </div>

        <div class="evidence-items">
          <div
            v-for="item in group.items"
            :key="item.id"
            class="evidence-item"
            :class="{ 'is-excluded': excludedIds.has(item.id) }"
          >
            <div class="evidence-item-header">
              <a-checkbox
                :checked="!excludedIds.has(item.id)"
                @change="(e: any) => toggleExclude(item.id, !e.target.checked)"
              />
              <a-tag
                v-if="item.consistency"
                :color="consistencyColor(item.consistency)"
                class="consistency-tag"
              >
                {{ item.consistency }}
              </a-tag>
              <span class="evidence-claim">{{ item.claim || 'No claim' }}</span>
              <span class="evidence-confidence">
                {{ (item.confidence * 100).toFixed(0) }}%
              </span>
            </div>
            <div class="evidence-finding" v-if="item.finding">
              <strong>Finding:</strong> {{ item.finding }}
            </div>
            <div class="evidence-meta">
              <span v-if="item.methodology" class="meta-item">
                <ExperimentOutlined /> {{ item.methodology }}
              </span>
              <span v-if="item.evidenceType" class="meta-item">
                <TagOutlined /> {{ item.evidenceType }}
              </span>
            </div>
            <div class="evidence-entities" v-if="item.entities && item.entities.length > 0">
              <a-tag v-for="entity in item.entities" :key="entity" size="small" color="cyan">
                {{ entity }}
              </a-tag>
            </div>
            <a-collapse :bordered="false" class="text-preview-collapse">
              <a-collapse-panel key="text" header="Original Text">
                <p class="original-text">{{ item.originalText || 'No text available' }}</p>
              </a-collapse-panel>
            </a-collapse>
          </div>
        </div>

        <div class="group-stats">
          <a-tag color="green">{{ group.includedCount }} included</a-tag>
          <a-tag color="red">{{ group.excludedCount }} excluded</a-tag>
        </div>
      </div>

      <a-empty v-if="subQuestionGroups.length === 0" description="No evidence available" />
    </div>

    <a-divider style="margin: 16px 0" />

    <div class="guidance-section">
      <div class="section-label">
        <EditOutlined />
        <span>Report Guidance (optional)</span>
      </div>
      <a-textarea
        v-model:value="userGuidance"
        placeholder="Provide specific instructions for the report, e.g., 'Focus on the expansion mechanisms of the LRR-XII subfamily, with attention to whole-genome duplication events'"
        :rows="3"
        :maxlength="1000"
        show-count
      />
    </div>

    <div class="summary-bar">
      <span>
        <a-tag color="green">{{ totalIncluded }} evidence included</a-tag>
        <a-tag color="red">{{ excludedIds.size }} user-excluded</a-tag>
        <a-tag color="blue">{{ focusedSubQuestions.size }} sub-questions focused</a-tag>
      </span>
    </div>

    <div class="panel-actions">
      <a-button @click="$emit('cancel')">Back</a-button>
      <div class="spacer" />
      <a-button
        type="primary"
        :disabled="totalIncluded === 0"
        @click="handleConfirm"
      >
        <ThunderboltOutlined />
        Generate Report
      </a-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import type { ReviewEvidenceRecord, EvidenceReviewRequest } from '@/services/review';
import {
  AuditOutlined,
  AimOutlined,
  EditOutlined,
  ExperimentOutlined,
  TagOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons-vue';

const props = defineProps<{
  evidence: ReviewEvidenceRecord[];
}>();

const emit = defineEmits<{
  confirm: [request: EvidenceReviewRequest];
  cancel: [];
}>();

const excludedIds = ref<Set<number>>(new Set());
const focusedSubQuestions = ref<Set<string>>(new Set());
const userGuidance = ref('');

interface EvidenceGroup {
  subQuestion: string;
  items: ReviewEvidenceRecord[];
  includedCount: number;
  excludedCount: number;
}

const subQuestionGroups = computed<EvidenceGroup[]>(() => {
  const groupMap = new Map<string, ReviewEvidenceRecord[]>();
  for (const item of props.evidence) {
    const sq = item.subQuestion || 'General';
    if (!groupMap.has(sq)) groupMap.set(sq, []);
    groupMap.get(sq)!.push(item);
  }

  return Array.from(groupMap.entries()).map(([sq, items]) => ({
    subQuestion: sq,
    items,
    includedCount: items.filter(i => !excludedIds.value.has(i.id)).length,
    excludedCount: items.filter(i => excludedIds.value.has(i.id)).length,
  }));
});

const totalIncluded = computed(() =>
  props.evidence.filter(e => !excludedIds.value.has(e.id)).length
);

const consistencyColor = (consistency: string | null) => {
  switch (consistency) {
    case 'CONSISTENT': return 'green';
    case 'CONFLICTING': return 'red';
    case 'INSUFFICIENT': return 'orange';
    default: return 'default';
  }
};

const toggleExclude = (id: number, excluded: boolean) => {
  const next = new Set(excludedIds.value);
  if (excluded) {
    next.add(id);
  } else {
    next.delete(id);
  }
  excludedIds.value = next;
};

const toggleFocus = (subQuestion: string) => {
  const next = new Set(focusedSubQuestions.value);
  if (next.has(subQuestion)) {
    next.delete(subQuestion);
  } else {
    next.add(subQuestion);
  }
  focusedSubQuestions.value = next;
};

const handleConfirm = () => {
  const request: EvidenceReviewRequest = {
    excludedEvidenceIds: [...excludedIds.value],
    focusSubQuestions: [...focusedSubQuestions.value],
    userGuidance: userGuidance.value.trim(),
  };
  emit('confirm', request);
};
</script>

<style scoped>
.evidence-review-panel {
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
  color: #722ed1;
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

.evidence-groups {
  max-height: 500px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.evidence-group {
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  padding: 16px;
}

.group-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.group-title {
  font-weight: 500;
  font-size: 14px;
  flex: 1;
}

.group-number {
  color: #1890ff;
  margin-right: 4px;
}

.evidence-items {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.evidence-item {
  border: 1px solid #f5f5f5;
  border-radius: 6px;
  padding: 10px;
  transition: all 0.2s;
}

.evidence-item:hover {
  border-color: #d9d9d9;
}

.evidence-item.is-excluded {
  opacity: 0.5;
  background: #fafafa;
}

.evidence-item-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.consistency-tag {
  font-size: 10px;
}

.evidence-claim {
  flex: 1;
  font-size: 13px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.evidence-confidence {
  color: #8c8c8c;
  font-size: 12px;
  flex-shrink: 0;
}

.evidence-finding {
  margin-top: 4px;
  font-size: 12px;
  color: #595959;
}

.evidence-meta {
  margin-top: 4px;
  display: flex;
  gap: 12px;
  font-size: 11px;
  color: #8c8c8c;
}

.meta-item {
  display: flex;
  align-items: center;
  gap: 4px;
}

.evidence-entities {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.text-preview-collapse {
  margin-top: 6px;
}

.text-preview-collapse :deep(.ant-collapse-header) {
  padding: 4px 8px !important;
  font-size: 12px;
  color: #722ed1 !important;
}

.text-preview-collapse :deep(.ant-collapse-content-box) {
  padding: 8px !important;
}

.original-text {
  font-size: 12px;
  line-height: 1.6;
  color: #595959;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 150px;
  overflow-y: auto;
  margin: 0;
}

.group-stats {
  margin-top: 8px;
  display: flex;
  gap: 4px;
}

.guidance-section {
  margin-bottom: 16px;
}

.section-label {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-weight: 500;
  font-size: 14px;
  color: #262626;
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
