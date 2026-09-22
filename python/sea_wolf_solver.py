from __future__ import annotations

import argparse
import itertools
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

DEFAULT_SCENARIO = Path(__file__).resolve().parents[1] / "data" / "sample_scenario.json"


@dataclass(frozen=True)
class Range:
    min_value: float
    max_value: float

    @property
    def midpoint(self) -> float:
        return (self.min_value + self.max_value) / 2.0


@dataclass(frozen=True)
class Microbe:
    name: str
    density: float
    energy: float
    size: float
    traits: Tuple[str, ...]
    input_index: int


@dataclass(frozen=True)
class SiteProfile:
    name: str
    ranges: Dict[str, Range]
    desired_trait: Optional[str] = None
    undesired_trait: Optional[str] = None


@dataclass(frozen=True)
class CategorizationResult:
    category: str
    score: float
    violations: int
    unknown_constraints: int
    explanation: str


@dataclass(frozen=True)
class TreatmentResult:
    microbes: Tuple[str, ...]
    average_density: float
    average_energy: float
    average_size: float
    attribute_score: float
    trait_score: float
    score: float
    violations: int
    desired_present: bool
    undesired_present: bool


@dataclass(frozen=True)
class Scenario:
    microbes: Tuple[Microbe, ...]
    sites: Dict[str, SiteProfile]
    initial_pool: Tuple[str, ...]
    available_additional: Tuple[str, ...]
    additional_pick_count: int
    treatment_pick_count: int
    target_site: str
    attribute_weight: float = 0.7
    trait_weight: float = 0.3


def _closeness(value: float, value_range: Range) -> Tuple[float, bool]:
    span = value_range.max_value - value_range.min_value
    if span <= 0:
        inside = value == value_range.min_value
        return (1.0 if inside else 0.0), (not inside)
    half_span = span / 2.0
    distance = abs(value - value_range.midpoint)
    score = max(0.0, 1.0 - (distance / half_span))
    outside = value < value_range.min_value or value > value_range.max_value
    if outside:
        score = 0.0
    return score, outside


def load_scenario(path: Path) -> Scenario:
    payload = json.loads(path.read_text(encoding="utf-8"))

    microbes: List[Microbe] = []
    for i, row in enumerate(payload["microbes"]):
        microbes.append(
            Microbe(
                name=row["name"],
                density=float(row["density"]),
                energy=float(row["energy"]),
                size=float(row["size"]),
                traits=tuple(row.get("traits", [])),
                input_index=i,
            )
        )

    sites: Dict[str, SiteProfile] = {}
    for name, config in payload["sites"].items():
        ranges = {
            attr: Range(float(values[0]), float(values[1]))
            for attr, values in config.get("ranges", {}).items()
        }
        sites[name] = SiteProfile(
            name=name,
            ranges=ranges,
            desired_trait=config.get("desired_trait"),
            undesired_trait=config.get("undesired_trait"),
        )

    prospect = payload["prospect"]
    weights = payload.get("weights", {})
    return Scenario(
        microbes=tuple(microbes),
        sites=sites,
        initial_pool=tuple(prospect.get("initial_pool", [])),
        available_additional=tuple(prospect.get("available_additional", [])),
        additional_pick_count=int(prospect.get("additional_pick_count", 4)),
        treatment_pick_count=int(prospect.get("treatment_pick_count", 3)),
        target_site=prospect.get("target_site", "Site 1"),
        attribute_weight=float(weights.get("attribute_weight", 0.7)),
        trait_weight=float(weights.get("trait_weight", 0.3)),
    )


def _site_score(microbe: Microbe, site: SiteProfile) -> Tuple[float, int, int, str]:
    attribute_scores: List[float] = []
    range_violations = 0
    for attr in ("density", "energy", "size"):
        if attr not in site.ranges:
            continue
        score, outside = _closeness(getattr(microbe, attr), site.ranges[attr])
        attribute_scores.append(score)
        if outside:
            range_violations += 1

    unknown_constraints = 3 - len(site.ranges)
    attribute_component = sum(attribute_scores) / len(attribute_scores) if attribute_scores else 0.5

    desired_component = 0.0
    desired_missing = False
    if site.desired_trait:
        desired_component = 1.0 if site.desired_trait in microbe.traits else 0.0
        desired_missing = desired_component == 0.0

    undesired_present = bool(site.undesired_trait and site.undesired_trait in microbe.traits)
    undesired_component = 0.0 if undesired_present else 1.0

    score = 0.6 * attribute_component + 0.2 * desired_component + 0.2 * undesired_component
    violations = range_violations + int(desired_missing) + int(undesired_present)

    if undesired_present:
        score = 0.0

    explanation = (
        f"attr={attribute_component:.3f}, desired={desired_component:.3f}, "
        f"undesired_ok={undesired_component:.3f}, range_violations={range_violations}, "
        f"unknown_constraints={unknown_constraints}"
    )
    return score, violations, unknown_constraints, explanation


