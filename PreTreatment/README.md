# PreTreatment 文献预筛工具

`PreTreatment` 是离线文献预筛入口，用于在现有 `data/rag` artifact 已生成之后、向量入库或清理向量库之前，判断文献是否属于“卵菌为主研究”。

## 筛选口径

默认采用广义卵菌主研究范围：卵菌生物学、防治、抗性、检测、效应子、基因组、生态等均可保留。只浅层提到卵菌，或研究主体明显不是卵菌的文献会被剔除。

期刊质量只作为风险标记，不作为默认淘汰条件。第二层采用中科院分区规则：

- `cas_partition = Q1` 或 `Q2`：可信任期刊，标记为 `HIGH`
- `cas_partition = Q3`：非可信任优先期刊，标记为 `MEDIUM`
- `cas_partition = Q4`：低优先级期刊，标记为 `LOW`
- 无法匹配期刊或缺少分区：标记为 `UNKNOWN`

`LOW` 和 `UNKNOWN` 会写入报告，仍由主题判定决定最终结果。

第二层会优先使用 DOI 查询 Crossref：`DOI -> container-title/ISSN -> journal-quality.csv`。匹配优先级为 ISSN/E-ISSN 精确匹配，然后是期刊名标准化匹配。Crossref 查询失败或 DOI 缺失时，降级使用 artifact 原始 `metadata.journal`。

## 运行

扫描并生成报告，不修改向量库：

```powershell
.\PreTreatment\run-scan.ps1
```

按最近一次扫描结果删除 rejected 文献的向量和 BM25 条目：

```powershell
.\PreTreatment\run-apply.ps1
```

`apply` 只处理 `rejected-document-ids.txt`，不会删除 `accepted` 或 `uncertain` 文献。建议先人工查看 `PreTreatment/outputs/{runId}` 下的 Markdown 和 CSV 报告。

## 输出

每次运行会生成：

- `results.jsonl`：每篇文献完整筛选结果
- `results.csv`：便于人工复核的表格
- `summary.md`：统计摘要
- `accepted-document-ids.txt`：通过清单
- `rejected-document-ids.txt`：剔除清单
- `uncertain-document-ids.txt`：人工复核清单

## 配置

默认配置在 `PreTreatment/config/pretreatment.yml`，期刊质量本地表在 `PreTreatment/config/journal-quality.csv`。
