# Q1 experiment-unit comparison

This fixture compares two paths on one multi-compound Q1 paper:

1. `DIRECT_SOURCE`: rebuild experiment-centric units from the source tables and method/result passages.
2. `LEGACY_TRANSFORM`: convert the 33 verified rows produced by the old Q1 flow into the same unit shape.

The selected paper reports six heterodimeric polyketides against four oomycetes, an extract against nine oomycetes, and cytotoxicity against six human tumor cell lines. The source PDF is used to audit concentration glyphs because TEI/OCR converted `μg/mL` to `mg/ml` and `μM` to `mm`.

Run from the repository root:

```powershell
problem_discovery\.venv\Scripts\python.exe scripts\q1_structured_experiment\run_experiment.py
```

Outputs are written to `outputs/q1-structured-comparison-20260915/`:

- `direct-source-units.json`: source-grounded experiment units.
- `legacy-transformed-units.json`: old Q1 rows converted to the same structure.
- `comparison.json`: observation matching, information gain, and evidence-maturity assessment.
- `summary.zh.md`: concise scientific interpretation.
- `report.html`: self-contained interactive visualization.

This is a schema acceptance fixture, not a general table parser. Source-specific mappings are intentionally explicit and evidence-linked so the proposed unit can be judged before generalizing extraction prompts and parsers.
