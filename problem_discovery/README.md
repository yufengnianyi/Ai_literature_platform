# 领域问题发现实验原型

2026-09-14 方案决策：A（直接摘要聚类）退出后续实验与优化，现有 A/B 命令和产物作为历史复现保留。后续方向为 B2“研究目的抽象、全局目录归纳、逐条归属、证据总结”，详见 [领域问题分层归纳方案](../docs/领域问题分层归纳方案-20260914.md)。B2 当前为设计建议，尚未实现；以下运行说明描述旧版原型。

从文献标题和**原始摘要**生成可回查原句的候选研究问题目录，比较两种流程：

- A / `abstract`：标题和摘要直接向量化、聚类，再归纳共同问题。
- B / `question`：逐篇提炼研究问题和证据原句，对问题聚类，再归纳共同问题。

输入文献相同，两组使用相同的向量模型、目录归纳模型和聚类设置。B 每篇可以产生多个问题；A 每篇一个向量，因此原始簇大小不能直接用来比较效果。专家应比较问题依据、覆盖、目录修改量及整理时间。

本目录是独立的命令行项目，通过文件读取现有文献。所有默认实验输出、缓存和虚拟环境均在本目录内。原工程的 Java 服务与数据库不需要启动。

## 安装与自动测试

本机已验证 Python 3.14.3。依赖及其传递依赖已在 `requirements.txt` 中锁定。以下 PowerShell 命令从仓库根目录运行：

```powershell
Set-Location 'D:\Project\ai_coding_platform\demo_01'
py -3.14 -m venv problem_discovery/.venv
$pdPython = '.\problem_discovery\.venv\Scripts\python.exe'
& $pdPython -m pip install --index-url https://pypi.org/simple -r problem_discovery/requirements.txt
& $pdPython -m unittest discover -s problem_discovery/tests -v
& $pdPython problem_discovery/discovery.py --help
```

测试使用明确标记的模拟文献、模拟响应和模拟向量，不调用真实模型。测试覆盖来源定位、错误响应、重复和缺失输入、聚类边界、缓存失效、失败重试、A/B 输出以及人工评价分母。

## 先跑一个完整的离线例子

示例包含五篇虚构且有摘要的记录，以及一篇缺摘要记录。`--fixture` 仅接受与模拟文件中的 ID、标题和摘要完全一致的语料，不能用于处理真实文献。

```powershell
$pdPython = '.\problem_discovery\.venv\Scripts\python.exe'
$pdStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$pdPrepared = "problem_discovery/outputs/demo-prepared-$pdStamp"
$pdRun = "problem_discovery/outputs/demo-run-$pdStamp"
& $pdPython problem_discovery/discovery.py prepare --input-jsonl problem_discovery/tests/fixtures/corpus.jsonl --limit 0 --output-dir $pdPrepared
& $pdPython problem_discovery/discovery.py run --corpus "$pdPrepared/corpus.jsonl" --mode both --fixture problem_discovery/tests/fixtures/model_responses.json --output-dir $pdRun
& $pdPython problem_discovery/discovery.py evaluate --run-dir $pdRun
```

打开输出目录里的 `report.md` 查看目录及全部原文依据，打开 `review.csv` 进行审阅。未填写的评价保持 `PENDING`，不会变成零分或满分。模拟运行的所有报告均标记 `SIMULATED`，只能验证软件流程。

## 准备真实语料

默认数据源是仓库已有的 `data/rag/<documentId>/`。优先读取 `header.tei.xml`，再从 `document.tei.xml` 补齐缺失的标题、原始摘要、DOI 和年份；不从正文臆造摘要。先试 10 篇：

```powershell
$pdPrepared = "problem_discovery/outputs/real-prepared-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
& $pdPython problem_discovery/discovery.py prepare --artifact-root data/rag --limit 10 --seed 42 --output-dir $pdPrepared
```

可用 `--ids 路径` 提供每行一个文献 ID 的 UTF-8 清单，或用 `--input-jsonl 路径` 读取固定导出文件：

```json
{"document_id":"paper-001","title":"Paper title","abstract":"Original abstract text.","doi":"10.example/paper-001","year":2025}
```

也接受现有 Java 导出常见的 `documentId`、`abstractText`、`doiNormalized`、`publicationYear` 名称。`--limit 0` 表示选择全部合格记录；默认上限为 100。抽样在去重和摘要检查之后进行，使用固定随机种子。

准备输出包括：

