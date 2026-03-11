import { marked } from 'marked';
import DOMPurify from 'dompurify';
// 如果后续需要高亮，可以在这里引入 highlight.js

// 配置 marked
marked.setOptions({
  breaks: true, // 支持回车换行
  gfm: true,    // GitHub 风格 Markdown
});

/**
 * 渲染 Markdown 并进行 XSS 净化
 * @param text 原始 Markdown 文本
 * @returns 安全的 HTML 字符串
 */
export const renderMarkdown = (text: string): string => {
  if (!text) return '';
  const rawHtml = marked.parse(text) as string;
  return DOMPurify.sanitize(rawHtml);
};
