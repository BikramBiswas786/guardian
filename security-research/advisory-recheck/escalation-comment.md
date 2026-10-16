# Escalation / status-request comment

Post on the advisory thread (or PR #1 of each advisory fork). Factual and
firm; it leads with the completed fix, not just a ping — that is what makes
it hard to leave unanswered.

---

Following up on GHSA-q776-f4m7-g642 (opened 11 Sep) and GHSA-jfxq-8944-q786
(opened 12 Sep). Both have been open ~5 weeks with the fix PRs awaiting a
code-owner review (`hiero-consensus-node-smart-contract-codeowners` /
@tinker-michaelj). Coordinated-disclosure practice is a 90-day window; we are
a third of the way through it with no maintainer assessment recorded, so I
want to move these forward rather than let them lapse.

To remove the review friction, I re-verified both against current `main`
(`a00481b`) in CI (JDK 25) and am attaching completed, test-backed fixes:

**GHSA-q776** — still reproducible on `main`. Regression tests fail on
unpatched `main` and pass with the fix (48/48 in the update package). Two
things the original fix PR did not cover, now closed:
- the `address_0x16c` decoder overrides `decodeTokenUpdateKeys` and was
  left unguarded — the no-op/degenerate inputs still dispatch there;
- `keyType` is narrowed via `BigInteger.intValue()`, so a value wider than
  32 bits truncates past a naive mask — the guard now rejects it before
  narrowing.
I can push these to the advisory fork branch if a maintainer re-opens it
for a push, or open a fresh PR.

**GHSA-jfxq** — still reproducible. Note the test in the current fix PR is a
false pass (it stubs the record-builder status, so it passes even unpatched);
the corrected test asserts the status actually written and fails on `main`.
The real blocker is scope: this changes a user-visible response code, and
~23 `test-clients` suites still assert the old code `326`. Those need to be
migrated in the same PR. Happy to take that on if the direction
(standardize on `INVALID_SIGNATURE`) is confirmed.

Could a smart-contract code-owner confirm: (1) the severity assessment for
q776, and (2) whether the jfxq response-code change is wanted before I do the
full test-suite migration? If these are out of scope for an advisory, that is
also a fine answer — I would just like a decision recorded so they do not sit
indefinitely.

Full recheck detail, CI runs, and the completed patches are available on
request.
