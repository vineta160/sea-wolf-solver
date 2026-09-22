from __future__ import annotations

import argparse
import itertools
import json
import os
from dataclasses import dataclass, field
from enum import Enum
from typing import Dict, List, Optional, Sequence, Tuple


ATTRIBUTE_NAMES = ("density", "energy", "size")
DEFAULT_SCENARIO_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "scenarios",
    "sample_scenario.json",
)


class Category(str, Enum):
    SITE_1 = "Site 1"
    SITE_2 = "Site 2"
    RETURN = "Return"


@dataclass(frozen=True)
class RangeRule:
    min: int
    max: int

    def contains(self, value: int) -> bool:
        return self.min <= value <= self.max

    def midpoint(self) -> float:
        return (self.min + self.max) / 2.0


@dataclass(frozen=True)
class Microbe:
    id: str
    name: str
    density: int
    energy: int
    size: int
    traits: Tuple[str, ...]

    def attribute(self, key: str) -> int:
        return getattr(self, key)


@dataclass(frozen=True)
class SiteProfile:
    name: str
    ranges: Dict[str, RangeRule] = field(default_factory=dict)
    desired_traits: Tuple[str, ...] = field(default_factory=tuple)
    undesired_traits: Tuple[str, ...] = field(default_factory=tuple)
    is_current: bool = False
    hard_reject_undesired: bool = True


@dataclass(frozen=True)
class SiteFit:
    site_name: str
    hard_rejected: bool
    score: float
    confidence: float
    reasons: Tuple[str, ...]


@dataclass(frozen=True)
class ClassificationResult:
    microbe_id: str
    recommended: Category
    score: float
    confidence: float
    reasons: Tuple[str, ...]
    candidates: Tuple[Tuple[Category, float], ...]


@dataclass(frozen=True)
class PlayerChoiceFeedback:
    microbe_id: str
    player_choice: Category
    optimal_choice: Category
    is_optimal: bool
    explanation: str


@dataclass(frozen=True)
class CombinationScore:
    microbe_ids: Tuple[str, ...]
    average_density: float
    average_energy: float
    average_size: float
    midpoint_distance: float
    efficiency_percent: float
    desired_trait_present: bool
    undesired_trait_absent: bool
    score: float
    reasons: Tuple[str, ...]


@dataclass(frozen=True)
class Scenario:
    name: str
    microbes: Tuple[Microbe, ...]
    site1: SiteProfile
    site2: SiteProfile
    combination_size: int = 3


