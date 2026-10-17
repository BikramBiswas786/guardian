# Immunefi submission — Incorrect/missing authoritative balance records from account-number integer truncation in the mirror-node balance importer

**Program:** Hedera (Immunefi)
**Asset:** hiero-mirror-node (importer)
**Asserted impact:** Medium — "Incorrect or missing records exported to mirror nodes" (authoritative ledger state: account balances)
**Reporter:** BikramBiswas786

---

## Summary

The mirror-node balance-file importer parses the **account** and **realm**
fields of every account-balance line with `Integer.parseInt(...)`, then widens
the result to `long`. Account and realm numbers are **int64** across the Hedera
protocol, and the downstream `EntityId.of(long, long, long)` already takes
`long`. Any account whose entity number exceeds `Integer.MAX_VALUE`
(2,147,483,647) therefore cannot be parsed: `Integer.parseInt` throws
`NumberFormatException`, which the importer converts to
`InvalidDatasetException`, and the balance record for that account is **dropped
from the exported balance snapshot** (and, depending on the caller, can fail the
whole balance file).

Account balances are authoritative ledger state and are the example impact
named in the program's Medium clause ("…including, but not limited to account
balances, token balances, or other authoritative ledger state"). The defect is
deterministic and affects every mirror importer identically, so the exported
balance dataset becomes silently incomplete for all consumers (exchanges,
wallets, explorers, reconciliation tooling) that rely on it as the authoritative
balance source.

## Affected code

`importer/src/main/java/org/hiero/mirror/importer/reader/balance/line/`

- `AccountBalanceLineParserV2.java`
- `AccountBalanceLineParserV1.java`

```java
long shardNum   = Long.parseLong(parts.get(0));
int  realmNum   = Integer.parseInt(parts.get(1));   // int64 field parsed as int
int  accountNum = Integer.parseInt(parts.get(2));   // int64 field parsed as int
long balance    = Long.parseLong(parts.get(3));
...
EntityId.of(shardNum, realmNum, accountNum);          // EntityId.of(long, long, long)
```

(The protobuf balance reader and the token-balance sub-parser use
`EntityId.of(long)` / `getBalance()` and are not affected — the defect is
specific to the CSV V1/V2 line parsers' realm/account fields.)

## Impact classification

- **Record type:** account balances — explicitly enumerated authoritative
  ledger state in the Medium clause.
- **Nature:** missing records (the account's balance is absent from the
  snapshot), not merely derived/informational — so it is **not** excluded by
  the clause's "non-authoritative, derived, informational, or statistical"
  carve-out.
- **Scope of effect:** network-wide and deterministic (every importer drops the
  same records), not a single-node condition.

## Current exploitability — disclosed in full

I am disclosing reachability plainly, per the program's "Functionality Not
Active in Production" clause.

The bug triggers only once an account (or realm) entity number exceeds
`2^31 = 2,147,483,648`. Hedera mainnet entity numbers are presently several
orders of magnitude below that, so the defect is **not reachable in production
today**, and an account's number is assigned by sequential network growth, not
by an attacker cheaply. I am therefore **not** claiming current exploitability.

I am submitting under the program's explicit provision:

> "Where a bug report demonstrates a valid impact that cannot presently be
> reached in production … the severity may be downgraded by at least one
> severity level … Bug reports of this nature **remain in scope and are
> encouraged**, as identifying issues in functionality that is not yet active in
> production is valuable … Any downgrade under this clause reflects only the
> current exploitability of the impact in production **and not the validity of
> the bug report itself**."

The impact *type* is Medium (missing authoritative balance records); the current
reachability is a severity-calibration matter for the Hedera team's discretion,
which I leave to them. I am asserting the valid Medium **impact mapping**, not a
guaranteed payout tier.

## Proof (reproduced by test against current `main`)

Ran against `hiero-mirror-node` @ `8c354af83d73a848867ee23ba84cab3902b6daff`
(Java 25). The test drives the real `AccountBalanceLineParserV1/V2`:

- `v2_accountNumberAtIntMaxParses_butAboveIntMaxIsDropped` — **PASS**:
  `parse("0,0,2147483647,100")` succeeds with `EntityId.num == 2147483647`;
  `parse("0,0,2147483648,100")` throws `InvalidDatasetException` — the balance
  line for account 2^31 is dropped.
- `v1_accountNumberAboveIntMaxIsDropped` — **PASS**: identical behavior in V1.

Suite: 2 tests, 0 failures. Test source `BalanceParserIntTruncationTest.java`
is attached.

## Suggested fix

Parse realm and account as `long`, preserving the existing non-negative guard:

```java
long realmNum   = Long.parseLong(parts.get(1));
long accountNum = Long.parseLong(parts.get(2));
```

(apply to both V1 and V2). The existing non-negative and `shardNum != 0` checks
continue to work unchanged. A GitHub PR implementing this fix plus the
regression test accompanies this report.

## Why this is worth fixing now

The defect is cheap to fix and is a deterministic, forward-dated correctness
failure in authoritative balance export. Fixing it before account numbers
approach `2^31` avoids a future silent balance-data loss that would be far more
disruptive to discover in production than to patch now.
