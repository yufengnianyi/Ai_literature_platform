<template>
  <div class="analysis-panel">
    <div class="panel-header">
      <div class="panel-icon">
        <BulbOutlined />
      </div>
      <div class="panel-title">
        <h3>Question Analysis</h3>
        <p class="panel-subtitle">
          AI has decomposed your question. Review and adjust the scope before generating the report.
        </p>
      </div>
    </div>

    <a-divider style="margin: 16px 0" />

    <!-- Main Question -->
    <div class="section">
      <div class="section-label">
        <EditOutlined />
        <span>Main Question</span>
      </div>
      <a-textarea
        v-model:value="editableMainQuestion"
        :rows="2"
        class="main-question-input"
      />
    </div>

    <!-- Sub-questions -->
    <div class="section">
      <div class="section-label">
        <UnorderedListOutlined />
        <span>Sub-questions</span>
        <a-tag color="blue" class="count-tag">{{ selectedSubQuestions.length }}/{{ allSubQuestions.length }}</a-tag>
      </div>
      <div class="sub-questions-list">
        <a-checkbox-group v-model:value="selectedSubQuestions" class="checkbox-group">
          <div v-for="sq in allSubQuestions" :key="sq" class="sub-question-item">
            <a-checkbox :value="sq">
              {{ sq }}
            </a-checkbox>
          </div>
        </a-checkbox-group>
      </div>
      <div class="add-custom">
        <a-input
          v-model:value="newSubQuestion"
          placeholder="Add a custom sub-question..."
          @pressEnter="addCustomSubQuestion"
          class="custom-input"
        >
          <template #suffix>
            <a-button type="link" size="small" :disabled="!newSubQuestion.trim()" @click="addCustomSubQuestion">
              <PlusOutlined />
            </a-button>
          </template>
        </a-input>
      </div>
    </div>

    <!-- Key Entities -->
    <div class="section" v-if="analysis.keyEntities.length > 0">
      <div class="section-label">
        <ExperimentOutlined />
        <span>Key Entities (Genes / Proteins)</span>
        <a-tag color="green" class="count-tag">{{ selectedEntities.length }}/{{ analysis.keyEntities.length }}</a-tag>
      </div>
      <div class="tag-selector">
        <a-checkable-tag
          v-for="entity in analysis.keyEntities"
          :key="entity"
          :checked="selectedEntities.includes(entity)"
          @change="(checked: boolean) => toggleEntity(entity, checked)"
          class="entity-tag"
        >
          {{ entity }}
        </a-checkable-tag>
      </div>
    </div>

    <!-- Key Concepts -->
    <div class="section" v-if="analysis.keyConcepts.length > 0">
      <div class="section-label">
        <TagsOutlined />
        <span>Key Concepts</span>
        <a-tag color="purple" class="count-tag">{{ selectedConcepts.length }}/{{ analysis.keyConcepts.length }}</a-tag>
      </div>
      <div class="tag-selector">
        <a-checkable-tag
          v-for="concept in analysis.keyConcepts"
          :key="concept"
          :checked="selectedConcepts.includes(concept)"
          @change="(checked: boolean) => toggleConcept(concept, checked)"
          class="concept-tag"
        >
          {{ concept }}
        </a-checkable-tag>
      </div>
    </div>

    <a-divider style="margin: 16px 0" />

    <!-- Actions -->
    <div class="panel-actions">
      <a-button @click="handleSelectAll" size="small">Select All</a-button>
      <a-button @click="handleDeselectAll" size="small">Deselect All</a-button>
      <div class="spacer" />
      <a-button @click="$emit('cancel')">Cancel</a-button>
      <a-button
        type="primary"
        :disabled="selectedSubQuestions.length === 0"
        @click="handleConfirm"
      >
        <ThunderboltOutlined />
        Confirm & Generate Report
      </a-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import type { QueryAnalysis, ReviewGenerateRequest } from '@/services/review';
