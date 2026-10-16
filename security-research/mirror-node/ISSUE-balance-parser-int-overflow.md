# Balance line parsers truncate realm/account numbers to `int`, dropping balance records above 2^31

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

Account and realm numbers are `int64` in the Hedera protocol, and
`EntityId.of` already takes `long`. Any account or realm number greater than
`Integer.MAX_VALUE` (2,147,483,647) makes `Integer.parseInt` throw
`NumberFormatException`, which is caught and rethrown as
`InvalidDatasetException`. The effect is that the balance line for such an
account cannot be parsed — the balance snapshot drops/errors on that account
rather than recording its balance.

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

Parse realm and account as `long`, keeping the existing non-negative guard,
to match `EntityId`'s `long` domain:

```java
long realmNum   = Long.parseLong(parts.get(1));
long accountNum = Long.parseLong(parts.get(2));
```

(apply to both V1 and V2). The existing
`shardNum < 0 || realmNum < 0 || accountNum < 0 || balance < 0` check and the
`shardNum != 0` check continue to work unchanged.

## Suggested regression test

Add a parser test with an account number of `2_147_483_648` (2^31) and assert
the parsed `EntityId` has `num() == 2147483648L` rather than the line being
rejected. A property/fuzz variant over random `long` account numbers in
`[0, Long.MAX_VALUE]` guards against any further narrowing.
