# 四阶段流水线重新设计方案

> 文档日期：2026-07-27  
> 状态：设计定稿；实施为分步推进（步骤 1–4 主体已落地，步骤 5–8 未完成）  
> 相关实现：`V31__decouple_classification_and_extraction.sql`、`QuestionExtraction*`、表格按需加载（`ai.evidence.table`）

---

## 1. 目标与原则

将项目明确拆为四个主要阶段，并满足：

1. **四阶段各自解耦**，每个阶段都可以独立运行  
2. **Q1 单篇测试** 归属于第四阶段的优化（补齐证据提取中表格信息缺失），而不是平行的另一套流水线  

已确认的产品决策：

| 决策 | 说明 |
|------|------|
| 向量化 | **保持全量嵌入**（不过滤后再嵌） |
| 过滤阶段 | **只打标签**，不做显式删除向量 / artifact |
| 证据提取 | 需要 **按问题单独测试 / 单独提取** 的接口 |

---

## 2. 现状诊断（设计时）

| 阶段 | 当时实现 | 独立性 | 关键问题 |
|------|----------|--------|----------|
| 1 文献入库 | `document_preprocess_*` + `rag_ingestion_*` 两套批次 | 可独立 | 一个逻辑阶段拆成两套并行流水线 |
| 2 文献过滤 | PreTreatment CLI；`scan` / `apply` | 半独立 | 无 REST、无断点续跑；`apply` 会删向量；产出无人消费 |
| 3 Q1–Q10 分类 | `MultiProfileEvidenceService.classify()`（private） | 不可独立 | 无独立入口，classify 后立刻 extract |
| 4 证据提取 | 同类 `extractProfile()` 紧跟 classify | 不可独立 | 无法复用已有分类；改抽取配置会作废分类结果 |

三个最严重的错位：

1. **阶段 3、4 共用一个 batch 和一个 `prompt_hash`** —— 改抽取 prompt / 开表格开关会让整批分类作废重跑  
2. **阶段 2 产出是孤儿** —— 下游吃的是 `rag_eval_document_judgment`，不是过滤结果  
3. **硬编码 `EXPECTED_DOCUMENTS = 1000`** —— 无法跑任意规模试验  

另：系统存在多套互不相干的 batch 状态机；旧版单 profile Q1 管线与多画像管线并存。

---

## 3. 核心抽象

### 3.1 Cohort（文献集合）

**Cohort = 一组被命名并冻结的 `document_id` 集合**，`cohort_id` 是交接凭证。

| 示例名 | 产出阶段 | 含义 |
|--------|----------|------|
| `ingested-20260727` | 阶段 1 | 入库完成的全部文献 |
| `filter-accepted-*` | 阶段 2 | 通过两层过滤的文献 |
| `Q1-supported-*` | 阶段 3 | 分类判定能回答 Q1 的文献 |
| `Q1-debug-5` | 手工 | 调试用小样本 |

每个阶段：**输入一个 `cohort_id`，输出结果 + 可选的新 cohort**。  
任一阶段都可拿任意 cohort 直接跑（不强制走完前序）。

### 3.2 Stage Run（阶段运行）

用统一的批次抽象收敛多套状态机（概念模型）：

```
stage_run (run_id, stage, cohort_id, input_hash, config_hash, status, ...)
stage_run_document (run_id, document_id, status, error, elapsed_ms)
```

关键约定：

- **`config_hash` 按阶段独立计算**（只含本阶段 prompt / 模型 / 开关）  
- **`input_hash` / `source_hash`** 标识输入文献集合是否相同  
- 二者都相同 → 可复用上次 COMPLETED 结果（幂等）

### 3.3 幂等

**幂等 = 同一输入 + 同一配置重复提交，效果等同于执行一次**（复用成功结果，不重复花钱、不重复脏写）。

| 哈希 | 管什么 |
|------|--------|
| `source_hash` / `input_hash` | 文献集合是否相同 |
| `config_hash` / `prompt_hash` | 配置是否相同 |

作用：省钱省时、可重试、可对照实验、防重复证据行。

---

## 4. 目标架构