| 文件 | 内容 |
| --- | --- |
| `corpus.jsonl` | 固定输入、来源位置、内容哈希及文献元数据 |
| `excluded.jsonl` | 重复、缺摘要、损坏、清单中不存在或未被抽中的记录 |
| `manifest.json` | 数据源、抽样参数、各状态数量和输入哈希 |

去重按显式重复标记、规范化 DOI、相同标题和摘要进行，属于报告层面的保守去重。它不等于研究身份或专利族识别。准备目录已经存在时会要求使用新目录，以保留旧输入快照。

随机抽样只描述当前文献库。正式评价应另行确定能覆盖多个研究方向的语料清单；不要只使用已有 Q1 分类通过的文献。Q1–Q10 不进入发现提示词。

## 调用真实模型

运行前在当前终端配置 `DASHSCOPE_API_KEY`。凭据只从环境变量读取，程序不会扫描 `.env` 或 Java 配置文件，也不会保存密钥。默认模型与地址：

| 配置 | 默认值 | 覆盖方式 |
| --- | --- | --- |
| Chat | `qwen3-max-2026-01-23` | `--chat-model` 或 `DASHSCOPE_CHAT_MODEL` |
| Embedding | `text-embedding-v4`，1024 维 | `--embedding-model`、`--dimensions` 或 `DASHSCOPE_EMBEDDING_MODEL` |
| OpenAI-compatible base URL | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `--base-url` 或 `DASHSCOPE_BASE_URL` |
| HTTP 超时 | 90 秒 | `--timeout` |
| 每次调用尝试次数 | 最多 3 次 | `--max-attempts`，范围 1–5 |

模型可用性和服务区域由提供商及当前凭据决定。地址与密钥应匹配；认证失败会记录 HTTP 状态，不记录响应中的凭据内容。

```powershell
$pdRun = "problem_discovery/outputs/real-run-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
& $pdPython problem_discovery/discovery.py run --corpus "$pdPrepared/corpus.jsonl" --mode both --distance-threshold 0.35 --output-dir $pdRun
```

两组统一使用余弦距离、平均连接的凝聚层次聚类。`0.35` 只是开发起点，需要在开发语料上选定参数并在正式评价前冻结。模型输入过长会保留失败状态，不静默截断后宣称完整抽取。

模型调用量、提供商返回的 tokens、耗时和失败原因保存在 `attempts.jsonl` 与 `metrics.json`，A/B 分组用量也保存在 `results.json`。缓存命中单独计数；未返回的 token 数量和未配置价格的货币成本保留为空。

## 输出与断点续跑

```text
outputs/<runId>/
  manifest.json           配置、版本、哈希和整体状态
  preparation.json        原始准备记录（存在时复制）
  corpus.jsonl            本次运行输入快照
  attempts.jsonl          累计调用、错误、缓存与模型用量
  abstract/               A 的向量、聚类和文献状态
  question/               B 的问题、向量、聚类和文献状态
  results.json            完整 A/B 结构化结果
  metrics.json            软件执行统计，专家评价初始为待完成
  report.md               目录、全部成员、原文、偏移位置和失败记录
  review.csv              人工审阅表
  evaluation.json         evaluate 生成的详细指标
  evaluation.md           evaluate 生成的可读报告
```

`questions.jsonl` 位于 `question/` 中。来源偏移采用 Python Unicode code point，零起始、右侧不包含，定位对象是 `corpus.jsonl` 的摘要快照。TEI 摘要已规整空白，JSONL 摘要保留原文本。原句重复时必须消歧；能找到原句只证明引用可定位，语义支持仍需人工核验。

每篇最多三个问题，`overflow=true` 表示存在超出上限的核心问题。小组和单例保留；大组分批覆盖全部成员，标为待拆分审阅，子批次分别归纳。未被共同问题覆盖的成员会列出。

失败后使用同样参数和同一输出目录，加 `--resume`：

```powershell
& $pdPython problem_discovery/discovery.py run --corpus "$pdPrepared/corpus.jsonl" --mode both --distance-threshold 0.35 --output-dir $pdRun --resume
```

模拟重跑还须提供同一个 `--fixture`。成功且通过校验的调用可复用缓存；失败调用重新尝试。输入、代码、模型、提示词、软件版本或关键参数变化时，使用新运行目录。跨运行缓存也按具体请求与模型配置区分，并在读取时重新验证。

重新生成审阅表时，内容未变化的条目保留人工填写值，变化条目重新待审，原表变更前保留备份。`--no-cache` 用于新建的独立稳定性实验，不能与 `--resume` 同时使用。缓存重放不能作为模型稳定性的证据。

