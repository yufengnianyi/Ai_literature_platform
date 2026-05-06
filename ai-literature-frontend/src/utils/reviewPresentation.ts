export type ReviewDisplayLanguage = 'zh' | 'en';

export interface MarkdownTablePreview {
  id: string;
  title: string;
  headers: string[];
  rows: string[][];
}

const cjkPattern = /[\u3400-\u9fff\uf900-\ufaff]/;

const reviewLabels = {
  zh: {
    pageTitle: '系统综述报告',
    pageSubtitle: '输入研究问题，生成可审阅、可下载的系统综述报告',
    questionPlaceholder:
      '输入你的研究问题，例如：陆生植物中 LRR-RLK 基因家族的进化起源和分化模式是什么？',
    analyze: '分析并配置',
    quickGenerate: '快速生成（跳过配置）',
    submitBackground: '后台提交',
    analyzingTitle: '正在分析问题...',
    analyzingText: '正在拆解子问题、实体和关键概念',
    retrievingTitle: '正在检索并重排文献...',
    extractingTitle: '正在抽取并融合证据...',
    generatingTitle: '正在生成报告...',
    cancel: '取消',
    newReport: '新建报告',
    copyMarkdown: '复制 Markdown',
    downloadXlsx: '下载汇总表（xlsx）',
    recentTasks: '最近任务',
    emptyTasks: '还没有 review 任务。先提交一个问题开始生成。',
    retry: '重试',
    delete: '删除',
    deleteTaskTitle: '删除任务',
    deleteTaskContent: '确定要删除这个任务吗？此操作无法撤销。',
    copied: '报告已复制到剪贴板',
    copyFailed: '复制失败',
    taskDeleted: '任务已删除',
    taskDeleteFailed: '任务删除失败',
    taskSubmitted: '任务已提交',
    taskResubmitted: '任务已重新提交',
    taskRunning: '任务仍在运行，请稍候...',
    reportUnavailable: '报告暂不可用',
    loadTaskFailed: '任务加载失败',
    submitFailed: '提交失败',
    retryFailed: '任务重试失败',
    noReportContent: '未收到报告内容',
    connectionLost: '与服务器连接中断',
    processing: '处理中...',
    initializing: '初始化中...',
    reportGenerating: '正在生成报告...',
    reportGuidanceGenerating: '正在根据你的审阅意见生成报告...',
    expandingQueries: '正在扩展检索式并检索文献...',
    extractingEvidence: '正在从选中文献中抽取证据...',
    analyzingQuestion: '正在分析问题...',
  },
  en: {
    pageTitle: 'Systematic Review Report',
    pageSubtitle: 'Enter a research question to generate a comprehensive systematic review report',
    questionPlaceholder:
      'Enter your research question, e.g.: What is the evolutionary origin and diversification pattern of the LRR-RLK gene family across land plants?',
    analyze: 'Analyze & Configure',
    quickGenerate: 'Quick Generate (Skip Config)',
    submitBackground: 'Submit (Background)',
    analyzingTitle: 'Analyzing Question...',
    analyzingText: 'Decomposing your question into sub-questions, entities, and concepts',
    retrievingTitle: 'Retrieving & Reranking Literature...',
    extractingTitle: 'Extracting & Fusing Evidence...',
    generatingTitle: 'Generating Report...',
    cancel: 'Cancel',
    newReport: 'New Report',
    copyMarkdown: 'Copy Markdown',
    downloadXlsx: 'Download Summary Table (xlsx)',
    recentTasks: 'Recent Tasks',
    emptyTasks: 'No review tasks yet. Submit a question above to get started.',
    retry: 'Retry',
    delete: 'Delete',
    deleteTaskTitle: 'Delete Task',
    deleteTaskContent: 'Are you sure you want to delete this task? This action cannot be undone.',
    copied: 'Report copied to clipboard',
    copyFailed: 'Copy failed',
    taskDeleted: 'Task deleted',
    taskDeleteFailed: 'Failed to delete task',
    taskSubmitted: 'Task submitted',
    taskResubmitted: 'Task resubmitted',
    taskRunning: 'Task is still running, please wait...',
    reportUnavailable: 'Report not available',
    loadTaskFailed: 'Failed to load task',
    submitFailed: 'Submit failed',
    retryFailed: 'Failed to retry task',
    noReportContent: 'No report content received',
    connectionLost: 'Lost connection to server',
    processing: 'Processing...',
    initializing: 'Initializing...',
    reportGenerating: 'Generating report...',
    reportGuidanceGenerating: 'Generating report with your guidance...',
    expandingQueries: 'Expanding queries & retrieving literature...',
    extractingEvidence: 'Extracting evidence from selected literature...',
    analyzingQuestion: 'Analyzing question...',
  },
} as const;

