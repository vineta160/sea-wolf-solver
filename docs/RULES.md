# Sea Wolf-style reconstruction notes

This repository reconstructs an educational **Sea Wolf-style** engine from conversation-provided gameplay details.

## Directly observed from provided gameplay context
- 10 microbes are reviewed one-by-one in a categorization phase.
- Each microbe has Density, Energy, Size values in the range 1-10 and one or more traits.
- A microbe is assigned to exactly one category: Site 1, Site 2, or Return.
- Site 1 example profile: Density 6-8, Energy 2-4, Size 8-10, desired trait Light Sensitive, undesired trait Heat Resistant.
- Site 2 can start with partial information (example: Density 8-10 only).
- Prospect Selection asks the player to add 4 microbes to the Prospect Pool, then pick 3 microbes to average into a treatment.
- Treatment feedback reports average Density/Energy/Size and efficiency percentages (examples seen: 60% and 100%).
- Desired trait presence and undesired trait absence are required for fully valid treatments in observed examples.

## Inferred design decisions used here
- Attribute fit uses normalized midpoint closeness in [0, 1].
- Values outside known ranges are treated as violations and contribute 0 for that attribute.
- Undesired trait is treated as a hard violation for site recommendation validity.
- Unknown Site 2 constraints are not assumed satisfied; they are explicitly reported as uncertainty.
- Treatment efficiency combines averaged attribute score and trait validity score using configurable weights from JSON.
