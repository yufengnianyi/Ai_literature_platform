# PreTreatment 文献预筛工具

`PreTreatment` 是离线文献预筛入口，用于在现有 `data/rag` artifact 已生成之后、向量入库或清理向量库之前，判断文献是否属于“卵菌为主研究”。

## 筛选口径

默认采用广义卵菌主研究范围：卵菌生物学、防治、抗性、检测、效应子、基因组、生态等均可保留。只浅层提到卵菌，或研究主体明显不是卵菌的文献会被剔除。

筛选不再使用期刊质量、分区或 Crossref 查询作为判断条件。通过质量门控后，只由 LLM 根据题名和摘要做二分类主题判断：`RELEVANT` 或 `NOT_RELEVANT`。

## 运行

扫描并生成报告，不修改向量库：

```powershell
.\PreTreatment\run-scan.ps1
```

按最近一次扫描结果删除 rejected 文献的向量和 BM25 条目：

```powershell
.\PreTreatment\run-apply.ps1
```

`apply` 只处理 `rejected-document-ids.txt`，不会删除 `accepted` 文献。建议先人工查看 `PreTreatment/outputs/{runId}` 下的 Markdown 和 CSV 报告。

## 输出

每次运行会生成：

- `results.jsonl`：每篇文献完整筛选结果
- `results.csv`：便于人工复核的表格
- `summary.md`：统计摘要
- `accepted-document-ids.txt`：通过清单
- `rejected-document-ids.txt`：剔除清单

## 配置

默认配置在 `PreTreatment/config/pretreatment.yml`。
