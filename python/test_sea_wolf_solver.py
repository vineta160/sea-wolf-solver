import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from sea_wolf_solver import (
    Microbe,
    Range,
    Scenario,
    SiteProfile,
    _closeness,
    evaluate_treatment,
    rank_additional_prospects,
    rank_treatments,
    recommend_category,
)


class SeaWolfSolverTests(unittest.TestCase):
    def setUp(self):
        self.site1 = SiteProfile(
            name="Site 1",
            ranges={"density": Range(6, 8), "energy": Range(2, 4), "size": Range(8, 10)},
            desired_trait="Light Sensitive",
            undesired_trait="Heat Resistant",
        )
        self.site2 = SiteProfile(name="Site 2", ranges={"density": Range(8, 10)})
        self.microbes = (
            Microbe("A", 7, 3, 9, ("Light Sensitive",), 0),
            Microbe("B", 8, 3, 10, ("Light Sensitive",), 1),
            Microbe("C", 6, 4, 8, ("Light Sensitive",), 2),
            Microbe("D", 9, 3, 7, ("Hydrophilic",), 3),
            Microbe("E", 7, 3, 9, ("Heat Resistant",), 4),
            Microbe("F", 8, 2, 9, ("Light Sensitive",), 5),
        )
        self.scenario = Scenario(
            microbes=self.microbes,
            sites={"Site 1": self.site1, "Site 2": self.site2},
            initial_pool=("A", "D"),
            available_additional=("B", "C", "E", "F"),
            additional_pick_count=4,
            treatment_pick_count=3,
            target_site="Site 1",
            attribute_weight=0.7,
            trait_weight=0.3,
        )

    def test_exact_range_fit_midpoint_is_one(self):
        score, outside = _closeness(7, Range(6, 8))
        self.assertEqual(score, 1.0)
        self.assertFalse(outside)

    def test_midpoint_scoring_penalizes_distance(self):
        center_score, _ = _closeness(7, Range(6, 8))
        edge_score, _ = _closeness(8, Range(6, 8))
        out_score, outside = _closeness(9, Range(6, 8))
        self.assertGreater(center_score, edge_score)
        self.assertEqual(out_score, 0.0)
        self.assertTrue(outside)

    def test_desired_and_undesired_trait_behavior(self):
        good = recommend_category(self.microbes[0], self.scenario)
        bad = recommend_category(self.microbes[4], self.scenario)
        self.assertEqual(good.category, "Site 1")
        self.assertEqual(bad.category, "Return")

    def test_partial_site2_information_reports_unknowns(self):
        result = recommend_category(self.microbes[3], self.scenario)
        self.assertIn("unknown_constraints=2", result.explanation)

    def test_return_recommendation_for_poor_fit(self):
        poor = Microbe("Poor", 1, 10, 1, ("Heat Resistant",), 6)
        scenario = Scenario(
            microbes=self.microbes + (poor,),
            sites={"Site 1": self.site1, "Site 2": self.site2},
            initial_pool=self.scenario.initial_pool,
            available_additional=self.scenario.available_additional,
            additional_pick_count=4,
            treatment_pick_count=3,
            target_site="Site 1",
        )
        result = recommend_category(poor, scenario)
        self.assertEqual(result.category, "Return")

    def test_three_microbe_treatment_optimization(self):
        ranked = rank_treatments(self.microbes, self.site1, 0.7, 0.3)
        self.assertGreater(len(ranked), 0)
        self.assertGreaterEqual(ranked[0].score, ranked[-1].score)

    def test_invalid_combination_length_raises(self):
        with self.assertRaises(ValueError):
            evaluate_treatment(self.microbes[:2], self.site1, 0.7, 0.3)

    def test_four_prospect_selection(self):
        ranked = rank_additional_prospects(self.scenario)
        self.assertEqual(len(ranked), 1)
        self.assertEqual(len(ranked[0]["additional_microbes"]), 4)

    def test_tie_breaking_is_stable(self):
        t1 = evaluate_treatment((self.microbes[0], self.microbes[1], self.microbes[2]), self.site1, 0.7, 0.3)
        t2 = evaluate_treatment((self.microbes[1], self.microbes[0], self.microbes[2]), self.site1, 0.7, 0.3)
        self.assertEqual(t1.score, t2.score)
        self.assertEqual(t1.microbes, t2.microbes)


if __name__ == "__main__":
    unittest.main()