const statusLabels: Record<ReviewDisplayLanguage, Record<string, string>> = {
  zh: {
    QUEUED: '排队中',
    RUNNING: '运行中',
    AWAITING_USER: '等待审阅',
    COMPLETED: '已完成',
    FAILED: '失败',
  },
  en: {
    QUEUED: 'Queued',
    RUNNING: 'Running',
    AWAITING_USER: 'Awaiting your review',
    COMPLETED: 'Completed',
    FAILED: 'Failed',
  },
};

const stageLabels: Record<ReviewDisplayLanguage, Record<string, string>> = {
  zh: {
    QUERY_ANALYSIS: '问题分析',
    RETRIEVAL: '文献检索',
    RERANKING: '候选文献审阅',
    EVIDENCE_EXTRACTION: '证据抽取',
    EVIDENCE_FUSION: '证据综合审阅',
    REPORT_GENERATION: '报告生成',
    COMPLETED: '已完成',
    FAILED: '失败',
  },
  en: {
    QUERY_ANALYSIS: 'Query analysis',
    RETRIEVAL: 'Retrieval',
    RERANKING: 'Candidate review',
    EVIDENCE_EXTRACTION: 'Evidence extraction',
    EVIDENCE_FUSION: 'Evidence review',
    REPORT_GENERATION: 'Report generation',
    COMPLETED: 'Completed',
    FAILED: 'Failed',
  },
};

export const detectReviewLanguage = (
  languageCode?: string | null,
  fallbackText = '',
): ReviewDisplayLanguage => {
  if (languageCode?.toLowerCase().startsWith('zh')) {
    return 'zh';
  }
  return cjkPattern.test(fallbackText) ? 'zh' : 'en';
};

export const reviewText = (language: ReviewDisplayLanguage) => reviewLabels[language];

export const translateReviewStatus = (status: string, language: ReviewDisplayLanguage): string =>
  statusLabels[language][status] ?? status;

export const translateReviewStage = (stage: string | null | undefined, language: ReviewDisplayLanguage): string =>
  stage ? stageLabels[language][stage] ?? stage : reviewLabels[language].processing;

const splitMarkdownTableRow = (line: string): string[] => {
  const trimmed = line.trim().replace(/^\|/, '').replace(/\|$/, '');
  const cells: string[] = [];
  let current = '';
  let escaped = false;

  for (const char of trimmed) {
    if (escaped) {
      current += char;
      escaped = false;
      continue;
    }
    if (char === '\\') {
      escaped = true;
      continue;
    }
    if (char === '|') {
      cells.push(current.trim());
      current = '';
      continue;
    }
    current += char;
  }

  cells.push(current.trim());
  return cells;
};

const isDelimiterRow = (line: string): boolean =>
  /^(\s*)\|?(\s*:?-{3,}:?\s*\|)+\s*:?-{3,}:?\s*\|?(\s*)$/.test(line);

const isTableRow = (line: string): boolean => /^\s*\|.+\|\s*$/.test(line);

export const extractMarkdownTables = (markdown: string): MarkdownTablePreview[] => {
  const lines = markdown.replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n');
  const tables: MarkdownTablePreview[] = [];
  let index = 0;

  while (index < lines.length - 1) {
    const headerLine = lines[index] ?? '';
    const delimiterLine = lines[index + 1] ?? '';
    if (!isTableRow(headerLine) || !isDelimiterRow(delimiterLine)) {
      index += 1;
      continue;
    }

    const headers = splitMarkdownTableRow(headerLine);
    const rows: string[][] = [];
    index += 2;

    while (index < lines.length && isTableRow(lines[index] ?? '')) {
      const row = splitMarkdownTableRow(lines[index] ?? '');
      rows.push(headers.map((_, cellIndex) => row[cellIndex] ?? ''));
      index += 1;
    }

    if (headers.length > 0 && rows.length > 0) {
      tables.push({
        id: `table-${tables.length + 1}`,
        title: `Table ${tables.length + 1}`,
        headers,
        rows,
      });
    }
  }

  return tables;
};
