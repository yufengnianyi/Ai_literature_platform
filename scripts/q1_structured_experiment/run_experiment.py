"""Build a source-vs-legacy Q1 experiment-unit comparison and offline report."""

from __future__ import annotations

import argparse
import html
import json
import math
import re
from collections import defaultdict
from pathlib import Path
from statistics import median


DOCUMENT_ID = "7ae6fb01-cad8-4b71-a8db-35b6e03a22fb"
TITLE = (
    "Acremoxanthone E, a Novel Member of Heterodimeric Polyketides with a "
    "Bicyclo[3.2.2]nonene Ring, Produced by Acremonium camptosporum W. Gams "
    "(Clavicipitaceae) Endophytic Fungus"
)
SCHEMA_VERSION = "q1-experiment-unit.v0.2"

COMPOUNDS = {
    "1": ("acremoxanthone E", "hydroxanthone", "new compound"),
    "2": ("acremoxanthone C", "hydroxanthone", "known compound"),
    "3": ("acremonidin A", "benzophenone", "known compound"),
    "4": ("acremonidin B", "benzophenone", "known compound"),
    "5": ("acremoxanthone A", "xanthone", "known compound"),
    "6": ("acremoxanthone B", "xanthone", "known compound"),
}

T1_DAYS = {
    "Pythium ultimum": 3,
    "Pythium debaryanum": 3,
    "Pythium polytylum": 3,
    "Pythium aphanidermatum": 1,
    "Phytophthora cactorum": 3,
    "Phytophthora cinnamomi": 3,
    "Phytophthora palmivora": 3,
    "Phytophthora capsici": 3,
    "Phytophthora parasitica": 4,
}

T4_TARGETS = {
    "P. aphanidermatum": ("Pythium aphanidermatum", 1),
    "P. cinnamomi": ("Phytophthora cinnamomi", 4),
    "P. capsici": ("Phytophthora capsici", 3),
    "P. parasitica": ("Phytophthora parasitica", 4),
}

CELL_LINES = {
    "U251": "central nervous system cancer",
    "PC-3": "human prostatic adenocarcinoma",
    "K562": "human chronic myelogenous leukemia",
    "HCT-15": "human colorectal adenocarcinoma",
    "MCF-7": "human mammary adenocarcinoma",
    "SKLU-1": "human lung adenocarcinoma",
}

