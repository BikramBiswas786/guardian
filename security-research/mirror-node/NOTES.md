# Mirror-node importer hunt — notes

Target: Medium tier ("incorrect or missing records exported to mirror nodes,
consensus-critical or financially significant"). Repo: hiero-mirror-node
@ 8c354af.

## Finding (robustness, NOT bounty-qualifying)

`AccountBalanceLineParserV1` and `V2`
(`importer/.../reader/balance/line/`) parse `realmNum` and `accountNum`
with `Integer.parseInt`, then widen to `long` for `EntityId.of(long,long,long)`.
Account/realm numbers are int64 in the protocol. An account number above
Integer.MAX_VALUE (2,147,483,647) makes `Integer.parseInt` throw
NumberFormatException -> InvalidDatasetException -> that balance line (and
the balance-file parse) fails. Result: balances for high-numbered accounts
would be dropped/error the snapshot — shape-matches the Medium "missing
financially-significant records" impact.

Why it is NOT bounty-qualifying (per the Immunefi Hedera program):
- Balance files are produced by consensus nodes from signed state; not
  attacker-controlled. "Reports with no attacker-induced action are out of
  scope."
- Triggering needs account numbers > 2.1e9. Mainnet is at ~1e7. Forcing the
  counter past 2^31 means ~2.1 billion paid account creations — "economically
  unviable," explicitly downgraded/excluded.
- Not active in mainnet conditions today -> downgraded.

Still worth filing as a correctness/robustness issue (use `long` + guard),
because it is a real future-correctness defect: deterministic balance-record
loss once account numbers cross 2^31. Suggested fix: `Long.parseLong` for
realm/account with the existing non-negative guard, matching EntityId's long
domain.

## Surfaces hunted this session and their verdicts

- consensus-node custom-fee assessors — value conservation, exemptions,
  rounding, depth/count limits: tested (~26M cases), CLEAN.
- consensus-node token/crypto handlers — authorization (preHandle/handle):
  audited + dynamic key-rotation probe, CLEAN (unauthorized caller blocked).
- mirror-node balance line parser — int-vs-long latent bug above; not
  attacker-reachable, not bounty.

No bounty-qualifying finding across the surfaces hunted. A payout realistically
requires a genuine attacker-reachable Critical/High (theft, fund freeze,
consensus/gossip, or a consensus-node record-export defect), none of which
turned up under testing.