```text
┌─────────────────────────────────────────────────────────┐
│ 阶段1 文献入库（无 LLM，确定性）                          │
│  1a 解析: PDF → GROBID → TEI                            │
│  产物: document.jsonl / tables.jsonl / manifest         │
│  1b 向量化: 全量嵌入（已确认）                             │
└──────────────────────────┬──────────────────────────────┘
                           │ cohort: ingested
                           ▼
┌─────────────────────────────────────────────────────────┐
│ 阶段2 文献过滤                                           │
│  L1 质量门控（确定性）→ L2 摘要 LLM 判定                   │
│  只打 ACCEPTED / REJECTED 标签，不删数据                    │
└──────────────────────────┬──────────────────────────────┘
                           │ cohort: accepted
                           ▼
┌─────────────────────────────────────────────────────────┐
│ 阶段3 Q1–Q10 分类                                        │
│  全文 chunk → 每问 SUPPORTED / UNCERTAIN / NOT_SUPPORTED │
│  派生 cohort: Q{n}-supported 等                          │
└──────────────────────────┬──────────────────────────────┘
                           │ cohort: Q{n}-supported
                           ▼
┌─────────────────────────────────────────────────────────┐
│ 阶段4 证据提取                                           │
│  表格增强（按需选表+代号消解）→ retrieve → extract          │
│  → verify → coverage → 证据行 + 锚点 → 表格输出            │
│  一次只处理一个 questionId                                │
└─────────────────────────────────────────────────────────┘

虚线能力：可跳过过滤直接分类；可复用已有分类快照只跑提取；
         可单 documentId 调试（Q1 单篇测试归位于此）。
```

---

## 5. 逐阶段设计

### 5.1 阶段 1：文献入库

| 项 | 设计 |
|----|------|
| 定位 | 纯确定性、无 LLM，提供后续所有基础数据 |
| 拆分 | `1a 解析`（全量，产 artifact）+ `1b 向量化`（全量嵌入） |
| 产物 | `document.jsonl`、`artifact-manifest.json`、**`tables.jsonl`**、`document.tei.xml` |
| 输入 | PDF 文件 / 文件夹 |
| 输出 | `rag_document` + cohort `ingested-*` |
| 幂等 | `pdf_sha256` / `doi_normalized` 去重 |
| 改动方向 | 合并 preprocess / rag 两套状态机为一个 stage_run；`tables.jsonl` 作为标准产物（可用 backfill，无需重跑 GROBID / embedding） |

**表格能力归属修正：**

| 能力 | 性质 | 应属阶段 |
|------|------|----------|
| TEI 表格解析 → `tables.jsonl` | 确定性派生 | **阶段 1** |
| LLM 选表 | 依赖问题上下文 | 阶段 4 |
| 代号消解 + 上下文注入 | 依赖问题上下文 | 阶段 4 |

### 5.2 阶段 2：文献过滤

| 项 | 设计 |
|----|------|
| 定位 | 便宜的两层筛选，主要读元数据 |
| 两层 | L1 质量门控；L2 摘要 LLM 主题判定 |
| 输入 | cohort（默认 `ingested-*`） |
| 输出 | `filter_result` + 派生 cohort `accepted-*` / `rejected-*` |
| 关键改动 | ① REST API ② **非破坏性（只打标）** ③ 断点续跑 ④ 产出被阶段 3 默认消费 |
| 删除向量 | 若需要，单独做成显式 GC，**不是过滤的一部分** |

### 5.3 阶段 3：Q1–Q10 分类

| 项 | 设计 |
|----|------|
| 输入 | cohort（任意大小；**删除 1000 硬编码**） |
| 输出 | 分类快照（每文献×每问标签）+ 按问派生 cohort |
| API 概念 | `POST /stages/classify/runs` 等 |
| 幂等 | `config_hash` **仅**含分类 prompt + profile + 模型 |
| 改动方向 | `classify` 提升为可独立调用；与 extract 解绑 |

### 5.4 阶段 4：证据提取

| 项 | 设计 |
|----|------|
| 输入三选一 | ① 分类快照 + `questionId`（默认）② cohort + `questionId`（强制抽）③ 单 `documentId`（调试） |
| 输出 | 证据行 + 锚点 + 导出表格 |
| API 概念 | 按问题批量 run + 单篇 dry-run / 单篇提取 |
| 幂等 | `config_hash` **仅**含抽取侧 prompt / agent / 表格开关 / 模型 |
| 子能力 | 表格增强、verifier、coverage、reconciler —— 各自开关，只影响本阶段 hash |

建议接口形态：

```http
POST /stages/extract/runs
{
  "questionId": "Q1",
  "cohortId": "Q1-supported",
  "force": false
}

POST /stages/extract/documents/{documentId}
{
  "questionId": "Q1"
}
```

约定：一次只处理一个 `questionId`；可复用阶段 3；也可跳过分类强制抽；输出按 `(run, question)` 隔离。

---

## 6. 数据模型改动（目标态）

