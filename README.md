# Ai Literature Platform

卵菌相关文献的智能筛选、RAG 检索问答、结构化证据抽取和报告生成平台。项目由 Spring Boot 后端和 Vue 3 前端组成，核心流程围绕文献入库、筛选、Q1-Q10 分类和按问题证据抽取展开。

默认地址：

| 服务 | 地址 |
| --- | --- |
| 前端开发服务 | `http://localhost:5173` |
| 后端 API | `http://localhost:8081/api` |
| 健康检查 | `http://localhost:8081/api/actuator/health` |
| GROBID | `http://localhost:8070` |
| PostgreSQL | `localhost:55432` |
| Neo4j Browser | `http://localhost:7474` |

## 核心能力

- PDF 文献预处理：调用 GROBID 解析 TEI，切分 chunk，生成可复用 artifact 和表格 JSONL。
- RAG 入库与检索：使用 PostgreSQL + pgvector 存储向量，Lucene BM25 做关键词检索，融合召回后供聊天、评测和报告使用。
- 四阶段文献流水线：入库、筛选、Q1-Q10 分类、按 questionId 抽取证据。
- Q1 结构化证据聊天：基于已抽取的化合物证据表构建上下文，并支持按需回查原文 chunk。
- 多 profile 证据抽取：支持检索、覆盖、验证、表格选择、线性化表格恢复和结果持久化。
- 深度报告：围绕会话和证据库生成文献报告，并可导出附件。
- 可选知识图谱：Neo4j 图谱写入和图谱增强检索，默认可关闭。
- 生产与离线部署：提供 Docker Compose 生产栈、镜像构建脚本和离线压缩包导出/恢复文档。

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Java 21, Spring Boot 3.5, MyBatis-Flex, Flyway |
| AI/RAG | LangChain4j, DashScope/Qwen, pgvector, Lucene BM25, GROBID |
| 数据库 | PostgreSQL + pgvector, Neo4j |
| 前端 | Vue 3, Vite, TypeScript, Pinia, Ant Design Vue |
| 部署 | Docker, Docker Compose, Nginx |

## 仓库结构

| 路径 | 说明 |
| --- | --- |
| `src/main/java/com/example/demo_01/ai/` | AI、RAG、筛选、证据抽取、报告和知识图谱后端代码 |
| `src/main/resources/prompts/` | RAG、证据抽取、报告、KG 等 LLM prompt |
| `src/main/resources/db/migration/` | Flyway 数据库迁移 |
| `ai-literature-frontend/` | Vue 3 前端 |
| `PreTreatment/` | 文献筛选 CLI 配置和脚本 |
| `scripts/` | 开发、构建、部署、批处理和离线打包脚本 |
| `docs/` | 流水线设计、迁移部署和离线部署文档 |
| `data/` | 本地 RAG artifact、BM25 索引等运行数据，默认不提交 |
| `Evidence/` | 证据抽取输出，默认不提交 |

## 四阶段流水线

推荐主线如下：

```text
阶段 1 入库    PDF -> GROBID -> chunks/artifacts -> pgvector/BM25
阶段 2 筛选    L1 质量门 + L2 摘要 LLM 筛选
阶段 3 分类    对通过筛选的文献执行 Q1-Q10 支持性分类
阶段 4 抽取    对指定 questionId 抽取结构化证据
```

阶段之间通过 `document_cohort` 传递文献集合，例如：

- `filter-accepted-{runId}`：筛选通过的文献集合。
- `Q1-supported-{batchId}`：分类后支持 Q1 的文献集合。

主要 HTTP 入口：

| 阶段 | API |
| --- | --- |
| 预处理 | `POST /api/preprocess/*` |
| RAG 入库 | `POST /api/rag/batches/folder` |
| 筛选 | `POST /api/stages/filter/runs` |
| 分类 | `POST /api/stages/classify/runs` |
| 抽取 | `POST /api/stages/extract/runs` |
| 单篇 dry-run | `POST /api/stages/extract/documents/{id}/dry-run` |
| 多 profile admin | `POST /api/admin/evidence/multi-profile-batches` |
| 按问抽取 admin | `POST /api/admin/evidence/question-extractions` |
| 报告 | `POST /api/report/runs` |
| 聊天 | `/api/ai/*` |

更完整的流程说明见 [docs/four-stage-pipeline-redesign.md](docs/four-stage-pipeline-redesign.md)。

## 本地开发

### 前置依赖

- JDK 21
- Node.js `^20.19.0` 或 `>=22.12.0`
- Docker / Docker Compose v2
- DashScope API Key

### 配置

复制环境变量模板：