class SeaWolfEngine:
    def __init__(self, scenario: Scenario):
        self.scenario = scenario

    @staticmethod
    def _range_fit_score(value: int, rule: RangeRule, scale: int = 9) -> float:
        if rule.contains(value):
            return 1.0
        if value < rule.min:
            distance = rule.min - value
        else:
            distance = value - rule.max
        return max(0.0, 1.0 - (distance / float(scale)))

    @staticmethod
    def _midpoint_score(value: int, rule: RangeRule, scale: int = 9) -> float:
        distance = abs(value - rule.midpoint())
        return max(0.0, 1.0 - (distance / float(scale)))

    def evaluate_site_fit(self, microbe: Microbe, site: SiteProfile) -> SiteFit:
        reasons: List[str] = []
        trait_set = set(microbe.traits)

        undesired_present = sorted(t for t in site.undesired_traits if t in trait_set)
        if undesired_present and site.hard_reject_undesired:
            return SiteFit(
                site_name=site.name,
                hard_rejected=True,
                score=0.0,
                confidence=1.0,
                reasons=(
                    f"Hard reject: contains undesired traits {', '.join(undesired_present)}",
                ),
            )

        range_scores: List[float] = []
        midpoint_scores: List[float] = []
        for attr in ATTRIBUTE_NAMES:
            rule = site.ranges.get(attr)
            if rule is None:
                continue
            value = microbe.attribute(attr)
            r_score = self._range_fit_score(value, rule)
            m_score = self._midpoint_score(value, rule)
            range_scores.append(r_score)
            midpoint_scores.append(m_score)
            reasons.append(
                f"{attr}={value}, target {rule.min}-{rule.max}, range fit={r_score:.2f}, midpoint fit={m_score:.2f}"
            )

        range_component = sum(range_scores) / len(range_scores) if range_scores else 0.5
        midpoint_component = sum(midpoint_scores) / len(midpoint_scores) if midpoint_scores else 0.5

        desired_hits = sorted(t for t in site.desired_traits if t in trait_set)
        desired_component = 0.5
        if site.desired_traits:
            desired_component = len(desired_hits) / float(len(site.desired_traits))
        if desired_hits:
            reasons.append(f"Desired traits matched: {', '.join(desired_hits)}")
        elif site.desired_traits:
            reasons.append("Desired trait not present")

        soft_undesired_penalty = 0.0
        if undesired_present:
            soft_undesired_penalty = 0.3
            reasons.append(
                f"Soft penalty: contains undesired traits {', '.join(undesired_present)}"
            )

        score = (
            0.55 * range_component
            + 0.25 * midpoint_component
            + 0.20 * desired_component
            - soft_undesired_penalty
        )

        known_signals = len(site.ranges)
        if site.desired_traits:
            known_signals += 1
        if site.undesired_traits:
            known_signals += 1
        confidence = min(1.0, known_signals / 5.0)

        return SiteFit(
            site_name=site.name,
            hard_rejected=False,
            score=max(0.0, score),
            confidence=confidence,
            reasons=tuple(reasons),
        )

    def classify_microbe(self, microbe: Microbe) -> ClassificationResult:
        site1_fit = self.evaluate_site_fit(microbe, self.scenario.site1)
        site2_fit = self.evaluate_site_fit(microbe, self.scenario.site2)

        candidates: List[Tuple[Category, float, float, Tuple[str, ...]]] = []
        if not site1_fit.hard_rejected:
            candidates.append((Category.SITE_1, site1_fit.score, site1_fit.confidence, site1_fit.reasons))
        if not site2_fit.hard_rejected:
            candidates.append((Category.SITE_2, site2_fit.score, site2_fit.confidence, site2_fit.reasons))

        if not candidates:
            return ClassificationResult(
                microbe_id=microbe.id,
                recommended=Category.RETURN,
                score=0.0,
                confidence=1.0,
                reasons=("Rejected by explicit hard constraints for known sites",),
                candidates=(),
            )

        candidates.sort(key=lambda c: (-c[1], c[0].value))
        top = candidates[0]

        reasons = list(top[3])
        if len(candidates) > 1:
            score_gap = top[1] - candidates[1][1]
            if score_gap < 0.10 or top[2] < 0.45:
                reasons.append(
                    "Low certainty: partial clues or close scores across categories"
                )

        if top[1] < 0.35:
            return ClassificationResult(
                microbe_id=microbe.id,
                recommended=Category.RETURN,
                score=top[1],
                confidence=top[2],
                reasons=tuple(reasons + ["Weak fit to known site profiles"]),
                candidates=tuple((c[0], round(c[1], 4)) for c in candidates),
            )

        return ClassificationResult(
            microbe_id=microbe.id,
            recommended=top[0],
            score=top[1],
            confidence=top[2],
            reasons=tuple(reasons),
            candidates=tuple((c[0], round(c[1], 4)) for c in candidates),
        )

    def recommend_all(self) -> List[ClassificationResult]:
        results = [self.classify_microbe(m) for m in self.scenario.microbes]
        return sorted(results, key=lambda r: (r.recommended.value, -r.score, r.microbe_id))

    def compare_player_choices(
        self, choices: Dict[str, Category]
    ) -> List[PlayerChoiceFeedback]:
        optimal = {r.microbe_id: r for r in self.recommend_all()}
        out: List[PlayerChoiceFeedback] = []
        for microbe in self.scenario.microbes:
            player_choice = choices.get(microbe.id, Category.RETURN)
            best = optimal[microbe.id]
            is_optimal = player_choice == best.recommended
            explanation = (
                "Choice matches highest-ranked category"
                if is_optimal
                else f"Recommended {best.recommended.value} (score {best.score:.2f})"
            )
            out.append(
                PlayerChoiceFeedback(
                    microbe_id=microbe.id,
                    player_choice=player_choice,
                    optimal_choice=best.recommended,
                    is_optimal=is_optimal,
                    explanation=explanation,
                )
            )
        return out

    def rank_combinations(
        self,
        microbe_pool: Optional[Sequence[Microbe]] = None,
        site: Optional[SiteProfile] = None,
        size: Optional[int] = None,
    ) -> List[CombinationScore]:
        pool = tuple(microbe_pool or self.scenario.microbes)
        target_site = site or self.scenario.site1
        combo_size = size or self.scenario.combination_size

        scores: List[CombinationScore] = []
        for combo in itertools.combinations(pool, combo_size):
            avg_density = sum(m.density for m in combo) / combo_size
            avg_energy = sum(m.energy for m in combo) / combo_size
            avg_size = sum(m.size for m in combo) / combo_size

            average_values = {
                "density": avg_density,
                "energy": avg_energy,
                "size": avg_size,
            }

            distances = []
            reasons = []
            for attr, avg_value in average_values.items():
                rule = target_site.ranges.get(attr)
                if rule is None:
                    continue
                midpoint = rule.midpoint()
                distance = abs(avg_value - midpoint)
                distances.append(distance)
                reasons.append(
                    f"Average {attr}={avg_value:.2f} vs midpoint {midpoint:.2f} (distance {distance:.2f})"
                )

            midpoint_distance = sum(distances) / len(distances) if distances else 0.0
            efficiency = max(0.0, 100.0 * (1.0 - (midpoint_distance / 9.0)))

            all_traits = {t for m in combo for t in m.traits}
            desired_present = (
                True
                if not target_site.desired_traits
                else any(t in all_traits for t in target_site.desired_traits)
            )
            undesired_absent = not any(
                t in all_traits for t in target_site.undesired_traits
            )

            combo_score = efficiency
            if desired_present:
                combo_score += 8.0
                reasons.append("Desired trait condition satisfied")
            else:
                combo_score -= 10.0
                reasons.append("Missing desired trait condition")

            if undesired_absent:
                combo_score += 5.0
                reasons.append("Undesired trait condition satisfied")
            else:
                combo_score -= 15.0
                reasons.append("Contains undesired trait")

            scores.append(
                CombinationScore(
                    microbe_ids=tuple(m.id for m in combo),
                    average_density=avg_density,
                    average_energy=avg_energy,
                    average_size=avg_size,
                    midpoint_distance=midpoint_distance,
                    efficiency_percent=efficiency,
                    desired_trait_present=desired_present,
                    undesired_trait_absent=undesired_absent,
                    score=combo_score,
                    reasons=tuple(reasons),
                )
            )

        return sorted(
            scores,
            key=lambda s: (
                -s.score,
                s.midpoint_distance,
                tuple(s.microbe_ids),
            ),
        )


