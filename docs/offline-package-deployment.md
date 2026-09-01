# 离线压缩包部署说明

本文档用于把当前项目、Docker 数据库数据以及宿主机数据目录打成一个压缩包，再在另一台机器上恢复部署。

当前源机实际有业务数据的 PostgreSQL 是正在运行的 `ai-code-postgres` 容器，库名是 `demo_01`。该容器是 PostgreSQL 17；压缩包内提供了 `docker-compose.pg17.override.yml`，目标机恢复这份 dump 时应一起使用，避免把 PG17 dump 还原到 PG16。

## 1. 源机打包

在项目根目录运行：

```powershell
.\scripts\export-deployment-package.ps1
```

脚本会生成：

```text
outputs/deployment/ai-literature-deploy-YYYYMMDD-HHMMSS.tar.gz
```

压缩包内容：

| 路径 | 说明 |
| --- | --- |
| `source/` | 项目源码，已排除 `.git`、`node_modules`、`target`、`.env`、`data/`、`Evidence/`、`outputs/` 等本地缓存或敏感文件 |
| `backup/postgres-demo_01.dump` | PostgreSQL 逻辑备份，`pg_dump -Fc -Z 9` 格式 |
| `backup/host-data-evidence.tar.gz` | 宿主机 `data/` 与 `Evidence/`，包含 RAG artifact、BM25 索引、证据导出等 |
| `backup/neo4j-data.tar.gz` | Neo4j `/data` volume 归档；脚本会短暂停止 Neo4j 后再打包，以保证一致性 |
| `restore-manifest.json` | 源容器、数据库版本、Git 分支和提交等恢复元信息 |

真实 `.env` 不会被打包。请通过安全渠道把必要密钥带到目标机，或在目标机根据 `source/.env.example` 重新填写。

如需手动指定容器：

```powershell
.\scripts\export-deployment-package.ps1 `
  -PostgresContainer ai-code-postgres `
  -PostgresDatabase demo_01 `
  -PostgresUser demo_01 `
  -Neo4jContainer ai_literature-neo4j-1
```

如果目标环境不需要 Neo4j，可加 `-SkipNeo4j`。

## 2. 目标机前置条件

目标机需要安装：

- Docker Engine / Docker Desktop
- Docker Compose v2
- `tar`

如果目标机不能访问 Docker Hub，需要先在有网络的机器上导出镜像，再在目标机 `docker load`。仓库已有镜像脚本：

```bash
bash scripts/build-images.sh
```

Windows 源机可用：

```powershell
.\scripts\build-images.ps1
```

## 3. 解压压缩包

Linux 示例：

```bash
mkdir -p /opt/ai-literature
tar -xzf ai-literature-deploy-YYYYMMDD-HHMMSS.tar.gz -C /opt/ai-literature
cd /opt/ai-literature/source
```

Windows PowerShell 示例：

```powershell
New-Item -ItemType Directory -Force C:\deploy\ai-literature | Out-Null
tar -xzf .\ai-literature-deploy-YYYYMMDD-HHMMSS.tar.gz -C C:\deploy\ai-literature
cd C:\deploy\ai-literature\source
```

## 4. 配置 `.env`

在 `source/` 目录下创建 `.env`：

```bash
cp .env.example .env
```

至少确认这些变量：

```text
COMPOSE_PROJECT_NAME=ai_literature
POSTGRES_DB=demo_01
POSTGRES_USER=demo_01
POSTGRES_PASSWORD=demo_01
WEB_PORT=8088
DASHSCOPE_API_KEY=<目标机真实密钥>
DASHSCOPE_CHAT_MODEL=qwen-max
DASHSCOPE_EMBEDDING_MODEL=text-embedding-v4
APP_AI_RAG_GROBID_BASE_URL=http://grobid:8070
APP_AI_KG_ENABLED=false
```

如果要恢复 Neo4j 并启用知识图谱：

