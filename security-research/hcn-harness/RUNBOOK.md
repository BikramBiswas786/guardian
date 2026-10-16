# Custom-fee conservation harness — runbook

## What this is
A JUnit property test (`CustomFeeConservationPropertyTest.java`) that drives the REAL custom-fee assessors in
hiero-consensus-node and asserts value conservation: a fee moves value, never creates or destroys it. Covers
`CustomFractionalFeeAssessor`, `CustomFixedFeeAssessor`, and `CustomRoyaltyFeeAssessor` (exchanged-value path).

Written against main @ 75bc55e (2026-10-16). NOT run by its author — the audit sandbox had no Maven/Gradle
artifact access. Must be run where the repo builds.

## Run it
1. Copy `CustomFeeConservationPropertyTest.java` into:
   hedera-node/hedera-token-service-impl/src/test/java/com/hedera/node/app/service/token/impl/test/handlers/transfer/customfees/
2. From the repo root:
   ./gradlew :app-service-token-impl:test --tests "*CustomFeeConservationPropertyTest"
3. Heavier sweep (more iterations, pick a seed):
   ./gradlew :app-service-token-impl:test --tests "*CustomFeeConservationPropertyTest" \
     -Dconservation.iterations=2000000 -Dconservation.seed=0x1234

## Reading the result
- GREEN: conservation held across all assessed cases for that assessor. Prints e.g.
  "[fractional] conservation held across N assessed cases".
- RED: a conservation break. The failure message prints the assessor, the token, the non-zero net, and the
  exact seed + inputs to reproduce. THIS IS A LEAD, NOT A CONFIRMED FINDING. Before reporting anything:
  1. Re-run with that single seed to confirm determinism.
  2. Hand-trace the assessor on those inputs to confirm units are truly minted/burned (not a harness bug).
  3. Only then write it up with the minimal reproducing transaction.

## If it's green everywhere
That's the likely outcome — this is audited code. Next targets (extend the same pattern):
- Multi-level assessment: drive `CustomFeeAssessmentStep.assessCustomFees` end-to-end (fees that trigger fees),
  checking conservation across ALL produced levels. Needs more store/config scaffolding (see
  CustomFeeAssessmentStepTest for the setup to copy).
- Royalty FALLBACK path (receiver pays a fixed fee when there's no exchanged value) — uses the real fixed
  assessor; assert the receiver debit equals the collector credit.
- CryptoTransfer adjust steps (AdjustHbarChangesStep / AdjustFungibleTokenChangesStep) for conservation after
  auto-association and hollow-account completion.

## Honest expectation
This finds a bug only if one exists; the top fee surfaces are overflow-safe and recently hardened, and an active
internal security team patches this area. The value is in running it against the NEWEST code each release, where
unaudited logic lands.

---

# Multi-level step — `MultiLevelConservation_ADDITIONS.java`

This is NOT a standalone file. It is a set of @Test methods + two helpers to PASTE INTO the existing
`CustomFeeAssessmentStepTest` (it reuses that test's StepsBase scaffolding, which already compiles and runs).

## Install
1. Open hedera-node/hedera-token-service-impl/src/test/java/.../handlers/transfer/CustomFeeAssessmentStepTest.java
2. Paste the two @Test methods and the `assertConservedAcrossLevels` + `HBAR_KEY` helpers into the class body.
3. Add the imports listed at the top of the additions file if they aren't already present.
4. Run: ./gradlew :app-service-token-impl:test --tests "*CustomFeeAssessmentStepTest"

## What it checks
`assessCustomFees()` returns the given op + one op per triggered fee level. Each assessed fee is a debit+credit
pair, so summing EVERY account amount across ALL returned ops, per asset (hbar + each token id), must net to the
input's net (0 for the balanced base transfer). A non-zero net = a dropped/duplicated leg while folding levels =
value created or destroyed across the multi-level pipeline.

## Reading the result
- GREEN: value is conserved across all levels for those fee combinations.
- RED: the failure message prints the offending asset, its net, and the full per-asset map. Reproduce, then
  hand-trace the level folding in CustomFeeAssessmentStep before treating it as a finding.

## Extend
Vary the seeded fee combinations (copy the fixture patterns from the sibling tests in the same file: fractional,
fixed self/other-denominated, royalty with/without fallback, multiple collectors) and re-assert conservation.
Each new combination is one more line of attack surface exercised.
