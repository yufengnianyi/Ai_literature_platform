# BERTopic 摘要主题基线

该目录独立保存 BERTopic 聚类代码、依赖、测试和输出。它只发现当前语料中的摘要主题，不把主题频率解释为科学价值，也不自动声称发现了 Q1-Q10。

## 输入

`run.py` 读取预计算的 `embeddings.json`。`embed_corpus.py` 可从已冻结的 `corpus.jsonl` 补齐向量，并复用模型、维度、文献 ID 和内容哈希均匹配的种子向量。输入必须满足：

- 每篇文献恰好一行；
- 包含 `document_id`、`title`、`abstract` 和 `vector`；
- 所有向量维数相同、有限且非零；
- 使用同一 Embedding 模型。

传入现有向量后不会调用 DashScope，也不会下载 SentenceTransformers 模型。

本机可见的两个 PostgreSQL 容器在 2026-09-15 检查时没有可复用向量记录，因此全量实验复用了已有 100 篇本地向量，并只为其余 954 篇调用 Embedding。若正式向量库已有数据，应先从数据库导出同一结构，避免重复向量化。

## 安装

PowerShell：

```powershell
cd D:\Project\ai_coding_platform\demo_01\problem_discovery\bertopic_baseline
py -3.14 -m venv .venv
.\.venv\Scripts\python.exe -m pip install --no-deps -r requirements.txt
```

这里采用 BERTopic 官方的轻量安装方式，并锁定了本机 Python 3.14.3 验证过的完整依赖。当前实验复用外部向量且不生成交互图，因此不安装 SentenceTransformers、PyTorch 和 Plotly。

`pip check` 会因 BERTopic 上游把 SentenceTransformers 和 Plotly 声明为常规依赖而报告二者缺失；这是本实验有意采用的官方轻量模式，不影响传入预计算向量后的聚类、c-TF-IDF 和文件报告。

## 测试

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

## 运行现有 100 篇摘要

```powershell
.\.venv\Scripts\python.exe run.py `
  --embeddings ..\outputs\real-run-100-20260914\abstract\embeddings.json `
  --output outputs\real-100-seed42 `
  --min-topic-size 5 `
  --min-samples 2 `
  --seed 42 `
  --verbose
```

100 篇仅用于安装和输出验证。正式评价应使用完整、去重且完成领域范围检查的摘要语料。

## 运行全部合格摘要

本次冻结语料包含 1,054 篇合格摘要，另记录 138 条缺产物、55 条缺摘要和 53 条重复。先生成或复用向量：

```powershell
$env:DASHSCOPE_API_KEY = "<your-key>"
.\.venv\Scripts\python.exe embed_corpus.py `
  --corpus ..\outputs\real-prepared-all-20260915\corpus.jsonl `
  --seed-embeddings ..\outputs\real-run-100-20260914\abstract\embeddings.json `
  --output outputs\real-all-embeddings-20260915
```

再运行聚类。当前 Python 3.14 环境中 UMAP/Numba 首次 JIT 出现异常长耗时，因此全量基线采用 BERTopic 支持的 PCA 降维器；HDBSCAN、c-TF-IDF 和主题表示流程保持不变：

```powershell
.\.venv\Scripts\python.exe run.py `
  --embeddings outputs\real-all-embeddings-20260915\embeddings.json `
  --output outputs\real-all-seed42 `
  --reducer pca `
  --min-topic-size 10 `
  --min-samples 5 `
  --seed 42 `
  --verbose
```

`--reducer umap` 仍保留。Windows Python 3.14 和 3.10 环境中的 Numba JIT 均出现异常长耗时，因此标准 UMAP 结果改在固定的 Linux 容器中运行：

```powershell
docker build -f Dockerfile.umap -t demo01-bertopic-umap:0.1 .
docker run --rm `
  -v "${PWD}:/work" `
  -w /work `
  demo01-bertopic-umap:0.1 `
  --embeddings outputs/real-all-embeddings-20260915/embeddings.json `
  --output outputs/real-all-seed42-umap-standard `
  --reducer umap `
  --min-topic-size 10 `
  --min-samples 5 `
  --seed 42 `
  --verbose
```

该命令不设置主题数上限。`n_components=5` 是 UMAP 输出维数，不是五个主题。若后续为人工目录展示而压缩主题，最多采用 10 类，并把它作为派生结果与未压缩科学基线分开保存。

## 输出

每次运行必须指定一个尚不存在的输出目录，生成：

- `config.json`：输入、向量维数、参数和 API 调用数；
- `topic-info.json` / `topic-info.csv`：BERTopic 主题概要；
- `topic-keywords.json`：各主题 c-TF-IDF 关键词及分值；
- `document-topics.jsonl`：每篇文献的主题、概率和离群标记；
- `outliers.jsonl`：主题 `-1` 的文献；
- `representative-documents.json`：主题代表摘要；
- `metrics.json`：主题数和离群率；
- `report.md`：便于审阅的摘要报告。
- `figure-1-document-map.*`：文献二维分布及离群项；
- `figure-2-topic-sizes.*`：主题规模及 c-TF-IDF 描述词；
- `figure-3-topic-similarity.*`：主题表示余弦相似度；
- `document-map.csv` / `topic-similarity.csv`：图形源数据；
- `figure-captions.md`：可用于论文或报告的英文方法图注。

图形同时输出 400 DPI PNG 和矢量 PDF。二维投影仅用于可视化，不用于重新定义成员关系。

所有输出仍为候选主题，映射到专家 Q1-Q10 前保持 `PENDING`。

## 本机验证记录

- Python：3.14.3；BERTopic：0.17.4；HDBSCAN：0.8.44；UMAP：0.5.12。
- 自动测试：2 项通过。
- 输入：现有 100 篇摘要的 1024 维缓存向量。
- 输出：6 个非离群主题、6 篇离群文献，离群率 6%。
- API 调用：0；专家评价：`PENDING`。
- 结果目录：`outputs/real-100-seed42`。

### 全量基线

- 语料：1,054 篇去重且具有摘要的文献；向量维数：1,024。
- Embedding：复用 100 篇，新生成 954 篇，零失败；232 次提供商请求，报告用量 280,761 tokens。
- 聚类：PCA（5 个分量）+ HDBSCAN，`min_cluster_size=10`、`min_samples=5`、随机种子 42。
- 输出：4 个非离群主题；511 篇离群文献，离群率 48.5%。
- 二维图：独立 PCA；PC1 解释 4.7%，PC2 解释 4.0%，仅用于展示。
- 代码测试：2 项通过；专家映射、主题稳定性和科学有效性评价仍为 `PENDING`。
- 结果目录：`outputs/real-all-seed42`。

### 标准 UMAP 全量结果

- 环境：Docker Linux、Python 3.11、BERTopic 0.17.4、UMAP 0.5.7、Numba 0.60.0、HDBSCAN 0.8.40。
- 参数：UMAP 5 维、`n_neighbors=15`；HDBSCAN `min_cluster_size=10`、`min_samples=5`；随机种子 42。
- 主题数上限：无，配置中明确记录为 `topic_limit: null`。
- 输出：自动发现 28 个非离群主题；243 篇离群文献，离群率 23.1%。
- 与 PCA 对照：PCA 为 4 个主题、48.5% 离群；UMAP 保留了更多局部语义结构。
- 可复现性：相同容器、参数和随机种子重复全量运行后，逐篇主题分配与主题关键词文件的 SHA-256 均完全一致。
- 结果目录：`outputs/real-all-seed42-umap-standard`。