```text
APP_AI_KG_ENABLED=true
SPRING_NEO4J_URI=bolt://neo4j:7687
SPRING_NEO4J_USERNAME=neo4j
SPRING_NEO4J_PASSWORD=demo_01_graph
```

## 5. 恢复宿主机数据

在 `source/` 目录执行：

```bash
tar -xzf ../backup/host-data-evidence.tar.gz -C .
```

完成后应存在：

```text
source/data/rag/
source/data/bm25-index/
source/Evidence/
```

## 6. 启动数据库并恢复 PostgreSQL

因为本压缩包的 PostgreSQL dump 来自 PG17，启动时要带上 pg17 override：

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.pg17.override.yml up -d postgres neo4j grobid
```

等待 PostgreSQL 健康后恢复：

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.pg17.override.yml exec -T postgres \
  pg_restore -U demo_01 -d demo_01 --clean --if-exists --no-owner < ../backup/postgres-demo_01.dump
```

Windows PowerShell 用二进制管道：

```powershell
Get-Content -AsByteStream ..\backup\postgres-demo_01.dump |
  docker compose -f docker-compose.prod.yml -f docker-compose.pg17.override.yml exec -T postgres `
    pg_restore -U demo_01 -d demo_01 --clean --if-exists --no-owner
```

恢复后可抽查：

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.pg17.override.yml exec -T postgres \
  psql -U demo_01 -d demo_01 -c "select count(*) from rag_document; select count(*) from embedding_store; select count(*) from compound_evidence;"
```

## 7. 恢复 Neo4j

如果压缩包包含 `backup/neo4j-data.tar.gz`，执行：

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.pg17.override.yml stop neo4j
docker run --rm \
  -v ai_literature_neo4j-data:/data \
  -v "$PWD/../backup:/backup" \
  alpine:3.20 tar xzf /backup/neo4j-data.tar.gz -C /data
docker compose -f docker-compose.prod.yml -f docker-compose.pg17.override.yml start neo4j
```

Windows PowerShell：

```powershell
docker compose -f docker-compose.prod.yml -f docker-compose.pg17.override.yml stop neo4j
docker run --rm `
  -v ai_literature_neo4j-data:/data `
  -v "${PWD}\..\backup:/backup" `
  alpine:3.20 tar xzf /backup/neo4j-data.tar.gz -C /data
docker compose -f docker-compose.prod.yml -f docker-compose.pg17.override.yml start neo4j
```

如果 `.env` 中改了 `COMPOSE_PROJECT_NAME`，Neo4j volume 名也会变化，格式通常是 `<COMPOSE_PROJECT_NAME>_neo4j-data`。

## 8. 启动应用

在 `source/` 目录执行：

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.pg17.override.yml up -d --build
```

检查：

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.pg17.override.yml ps
curl http://127.0.0.1:8088/viteApp/api/actuator/health
```

浏览器访问：

```text
http://127.0.0.1:8088/viteApp/
```

生产 compose 默认只绑定 `127.0.0.1:${WEB_PORT}`。如果需要外网访问，请在机器前面配置 Nginx/Apache 反向代理，不建议直接把应用端口暴露到公网。

## 9. 常见问题

`pg_restore` 报版本或 SQL 语法错误：确认启动命令带了 `-f docker-compose.pg17.override.yml`，并且 `postgres` 镜像是 `pgvector/pgvector:pg17`。

应用能启动但检索结果为空：优先检查 `data/rag`、`data/bm25-index` 是否已从 `host-data-evidence.tar.gz` 解压到 `source/` 根目录。

模型调用失败：确认目标机 `.env` 中的 `DASHSCOPE_API_KEY` 是真实值。压缩包故意不包含源机 `.env`。

Neo4j 恢复后没有图谱：确认 `APP_AI_KG_ENABLED=true`，并确认恢复 volume 名与 `COMPOSE_PROJECT_NAME` 匹配。