退出码：`0` 表示软件流程完成，`1` 表示参数、输入或运行级错误，`2` 表示流程完成但存在抽取、向量或归纳失败。成功退出不等于科学验收通过；空目录仍需结合语料和失败统计解释。

## 人工评价

先由专家在尚未查看模型答案时，为固定抽样摘要列出参考问题，再审阅 A/B 输出。表中的来源、编号和状态列由程序管理，只填写以下人工列：

| 行类型 `kind` | 填写字段 | 含义 |
| --- | --- | --- |
| `document` | `reference_question_count`、`covered_question_count` | 人工参考问题数，以及目录通过该文献成员覆盖的数量 |
| `question` | `supported`：`yes` / `no` | 共同问题是否受到列出的各项原文支持 |
| `extraction` | `supported`：`yes` / `no` | B 的逐篇问题是否准确表达原摘要 |
| `membership` | `grouping_correct`：`yes` / `no` | 该成员是否适合所属主题 |
| `cluster` | `decision`：`keep` / `rename` / `merge` / `split` / `reject` | 目录条目应如何处理 |
| 所有行 | `reviewer`、`notes` | 审阅人、修改意见、漏掉的具体问题 |
| `document` | `review_minutes` | 完成该篇对应方案审阅的人工分钟数 |

同一文献的 A/B 参考问题数必须一致；参考数可以只在一组填写，评价会共享该参考分母。只要给出了参考数，无目录输出的文献自动计为覆盖零；不得把失败文献删除来抬高指标。尚未审阅或被删去的表格行仍是待评价。A/B 用同一份独立人工参考，避免模型答案影响参考问题。

```powershell
& $pdPython problem_discovery/discovery.py evaluate --run-dir $pdRun
```

支持 `--review 另一个CSV路径`。参考字段和行标识被改动、重复或混入其他运行的审阅行时会拒绝评价。

完整指标只有在对应分母审阅完成后才计算；同时给出已审阅子集的结果及待审数量。输出无问题时支持率为空，不会显示为 100%。报告包含遗漏、失败、单例比例和人工时间，评价时应一起阅读。

首轮可与专家约定“问题依据达到 90%、参考问题覆盖达到 80%”等试点目标，并在运行前冻结。单一比例不自动触发科学验收；仍需检查大组、小组、跨组重复、被拆散的问题和专家分歧。若 A 已满足实际需求且 B 未改善覆盖或整理工作量，应保留更简单的流程。

## 验证状态

### 离线阈值诊断

真实十篇完成后，可复用已有向量扫描阈值，不重新调用模型，也不改写原始实验结果：

```powershell
.\problem_discovery\.venv\Scripts\python.exe problem_discovery/experiments/diagnose_thresholds.py --run-dir problem_discovery/outputs/real-run-10-20260914 --output-dir "problem_discovery/outputs/diagnostics-$(Get-Date -Format 'yyyyMMdd-HHmmss')" --thresholds 0.25 0.35 0.45 0.55 0.65 0.75 0.85 0.95
```

输出 `diagnostics.json` 和 `diagnostics.md`，区分单成员组、单文献组、跨文献组，记录缺向量的文献及来源哈希。结果仅用于开发诊断，不自动选择最佳阈值，不将合并率作为科学评分。目录必须是新的输出目录。

首轮中文诊断和助手初步审阅见 `outputs/real-run-10-diagnostics-20260914/development-review.zh.md`。其中的助手意见与 `review.csv` 中的专家判断分开，不能当作盲审答案。

100 篇真实扩展实验位于 `outputs/real-run-100-20260914`，中文小结为 `summary.zh.md`；对应离线阈值诊断位于 `outputs/real-run-100-diagnostics-20260914`。该轮状态为 `PARTIAL`，表示真实流程已结束但存在未通过证据校验的抽取或归纳项，不等于专家验收完成。

自动测试通过、模拟 A/B 完成、真实模型运行完成、专家评价完成是四种不同状态。具体本机验证见 `VALIDATION.md`。这里提供的模拟语料和向量不构成抽取准确率、聚类质量或领域覆盖的证据。

工程设计来源：仓库 `docs/领域问题发现最小原型实施方案-20260913.md`。算法参数参考：[scikit-learn AgglomerativeClustering](https://scikit-learn.org/stable/modules/generated/sklearn.cluster.AgglomerativeClustering.html)；向量接口参考：[DashScope 文档](https://www.alibabacloud.com/help/en/model-studio/embedding)。
