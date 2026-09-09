# PreTreatment 文献预筛工具

`PreTreatment` 是文献预筛入口，面向所有去重后的文献记录执行可用性检查和卵菌相关性筛选，并为后续 Q1-Q10 分类提供不同用途的 cohort。

## 筛选口径

当前筛选版本分为两步：L1 文献质量与可用性检查 + L2 题名/摘要 LLM 三分类。

筛选不使用期刊质量、分区或 Crossref 查询作为判断条件。全文质量不足只会标记为 `METADATA_READY`，不会直接排除；只要题名或摘要可用，文献仍会进入主题判断。

LLM 只接收 `Title`、`Journal`、`DOI`、`Abstract`，不读取正文 chunks，并输出 `RELEVANT`、`POTENTIALLY_RELEVANT` 或 `NOT_RELEVANT`。`RELEVANT` 和 `POTENTIALLY_RELEVANT` 都归入 `ACCEPTED`；不确定、摘要缺失和模型失败也按可能相关保留。只有明确无关才会进入 `REJECTED`。

## 运行

扫描并生成报告，不修改向量库：

```powershell
.\PreTreatment\run-scan.ps1
```

记录最近一次扫描结果对应的 rejected 清单，不直接删除向量：

```powershell
.\PreTreatment\run-apply.ps1
```

`apply` 只读取 `rejected-document-ids.txt` 并记录执行摘要，不删除 `accepted` 文献，也不直接清理向量。需要清理 rejected 文献向量时，使用 REST 接口 `POST /api/stages/filter/runs/{runId}/vector-gc?dryRun=false` 显式触发。

## 输出

每次运行会生成：

- `results.jsonl`：每篇文献完整筛选结果
- `results.csv`：便于人工复核的表格
- `summary.md`：统计摘要
- `accepted-document-ids.txt`：相关或可能相关清单
- `rejected-document-ids.txt`：明确无关清单
- `skipped-document-ids.txt`：无可用内容清单

REST 运行完成后还会写入 `pretreatment_run`、`pretreatment_document_result`，并创建接受、全文证据、摘要分析和明确排除 cohort。

## 配置

默认配置在 `PreTreatment/config/pretreatment.yml`。
