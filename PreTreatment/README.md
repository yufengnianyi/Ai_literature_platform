# PreTreatment 文献预筛工具

`PreTreatment` 是文献预筛入口，用于在现有 `data/rag` artifact 已生成之后，判断文献是否属于“卵菌相关研究”，并为后续 Q1-Q10 分类提供 accepted/rejected cohort。

## 筛选口径

当前只保留一个筛选版本：L1 确定性质量门控 + L2 题名/摘要 LLM 二分类。

筛选不再使用期刊质量、分区或 Crossref 查询作为判断条件。通过质量门控后，只由 LLM 根据题名和摘要做二分类主题判断：`RELEVANT` 或 `NOT_RELEVANT`。

LLM 只接收 `Title`、`Journal`、`DOI`、`Abstract`，不读取正文 chunks。

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
- `accepted-document-ids.txt`：通过清单
- `rejected-document-ids.txt`：剔除清单

REST 运行完成后还会写入 `pretreatment_run`、`pretreatment_document_result`，并创建 `filter-accepted-{runId}` / `filter-rejected-{runId}` cohort。

## 配置

默认配置在 `PreTreatment/config/pretreatment.yml`。
