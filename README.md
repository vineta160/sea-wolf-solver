# sea-wolf-solver

Educational, offline **Sea Wolf-style** helper engine with aligned Python and Java implementations.

> ⚠️ This project is a reconstruction from observed gameplay screenshots and feedback behavior. It is **not** an official or verified implementation of proprietary assessment rules.

## What this repo now supports

- Sequential-style microbe categorization into:
  - `Site 1` (current site)
  - `Site 2` (next site)
  - `Return`
- Partial/incomplete site clues (for example, only Site 2 density range known)
- Hard constraint handling for undesired traits (configurable per site)
- Soft ranking signals:
  - Attribute range fit
  - Distance to target midpoint
  - Desired trait matching
  - Next-site compatibility
- Combination optimization/ranking with explanations:
  - Averages for selected combinations
  - Midpoint distance and efficiency percentage
  - Desired/undesired trait checks

## Repository layout

- `/scenarios/sample_scenario.json` — shared scenario input for both languages (10 microbes, includes Drava Volvox)
- `/python/sea_wolf_solver.py` — Python engine + CLI
- `/java/SeaWolfSolver.java` — Java engine + CLI + deterministic self-test mode
- `/tests/test_sea_wolf_solver.py` — Python unit tests

## Reconstructed rule model (explicitly inferred)

Inferred from screenshots and earlier discussion:

1. Each microbe has numeric attributes on 1-10 scale: `density`, `energy`, `size`, plus traits.
2. Site profiles can define:
   - optional numeric ranges per attribute
   - desired traits
   - undesired traits
3. Undesired traits can be hard rejects (`hardRejectUndesired=true`) where appropriate.
4. Classification is recommendation-based and can be uncertain when clues are partial:
   - engine outputs recommended category
   - candidate categories with scores
   - confidence and reason text
5. Combination scoring uses average attributes and midpoint-distance efficiency plus trait conditions.

## Hard constraints vs soft ranking

- **Hard constraints**
  - If a site has `hardRejectUndesired=true` and microbe contains any undesired trait for that site, that site is rejected for that microbe.
- **Soft ranking signals**
  - range fit score
  - midpoint proximity score
  - desired trait match ratio
  - optional soft undesired penalty when not hard-rejected
  - relative Site 1 vs Site 2 suitability

## JSON scenario format

Shared format used by Python and Java:

```json
{
  "name": "sea-wolf-style-sample",
  "combinationSize": 3,
  "sites": [
    {
      "name": "Site 1",
      "isCurrent": true,
      "ranges": {
        "density": { "min": 6, "max": 8 },
        "energy": { "min": 2, "max": 4 },
        "size": { "min": 8, "max": 10 }
      },
      "desiredTraits": ["Light Sensitive"],
      "undesiredTraits": ["Heat Resistant"],
      "hardRejectUndesired": true
    },
    {
      "name": "Site 2",
      "isCurrent": false,
      "ranges": {
        "density": { "min": 8, "max": 10 }
      },
      "desiredTraits": [],
      "undesiredTraits": [],
      "hardRejectUndesired": false
    }
  ],
  "microbes": [
    {
      "id": "m1",
      "name": "Drava Volvox",
      "density": 6,
      "energy": 3,
      "size": 2,
      "traits": ["Hydrophilic"]
    }
  ]
}
```

Schema summary:

- `name`: string
- `combinationSize`: integer (default 3)
- `sites`: array of site objects
  - `name`: string (typically Site 1 / Site 2)
  - `isCurrent`: boolean
  - `ranges`: object with optional `density` / `energy` / `size`
    - each range has `min`, `max` integers
  - `desiredTraits`: string[]
  - `undesiredTraits`: string[]
  - `hardRejectUndesired`: boolean
- `microbes`: array of microbe objects
  - `id`, `name`: string
  - `density`, `energy`, `size`: integer
  - `traits`: string[]

## Classification and scoring formulas

For each site fit:

- `range_component` = average(range fit per known attribute)
- `midpoint_component` = average(midpoint proximity per known attribute)
- `desired_component` = matched desired traits / desired trait count (or neutral when none)
- optional soft undesired penalty when traits conflict but site is not hard-rejecting

Final per-site score:

- `site_score = 0.55*range_component + 0.25*midpoint_component + 0.20*desired_component - soft_penalty`

Classification:

- evaluate Site 1 and Site 2
- discard hard-rejected site candidates
- pick highest score unless too weak (then `Return`)
- expose candidate list and uncertainty reason when partial info or close scores

Combination ranking (configurable size):

- compute average density/energy/size
- compute distance to each known site midpoint
- efficiency:
  - `efficiency_percent = max(0, 100*(1 - avg_midpoint_distance/9))`
- adjust score for desired/undesired trait condition checks
- rank descending by final score, then by midpoint distance, then stable id order

## Run Python

From repo root:

```bash
python3 -m unittest discover -s tests -p "test_*.py"
python3 python/sea_wolf_solver.py
python3 python/sea_wolf_solver.py /home/runner/work/sea-wolf-solver/sea-wolf-solver/scenarios/sample_scenario.json --top 5
```

## Run Java

From repo root (standard library only):

```bash
javac java/SeaWolfSolver.java
java -cp java SeaWolfSolver --self-test
java -cp java SeaWolfSolver
java -cp java SeaWolfSolver /home/runner/work/sea-wolf-solver/sea-wolf-solver/scenarios/sample_scenario.json 5
```

## Notes on alignment

Python and Java share the same scenario input and use the same scoring structure, confidence handling, hard/soft constraint split, and deterministic tie-breaking intent.