# Table 5's final row is structurally merged in TEI. The values below are audited
# against source PDF page 9 (journal page 141), not inferred from the broken cells.
CPT_VALUES = {
    "U251": "0.024 ± 0.005",
    "PC-3": "0.12 ± 0.010",
    "K562": "0.59 ± 0.020",
    "HCT-15": "0.13 ± 0.005",
    "MCF-7": "0.16 ± 0.001",
    "SKLU-1": "0.15 ± 0.009",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--output-dir", type=Path)
    return parser.parse_args()


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def read_jsonl(path: Path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_json(path: Path, value) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def slug(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-")


def clean_species(value: str) -> str:
    value = re.sub(r"\s+[bcd]\s*\)\s*$", "", value.strip(), flags=re.I)
    return value.replace("P. aphanidermatum", "Pythium aphanidermatum").replace(
        "P. cinnamomi", "Phytophthora cinnamomi"
    ).replace("P. capsici", "Phytophthora capsici").replace(
        "P. parasitica", "Phytophthora parasitica"
    )


def parse_value(raw: str) -> tuple[str, float | None, float | None]:
    text = str(raw).strip().replace("AE", "±")
    if not text or text.upper() == "ND":
        return "ND", None, None
    relation = "GT" if text.startswith(">") else "EQ"
    numbers = re.findall(r"[0-9]+(?:\.[0-9]+)?", text)
    number = float(numbers[0]) if numbers else None
    sd = float(numbers[1]) if len(numbers) > 1 else None
    return relation, number, sd


def parse_legacy_activity(raw: str) -> tuple[str, str, float | None]:
    match = re.search(r"IC\s*50\s*\[([^\]]+)\]\s*:\s*(>\s*)?([0-9.]+)", raw, flags=re.I)
    if not match:
        raise ValueError(f"Cannot parse legacy activity: {raw}")
    relation = "GT" if match.group(2) else "EQ"
    return match.group(1), relation, float(match.group(3))


def evidence(chunk_id: str, quote: str, page: int | None = None, note: str | None = None) -> list[dict]:
    item = {"chunk_id": chunk_id, "exact_quote": quote}
    if page is not None:
        item["source_pdf_page"] = page
    if note:
        item["note"] = note
    return [item]


def observation(
    observation_id: str,
    endpoint: str,
    raw: str,
    raw_unit: str | None,
    normalized_unit: str | None,
    status: str,
    evidence_items: list[dict],
    source_legacy_row_ids: list[str] | None = None,
) -> dict:
    relation, number, sd = parse_value(raw)
    row = {
        "observation_id": observation_id,
        "endpoint": endpoint,
        "value_raw": raw,
        "value_numeric": number,
        "relation": relation,
        "sd": sd,
        "unit": {"raw": raw_unit, "normalized": normalized_unit, "status": status},
        "evidence": evidence_items,
    }
    if source_legacy_row_ids:
        row["source_legacy_row_ids"] = source_legacy_row_ids
    return row


def group(group_id: str, role: str, material_id: str, obs: dict) -> dict:
    return {"group_id": group_id, "role": role, "material_id": material_id, "observations": [obs]}


def material_catalog(include_safety: bool) -> list[dict]:
    source = {
        "category": "microbial",
        "producer": "Acremonium camptosporum",
        "producer_ecology": "endophytic fungus",
        "host": "Bursera simaruba",
        "host_tissue": "leaves",
    }
    rows = [
        {
            "material_id": "extract-mycelium",
            "display_name": "A. camptosporum mycelium extract",
            "original_label": "mycelium extract",
            "material_type": "EXTRACT",
            "structure_family": None,
            "structure_subunit": None,
            "novelty": None,
            "source": source,
        }
    ]
    for code, (name, subunit, novelty) in COMPOUNDS.items():
        rows.append(
            {
                "material_id": f"compound-{code}",
                "display_name": name,
                "original_label": code,
                "material_type": "PURE_COMPOUND",
                "structure_family": "heterodimeric polyketide",
                "structure_subunit": subunit,
                "novelty": novelty,
                "source": source,
            }
        )
    rows.append(
        {
            "material_id": "control-metalaxyl",
            "display_name": "Metalaxyl-m (Mefenoxam)",
            "original_label": "Metalaxyl",
            "material_type": "CONTROL",
            "structure_family": None,
            "structure_subunit": None,
            "novelty": None,
            "source": {"category": "commercial oomyceticide", "product": "Ridomil Gold 4E"},
        }
    )
    if include_safety:
        rows.extend(
            [
                {
                    "material_id": "control-cisplatin",
                    "display_name": "Cisplatin",
                    "original_label": "cisPt",
                    "material_type": "CONTROL",
                    "structure_family": None,
                    "structure_subunit": None,
                    "novelty": None,
                    "source": {"category": "cytotoxicity positive control"},
                },
                {
                    "material_id": "control-camptothecin",
                    "display_name": "Camptothecin",
                    "original_label": "CPT",
                    "material_type": "CONTROL",
                    "structure_family": None,
                    "structure_subunit": None,
                    "novelty": None,
                    "source": {"category": "cytotoxicity positive control"},
                },
            ]
        )
    return rows


def document_record(source_pdf: Path) -> dict:
    return {"document_id": DOCUMENT_ID, "title": TITLE, "source_pdf": str(source_pdf)}


def table_map(tables: list[dict]) -> dict[str, dict]:
    return {row["tableRef"]: row for row in tables}


def direct_source_bundle(source_pdf: Path, tables: list[dict]) -> dict:
    by_id = table_map(tables)
    experiments = []
    t1 = by_id["T1"]
    for row in t1["rows"]:
        species = clean_species(row[0])
        if species not in T1_DAYS:
            continue
        row_quote = next(line for line in t1["markdown"].splitlines() if line.startswith(f"| {row[0]} |"))
        exp_id = f"efficacy-extract-{slug(species)}"
        conditions = [
            {"factor": "incubation_time", "value_numeric": T1_DAYS[species], "unit": "day", "status": "REPORTED"},
            {"factor": "endpoint_definition", "value_raw": "50% diameter growth reduction", "status": "REPORTED"},
        ]
        groups = [
            group(
                f"{exp_id}-test",
                "TEST",
                "extract-mycelium",
                observation(
                    f"{exp_id}-test-ic50",
                    "radial_growth_IC50",
                    row[1],
                    "mg/ml (TEI)",
                    "μg/mL",
                    "PDF_GLYPH_AUDITED",
                    evidence(f"{DOCUMENT_ID}:table:T1", row_quote, 3, "PDF table header confirms μg/mL"),
                ),
            ),
            group(
                f"{exp_id}-positive-control",
                "POSITIVE_CONTROL",
                "control-metalaxyl",
                observation(
                    f"{exp_id}-control-ic50",
                    "radial_growth_IC50",
                    row[2],
                    "mg/ml (TEI)",
                    "μg/mL",
                    "PDF_GLYPH_AUDITED",
                    evidence(f"{DOCUMENT_ID}:table:T1", row_quote, 3, "PDF table header confirms μg/mL"),
                ),
            ),
        ]
        experiments.append(
            {
                "experiment_id": exp_id,
                "domain": "EFFICACY",
                "target": {"type": "oomycete", "original_name": row[0], "standard_name": species, "strain": None, "life_stage": "mycelial growth"},
                "assay": {"method": "radial growth inhibition", "endpoint": "IC50", "setting": "in vitro"},
                "conditions": conditions,
                "groups": groups,
                "source_table": "T1",
            }
        )

    t4 = by_id["T4"]
    for row in t4["rows"]:
        short = re.sub(r"\s+[bcd]\s*\)\s*$", "", row[0].strip(), flags=re.I)
        if short not in T4_TARGETS:
            continue
        species, days = T4_TARGETS[short]
        row_quote = next(line for line in t4["markdown"].splitlines() if line.startswith(f"| {row[0]} |"))
        exp_id = f"efficacy-compounds-{slug(species)}"
        groups = []
        for index, code in enumerate(COMPOUNDS, start=1):
            groups.append(
                group(
                    f"{exp_id}-compound-{code}",
                    "TEST",
                    f"compound-{code}",
                    observation(
                        f"{exp_id}-compound-{code}-ic50",
                        "radial_growth_IC50",
                        row[index].replace(" ", ""),
                        "mm (TEI)",
                        "μM",
                        "PDF_GLYPH_AUDITED",
                        evidence(f"{DOCUMENT_ID}:table:T4", row_quote, 9, "PDF table header confirms μM"),
                    ),
                )
            )
        groups.append(
            group(
                f"{exp_id}-positive-control",
                "POSITIVE_CONTROL",
                "control-metalaxyl",
                observation(
                    f"{exp_id}-control-ic50",
                    "radial_growth_IC50",
                    row[7],
                    "mm (TEI)",
                    "μM",
                    "PDF_GLYPH_AUDITED",
                    evidence(f"{DOCUMENT_ID}:table:T4", row_quote, 9, "PDF table header confirms μM"),
                ),
            )
        )
        experiments.append(
            {
                "experiment_id": exp_id,
                "domain": "EFFICACY",
                "target": {"type": "oomycete", "original_name": short, "standard_name": species, "strain": None, "life_stage": "mycelial growth"},
                "assay": {"method": "radial growth inhibition", "endpoint": "IC50", "setting": "in vitro"},
                "conditions": [
                    {"factor": "incubation_time", "value_numeric": days, "unit": "day", "status": "REPORTED"},
                    {"factor": "tested_concentration_range", "lower": 1, "upper": 50, "unit": "μM", "status": "PDF_GLYPH_AUDITED"},
                    {"factor": "vehicle", "value_raw": "MeOH or acetone", "status": "REPORTED"},
                    {"factor": "vehicle_max", "value_numeric": 0.5, "unit": "%", "status": "REPORTED"},
                    {"factor": "negative_control", "value_raw": "PDA with 0.5% vehicle and PDA without organic solvent", "status": "REPORTED"},
                    {"factor": "statistics", "value_raw": "ANOVA and Tukey; P ≤ 0.05; data represented as mean ± SD", "status": "REPORTED"},
                ],
                "groups": groups,
                "source_table": "T4",
            }
        )

    t5 = by_id["T5"]
    headers = [header.rsplit("—", 1)[-1].strip() for header in t5["headers"][1:]]
    rows_by_label = {row[0].split()[0]: row for row in t5["rows"]}
    for col, cell_line in enumerate(headers, start=1):
        exp_id = f"safety-mtt-{slug(cell_line)}"
        groups = []
        for code in COMPOUNDS:
            source_row = rows_by_label[code]
            raw = source_row[col].replace("AE", "±")
            row_quote = next(line for line in t5["markdown"].splitlines() if line.startswith(f"| {code} |"))
            groups.append(
                group(
                    f"{exp_id}-compound-{code}",
                    "TEST",
                    f"compound-{code}",
                    observation(
                        f"{exp_id}-compound-{code}-ic50",
                        "tumor_cell_cytotoxicity_IC50",
                        raw,
                        "mm (TEI)",
                        "μM",
                        "PDF_GLYPH_AUDITED",
                        evidence(f"{DOCUMENT_ID}:table:T5", row_quote, 9, "PDF table header confirms μM"),
                    ),
                )
            )
        cis_row = rows_by_label["cisPt"]
        cis_quote = next(line for line in t5["markdown"].splitlines() if line.startswith("| cisPt"))
        groups.append(
            group(
                f"{exp_id}-cisplatin",
                "POSITIVE_CONTROL",
                "control-cisplatin",
                observation(
                    f"{exp_id}-cisplatin-ic50",
                    "tumor_cell_cytotoxicity_IC50",
                    cis_row[col].replace("AE", "±"),
                    "mm (TEI)",
                    "μM",
                    "PDF_GLYPH_AUDITED",
                    evidence(f"{DOCUMENT_ID}:table:T5", cis_quote, 9, "PDF table header confirms μM"),
                ),
            )
        )
        groups.append(
            group(
                f"{exp_id}-camptothecin",
                "POSITIVE_CONTROL",
                "control-camptothecin",
                observation(
                    f"{exp_id}-camptothecin-ic50",
                    "tumor_cell_cytotoxicity_IC50",
                    CPT_VALUES[cell_line],
                    "mm (TEI, merged row)",
                    "μM",
                    "PDF_GLYPH_AUDITED",
                    evidence(f"{DOCUMENT_ID}:table:T5", "CPT row", 9, "Values read from PDF because TEI merged the row"),
                ),
            )
        )
        extract_row = rows_by_label["Extract"]
        extract_quote = next(line for line in t5["markdown"].splitlines() if line.startswith("| Extract"))
        groups.append(
            group(
                f"{exp_id}-extract",
                "TEST",
                "extract-mycelium",
                observation(
                    f"{exp_id}-extract-growth-inhibition",
                    "tumor_cell_growth_inhibition_at_50_ug_ml",
                    extract_row[col],
                    "%",
                    "%",
                    "AS_REPORTED",
                    evidence(f"{DOCUMENT_ID}:table:T5", extract_quote, 9),
                ),
            )
        )
        experiments.append(
            {
                "experiment_id": exp_id,
                "domain": "SAFETY",
                "target": {"type": "human_tumor_cell_line", "original_name": cell_line, "standard_name": cell_line, "disease_context": CELL_LINES[cell_line]},
                "assay": {"method": "MTT", "endpoint": "IC50 and fixed-dose growth inhibition", "setting": "in vitro", "duration_days": 7},
                "conditions": [
                    {"factor": "assay_duration", "value_numeric": 7, "unit": "day", "status": "REPORTED"},
                    {"factor": "extract_test_concentration", "value_numeric": 50, "unit": "μg/mL", "status": "PDF_GLYPH_AUDITED"},
                    {"factor": "compound_summary", "value_raw": "mean ± SD where reported", "status": "REPORTED"},
                ],
                "groups": groups,
                "source_table": "T5",
            }
        )

    return {
        "schema_version": SCHEMA_VERSION,
        "extraction_path": "DIRECT_SOURCE",
        "document": document_record(source_pdf),
        "materials": material_catalog(include_safety=True),
        "experiments": experiments,
        "cross_experiment_findings": [
            {
                "finding_id": "sar-1",
                "type": "STRUCTURE_ACTIVITY_RELATIONSHIP",
                "claim": "Hydroxanthone compounds 1 and 2 were generally most active; xanthone compounds 5 and 6 were inactive at the highest tested concentration; benzophenone compounds 3 and 4 were intermediate.",
                "evidence": evidence(f"{DOCUMENT_ID}:6", "The most active polyketides were the compounds formed by a hydroxanthone subunit", 8),
            },
            {
                "finding_id": "gap-1",
                "type": "RESEARCH_GAP",
                "claim": "Life-cycle endpoints such as sporangial germination, zoospore release, germination, and movement remain untested.",
                "evidence": evidence(f"{DOCUMENT_ID}:7", "Further studies of heterodimeric polyketides at several points in the reproductive cycle", 10),
            },
        ],
        "evidence_assessment": {
            "maturity_label": "EARLY_IN_VITRO_EVIDENCE",
            "score": 7,
            "max_score": 16,
            "dimensions": [
                {"name": "multi-target breadth", "score": 2, "max": 2, "reason": "Six compounds across four oomycetes; extract across nine oomycetes."},
                {"name": "positive/negative controls", "score": 2, "max": 2, "reason": "Metalaxyl and vehicle/no-solvent controls reported."},
                {"name": "dose-response support", "score": 1, "max": 2, "reason": "IC50 and 1–50 μM range reported, but raw dose-response points are absent."},
                {"name": "replication/statistical detail", "score": 1, "max": 2, "reason": "ANOVA, Tukey, P threshold, and mean ± SD reported; sample size is missing."},
                {"name": "mechanism validation", "score": 0, "max": 2, "reason": "Only structure-activity interpretation; no molecular target or functional validation."},
                {"name": "plant/host validation", "score": 0, "max": 2, "reason": "No detached-organ, greenhouse, or whole-plant efficacy experiment."},
                {"name": "field evidence", "score": 0, "max": 2, "reason": "No field trial."},
                {"name": "safety/selectivity", "score": 1, "max": 2, "reason": "Human tumor-cell cytotoxicity is reported, but crop-host selectivity is not."},
            ],
            "missing_high_value_fields": ["strain/isolate IDs", "temperature", "biological replicate count", "raw dose-response points", "crop phytotoxicity", "in planta efficacy", "field efficacy", "molecular target validation"],
        },
    }


def legacy_bundle(source_pdf: Path, legacy: dict) -> dict:
    rows = legacy["extraction"]["rows"]
    grouped: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for row in rows:
        table_id = "T1" if "Table 1" in row["cells"][14] else "T4"
        grouped[(table_id, row["cells"][5])].append(row)

    experiments = []
    for (table_id, target), target_rows in grouped.items():
        species = clean_species(target)
        mode = "extract" if table_id == "T1" else "compounds"
        exp_id = f"legacy-efficacy-{mode}-{slug(species)}"
        groups = []
        for row in target_rows:
            cells = row["cells"]
            material_id = "extract-mycelium" if table_id == "T1" else f"compound-{cells[0]}"
            raw_unit, relation, number = parse_legacy_activity(cells[7])
            value_raw = (">" if relation == "GT" else "") + str(number).rstrip("0").rstrip(".")
            anchor = row["anchors"][0]
            groups.append(
                group(
                    f"{exp_id}-{material_id}",
                    "TEST",
                    material_id,
                    observation(
                        f"{exp_id}-{material_id}-ic50",
                        "radial_growth_IC50",
                        value_raw,
                        raw_unit,
                        None,
                        "UNRESOLVED",
                        evidence(anchor["chunkId"], anchor["exactQuote"]),
                        [row["recordId"]],
                    ),
                )
            )

        first = target_rows[0]
        control_unit, control_relation, control_number = parse_legacy_activity(first["cells"][8])
        control_raw = (">" if control_relation == "GT" else "") + str(control_number).rstrip("0").rstrip(".")
        anchor = first["anchors"][0]
        groups.append(
            group(
                f"{exp_id}-positive-control",
                "POSITIVE_CONTROL",
                "control-metalaxyl",
                observation(
                    f"{exp_id}-control-ic50",
                    "radial_growth_IC50",
                    control_raw,
                    control_unit,
                    None,
                    "UNRESOLVED",
                    evidence(anchor["chunkId"], anchor["exactQuote"]),
                    [row["recordId"] for row in target_rows],
                ),
            )
        )
        experiments.append(
            {
                "experiment_id": exp_id,
                "domain": "EFFICACY",
                "target": {"type": "oomycete", "original_name": target, "standard_name": species, "strain": None, "life_stage": None},
                "assay": {"method": first["cells"][6], "endpoint": "IC50", "setting": None},
                "conditions": [],
                "groups": groups,
                "source_table": table_id,
            }
        )

    return {
        "schema_version": SCHEMA_VERSION,
        "extraction_path": "LEGACY_TRANSFORM",
        "document": document_record(source_pdf),
        "materials": material_catalog(include_safety=False),
        "experiments": experiments,
        "cross_experiment_findings": [],
        "evidence_assessment": {
            "maturity_label": "NOT_ASSESSABLE_FROM_FLAT_ROWS",
            "score": None,
            "max_score": 16,
            "dimensions": [],
            "missing_high_value_fields": ["incubation time", "tested concentration range", "vehicle", "negative control", "statistics", "safety observations", "evidence maturity"],
        },
        "legacy_source": {
            "verified_rows": len(rows),
            "expected_rows": legacy["coverage"]["expectedCount"],
            "validation_statuses": dict((status, sum(r["validationStatus"] == status for r in rows)) for status in sorted({r["validationStatus"] for r in rows})),
        },
    }


def iter_observations(bundle: dict):
    for experiment in bundle["experiments"]:
        for item in experiment["groups"]:
            for obs in item["observations"]:
                yield experiment, item, obs


def anti_test_index(bundle: dict) -> dict[tuple[str, str], dict]:
    result = {}
    for experiment, item, obs in iter_observations(bundle):
        if experiment["domain"] != "EFFICACY" or item["role"] != "TEST":
            continue
        result[(item["material_id"], experiment["target"]["standard_name"])] = obs
    return result


def bundle_metrics(bundle: dict) -> dict:
    observations = list(iter_observations(bundle))
    return {
        "materials": len(bundle["materials"]),
        "experiments": len(bundle["experiments"]),
        "efficacy_experiments": sum(exp["domain"] == "EFFICACY" for exp in bundle["experiments"]),
        "safety_experiments": sum(exp["domain"] == "SAFETY" for exp in bundle["experiments"]),
        "observations": len(observations),
        "test_observations": sum(group_row["role"] == "TEST" for _, group_row, _ in observations),
        "control_observations": sum(group_row["role"] == "POSITIVE_CONTROL" for _, group_row, _ in observations),
        "reported_conditions": sum(len(exp["conditions"]) for exp in bundle["experiments"]),
        "evidence_links": sum(len(obs["evidence"]) for _, _, obs in observations),
    }


def compare_bundles(direct: dict, legacy: dict) -> dict:
    direct_index = anti_test_index(direct)
    legacy_index = anti_test_index(legacy)
    keys = sorted(legacy_index)
    details = []
    numeric_matches = 0
    relation_matches = 0
    corrected_units = 0
    for key in keys:
        old = legacy_index[key]
        new = direct_index.get(key)
        numeric_match = bool(new) and old["value_numeric"] == new["value_numeric"]
        relation_match = bool(new) and old["relation"] == new["relation"]
        if numeric_match:
            numeric_matches += 1
        if relation_match:
            relation_matches += 1
        if new and old["unit"]["raw"] != new["unit"]["normalized"]:
            corrected_units += 1
        details.append(
            {
                "material_id": key[0],
                "target": key[1],
                "legacy_value": old["value_raw"],
                "direct_value": new["value_raw"] if new else None,
                "numeric_match": numeric_match,
                "relation_match": relation_match,
                "legacy_unit": old["unit"]["raw"],
                "audited_unit": new["unit"]["normalized"] if new else None,
                "unit_correction_required": bool(new) and old["unit"]["raw"] != new["unit"]["normalized"],
            }
        )

    return {
        "document_id": DOCUMENT_ID,
        "comparison_basis": "The old Q1 output is converted without consulting the source. The direct path rebuilds units from source tables/passages and visually audits PDF glyphs.",
        "metrics": {"direct_source": bundle_metrics(direct), "legacy_transform": bundle_metrics(legacy)},
        "shared_efficacy_test_observations": len(keys),
        "numeric_value_matches": numeric_matches,
        "relation_matches": relation_matches,
        "numeric_match_rate": round(numeric_matches / len(keys), 4) if keys else None,
        "unit_corrections_required": corrected_units,
        "safety_observations_recovered": sum(exp["domain"] == "SAFETY" for exp, _, _ in iter_observations(direct)),
        "information_gain": [
            {"field": "experiment conditions", "legacy": "0 structured condition fields", "direct": "incubation time, 1–50 μM range, ≤0.5% vehicle, negative controls, statistics"},
            {"field": "unit provenance", "legacy": "TEI/OCR strings stored as mg/ml and mm", "direct": "PDF-audited μg/mL and μM plus original OCR string"},
            {"field": "control semantics", "legacy": "positive control repeated inside each compound row", "direct": "one explicit control group per experiment"},
            {"field": "safety", "legacy": "not extracted", "direct": "six 7-day MTT experiments with compound and positive-control observations"},
            {"field": "structure-activity", "legacy": "structure family only", "direct": "hydroxanthone / benzophenone / xanthone subunits linked to activity pattern"},
            {"field": "evidence maturity", "legacy": "not assessable", "direct": "7/16, early in-vitro evidence; explicit gaps"},
        ],
        "observation_comparison": details,
    }


def potency_rows(direct: dict) -> list[dict]:
    values: dict[str, list[float]] = defaultdict(list)
    censored: dict[str, int] = defaultdict(int)
    for experiment, item, obs in iter_observations(direct):
        if experiment["source_table"] != "T4" or item["role"] != "TEST":
            continue
        if obs["relation"] == "GT":
            censored[item["material_id"]] += 1
            values[item["material_id"]].append(obs["value_numeric"] or 50)
        elif obs["value_numeric"] is not None:
            values[item["material_id"]].append(obs["value_numeric"])
    names = {row["material_id"]: row["display_name"] for row in direct["materials"]}
    subunits = {row["material_id"]: row["structure_subunit"] for row in direct["materials"]}
    result = []
    for material_id, nums in values.items():
        result.append(
            {
                "material_id": material_id,
                "name": names[material_id],
                "subunit": subunits[material_id],
                "median_lower_bound": round(median(nums), 2),
                "censored_count": censored[material_id],
                "tested_targets": len(nums),
            }
        )
    return sorted(result, key=lambda row: row["median_lower_bound"])


def summary_markdown(direct: dict, legacy: dict, comparison: dict) -> str:
    direct_metrics = comparison["metrics"]["direct_source"]
    legacy_metrics = comparison["metrics"]["legacy_transform"]
    ranking = potency_rows(direct)
    ranking_text = "、".join(
        f"{row['name']}（中位 IC50 下界 {row['median_lower_bound']} μM）" for row in ranking[:4]
    )
    return f"""# Q1 实验单元对照结果

## 测试对象

- 文献：{TITLE}
- 选择理由：同一篇文献包含 6 个纯化合物 × 4 种卵菌、菌丝提取物 × 9 种卵菌，以及 6 条人肿瘤细胞系的安全性相关实验，适合检验多化合物、多靶标与多实验域的数据组织。
- 旧流程基线：33 条 Q1 记录，覆盖校验 33/33，全部锚点校验通过。

## 主要结果

1. **数值保真**：两条路径共享的 33 个抗卵菌测试观察中，数值和 `>` 截尾关系均为 {comparison['numeric_value_matches']}/33 一致。
2. **单位纠错**：旧流程的 33 条测试观察全部需要量纲修订。PDF 原页显示 Table 1 为 `μg/mL`，Table 4 为 `μM`；TEI/OCR 分别变成了 `mg/ml` 与 `mm`。
3. **结构增益**：旧流程转换后有 {legacy_metrics['experiments']} 个实验、{legacy_metrics['observations']} 个观察、{legacy_metrics['reported_conditions']} 个结构化条件；源文献直提后有 {direct_metrics['experiments']} 个实验、{direct_metrics['observations']} 个观察、{direct_metrics['reported_conditions']} 个条件字段。
4. **安全性补全**：直提路径恢复 {comparison['safety_observations_recovered']} 个 Table 5 观察；旧流程没有提取该实验域。这里的人肿瘤细胞毒性不能等同于作物安全性，只能作为早期非靶标风险信号。
5. **证据成熟度**：7/16，属于早期体外证据。优点是多物种、阳性/阴性对照和统计方法较完整；缺口是菌株、样本量、原始剂量-反应点、作物药害、离体器官/整株、田间与分子靶点验证。

## 可比效力与影响因素

- 同表内按 IC50 下界排序，前四位为：{ranking_text}。化合物 5、6 在四种卵菌上均为 `>50 μM`。
- 靶标物种是显著影响因素：只有在 *Phytophthora cinnamomi* 上，化合物 1、2 的 IC50 低于 Metalaxyl；其余物种上阳性对照明显更强。
- 结构亚单元与活性呈文内支持的关联：hydroxanthone（1、2）整体较强，benzophenone（3、4）居中，xanthone（5、6）在最高测试浓度下未达 IC50。
- 培养时间在不同物种间为 1–4 天，因此跨物种直接比较必须保留实验条件，不能只比较一列 IC50。

## 对数据结构的结论

旧结果转换为实验单元后可以保住已有数值，并把重复的阳性对照去重为实验组；但它无法凭空恢复被旧流程遗漏的条件、安全性和证据层级。推荐生产结构采用 `Document → Material → Experiment → Group → Observation → Evidence`，并把跨实验的 SAR 结论和证据成熟度作为独立派生层，而不是继续扩充一张平面表。
"""


def render_report(direct: dict, legacy: dict, comparison: dict) -> str:
    payload = {"direct": direct, "legacy": legacy, "comparison": comparison, "potency": potency_rows(direct)}
    data_json = json.dumps(payload, ensure_ascii=False).replace("</", "<\\/")
    escaped_title = html.escape(TITLE)
    return f"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Q1 实验单元对照</title>
<style>
:root{{--ink:#17211d;--muted:#62706a;--line:#d8dfdb;--paper:#f6f7f4;--white:#fff;--green:#176b4d;--teal:#147783;--amber:#b16a16;--red:#a44335;--soft:#e9efeb;--navy:#25394a}}
*{{box-sizing:border-box}} body{{margin:0;background:var(--paper);color:var(--ink);font-family:"Segoe UI","Microsoft YaHei",Arial,sans-serif;letter-spacing:0}}
header{{background:var(--white);border-bottom:1px solid var(--line)}} .head{{max-width:1440px;margin:auto;padding:28px 34px 22px}}
.eyebrow{{font:700 12px/1.2 Consolas,monospace;color:var(--green);text-transform:uppercase}} h1{{font-size:30px;line-height:1.22;margin:8px 0 10px;max-width:1000px}}
.paper-title{{font-family:Georgia,"Times New Roman",serif;font-size:16px;line-height:1.5;color:#43504a;max-width:1080px}}
.notice{{margin-top:16px;padding:10px 12px;border-left:4px solid var(--amber);background:#fff8e9;color:#5a431f;font-size:14px;max-width:1080px}}
nav{{border-top:1px solid var(--line);background:#fbfcfa;position:sticky;top:0;z-index:5}} .tabs{{max-width:1440px;margin:auto;padding:0 34px;display:flex;gap:2px;overflow:auto}}
.tab{{border:0;background:transparent;padding:13px 16px;color:var(--muted);font-weight:650;white-space:nowrap;cursor:pointer;border-bottom:3px solid transparent}} .tab.active{{color:var(--green);border-color:var(--green)}}
main{{max-width:1440px;margin:auto;padding:24px 34px 54px}} .panel{{display:none}} .panel.active{{display:block}}
.kpis{{display:grid;grid-template-columns:repeat(6,minmax(130px,1fr));border:1px solid var(--line);background:var(--white)}} .kpi{{padding:17px;border-right:1px solid var(--line)}} .kpi:last-child{{border-right:0}}
.kpi b{{display:block;font-size:25px;font-variant-numeric:tabular-nums}} .kpi span{{font-size:12px;color:var(--muted)}}
.section{{margin-top:26px}} h2{{font-size:20px;margin:0 0 12px}} h3{{font-size:15px;margin:0 0 9px}} .sub{{color:var(--muted);font-size:13px;margin:-5px 0 12px}}
.grid2{{display:grid;grid-template-columns:1.15fr .85fr;gap:18px}} .grid3{{display:grid;grid-template-columns:repeat(3,1fr);gap:14px}}
.card{{background:var(--white);border:1px solid var(--line);border-radius:6px;padding:17px;min-width:0}} .callout{{border-left:4px solid var(--green)}}
.delta{{display:grid;grid-template-columns:1fr auto 1fr;align-items:center;border-bottom:1px solid var(--line);padding:12px 0;gap:12px}} .delta:last-child{{border-bottom:0}}
.delta .old{{color:var(--muted)}} .arrow{{color:var(--green);font-weight:800}} .delta strong{{display:block;font-size:13px;margin-bottom:3px}}
.badge{{display:inline-flex;padding:3px 7px;border-radius:3px;font-size:11px;font-weight:700;background:var(--soft);color:var(--green)}} .badge.warn{{background:#fff0d6;color:#85500e}}
table{{width:100%;border-collapse:collapse;font-size:13px;background:var(--white)}} th,td{{padding:10px 11px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}} th{{background:#eef2ef;color:#3f4d46;font-size:12px;position:sticky;top:45px}} td.num{{text-align:right;font-variant-numeric:tabular-nums}}
.table-wrap{{overflow:auto;border:1px solid var(--line);border-radius:6px}} .heat{{min-width:820px}} .heat td:not(:first-child),.heat th:not(:first-child){{text-align:center}}
.cell{{display:block;padding:8px 6px;border-radius:3px;font-weight:700}} .censored{{border:1px dashed #9da8a2;color:#59645e;background:#f5f6f4}}
.legend{{display:flex;gap:14px;align-items:center;color:var(--muted);font-size:12px;margin-top:9px;flex-wrap:wrap}} .swatch{{width:18px;height:9px;display:inline-block;margin-right:5px}}
.maturity{{display:grid;gap:9px}} .meter-row{{display:grid;grid-template-columns:180px 1fr 44px;align-items:center;gap:10px;font-size:13px}} .track{{height:9px;background:#e7ebe8;border-radius:2px;overflow:hidden}} .fill{{height:100%;background:var(--green)}}
.tree{{font-family:Consolas,monospace;font-size:13px;line-height:1.7;background:#17211d;color:#dce8e1;padding:18px;border-radius:6px;overflow:auto}} .tree .accent{{color:#7ad6af}} .tree .dim{{color:#9caaa3}}
.flow{{display:grid;grid-template-columns:repeat(6,1fr);gap:0;align-items:stretch}} .node{{border:1px solid var(--line);padding:13px;background:#fff;min-height:86px}} .node b{{display:block;font-size:13px}} .node span{{font-size:11px;color:var(--muted)}}
.finding{{padding:13px 0;border-bottom:1px solid var(--line)}} .finding:last-child{{border-bottom:0}} .finding p{{margin:5px 0 0;color:#43504a;font-size:13px;line-height:1.55}}
.footer-note{{font-size:12px;color:var(--muted);margin-top:14px;line-height:1.5}}
@media(max-width:1000px){{.kpis{{grid-template-columns:repeat(3,1fr)}}.kpi:nth-child(3){{border-right:0}}.grid2,.grid3{{grid-template-columns:1fr}}.flow{{grid-template-columns:repeat(3,1fr)}}}}
@media(max-width:620px){{.head,main{{padding-left:16px;padding-right:16px}}.tabs{{padding:0 8px}}h1{{font-size:24px}}.kpis{{grid-template-columns:repeat(2,1fr)}}.kpi:nth-child(3){{border-right:1px solid var(--line)}}.kpi:nth-child(2n){{border-right:0}}.meter-row{{grid-template-columns:120px 1fr 38px}}.flow{{grid-template-columns:repeat(2,1fr)}}}}
</style>
</head>
<body>
<header><div class="head"><div class="eyebrow">Q1 / STRUCTURED EVIDENCE FIXTURE / 2026-09-15</div><h1>从平面条目到可比较的实验单元</h1><div class="paper-title">{escaped_title}</div><div class="notice"><strong>量纲审计：</strong>PDF 原页为 Table 1 = μg/mL、Table 4/5 = μM；旧 Q1/TEI 分别记录成 mg/ml、mm。数值相同不代表量纲可信。</div></div></header>
<nav><div class="tabs"><button class="tab active" data-tab="overview">总览</button><button class="tab" data-tab="potency">可比效力</button><button class="tab" data-tab="structure">数据结构</button><button class="tab" data-tab="audit">证据审计</button></div></nav>
<main>
<section class="panel active" id="overview">
  <div class="kpis" id="kpis"></div>
  <div class="section grid2">
    <div class="card"><h2>旧流程与源文献直提</h2><div id="deltas"></div></div>
    <div class="card callout"><h2>结论先行</h2><div class="finding"><span class="badge">保真</span><p>共享的 33 个抗卵菌测试值全部一致，说明旧结果可作为数值基线。</p></div><div class="finding"><span class="badge warn">纠错</span><p>33/33 个测试观察需要 PDF 字形级单位修订；Table 1 若按 mg/mL 使用会造成 1000 倍量纲误读。</p></div><div class="finding"><span class="badge">增益</span><p>实验条件、安全性、结构-活性关系和证据成熟度只能由源文献直提恢复，旧条目转换无法补回。</p></div></div>
  </div>
  <div class="section grid2"><div class="card"><h2>证据成熟度 7 / 16</h2><div class="sub">早期体外证据：可形成研究假设，尚不足以支持作物应用结论。</div><div class="maturity" id="maturity"></div></div><div class="card"><h2>研究分布</h2><div id="distribution"></div></div></div>
</section>
<section class="panel" id="potency">
  <div class="section"><h2>六个化合物 × 四种卵菌</h2><div class="sub">IC50（μM）；数值越低活性越强。`>50` 是右删失值，不能按 50 的精确测量处理。</div><div class="table-wrap"><table class="heat" id="heatmap"></table></div><div class="legend"><span><i class="swatch" style="background:#bfe6d4"></i>较强</span><span><i class="swatch" style="background:#f1d59d"></i>中等</span><span><i class="swatch" style="background:#efb1a5"></i>较弱</span><span><i class="swatch" style="background:#f5f6f4;border:1px dashed #9da8a2"></i>右删失</span></div></div>
  <div class="section grid2"><div class="card"><h2>跨物种效力排序</h2><div id="ranking"></div><div class="footer-note">含 `>50` 时以 50 作为下界计算，因此标记为“中位数下界”，不是精确中位数。</div></div><div class="card"><h2>影响因素</h2><div class="finding"><strong>结构亚单元</strong><p>hydroxanthone（1、2）整体较强；benzophenone（3、4）居中；xanthone（5、6）四个靶标均 >50 μM。</p></div><div class="finding"><strong>靶标物种</strong><p>只有 P. cinnamomi 上化合物 1、2 强于 Metalaxyl；物种差异改变相对排序。</p></div><div class="finding"><strong>培养时间</strong><p>不同物种读取终点为 1、3 或 4 天，跨物种比较必须携带该条件。</p></div></div></div>
  <div class="section"><h2>安全性信号，不等同于作物安全</h2><div class="sub">7 天 MTT，人肿瘤细胞 IC50（μM）。它说明广泛细胞毒性可能存在，但不能替代作物药害、选择性指数或环境毒理。</div><div class="table-wrap"><table id="safety"></table></div></div>
</section>
<section class="panel" id="structure">
  <div class="section"><h2>推荐组织骨架</h2><div class="flow"><div class="node"><b>Document</b><span>论文与版本</span></div><div class="node"><b>Material</b><span>化合物、提取物、对照</span></div><div class="node"><b>Experiment</b><span>靶标、方法、条件</span></div><div class="node"><b>Group</b><span>测试组与对照角色</span></div><div class="node"><b>Observation</b><span>终点、数值、截尾、单位</span></div><div class="node"><b>Evidence</b><span>表格行、原文、PDF 页</span></div></div></div>
  <div class="section grid2"><div><h2>旧 Q1 转换单元</h2><div class="tree" id="legacyTree"></div></div><div><h2>源文献直提单元</h2><div class="tree" id="directTree"></div></div></div>
  <div class="section card"><h2>为什么不做一张“万能大表”</h2><p>实验条件属于 Experiment，化合物来源属于 Material，对照角色属于 Group，IC50 属于 Observation，原文锚点属于 Evidence。拆层后，同一对照不再随每个化合物重复，安全性与效力可以共享材料实体但保持不同实验域，跨文献比较也能在明确条件下进行。</p></div>
</section>
<section class="panel" id="audit">
  <div class="section grid2"><div class="card"><h2>信息增益</h2><div id="gain"></div></div><div class="card"><h2>高价值缺口</h2><div id="gaps"></div></div></div>
  <div class="section"><h2>33 个共享观察的逐项核对</h2><div class="sub">数值/截尾关系与旧流程逐项比对；单位依据 PDF 原页重新审计。</div><div class="table-wrap"><table id="comparisonTable"></table></div></div>
</section>
</main>
<script type="application/json" id="payload">{data_json}</script>
<script>
const D=JSON.parse(document.getElementById('payload').textContent), C=D.comparison, direct=D.direct;
const material=Object.fromEntries(direct.materials.map(x=>[x.material_id,x]));
document.querySelectorAll('.tab').forEach(b=>b.onclick=()=>{{document.querySelectorAll('.tab,.panel').forEach(x=>x.classList.remove('active'));b.classList.add('active');document.getElementById(b.dataset.tab).classList.add('active')}});
const dm=C.metrics.direct_source,lm=C.metrics.legacy_transform;
document.getElementById('kpis').innerHTML=[['33 / 33','共享数值一致'],[C.unit_corrections_required,'单位需修订'],[dm.experiments,'源文献实验'],[dm.observations,'源文献观察'],[C.safety_observations_recovered,'安全性观察'],['7 / 16','证据成熟度']].map(x=>`<div class="kpi"><b>${{x[0]}}</b><span>${{x[1]}}</span></div>`).join('');
document.getElementById('deltas').innerHTML=C.information_gain.map(x=>`<div class="delta"><div class="old"><strong>${{x.field}}</strong>${{x.legacy}}</div><div class="arrow">→</div><div><strong>源文献直提</strong>${{x.direct}}</div></div>`).join('');
document.getElementById('maturity').innerHTML=direct.evidence_assessment.dimensions.map(x=>`<div class="meter-row"><span>${{x.name}}</span><div class="track" title="${{x.reason}}"><div class="fill" style="width:${{100*x.score/x.max}}%"></div></div><b>${{x.score}}/${{x.max}}</b></div>`).join('');
document.getElementById('distribution').innerHTML=`<table><tr><th>维度</th><th>覆盖</th></tr><tr><td>来源</td><td>1 个内生真菌来源 / 植物叶片宿主</td></tr><tr><td>材料</td><td>6 个纯化合物 + 1 个菌丝提取物</td></tr><tr><td>抗卵菌谱</td><td>纯化合物 4 种；提取物 9 种</td></tr><tr><td>实验域</td><td>抗卵菌效力 + 人肿瘤细胞毒性</td></tr><tr><td>证据阶段</td><td>体外；无整株与田间</td></tr></table>`;
const eff=direct.experiments.filter(x=>x.source_table==='T4'), targets=eff.map(x=>x.target.standard_name);
function color(obs){{if(obs.relation==='GT')return 'censored';const v=obs.value_numeric;return v<=15?'background:#bfe6d4':v<=30?'background:#f1d59d':'background:#efb1a5'}}
let heat='<thead><tr><th>化合物 / 亚单元</th>'+targets.map(x=>`<th>${{x.replace('Phytophthora ','P. ').replace('Pythium ','P. ')}}</th>`).join('')+'</tr></thead><tbody>';
for(let i=1;i<=6;i++){{const mid='compound-'+i;heat+=`<tr><td><strong>${{i}} · ${{material[mid].display_name}}</strong><br><small>${{material[mid].structure_subunit}}</small></td>`;for(const e of eff){{const o=e.groups.find(g=>g.material_id===mid).observations[0];heat+=`<td><span class="cell ${{color(o)}}">${{o.relation==='GT'?'>':''}}${{o.value_numeric}}</span></td>`}}heat+='</tr>'}}document.getElementById('heatmap').innerHTML=heat+'</tbody>';
document.getElementById('ranking').innerHTML='<table><tr><th>排名</th><th>化合物</th><th>中位 IC50 下界</th><th>删失</th></tr>'+D.potency.map((x,i)=>`<tr><td>${{i+1}}</td><td>${{x.name}}</td><td class="num">${{x.median_lower_bound}} μM</td><td class="num">${{x.censored_count}} / ${{x.tested_targets}}</td></tr>`).join('')+'</table>';
const safety=direct.experiments.filter(x=>x.domain==='SAFETY');let st='<thead><tr><th>化合物</th>'+safety.map(x=>`<th>${{x.target.standard_name}}</th>`).join('')+'</tr></thead><tbody>';for(let i=1;i<=6;i++){{const mid='compound-'+i;st+=`<tr><td>${{i}} · ${{material[mid].display_name}}</td>`;for(const e of safety){{const o=e.groups.find(g=>g.material_id===mid).observations[0];st+=`<td class="num">${{o.relation==='ND'?'ND':o.value_raw}}</td>`}}st+='</tr>'}}document.getElementById('safety').innerHTML=st+'</tbody>';
document.getElementById('legacyTree').innerHTML=`<span class="accent">LEGACY_TRANSFORM</span>\n├─ materials: ${{lm.materials}}\n├─ experiments: ${{lm.experiments}}\n│  ├─ efficacy: ${{lm.efficacy_experiments}}\n│  └─ safety: <span class="dim">0</span>\n├─ observations: ${{lm.observations}}\n├─ condition fields: <span class="dim">${{lm.reported_conditions}}</span>\n└─ maturity: <span class="dim">not assessable</span>`;
document.getElementById('directTree').innerHTML=`<span class="accent">DIRECT_SOURCE</span>\n├─ materials: ${{dm.materials}}\n├─ experiments: ${{dm.experiments}}\n│  ├─ efficacy: ${{dm.efficacy_experiments}}\n│  └─ safety: ${{dm.safety_experiments}}\n├─ observations: ${{dm.observations}}\n├─ condition fields: ${{dm.reported_conditions}}\n└─ maturity: <span class="accent">7 / 16</span>`;
document.getElementById('gain').innerHTML=C.information_gain.map(x=>`<div class="finding"><strong>${{x.field}}</strong><p>${{x.direct}}</p></div>`).join('');
document.getElementById('gaps').innerHTML=direct.evidence_assessment.missing_high_value_fields.map(x=>`<span class="badge warn" style="margin:4px">${{x}}</span>`).join('');
document.getElementById('comparisonTable').innerHTML='<thead><tr><th>材料</th><th>靶标</th><th>旧值</th><th>新值</th><th>旧单位</th><th>PDF 审计单位</th><th>核对</th></tr></thead><tbody>'+C.observation_comparison.map(x=>`<tr><td>${{material[x.material_id].display_name}}</td><td>${{x.target}}</td><td class="num">${{x.legacy_value}}</td><td class="num">${{x.direct_value}}</td><td>${{x.legacy_unit}}</td><td><strong>${{x.audited_unit}}</strong></td><td><span class="badge">数值一致</span> <span class="badge warn">单位修订</span></td></tr>`).join('')+'</tbody>';
</script>
</body></html>"""


def validate(bundle: dict) -> None:
    assert bundle["schema_version"] == SCHEMA_VERSION
    material_ids = {row["material_id"] for row in bundle["materials"]}
    experiment_ids = [row["experiment_id"] for row in bundle["experiments"]]
    assert len(experiment_ids) == len(set(experiment_ids))
    observation_ids = set()
    for experiment, item, obs in iter_observations(bundle):
        assert item["material_id"] in material_ids
        assert obs["observation_id"] not in observation_ids
        observation_ids.add(obs["observation_id"])
        assert obs["relation"] in {"EQ", "GT", "ND"}
        assert obs["evidence"]
        if obs["relation"] != "ND":
            assert obs["value_numeric"] is not None and math.isfinite(obs["value_numeric"])


def main() -> None:
    args = parse_args()
    root = args.repo_root.resolve()
    output_dir = (args.output_dir or root / "outputs" / "q1-structured-comparison-20260915").resolve()
    source_dir = root / "data" / "rag" / DOCUMENT_ID
    source_pdf = source_dir / "source.pdf"
    tables = read_jsonl(source_dir / "tables.jsonl")
    legacy = read_json(root / "outputs" / "q1-new-flow-test-20260724" / "q1-new-flow-optimized-result.json")

    direct = direct_source_bundle(source_pdf, tables)
    converted = legacy_bundle(source_pdf, legacy)
    validate(direct)
    validate(converted)
    comparison = compare_bundles(direct, converted)
    assert comparison["shared_efficacy_test_observations"] == 33
    assert comparison["numeric_value_matches"] == 33
    assert comparison["relation_matches"] == 33

    output_dir.mkdir(parents=True, exist_ok=True)
    write_json(output_dir / "direct-source-units.json", direct)
    write_json(output_dir / "legacy-transformed-units.json", converted)
    write_json(output_dir / "comparison.json", comparison)
    (output_dir / "summary.zh.md").write_text(summary_markdown(direct, converted, comparison), encoding="utf-8")
    (output_dir / "report.html").write_text(render_report(direct, converted, comparison), encoding="utf-8")
    print(json.dumps({"output_dir": str(output_dir), "comparison": comparison | {"observation_comparison": "omitted"}}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
