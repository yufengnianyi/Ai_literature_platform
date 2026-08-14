# 机器迁移与部署指南

把本机已跑通的文献 RAG / 证据抽取系统迁到另一台机器时，**代码可以 git 拉取，数据和密钥必须单独搬运**。本文说明要搬哪些数据、怎么搬、以及新机器上如何启动。

当前这台开发机的典型形态是：

- `docker compose -f docker-compose.yml up -d`：PostgreSQL（含 pgvector）、GROBID、Neo4j
- 后端 / 前端在宿主机运行：`.\start-dev.ps1`（API `http://localhost:8081/api`，页面 `http://localhost:5173`）
- 解析产物在项目根目录的 `data/`（不进 git）

新机器可以继续用这套「基础设施用 Docker、应用在宿主机跑」，也可以改成全 Docker 生产栈（`docker-compose.prod.yml`）。

---

## 1. 必须迁移的数据

| 资产 | 本机位置 | 本机大约体积 | 丢失后果 | 是否必须 |
|------|----------|--------------|----------|----------|
| PostgreSQL（业务库 + 向量表 `embedding_store`） | Docker volume `postgres-data`（compose 项目名默认 `demo_01`） | 取决于文献量 | 用户、会话、文献元数据、chunk/向量、Q1–Q10 证据、cohort 全部丢失 | **必须** |
| RAG 解析产物 | `data/rag/` | 约 **2.1 GB** | 无法打开原文 artifact；重新 GROBID 解析极耗时且结果可能不完全一致 | **必须** |
| BM25 索引 | `data/bm25-index/` | 约 35 MB | 混合检索的关键词通路失效，可用库内数据重建 | 强烈建议 |
| 证据抽取 Markdown 导出 | `Evidence/` | 约 1 MB | 磁盘侧导出丢失；**结构化证据主数据在 PostgreSQL** | 建议 |
| Neo4j 图数据 | Docker volume `neo4j-data` | 视图谱是否启用 | 图谱检索为空。生产默认 `APP_AI_KG_ENABLED=false` | 本机开了 KG 则建议带上 |
| 评测报告 | `data/rag-evaluation/` | 约 29 MB | 仅影响历史评测，不影响线上问答 | 可选 |
| 报告临时文件 | `tmp/` | 约 7 MB | 可再生成 | 可选 |
| 筛选 CLI 输出 | `PreTreatment/outputs/` | 约 4 MB | 可再跑筛选 | 可选 |
| 密钥 | `.env` 或本机环境变量（`DASHSCOPE_API_KEY` 等） | — | 模型调用失败 | **必须手工配置，不要提交 git** |

不要指望只拷代码仓库。`data/`、`Evidence/`、`.env` 都在 `.gitignore` 里。

---

## 2. 推荐迁移流程（停服 → 导出 → 拷贝 → 恢复 → 启动）

在源机器和目标机器上都安装：**Git、Docker Desktop（或 Docker Engine）、JDK 21、Node.js ≥ 20.19**。生产全 Docker 部署可以不装 JDK/Node，但本机当前是宿主机跑 Java，导出数据库仍需要 Docker。

### 2.1 源机器：停写并导出

1. 停后端 / 前端（关掉 `start-dev` 窗口），避免迁移过程中写入。
2. **启动 Docker Desktop**（volume 在 Docker 虚拟机里，Docker 没开就导不出来）。
3. 只拉起数据库（GROBID 不必开）：

```powershell
cd D:\Project\ai_coding_platform\demo_01
docker compose -f docker-compose.yml up -d postgres neo4j
```

4. 建导出目录并 dump PostgreSQL（自定义格式，可压缩、可并行恢复）：

```powershell
New-Item -ItemType Directory -Force backup | Out-Null
docker compose -f docker-compose.yml exec -T postgres pg_dump -U demo_01 -d demo_01 -Fc -Z 9 > backup\demo_01.dump
```

确认文件不是空的：`Get-Item backup\demo_01.dump`。

5. 导出 Neo4j 数据目录（本机开了图谱时）：

```powershell
docker compose -f docker-compose.yml stop neo4j
docker run --rm `
  -v demo_01_neo4j-data:/data `
  -v ${PWD}\backup:/backup `
  alpine tar czf /backup/neo4j-data.tar.gz -C /data .