import {
  BulbOutlined,
  EditOutlined,
  UnorderedListOutlined,
  ExperimentOutlined,
  TagsOutlined,
  PlusOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons-vue';

const props = defineProps<{
  analysis: QueryAnalysis;
  originalQuestion: string;
}>();

const emit = defineEmits<{
  confirm: [request: ReviewGenerateRequest];
  cancel: [];
}>();

const editableMainQuestion = ref(props.analysis.mainQuestion || props.originalQuestion);
const customSubQuestions = ref<string[]>([]);
const newSubQuestion = ref('');

const allSubQuestions = computed(() => [
  ...props.analysis.subQuestions,
  ...customSubQuestions.value,
]);

const selectedSubQuestions = ref<string[]>([...props.analysis.subQuestions]);
const selectedEntities = ref<string[]>([...props.analysis.keyEntities]);
const selectedConcepts = ref<string[]>([...props.analysis.keyConcepts]);

watch(() => props.analysis, (newAnalysis) => {
  editableMainQuestion.value = newAnalysis.mainQuestion || props.originalQuestion;
  selectedSubQuestions.value = [...newAnalysis.subQuestions];
  selectedEntities.value = [...newAnalysis.keyEntities];
  selectedConcepts.value = [...newAnalysis.keyConcepts];
  customSubQuestions.value = [];
});

const addCustomSubQuestion = () => {
  const trimmed = newSubQuestion.value.trim();
  if (!trimmed) return;
  if (allSubQuestions.value.includes(trimmed)) return;
  customSubQuestions.value.push(trimmed);
  selectedSubQuestions.value.push(trimmed);
  newSubQuestion.value = '';
};

const toggleEntity = (entity: string, checked: boolean) => {
  if (checked) {
    selectedEntities.value.push(entity);
  } else {
    selectedEntities.value = selectedEntities.value.filter(e => e !== entity);
  }
};

const toggleConcept = (concept: string, checked: boolean) => {
  if (checked) {
    selectedConcepts.value.push(concept);
  } else {
    selectedConcepts.value = selectedConcepts.value.filter(c => c !== concept);
  }
};

const handleSelectAll = () => {
  selectedSubQuestions.value = [...allSubQuestions.value];
  selectedEntities.value = [...props.analysis.keyEntities];
  selectedConcepts.value = [...props.analysis.keyConcepts];
};

const handleDeselectAll = () => {
  selectedSubQuestions.value = [];
  selectedEntities.value = [];
  selectedConcepts.value = [];
};

const handleConfirm = () => {
  const originalSubs = props.analysis.subQuestions;
  const selected = selectedSubQuestions.value.filter(sq => originalSubs.includes(sq));
  const custom = selectedSubQuestions.value.filter(sq => !originalSubs.includes(sq));

  const request: ReviewGenerateRequest = {
    question: props.originalQuestion,
    mainQuestion: editableMainQuestion.value,
    selectedSubQuestions: selected,
    selectedEntities: selectedEntities.value,
    selectedConcepts: selectedConcepts.value,
    customSubQuestions: custom,
  };
  emit('confirm', request);
};
</script>

<style scoped>
.analysis-panel {
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

.section {
  margin-bottom: 20px;
}

.section-label {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  font-weight: 500;
  font-size: 14px;
  color: #262626;
}

.count-tag {
  margin-left: auto;
  font-size: 12px;
}

.main-question-input {
  font-size: 14px;
}

.sub-questions-list {
  margin-bottom: 8px;
}

.checkbox-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
}

.sub-question-item {
  padding: 6px 12px;
  border-radius: 6px;
  transition: background 0.2s;
}

.sub-question-item:hover {
  background: #f5f5f5;
}

.add-custom {
  margin-top: 8px;
}

.custom-input {
  border-style: dashed;
}

.tag-selector {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.entity-tag {
  border-radius: 4px;
  padding: 2px 10px;
  font-size: 13px;
  cursor: pointer;
  user-select: none;
}

.concept-tag {
  border-radius: 4px;
  padding: 2px 10px;
  font-size: 13px;
  cursor: pointer;
  user-select: none;
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
