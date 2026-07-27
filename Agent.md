# RAG 评估实验说明

更新时间：2026-06-05

## 实验入口

- 普通单次实验：`POST /api/rag-evaluation/experiments`
- 必做实验套件：`POST /api/rag-evaluation/experiment-suites/required`

`required` 套件按顺序创建并执行：

1. 冒泡实验：100 篇，`JUDGED_DOCUMENTS`，不启用问题重写和 rerank。
2. 实验一：100 篇，启用问题重写，并使用 Review Entity 约 97% Recall 版本的实体查询策略。
3. 实验一：1000 篇，配置同上。
4. 实验二：500 篇平衡数据集，从既有 1000 篇标注集中抽样 100 篇相关、100 篇干扰、300 篇无关文献。
5. 实验三：100 篇，Review Entity 约 97% Recall 配置启用 `qwen3-vl-rerank`，对候选文献做文献级 rerank。

当前已取消 1000 篇 rerank 实验；需要恢复时再把实验三 1000 篇规格加入 `requiredSuiteSpecs`。

## 标注复用

- LLM 文献标注视为一次性流程，新实验不再重新调用 `document-judgment`。
- 100 篇默认复用来源：`b6aec474-a84c-48f6-8717-531042f09143`。
- 1000 篇默认复用来源：`9038d6bc-6213-4009-ae5e-d1bd45e0c4b8`。
- 每个新实验会在报告目录生成 `reused-judgments.md`，记录来源实验和复用标签分布。

## 检索与 Rerank

- Review Entity 使用历史参数扫描中的 `S1 dense300` 版本：`ftsMaxResults=100`、`denseMaxResults=300`、`bm25MaxResults=100`、`priorityChunksPerFtsDocument=2`、默认 `rrfK=60`。历史 100 篇报告中该版本 Recall 97.44%、Precision 48.10%、候选文献 79 篇。
- 实验三 rerank 不再对 chunks 排序，而是对 `REVIEW_ENTITY_OVERALL` 产生的候选文献逐篇做 Cross Encoder 相关性判别。
- 文献级 rerank route：`RERANK_DOCUMENT_OVERALL`。每篇候选文献会聚合若干代表 chunk，截断到 7800 字符以内，作为一条文献输入给 `qwen3-vl-rerank`；默认只保留 `relevance_score >= 0.5` 的文献。

## 数据库记录

- `rag_eval_experiment.config_json`：记录 `suiteId`、`phase`、`phaseName`、`corpusSize`、平衡抽样目标、标注来源、问题重写开关、Review Entity 约 97% Recall 策略、rerank 开关、rerank 模型、文献级 rerank 参数和检索参数。
- `rag_eval_experiment.metrics_json.routes`：记录每个 route 的 recall、precision、distractorRate、irrelevantRate、MRR、nDCG、MAP、keyChunkRecall。
- `rag_eval_experiment.metrics_json.modelUsage`：记录各阶段/模型 token 和耗时，包括 `judgment-reuse`、`question-rewrite`、`query-analysis`、`query-expansion`、各检索 route、`document-rerank`。
- `rag_eval_experiment.metrics_json.totalElapsedMs`：记录单个实验总耗时。
- `rag_eval_document_judgment`：记录复用后的每篇文献有效标签、关键实体、关键 chunk、理由和人工 override。
- `rag_eval_retrieval_hit`：记录每个检索 route 的命中、rank 和 score。

## 报告位置

- 默认报告根目录：`data/rag-evaluation`
- 必做实验套件报告：`data/rag-evaluation/{suiteId}/{中文实验阶段}/{experimentId}`
- 标注复用记录：`reused-judgments.md`
- 问题重写记录：`question-rewrite.md`
- 历史报告中文归档：`data/rag-evaluation/历史实验报告`

## 最新 100 篇 Rerank 实验