def recommend_category(microbe: Microbe, scenario: Scenario) -> CategorizationResult:
    site_results: List[Tuple[str, float, int, int, str]] = []
    for site_name in sorted(scenario.sites.keys()):
        score, violations, unknown_constraints, explanation = _site_score(
            microbe, scenario.sites[site_name]
        )
        site_results.append((site_name, score, violations, unknown_constraints, explanation))

    best_site, best_score, best_violations, unknown, explanation = sorted(
        site_results,
        key=lambda item: (-item[1], item[2], item[0]),
    )[0]

    if best_score < 0.45 or best_violations >= 2:
        return CategorizationResult(
            category="Return",
            score=best_score,
            violations=best_violations,
            unknown_constraints=unknown,
            explanation=f"Return recommended: insufficient fit. Best site {best_site}: {explanation}",
        )

    return CategorizationResult(
        category=best_site,
        score=best_score,
        violations=best_violations,
        unknown_constraints=unknown,
        explanation=f"Best fit {best_site}: {explanation}",
    )


def _microbe_lookup(scenario: Scenario) -> Dict[str, Microbe]:
    return {m.name: m for m in scenario.microbes}


def evaluate_treatment(
    microbes: Sequence[Microbe], site: SiteProfile, attribute_weight: float, trait_weight: float
) -> TreatmentResult:
    if len(microbes) != 3:
        raise ValueError("Treatment must contain exactly 3 microbes")

    avg_density = sum(m.density for m in microbes) / 3.0
    avg_energy = sum(m.energy for m in microbes) / 3.0
    avg_size = sum(m.size for m in microbes) / 3.0

    attr_scores = []
    violations = 0
    for attr, avg in (("density", avg_density), ("energy", avg_energy), ("size", avg_size)):
        if attr not in site.ranges:
            continue
        score, outside = _closeness(avg, site.ranges[attr])
        attr_scores.append(score)
        if outside:
            violations += 1
    attribute_score = sum(attr_scores) / len(attr_scores) if attr_scores else 0.5

    traits = {trait for m in microbes for trait in m.traits}
    desired_present = True
    undesired_present = False
    trait_components = []

    if site.desired_trait:
        desired_present = site.desired_trait in traits
        trait_components.append(1.0 if desired_present else 0.0)
        if not desired_present:
            violations += 1
    if site.undesired_trait:
        undesired_present = site.undesired_trait in traits
        trait_components.append(0.0 if undesired_present else 1.0)
        if undesired_present:
            violations += 1
    trait_score = sum(trait_components) / len(trait_components) if trait_components else 1.0

    weighted = (attribute_weight * attribute_score) + (trait_weight * trait_score)
    score = round(max(0.0, min(1.0, weighted)) * 100.0, 2)

    ordered_names = tuple(sorted((m.name for m in microbes), key=str.casefold))
    return TreatmentResult(
        microbes=ordered_names,
        average_density=avg_density,
        average_energy=avg_energy,
        average_size=avg_size,
        attribute_score=attribute_score,
        trait_score=trait_score,
        score=score,
        violations=violations,
        desired_present=desired_present,
        undesired_present=undesired_present,
    )


def rank_treatments(
    pool: Sequence[Microbe], site: SiteProfile, attribute_weight: float, trait_weight: float
) -> List[TreatmentResult]:
    if len(pool) < 3:
        return []
    ranked = [
        evaluate_treatment(combo, site, attribute_weight, trait_weight)
        for combo in itertools.combinations(pool, 3)
    ]
    ranked.sort(key=lambda r: (-r.score, r.violations, r.microbes))
    return ranked