docker compose -f docker-compose.yml start neo4j
```

若 `demo_01_neo4j-data` 不存在，先执行 `docker volume ls`，项目名可能是目录名或 `COMPOSE_PROJECT_NAME`。

6. 打包宿主机文件数据（最大头是 `data/rag`）：

```powershell
tar -czf backup\rag-files.tgz data Evidence
```

可选再加 `tmp`、`PreTreatment/outputs`、`data/rag-evaluation`。

7. 单独保存密钥（不要放进 git）：把 `DASHSCOPE_API_KEY`、`BIGMODEL_API_KEY`、数据库密码记到目标机器的 `.env`。可参考仓库根目录 `.env.example`。

### 2.2 传到新机器

任选一种即可：移动硬盘、`scp`、内网共享。建议整包：

```text
backup/demo_01.dump          # PostgreSQL
backup/neo4j-data.tar.gz     # 可选
backup/rag-files.tgz         # data/ + Evidence/
.env                         # 仅私钥，走安全通道
```

代码用 git，不要用拷贝工作区代替：

```bash
git clone <your-remote> demo_01
cd demo_01
git checkout <部署分支>
```

当前开发分支是 `codex/literature-screening-rag-entities`。生产脚本默认拉 `DEPLOY_BRANCH`（`.env.example` 里是 `main`），换机前先确认目标分支已包含本次提交。

### 2.3 目标机器：恢复数据

```bash
# 1) 基础设施
docker compose -f docker-compose.yml up -d postgres neo4j grobid

# 2) 等 postgres healthy 后恢复（会覆盖空库）
docker compose -f docker-compose.yml exec -T postgres \
  pg_restore -U demo_01 -d demo_01 --clean --if-exists --no-owner < backup/demo_01.dump
```

Windows PowerShell 下 stdin 重定向有时不可靠，可改成：

```powershell
Get-Content -AsByteStream backup\demo_01.dump | docker compose -f docker-compose.yml exec -T postgres pg_restore -U demo_01 -d demo_01 --clean --if-exists --no-owner
```

解压文件数据到**仓库根目录**（与 `application-local.yml` 的 `data/rag` 相对路径一致）：

```bash
tar -xzf backup/rag-files.tgz
# 完成后应存在 ./data/rag 与 ./Evidence
```

恢复 Neo4j（可选）：

```bash
docker compose -f docker-compose.yml stop neo4j
docker run --rm \
  -v demo_01_neo4j-data:/data \
  -v "$PWD/backup:/backup" \
  alpine tar xzf /backup/neo4j-data.tar.gz -C /data
docker compose -f docker-compose.yml start neo4j
```

首次启动新版本后端时，Flyway 会继续执行尚未打过的迁移（例如 `V34` 删遗留表、`V35` 给 `rag_document` 加 keywords 元数据）。**先恢复旧库，再启动新应用**，不要在空库上先跑一遍再 restore。

---

## 3. 新机器部署方式

### 方案 A — 与本机一致（推荐用于继续开发 / 内网试用）

前置：JDK 21、Maven Wrapper（仓库自带 `mvnw`）、Node.js ≥ 20.19、Docker 中的 Postgres `localhost:55432`、GROBID `localhost:8070`。

1. 复制 `.env.example` 为 `.env`，填入 `DASHSCOPE_API_KEY`。宿主机跑 Spring 时，也可直接设系统环境变量；`application-local.yml` 会读 `DASHSCOPE_API_KEY`。
2. 启动基础设施：

```bash
docker compose -f docker-compose.yml up -d
```

3. 启动应用：

- Windows：`.\start-dev.ps1`
- Linux/macOS：`./start-dev.sh`
- 只要后端：`.\start-dev.ps1 -BackendOnly`

4. 验收：

| 检查 | 期望 |
|------|------|
| `http://localhost:8081/api/actuator/health` | `"status":"UP"` |
| `http://localhost:5173` | 能打开并登录 |
| 随便问一个已入库化合物相关问题 | 能返回文献来源；Q1 证据开启时应能引用结构化表格 |

### 方案 B — 全 Docker 生产（`docker-compose.prod.yml`）

适合新机器只跑服务、不在宿主机装 JDK。后端容器已绑定：

- `./data` → `/app/data`（含 `rag/`、`bm25-index/`）
- `./Evidence` → `/app/Evidence`

因此 **2.3 解压到仓库根目录的 `data/` 后，直接 `compose up` 即可被后端读到**。不要只 restore 数据库却漏拷 `data/rag`。