- 实验 ID：`ee11ee29-a381-4904-80d3-dd06a8b7fb49`
- 状态：`COMPLETED`
- 报告目录：`data/rag-evaluation/ee11ee29-a381-4904-80d3-dd06a8b7fb49`
- Review Entity 档位：`S1 dense300`，配置记录为 `Recall 97.44%, Precision 48.10%`
- 本次实际 `REVIEW_ENTITY_OVERALL`：67 篇候选文献，Recall 0.8974，Precision 0.5224
- 本次实际 `RERANK_DOCUMENT_OVERALL`：46 篇保留文献，筛掉 21 篇，Recall 0.7949，Precision 0.6739
- `document-rerank`：`qwen3-vl-rerank`，Provider Tokens 132377，耗时 4065 ms
- 实验总耗时：86812 ms

## 最新 500 篇平衡数据集实验

- 实验 ID：`08777e18-6028-44da-ab30-eb22cc21ea10`
- 状态：`COMPLETED`
- 报告目录：`data/rag-evaluation/08777e18-6028-44da-ab30-eb22cc21ea10`
- 标注来源：`9038d6bc-6213-4009-ae5e-d1bd45e0c4b8`
- 抽样分布：100 篇相关、100 篇干扰、300 篇无关
- 本次 `BASELINE_OVERALL`：Recall 0.2300，Precision 0.9583，24 篇文献
- 本次 `OVERALL`：Recall 0.3900，Precision 0.5571，70 篇文献
- 本次 `REVIEW_ENTITY_OVERALL`：Recall 0.8200，Precision 0.3320，247 篇文献
- 本次 `GOLD_ENTITY_OVERALL`：Recall 0.9500，Precision 0.2411，394 篇文献
- Rerank：未启用
- 实验总耗时：77196 ms

## 测试文献

- Acremoxanthone E 测试文献：
  - 文献 ID：`7ae6fb01-cad8-4b71-a8db-35b6e03a22fb`
  - 标题：`Acremoxanthone E, a Novel Member of Heterodimeric Polyketides with a Bicyclo[3.2.2]nonene Ring, Produced by Acremonium camptosporum W. Gams (Clavicipitaceae) Endophytic Fungus`
  - GROBID 全文 TEI：`D:\Project\ai_coding_platform\demo_01\data\rag\7ae6fb01-cad8-4b71-a8db-35b6e03a22fb\document.tei.xml`
  - GROBID 头部 TEI：`D:\Project\ai_coding_platform\demo_01\data\rag\7ae6fb01-cad8-4b71-a8db-35b6e03a22fb\header.tei.xml`
  - 原始 PDF：`D:\Project\ai_coding_platform\demo_01\data\rag\7ae6fb01-cad8-4b71-a8db-35b6e03a22fb\source.pdf`

## 运行配置

- `APP_AI_RAG_EVALUATION_SOURCE_JUDGMENTS_100_EXPERIMENT_ID`：100 篇标注复用来源。
- `APP_AI_RAG_EVALUATION_SOURCE_JUDGMENTS_1000_EXPERIMENT_ID`：1000 篇/实验二标注复用来源。
- `APP_AI_RAG_EVALUATION_DENSE_MAX_RESULTS`：Review Entity 97% Recall 档默认需要 `300`。
- `APP_AI_RAG_EVALUATION_REVIEW_ENTITY_BEST_RECALL_ENABLED`：是否使用 Review Entity 固定实体词表。
- `APP_AI_RAG_EVALUATION_REVIEW_ENTITY_HIGH_PRECISION_ENABLED`：是否启用 Review Entity 高精度过滤；97% Recall 档默认关闭。
- `APP_AI_RAG_EVALUATION_REVIEW_ENTITY_HIGH_PRECISION_QUERY_MARKER`：高精度过滤保留的 query 关键词，默认 `antibacterial`。
- `APP_AI_RAG_EVALUATION_DOCUMENT_RERANK_MAX_DOCUMENTS`：文献级 rerank 最大候选文献数，`0` 表示不限制。
- `APP_AI_RAG_EVALUATION_DOCUMENT_RERANK_MAX_CHUNKS_PER_DOCUMENT`：每篇文献拼入 rerank 输入的代表 chunk 数。
- `APP_AI_RAG_EVALUATION_DOCUMENT_RERANK_MIN_SCORE`：文献级 rerank 保留阈值，默认 `0.5`。
- `APP_AI_RAG_EVALUATION_RERANK_MODEL`：默认 `qwen3-vl-rerank`。
- `DASHSCOPE_API_KEY`：DashScope chat、embedding、rerank 共用 API key。
