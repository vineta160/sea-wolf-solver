import os
import unittest

from python.sea_wolf_solver import (
    Category,
    Microbe,
    RangeRule,
    Scenario,
    SeaWolfEngine,
    SiteProfile,
    load_scenario,
)


ROOT = "/home/runner/work/sea-wolf-solver/sea-wolf-solver"
SAMPLE = os.path.join(ROOT, "scenarios", "sample_scenario.json")


def build_engine():
    return SeaWolfEngine(load_scenario(SAMPLE))


class SeaWolfSolverTests(unittest.TestCase):
    def test_range_fit_inside_and_outside(self):
        score_inside = SeaWolfEngine._range_fit_score(7, RangeRule(6, 8))
        score_outside = SeaWolfEngine._range_fit_score(3, RangeRule(6, 8))
        self.assertEqual(score_inside, 1.0)
        self.assertLess(score_outside, 1.0)
        self.assertGreaterEqual(score_outside, 0.0)

    def test_hard_reject_undesired_trait(self):
        engine = build_engine()
        microbe = Microbe(
            id="x1",
            name="x",
            density=7,
            energy=3,
            size=9,
            traits=("Heat Resistant",),
        )
        fit = engine.evaluate_site_fit(microbe, engine.scenario.site1)
        self.assertTrue(fit.hard_rejected)
        self.assertEqual(fit.score, 0.0)

    def test_partial_site_information_lowers_confidence(self):
        engine = build_engine()
        microbe = engine.scenario.microbes[0]
        fit = engine.evaluate_site_fit(microbe, engine.scenario.site2)
        self.assertLess(fit.confidence, 0.5)

    def test_classification_recommendation_for_known_good_site1(self):
        engine = build_engine()
        candidate = next(m for m in engine.scenario.microbes if m.id == "m2")
        result = engine.classify_microbe(candidate)
        self.assertEqual(result.recommended, Category.SITE_1)

    def test_return_when_both_sites_reject(self):
        site1 = SiteProfile(
            name="Site 1",
            ranges={"density": RangeRule(6, 8)},
            desired_traits=(),
            undesired_traits=("Bad",),
            is_current=True,
            hard_reject_undesired=True,
        )
        site2 = SiteProfile(
            name="Site 2",
            ranges={"density": RangeRule(6, 8)},
            desired_traits=(),
            undesired_traits=("Bad",),
            is_current=False,
            hard_reject_undesired=True,
        )
        scenario = Scenario(
            name="reject",
            microbes=(
                Microbe(
                    id="m",
                    name="m",
                    density=7,
                    energy=7,
                    size=7,
                    traits=("Bad",),
                ),
            ),
            site1=site1,
            site2=site2,
        )
        engine = SeaWolfEngine(scenario)
        result = engine.classify_microbe(scenario.microbes[0])
        self.assertEqual(result.recommended, Category.RETURN)

    def test_combination_scoring_and_tie_break(self):
        site = SiteProfile(
            name="Site 1",
            ranges={"density": RangeRule(6, 8)},
            desired_traits=("Good",),
            undesired_traits=(),
            is_current=True,
            hard_reject_undesired=True,
        )
        microbes = (
            Microbe("a", "a", 7, 1, 1, ("Good",)),
            Microbe("b", "b", 7, 1, 1, ("Good",)),
            Microbe("c", "c", 7, 1, 1, ("Good",)),
            Microbe("d", "d", 7, 1, 1, ("Good",)),
        )
        scenario = Scenario(
            name="tie",
            microbes=microbes,
            site1=site,
            site2=SiteProfile(name="Site 2"),
            combination_size=3,
        )
        engine = SeaWolfEngine(scenario)
        ranked = engine.rank_combinations()
        self.assertGreaterEqual(len(ranked), 2)
        # Equal scoring combos should resolve deterministically by ids
        self.assertEqual(ranked[0].microbe_ids, ("a", "b", "c"))


if __name__ == "__main__":
    unittest.main()
