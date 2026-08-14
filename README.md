# demo_01 — 卵菌文献智能分析平台

Spring Boot 后端 + Vue 前端的文献 RAG 与证据抽取系统，主线为**四阶段流水线**：入库 → 筛选 → Q1–Q10 分类 → 按问证据提取。

默认 API 前缀：`http://localhost:8081/api`

## 仓库结构

| 目录 | 说明 |
|------|------|
| `src/main/java/.../ai/` | 后端业务代码 |
| `src/main/resources/prompts/` | 各阶段 LLM system prompt |
| `src/main/resources/db/migration/` | Flyway 迁移 |
| `ai-literature-frontend/` | Vue 3 前端（聊天、用户、文献导入、报告） |
| `PreTreatment/` | 文献筛选 CLI 脚本与配置 |
| `scripts/` | 批处理与运维脚本 |
| `docs/` | 架构与设计文档 |

## 四阶段流水线（推荐路径）

```text
阶段1 入库     POST /preprocess/*  +  POST /rag/batches/folder
阶段2 筛选     POST /stages/filter/runs
阶段3 分类     POST /stages/classify/runs   （仅分类，不抽取）
阶段4 抽取     POST /stages/extract/runs    （单 questionId）
```

阶段间用 **Cohort**（`document_cohort`）交接文献集合，例如 `filter-accepted-{runId}`、`Q1-supported-{batchId}`。

详细设计见 [docs/four-stage-pipeline-redesign.md](docs/four-stage-pipeline-redesign.md)。

## 后端模块地图

### 主线

| 包 | 职责 |
|----|------|
| `ai.preprocessing` | PDF → GROBID → chunk → artifact（含 `tables.jsonl`） |
| `ai.rag` | 向量化入库、混合检索、RAG 聊天 |
| `ai.pretreatment` | L1 质量门 + L2 摘要 LLM 筛选 |
| `ai.stage` | `/stages/*` REST + `CohortService` |
| `ai.evidence.multiprofile` | Q1–Q10 分类、抽取 agent、`QuestionExtractionService` |
| `ai.evidence.table` | 表格按需注入与线性化恢复 |

### 产品能力（与流水线并行）

| 包 | 职责 |
|----|------|
| `conversation` + `ai`（ChatStreaming） | 会话与流式问答 |
| `ai.report` | 基于会话的深度报告 |
| `user` | 认证与权限 |

### 评测 / 可选

| 包 | 职责 |
|----|------|
| `ai.rag.evaluation` | RAG 检索评测实验 |
| `ai.kg` | Neo4j 知识图谱（`graph-builder` 默认关闭） |
| `ai.entitylibrary` | 实体库与人工审核 |

### 共享基础设施

| 包 | 职责 |
|----|------|
| `ai.review` | `ReviewReasoningChatClient`、查询分析/扩展（**非**旧 review 任务 UI） |
| `ai.rag.repository.RagChunkRepository` | 从向量表读取 chunk、FTS 查文献 |
| `ai.prompt` | Prompt 加载 |

## 主要 HTTP 入口

| 路径 | 说明 |
|------|------|
| `/stages/filter/runs` | 阶段 2 筛选 |
| `/stages/classify/runs` | 阶段 3 分类 |
| `/stages/extract/runs` | 阶段 4 按问抽取 |
| `/stages/extract/documents/{id}/dry-run` | 单篇试跑 |
| `/admin/evidence/multi-profile-batches` | 兼容：分类+抽取一体 batch |
| `/admin/evidence/question-extractions` | 与 stage extract 等价的 admin 路径 |
| `/preprocess/*` | 阶段 1a 解析 |
| `/rag/batches/*` | 阶段 1b 向量化 |
| `/report/runs` | 报告生成 |
| `/ai/*` | 聊天 |

## 已移除的遗留模块（2026-08）

以下代码与表已在 V34 前后清理，**请勿再依赖**：

- 旧 **Review 任务流水线**（`review_task` 等表在 V20 已删；Java CRUD 死代码已移除）
- **`stage_run` / `stage_run_document`** 统一状态机（V32 建表、从未接线，V34 删除）
- **单篇 Q1 旧抽取** `EvidenceExtractionService` 与 `/admin/evidence/extractions/*`
- **`evidence_extraction_batch`** 批量 backfill 表（V34 删除）

仍保留：`evidence_extraction_run` + `compound_evidence`，供 Q1 结果镜像写入与报告模块读取。

## 本地运行（概要）

1. 复制 `.env.example` 为 `.env`（或导出 `DASHSCOPE_API_KEY`），再启动基础设施：`docker compose up -d`
2. Windows：`.\start-dev.ps1`；Linux/macOS：`./start-dev.sh`（后端 `http://localhost:8081/api`，前端 `http://localhost:5173`）
3. 只要后端：`.\start-dev.ps1 -BackendOnly`

筛选 CLI：见 [PreTreatment/README.md](PreTreatment/README.md)。

换机部署与数据搬家：见 [docs/migration-and-deployment.md](docs/migration-and-deployment.md)。生产栈：`bash scripts/bootstrap-server.sh`。

## 相关文档

- [四阶段流水线设计](docs/four-stage-pipeline-redesign.md)
- [机器迁移与部署](docs/migration-and-deployment.md)
- [前端说明](ai-literature-frontend/README.md)
