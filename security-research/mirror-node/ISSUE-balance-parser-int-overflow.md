# Balance line parsers truncate account numbers to `int`, dropping balance records above 2^31

**Repo:** hiero-mirror-node · **Component:** importer / balance file reader
**Type:** correctness / robustness (future-proofing) — *not* a security vulnerability
**Severity (self-assessed):** Low/medium correctness bug; no current mainnet impact

## Summary

`AccountBalanceLineParserV1` and `AccountBalanceLineParserV2`
(`importer/src/main/java/org/hiero/mirror/importer/reader/balance/line/`)
parse the realm and account fields of a balance-file line with
`Integer.parseInt`, then widen them to `long` when constructing the
`EntityId`:

```java
long shardNum   = Long.parseLong(parts.get(0));
int  realmNum   = Integer.parseInt(parts.get(1));   // <-- int
int  accountNum = Integer.parseInt(parts.get(2));   // <-- int
long balance    = Long.parseLong(parts.get(3));
...
EntityId.of(shardNum, realmNum, accountNum);         // EntityId.of(long, long, long)
```

Account numbers are `int64` in the Hedera protocol (`EntityId`'s own `num`
field is 38 bits), and `EntityId.of` already takes `long`. Any account number
greater than `Integer.MAX_VALUE` (2,147,483,647) makes `Integer.parseInt`
throw `NumberFormatException`, which is caught and rethrown as
`InvalidDatasetException`. The effect is that the balance line for such an
account cannot be parsed — the balance snapshot drops/errors on that account
rather than recording its balance.

**Realm is not actually affected despite being parsed the same way**: `EntityId`
independently bounds realm to 16 bits (`REALM_MASK = 65,535`), so a realm
value anywhere near `2^31` is already rejected by `EntityId.of` regardless of
whether it was parsed as `int` or `long`. I verified this directly — a test
asserting a realm of `2^31` parses successfully correctly *fails* (`EntityId`
throws), while the equivalent test for the account field passes cleanly. The
reachable defect is the account field only.

Account balances are authoritative ledger state, so silently failing to
import them once account numbers cross 2^31 is a correctness problem.

## Why this is filed as robustness, not security

To be clear about scope: this is **not** attacker-exploitable today.

- Balance files are produced by consensus nodes from signed state; they are
  not attacker-controlled input.
- Triggering requires account numbers above ~2.1 billion. Mainnet account
  numbers are currently ~5–6 orders of magnitude below that.
- Forcing the entity counter past 2^31 would require ~2.1 billion paid
  account creations, which is not realistic.

It is a latent, deterministic defect that will surface as account numbers
grow over the network's lifetime, and it is cheap to fix now.

## Suggested fix

Parse account as `long`, keeping the existing non-negative guard, to match
`EntityId`'s `long` domain:

```java
long accountNum = Long.parseLong(parts.get(2));
```

(apply to both V1 and V2; realm can be widened to `long` too for symmetry
with `EntityId.of`'s signature, though it doesn't change observable behavior
on its own since `EntityId` bounds it separately). The existing
`shardNum < 0 || realmNum < 0 || accountNum < 0 || balance < 0` check and the
`shardNum != 0` check continue to work unchanged.

## Suggested regression test

Add a parser test with an account number of `2_147_483_648` (2^31) and assert
the parsed `EntityId` has `num() == 2147483648L` rather than the line being
rejected. A property/fuzz variant over random `long` account numbers in
`[0, Long.MAX_VALUE]` guards against any further narrowing.

## Evidence (reproduced by test against current main)

A JUnit test run against `hiero-mirror-node` @ `8c354af` (Java 25) on GitHub
Actions confirms the defect by execution, not inspection:

- `v2_accountNumberAtIntMaxParses_butAboveIntMaxIsDropped` — PASS:
  `AccountBalanceLineParserV2.parse("0,0,2147483647,100")` succeeds
  (`EntityId.num == 2147483647`), while `parse("0,0,2147483648,100")` throws
  `InvalidDatasetException` — i.e. the balance line for account 2^31 is
  dropped.
- `v1_accountNumberAboveIntMaxIsDropped` — PASS: same behavior in
  `AccountBalanceLineParserV1`.

Suite: 2 tests, 0 failures. The test source is
`BalanceParserIntTruncationTest.java` (in this directory).

The fix itself was separately verified in CI against real `main`: a new
regression row asserting an account value of `2^31` parses correctly passes
in both V1 and V2 with no regressions in either parser's full existing test
suite, while the equivalent assertion for realm at `2^31` correctly fails
(`EntityId` rejects it) — confirming the fix's correct, narrower scope.