```powershell
Copy-Item .env.example .env
```

至少填写：

```text
DASHSCOPE_API_KEY=your-dashscope-api-key
DASHSCOPE_CHAT_MODEL=qwen-max
DASHSCOPE_EMBEDDING_MODEL=text-embedding-v4
```

本地默认配置使用 `application-local.yml` 和 `application.yml`。后端默认监听 `8081`，API context path 为 `/api`。

### 启动基础设施

```powershell
docker compose up -d
```

这会启动 PostgreSQL + pgvector、GROBID 和 Neo4j。知识图谱功能可以通过环境变量关闭，关闭时 Neo4j 不是主流程必需项。

### 启动应用

Windows：

```powershell
.\start-dev.ps1
```

只启动后端：

```powershell
.\start-dev.ps1 -BackendOnly
```

Linux/macOS：

```bash
./start-dev.sh
```

也可以手动启动：

```powershell
.\mvnw.cmd -DskipTests spring-boot:run
```

```powershell
cd ai-literature-frontend
npm install
npm run dev
```

## 常用命令

后端测试：

```powershell
.\mvnw.cmd test
```

前端构建：

```powershell
cd ai-literature-frontend
npm run build
```

前端 Markdown 工具测试：

```powershell
cd ai-literature-frontend
npm run test:markdown
```

生产镜像构建：

```powershell
.\scripts\build-images.ps1
```

Linux：

```bash
bash scripts/build-images.sh
```

## 生产部署

生产栈使用 `docker-compose.prod.yml`，默认只将前端容器绑定到 `127.0.0.1:${WEB_PORT}`，建议放在 Nginx 或 Apache 反向代理后。

基础步骤：

```bash
cp .env.example .env
docker compose -f docker-compose.prod.yml up -d --build
```

如果需要恢复来自 PostgreSQL 17 的 dump，请叠加 PG17 override：

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.pg17.override.yml up -d --build
```

离线压缩包导出：

```powershell
.\scripts\export-deployment-package.ps1
```

离线恢复步骤见 [docs/offline-package-deployment.md](docs/offline-package-deployment.md)，常规迁移见 [docs/migration-and-deployment.md](docs/migration-and-deployment.md)。

## 关键配置

| 变量 | 说明 |
| --- | --- |
| `DASHSCOPE_API_KEY` | DashScope 模型调用密钥 |
| `DASHSCOPE_CHAT_MODEL` | 聊天/抽取模型 |
| `DASHSCOPE_EMBEDDING_MODEL` | embedding 模型 |
| `APP_AI_RAG_GROBID_BASE_URL` | GROBID 地址 |
| `APP_AI_RAG_VECTOR_TABLE` | pgvector 表名，默认 `embedding_store` |
| `APP_AI_RAG_CHAT_Q1_EVIDENCE_ENABLED` | Q1 证据聊天开关 |
| `APP_AI_EVIDENCE_ENABLED` | 证据抽取总开关 |
| `APP_AI_EVIDENCE_TABLE_ENABLED` | 表格上下文注入开关 |
| `APP_AI_KG_ENABLED` | 知识图谱功能开关 |
| `APP_USER_INIT_ADMIN_ENABLED` | 生产初始化管理员开关 |

更多默认值见 [src/main/resources/application.yml](src/main/resources/application.yml)。

## 数据与输出

| 路径 | 内容 |
| --- | --- |
| `data/rag/` | 预处理 artifact 和文献入库中间产物 |
| `data/bm25-index/` | Lucene BM25 索引 |
| `Evidence/` | 证据抽取输出 |
| `tmp/` | 报告生成等临时输出 |
| `outputs/deployment/` | 离线部署包输出 |

这些目录通常包含本机数据或大文件，不应提交到 Git。

## 已清理的遗留模块

以下旧路径已经不再作为主线依赖：

- 旧 Review 任务流水线，相关表在 V20 后被替换。
- `stage_run` / `stage_run_document` 统一状态机，V34 已删除。
- 单篇 Q1 旧抽取 `EvidenceExtractionService` 和 `/admin/evidence/extractions/*`。
- `evidence_extraction_batch` 批量 backfill 表，V34 已删除。

仍保留 `evidence_extraction_run` 和 `compound_evidence`，用于 Q1 结果镜像写入以及报告模块读取。

## 相关文档

- [四阶段流水线设计](docs/four-stage-pipeline-redesign.md)
- [机器迁移与部署](docs/migration-and-deployment.md)
- [离线压缩包部署](docs/offline-package-deployment.md)
- [前端说明](ai-literature-frontend/README.md)
- [预筛选 CLI](PreTreatment/README.md)