| 动作 | 对象 |
|------|------|
| 新增 | `document_cohort`、`cohort_member` |
| 新增 | `stage_run`、`stage_run_document` |
| 拆分 | multi-profile batch → 分类 run + 提取 run，各自 `config_hash` |
| 拆分 | 分类标签表（只读快照）与提取任务状态表（避免 upsert 重置 extraction） |
| 提升 | pretreatment 结果接入 stage_run，供下游消费 |
| 补齐 | 缺失的早期 Flyway baseline（`rag_document` 等） |
| 退役 | 旧版单 profile Q1 管线（统一走多画像 / 按问提取） |

---

## 7. 建议迁移路径（按 ROI）

| 步骤 | 内容 | 收益 | 风险 |
|------|------|------|------|
| **1** | 拆分 `promptHash` 为分类 / 抽取两个 hash | 改抽取不再作废分类 | 极低 |
| **2** | 去掉 `EXPECTED_DOCUMENTS=1000` | 任意规模试验 | 低 |
| **3** | classify 可独立跑 + 快照 | 阶段 3 独立 | 中 |
| **4** | extract 复用分类 + 单问 / 单篇入口 | 阶段 4 独立；JS 单篇测试归位 | 中 |
| **5** | `tables.jsonl` 移到阶段 1 + backfill | 表格归位 | 低 |
| **6** | 过滤 REST + 续跑 + 非破坏化 | 阶段 2 一等公民 | 中 |
| **7** | cohort / stage_run 统一抽象 | 架构统一 | 高 |
| **8** | Flyway baseline + 退役旧管线 | 清技术债 | 中 |

说明：步骤 1–2 可立刻止血；步骤 7 是终态，可最后做。前 6 步完成后，四阶段在工程上已可独立运行。

---

## 8. Q1 单篇测试与总流程的关系

| 维度 | Q1 单篇测试（应有定位） | 完整证据提取（阶段 4 批量） |
|------|-------------------------|----------------------------|
| 范围 | 单文献 × 单问题（如 Q1） | 多文献 × 指定问题 |
| 目的 | 优化表格获取、prompt、agent 开关 | 正式产出证据表 |
| 实现归属 | **阶段 4 的单文档入口 / dry-run** | 阶段 4 的 question extraction run |
| 不应再做 | `outputs/` 下平行 JS 再实现一套 TEI 解析 | — |

表格按需加载（解析、选表、代号消解）是阶段 4 的能力增强；其中「解析落盘」长期应归阶段 1。

---

## 9. 实施进度快照（相对本方案）

| 步骤 / 阶段 | 状态（截至文档编写时） |
|-------------|------------------------|
| 步骤 1 拆 hash | 已做 |
| 步骤 2 去 1000 硬编码 | 已做 |
| 步骤 3 分类可独立 | 部分（`runExtraction=false`；无独立 classify 端点 / cohort） |
| 步骤 4 按问提取 + dry-run | 已做（`/admin/evidence/question-extractions`） |
| 步骤 5 表格归入库 | 未做（仍阶段 4 惰性 `tables.jsonl`） |
| 步骤 6 过滤非破坏化 + REST | 未做（`apply` 仍可能删向量） |
| 步骤 7 cohort / stage_run | 未做 |
| 步骤 8 baseline + 退役旧管线 | 未做 |
| 全量嵌入 | 维持原样（符合决策） |
| 过滤只打标 | **决策已定，代码未改** |

---

## 10. 使用约定（在步骤 1–4 已落地前提下）

只分类：

```http
POST /admin/evidence/multi-profile-batches
{ "sourceExperimentId": "...", "runExtraction": false }
```

按问题提取（复用分类 batch）：

```http
POST /admin/evidence/question-extractions
{
  "questionId": "Q1",
  "sourceType": "CLASSIFICATION_RUN",
  "classificationBatchId": "<batchId>",
  "overrides": { "table": { "enabled": true } }
}
```

单篇调试：

```http
POST /admin/evidence/question-extractions/documents/{documentId}/dry-run
{ "questionId": "Q1", "overrides": { "table": { "enabled": true } } }
```

运行前需确保 Flyway **V31** 已应用。

---

## 11. 相关路径索引

| 路径 | 说明 |
|------|------|
| `src/main/resources/db/migration/V31__decouple_classification_and_extraction.sql` | 分类/提取解耦迁移 |
| `src/main/java/.../multiprofile/QuestionExtractionService.java` | 按问提取编排 |
| `src/main/java/.../api/QuestionExtractionAdminController.java` | 按问提取 API |
| `src/main/java/.../evidence/table/` | 表格按需解析与注入 |
| `PreTreatment/` | 过滤阶段 CLI（待按本方案改造） |
