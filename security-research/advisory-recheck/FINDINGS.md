# Advisory recheck — status against current `hiero-consensus-node` main

All results below were produced by building the real repository at
`main` commit `a00481bcf7739f527df6375faf15fb1b3969bd83` (2026-10-16) on
GitHub Actions (JDK 25), not from local inspection alone. Workflow and
harness sources: `security-research/advisory-recheck/`,
`security-research/fee-exemption-harness/`.

## GHSA-q776-f4m7-g642 — `updateTokenKeys` reports SUCCESS without effect

**Still present on current main.** Added regression tests fail on
unpatched main and pass with the fix:

| Job | Result |
|-----|--------|
| `q776-baseline` (tests only, unpatched main) | 11/14 fail — the no-op/degenerate cases are dispatched, not rejected, at **both** `0x167` and `0x16c` |
| `q776-fix` (fix + tests) | 48/48 pass (includes existing update-package tests) |

**Two gaps in the current advisory-fork PR (#1) that this recheck closes:**

1. **`0x16c` is unguarded.** `address_0x16c/UpdateDecoder` *overrides*
   `decodeTokenUpdateKeys`, so a guard added only to the common/`0x167`
   decoder never runs there. The bug is fully open at `0x16c`
   (`updateTokenKeysV2` / metadata-capable path). The completed fix adds
   the guard to both, with mask `0x7F` at `0x167` and `0xFF` at `0x16c`
   (metadata key `0x80` is valid there).
2. **Wide `keyType` is truncated before the mask check.** `keyType` is
   narrowed with `BigInteger.intValue()`. A value like `2^32 + 1` narrows
   to `1` (admin) and would pass a naive `& 0x7F` mask. The completed fix
   rejects any `keyType` with `signum() <= 0` or `bitLength() > 31`
   *before* narrowing.

**Advisory-text corrections (code-verified against main):**

- Scenario F ("duplicate keyType → first entry wins"): in current main,
  `addKeys` calls `builder.<role>Key(key)` once per entry, so the **last**
  entry would win, not the first. The winner is build-dependent; the fix
  rejects duplicates outright, mooting it. State it as "silently resolved,
  order unspecified," not "first wins."
- `0x80` is **not** an undefined bit. It is the metadata-key bit:
  ignored at `0x167`, applied at `0x16c`. Grouping it with `0x100`–`0x8000`
  as "undefined" is inaccurate.
- CWE: the form lists CWE-862 (Missing Authorization). On current main
  `TokenUpdateHandler.addRequiredSigners` *does* enforce admin/role-key
  signing for any key present in the update, and returns `TOKEN_IS_IMMUTABLE`
  for keyless tokens. SUCCESS-without-effect occurs specifically when the
  dispatched body carries **no key fields** (empty/zero/undefined/`0x80`).
  CWE-20 (Improper Input Validation) + CWE-754 is the accurate class.
- Scenarios A and B (claim: a *real* key update by an unauthorized caller,
  or against an immutable token, returns rc=22) should be **re-verified on
  current main with the decoded calldata shown.** By the current handler
  logic they should return `INVALID_SIGNATURE (7)` / `TOKEN_IS_IMMUTABLE`,
  not 22. If the 2026-09 testnet build differed, note the build; otherwise
  the degenerate-input cases (C–G) are the solid, reproducible core.

## GHSA-jfxq-8944-q786 — 326 vs 7 inconsistency

**Still present on current main.**

| Job | Result |
|-----|--------|
| `jfxq-baseline` (test only, unpatched main) | fails — the dispatch convenience constructors remap `INVALID_SIGNATURE (7)` → `INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE (326)` |
| `jfxq-fix` (fix + test) | passes; full `app-service-contract-impl` suite = 2172 tests, 0 failures |

**Correction to the advisory-fork PR:** its `InvalidSignatureConsistencyTest`
is a **false pass** — it stubs `recordBuilder.status()` to return
`INVALID_SIGNATURE`, so the encoded output is 7 regardless of whether the
remap ran, and the test passes even on unpatched main. The corrected test
verifies the *write*: `verify(recordBuilder).status(INVALID_SIGNATURE)` and
`never().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)`. It fails on
main, passes with the fix.

**Merge blocker to flag honestly:** the fix changes a user-visible response
code across many functions. ~23 end-to-end suites (~145 assertions) in
`test-clients` still assert 326 for these paths; the current PR updates only
2. The remaining suites (Mint/Burn/Pause/Delete families, ContractKeysHTS,
the AtomicBatch variants) must be migrated to `INVALID_SIGNATURE` in the same
PR, or the change breaks CI. This is almost certainly why it has not merged.

## Correctness surface that is NOT broken (negative results)

Heavy randomized property testing against the same `main` commit found no
value-conservation or exemption defect in the custom-fee assessors:

- Value conservation (fixed, fractional incl. net-of-transfers, royalty,
  large amounts to `Long.MAX/4`): ~24M cases across 3 seeds, 0 breaks,
  0 unexpected exceptions.
- Fee exemptions (treasury / collector / `allCollectorsAreExempt` /
  royalty fallback): 2M cases across 2 seeds — every policy-exempt payer
  netted exactly 0 (never debited), every chargeable payer was debited,
  conservation held. 0 breaks.

These are reported as negatives on purpose: they bound the issue to the two
response-code/validation findings above and show the assessment math itself
is sound.