def _load_range(payload: Dict[str, int]) -> RangeRule:
    return RangeRule(min=int(payload["min"]), max=int(payload["max"]))


def load_scenario(path: str) -> Scenario:
    with open(path, "r", encoding="utf-8") as fh:
        data = json.load(fh)

    sites = []
    for raw_site in data["sites"]:
        ranges = {
            key: _load_range(value)
            for key, value in raw_site.get("ranges", {}).items()
        }
        sites.append(
            SiteProfile(
                name=raw_site["name"],
                ranges=ranges,
                desired_traits=tuple(raw_site.get("desiredTraits", [])),
                undesired_traits=tuple(raw_site.get("undesiredTraits", [])),
                is_current=bool(raw_site.get("isCurrent", False)),
                hard_reject_undesired=bool(raw_site.get("hardRejectUndesired", True)),
            )
        )

    site1 = next((s for s in sites if s.name == "Site 1"), None) or sites[0]
    site2 = next((s for s in sites if s.name == "Site 2"), None) or sites[1]

    microbes = tuple(
        Microbe(
            id=m["id"],
            name=m["name"],
            density=int(m["density"]),
            energy=int(m["energy"]),
            size=int(m["size"]),
            traits=tuple(m.get("traits", [])),
        )
        for m in data["microbes"]
    )

    return Scenario(
        name=data.get("name", "unnamed"),
        microbes=microbes,
        site1=site1,
        site2=site2,
        combination_size=int(data.get("combinationSize", 3)),
    )


def _render_classifications(engine: SeaWolfEngine) -> str:
    lines = ["Classification recommendations:"]
    by_id = {m.id: m for m in engine.scenario.microbes}
    for result in engine.recommend_all():
        microbe = by_id[result.microbe_id]
        candidates = ", ".join(
            f"{c.value}:{s:.2f}" for c, s in result.candidates
        ) or "none"
        lines.append(
            f"- {microbe.name} ({microbe.id}) -> {result.recommended.value} "
            f"[score={result.score:.2f}, confidence={result.confidence:.2f}]"
        )
        lines.append(f"  candidates: {candidates}")
        if result.reasons:
            lines.append(f"  reason: {result.reasons[0]}")
    return "\n".join(lines)


def _render_combinations(engine: SeaWolfEngine, top_n: int = 5) -> str:
    lines = [f"Top {top_n} combinations for {engine.scenario.site1.name}:"]
    combos = engine.rank_combinations()[:top_n]
    for idx, combo in enumerate(combos, 1):
        lines.append(
            f"{idx}. {', '.join(combo.microbe_ids)} score={combo.score:.2f} "
            f"efficiency={combo.efficiency_percent:.2f}%"
        )
        if combo.reasons:
            lines.append(f"   {combo.reasons[0]}")
    return "\n".join(lines)


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Sea Wolf-style microbe categorization and combination solver"
    )
    parser.add_argument(
        "scenario",
        nargs="?",
        default=DEFAULT_SCENARIO_PATH,
        help="Path to scenario JSON (defaults to bundled sample)",
    )
    parser.add_argument(
        "--top",
        type=int,
        default=5,
        help="Top N combinations to print",
    )
    args = parser.parse_args(argv)

    scenario = load_scenario(args.scenario)
    engine = SeaWolfEngine(scenario)
    print(f"Scenario: {scenario.name}")
    print(_render_classifications(engine))
    print()
    print(_render_combinations(engine, top_n=args.top))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
