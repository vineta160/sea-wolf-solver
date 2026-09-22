# sea-wolf-solver

Educational, deterministic **Sea Wolf-style** command-line solver in both Python and Java.

This project is a reconstruction for practice and analysis only. It does **not** claim official proprietary rules.

## Honesty and scope
- No external scraping is used.
- Rules in this repository are based only on the provided conversation context and screenshots.
- Observed-vs-inferred details are documented in [`docs/RULES.md`](docs/RULES.md).

## Repository layout
- `data/sample_scenario.json` shared scenario data
- `python/sea_wolf_solver.py` Python engine + CLI
- `python/test_sea_wolf_solver.py` Python unit tests
- `java/SeaWolfSolver.java` Java engine + CLI + `--self-test`
- `docs/RULES.md` observed vs inferred reconstruction notes

## JSON scenario schema (controlled)
Top-level fields:
- `weights.attribute_weight`, `weights.trait_weight`
- `sites.<site_name>.ranges.<density|energy|size> = [min, max]` (inclusive)
- `sites.<site_name>.desired_trait`, `undesired_trait`
- `microbes[]`: `name`, `density`, `energy`, `size`, `traits[]`
- `prospect.initial_pool[]`
- `prospect.available_additional[]`
- `prospect.additional_pick_count` (observed flow uses 4)
- `prospect.treatment_pick_count` (observed flow uses 3)
- `prospect.target_site`

Sample includes:
- 10 illustrative microbes
- Drava Volvox exactly as observed: Density 6, Energy 3, Size 2, trait Hydrophilic
- Site 1 exactly as observed
- Site 2 only known Density 8-10

## Scoring and formulas
Numeric ranges are inclusive.

For each attribute (Density/Energy/Size):
- midpoint = `(min + max) / 2`
- closeness = `max(0, 1 - abs(value - midpoint) / ((max - min) / 2))`
- outside range => closeness `0` and violation

Treatment efficiency (0-100):
- `attribute_score` = average closeness across known attributes
- `trait_score` = average of:
  - desired trait present? `1` else `0`
  - undesired trait absent? `1` else `0`
- `efficiency = 100 * (attribute_weight * attribute_score + trait_weight * trait_score)`

With all ideal midpoints plus desired present and undesired absent, efficiency is 100%.

## Hard constraints vs soft signals
- Hard: undesired trait present is a hard violation for categorization validity.
- Hard: treatment must contain exactly 3 microbes.
- Soft: midpoint distance and desired-trait support rank options.
- Partial Site 2 information: only known constraints affect score; unknown constraints are reported.

## Python usage
From repo root:

```bash
python3 python/sea_wolf_solver.py summary
python3 python/sea_wolf_solver.py categorize
python3 python/sea_wolf_solver.py categorize --microbe "Drava Volvox"
python3 python/sea_wolf_solver.py prospects --top 5
python3 python/sea_wolf_solver.py optimize --site "Site 1" --pool "Lumen Spira,Helio Thread,Tide Prism,Mist Orb" --top 5
python3 -m unittest python/test_sea_wolf_solver.py
```

## Java usage
From repo root:

```bash
javac java/SeaWolfSolver.java
java -cp java SeaWolfSolver summary
java -cp java SeaWolfSolver categorize
java -cp java SeaWolfSolver prospects --top 5
java -cp java SeaWolfSolver optimize --site "Site 1"
java -cp java SeaWolfSolver --self-test
```

## Prospect Selection flow modeled
1. Start from `prospect.initial_pool`.
2. Search all combinations that add exactly 4 microbes from `available_additional`.
3. For each resulting pool, rank all 3-microbe treatment combinations.
4. Rank prospect-addition choices by best treatment score, then fewer violations, then stable name order.

## Limitations
- This is a deterministic educational reconstruction, not a verified official game implementation.
- Unknown hidden game constraints cannot be inferred with certainty and are surfaced as uncertainty.