def rank_additional_prospects(scenario: Scenario, site_name: Optional[str] = None) -> List[Dict[str, object]]:
    site = scenario.sites[site_name or scenario.target_site]
    lookup = _microbe_lookup(scenario)
    initial = [lookup[name] for name in scenario.initial_pool]
    available = [lookup[name] for name in scenario.available_additional]

    pick_count = scenario.additional_pick_count
    if pick_count > len(available):
        raise ValueError("Not enough available microbes to satisfy additional pick count")

    results: List[Dict[str, object]] = []
    for additions in itertools.combinations(available, pick_count):
        pool = initial + list(additions)
        ranked_treatments = rank_treatments(
            pool,
            site,
            scenario.attribute_weight,
            scenario.trait_weight,
        )
        if not ranked_treatments:
            continue
        best = ranked_treatments[0]
        results.append(
            {
                "additional_microbes": tuple(m.name for m in additions),
                "best_treatment": best,
                "top_treatments": ranked_treatments,
            }
        )

    results.sort(
        key=lambda row: (
            -row["best_treatment"].score,
            row["best_treatment"].violations,
            tuple(name.casefold() for name in row["additional_microbes"]),
        )
    )
    return results


def scenario_summary(scenario: Scenario) -> str:
    lines = [
        "Sea Wolf-style reconstruction summary",
        f"Microbes: {len(scenario.microbes)}",
        f"Sites: {', '.join(sorted(scenario.sites.keys()))}",
        f"Prospect picks: +{scenario.additional_pick_count}, then {scenario.treatment_pick_count} for treatment",
    ]
    return "\n".join(lines)


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Sea Wolf-style educational solver")
    parser.add_argument("--scenario", type=Path, default=DEFAULT_SCENARIO)

    subparsers = parser.add_subparsers(dest="command", required=False)
    subparsers.add_parser("summary")

    categorize = subparsers.add_parser("categorize")
    categorize.add_argument("--microbe", help="Microbe name; omit to score all microbes")

    prospects = subparsers.add_parser("prospects")
    prospects.add_argument("--site", default=None)
    prospects.add_argument("--top", type=int, default=5)

    optimize = subparsers.add_parser("optimize")
    optimize.add_argument("--site", default=None)
    optimize.add_argument(
        "--pool",
        help="Comma-separated microbe names. Defaults to initial prospect pool from scenario.",
    )
    optimize.add_argument("--top", type=int, default=5)

    return parser


def _print_categorization(scenario: Scenario, microbe_name: Optional[str]) -> None:
    microbes = scenario.microbes
    if microbe_name:
        microbes = tuple(m for m in microbes if m.name == microbe_name)
    for microbe in microbes:
        result = recommend_category(microbe, scenario)
        print(
            f"{microbe.name}: {result.category} | score={result.score:.3f} "
            f"| violations={result.violations} | unknown={result.unknown_constraints}\n"
            f"  {result.explanation}"
        )


def _print_prospects(scenario: Scenario, site_name: Optional[str], top_n: int) -> None:
    ranked = rank_additional_prospects(scenario, site_name)
    for row in ranked[:top_n]:
        best: TreatmentResult = row["best_treatment"]
        print(
            f"Add {row['additional_microbes']} -> best treatment {best.microbes} "
            f"score={best.score:.2f}% violations={best.violations}"
        )


def _print_optimize(scenario: Scenario, site_name: Optional[str], pool_csv: Optional[str], top_n: int) -> None:
    site = scenario.sites[site_name or scenario.target_site]
    lookup = _microbe_lookup(scenario)
    pool_names = list(scenario.initial_pool)
    if pool_csv:
        pool_names = [name.strip() for name in pool_csv.split(",") if name.strip()]
    pool = [lookup[name] for name in pool_names]

    ranked = rank_treatments(pool, site, scenario.attribute_weight, scenario.trait_weight)
    for result in ranked[:top_n]:
        print(
            f"{result.microbes}: score={result.score:.2f}% attr={result.attribute_score:.3f} "
            f"trait={result.trait_score:.3f} avg=({result.average_density:.2f},"
            f"{result.average_energy:.2f},{result.average_size:.2f})"
        )


def main(argv: Optional[Iterable[str]] = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(list(argv) if argv is not None else None)
    scenario = load_scenario(args.scenario)

    command = args.command or "summary"
    if command == "summary":
        print(scenario_summary(scenario))
    elif command == "categorize":
        _print_categorization(scenario, args.microbe)
    elif command == "prospects":
        _print_prospects(scenario, args.site, args.top)
    elif command == "optimize":
        _print_optimize(scenario, args.site, args.pool, args.top)
    else:
        parser.error(f"Unknown command: {command}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
