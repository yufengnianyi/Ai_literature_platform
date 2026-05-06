<template>
  <section v-if="tables.length" class="table-preview">
    <div class="preview-heading">
      <div>
        <h3>{{ isChinese ? '汇总表在线预览' : 'Summary Table Preview' }}</h3>
        <p>
          {{ isChinese
            ? `已根据任务证据生成 ${tables.length} 个汇总表预览，可在下载 xlsx 前快速检查。`
            : `${tables.length} summary table${tables.length > 1 ? 's' : ''} generated from task evidence. Preview them before downloading xlsx.` }}
        </p>
      </div>
      <a-tag color="blue">{{ tables.length }} {{ isChinese ? '个表格' : 'tables' }}</a-tag>
    </div>

    <a-tabs v-model:activeKey="activeTableId" size="small">
      <a-tab-pane v-for="table in tables" :key="table.id" :tab="table.title">
        <div class="table-frame">
          <table>
            <thead>
              <tr>
                <th v-for="header in table.headers" :key="header">{{ stripMarkdown(header) }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, rowIndex) in table.rows" :key="rowIndex">
                <td v-for="(cell, cellIndex) in row" :key="`${rowIndex}-${cellIndex}`">
                  {{ stripMarkdown(cell) }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </a-tab-pane>
    </a-tabs>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { ReviewDisplayLanguage } from '@/utils/reviewPresentation';
import type { ReviewSummaryTable } from '@/services/review';

const props = defineProps<{
  tables: ReviewSummaryTable[];
  language: ReviewDisplayLanguage;
}>();

const activeTableId = ref('');
const isChinese = computed(() => props.language === 'zh');

watch(
  () => props.tables,
  (tables) => {
    if (!tables.some(table => table.id === activeTableId.value)) {
      activeTableId.value = tables[0]?.id ?? '';
    }
  },
  { immediate: true },
);

const stripMarkdown = (value: string): string =>
  value
    .replace(/\*\*(.*?)\*\*/g, '$1')
    .replace(/__(.*?)__/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .trim();
</script>

<style scoped>
.table-preview {
  border: 1px solid #dbe5f2;
  border-radius: 8px;
  background: linear-gradient(180deg, #fbfdff 0%, #ffffff 100%);
  padding: 16px;
  margin-bottom: 16px;
}

.preview-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.preview-heading h3 {
  margin: 0 0 4px;
  font-size: 16px;
}

.preview-heading p {
  margin: 0;
  color: #667085;
  font-size: 13px;
}

.table-frame {
  overflow: auto;
  max-height: 420px;
  border: 1px solid #edf0f3;
  border-radius: 6px;
}

.table-frame table {
  width: 100%;
  min-width: 640px;
  border-collapse: collapse;
  font-size: 13px;
}

.table-frame th {
  position: sticky;
  top: 0;
  z-index: 1;
  background: #f6f8fb;
  color: #1f2937;
  font-weight: 600;
}

.table-frame th,
.table-frame td {
  border-bottom: 1px solid #edf0f3;
  border-right: 1px solid #edf0f3;
  padding: 9px 10px;
  text-align: left;
  vertical-align: top;
  line-height: 1.5;
}

.table-frame tr:last-child td {
  border-bottom: 0;
}

.table-frame th:last-child,
.table-frame td:last-child {
  border-right: 0;
}
</style>