```bash
cp .env.example .env
# 编辑 .env：DASHSCOPE_API_KEY、POSTGRES_PASSWORD、WEB_PORT 等

# 首次
bash scripts/bootstrap-server.sh

# 之后更新代码并重建
bash scripts/deploy-prod.sh
```

`deploy-prod.sh` 要求：当前分支等于 `DEPLOY_BRANCH`、工作区干净、已配置 `origin`。

验收：

```bash
bash scripts/check-prod.sh
```

默认前端只监听 `127.0.0.1:8088`。对外网开放时，应在前面加 Nginx/Apache 反代，并按 `.env` 里的 `VITE_APP_BASE_PATH=/viteApp/` 配置网关路径。重新改 `VITE_*` 后必须重新 `docker compose build web`。

生产默认关闭知识图谱：`APP_AI_KG_ENABLED=false`。若新机器也要图谱，在 `.env` 打开并恢复 Neo4j volume。

离线搬镜像（新机器拉不到 Docker Hub 时）：

```bash
# 源机器
bash scripts/build-images.sh          # 生成 ai-literature-images.tar

# 目标机器
docker load -i ai-literature-images.tar
```

Windows 源机器可用 `.\scripts\build-images.ps1`。

---

## 4. 环境变量清单（生产 `.env`）

以 `.env.example` 为准，至少确认：

| 变量 | 作用 |
|------|------|
| `DASHSCOPE_API_KEY` | 聊天、抽取、embedding、rerank |
| `DASHSCOPE_CHAT_MODEL` / `DASHSCOPE_EMBEDDING_MODEL` | 与现网保持一致，换模型会导致向量空间不兼容 |
| `POSTGRES_*` | 必须与 dump 时的用户/库名一致，否则 restore 后应用连不上 |
| `APP_AI_RAG_GROBID_BASE_URL` | 生产容器内用 `http://grobid:8070` |
| `APP_AI_KG_ENABLED` | 生产默认 false |
| `VITE_APP_BASE_PATH` / `VITE_API_BASE_URL` | 打进前端包，改完必须重建 web 镜像 |
| `WEB_PORT` | 宿主机访问端口，默认 8088 |
| `APP_USER_INIT_ADMIN_*` | 仅空库首次建管理员时打开 |

**不要更换 embedding 模型或 `embedding-dimension`（当前 1024）**，否则必须全量重建向量表。

---

## 5. 迁移后核对

在新机器 Postgres 里抽查（数字应与源机器同一量级）：

```sql
SELECT count(*) FROM rag_document;
SELECT count(*) FROM embedding_store;
SELECT count(*) FROM compound_evidence;
SELECT count(*) FROM document_cohort;
```

再确认磁盘：

```text
data/rag/<documentId>/     应有 GROBID / chunk / tables 等 artifact
data/bm25-index/           有索引文件；若缺失，可在应用起来后按现有入库流程重建
```

若聊天能查到文献但引用质量明显下降：优先检查 BM25 目录是否拷到、以及 `embedding_store` 行数是否完整。

---

## 6. 常见问题

**Docker 未启动就 dump。** volume 在 Docker VM 内，必须先开 Docker Desktop。

**只拷了 `data/` 没拷数据库。** 聊天检索、证据表、用户会话都在 PostgreSQL；缺库等于空系统。

**只拷了数据库没拷 `data/rag`。** 向量检索可能还能命中 chunk 文本，但预处理/再抽取/打开 artifact 会失败，且无法按原路径复现。

**Flyway 报校验失败。** 历史库常见。可临时 `SPRING_FLYWAY_VALIDATE_ON_MIGRATE=false` 启动，确认 `flyway.ignore-migration-patterns` 已包含 `*:missing` / `*:future`。

**Windows `pg_dump > file` 得到乱码或体积异常。** 改用 `docker compose exec` 在容器内 dump 到挂载目录，或 PowerShell `Get-Content -AsByteStream` 管道。

**新机器是 Linux、源机器是 Windows。** dump 文件与 `tar` 包是跨平台的；注意解压后 `data/rag` 路径大小写，Linux 区分大小写。

**端口。** 方案 A：Postgres `55432`、GROBID `8070`、后端 `8081`、前端 `5173`、Neo4j `7687/7474`。方案 B：对外默认只有 `127.0.0.1:8088`。
