## Background

This is a new finding from auditing related code while rechecking two existing advisories (GHSA-q776-f4m7-g642, GHSA-jfxq-8944-q786) filed against `hiero-consensus-node`. I'm requesting bounty review for this finding directly, under the Hedera Immunefi program's "Incorrect or missing records exported to mirror nodes" Medium category.

## The bug, in full

`AccountBalanceLineParserV1` and `AccountBalanceLineParserV2` parse every account-balance line's `account` field with `Integer.parseInt`, then widen the result to `long` to construct an `EntityId`:

```java
long shardNum   = Long.parseLong(parts.get(0));
int  realmNum   = Integer.parseInt(parts.get(1));
int  accountNum = Integer.parseInt(parts.get(2));   // <- the actual defect
long balance    = Long.parseLong(parts.get(3));
...
EntityId.of(shardNum, realmNum, accountNum);          // EntityId.of(long, long, long)
```

Account numbers are part of Hedera's 38-bit entity-number domain (`EntityId`'s own `num` field), and `EntityId.of` already takes `long`. Any account number greater than `Integer.MAX_VALUE` (2,147,483,647) makes `Integer.parseInt` throw `NumberFormatException`, converted by the importer to `InvalidDatasetException` — so **the balance line for that account is dropped from the exported snapshot** instead of being imported, and depending on the caller, can fail the whole balance file.

Account balances are authoritative ledger state — explicitly the example impact named in the Hedera program's Medium clause ("…including, but not limited to account balances, token balances, or other authoritative ledger state"). This is a missing-record defect (the balance is absent), not a derived/informational one, so it isn't excluded by that clause's "non-authoritative, derived, informational, or statistical" carve-out. It's also network-wide and deterministic — every mirror importer drops the identical records — not a single-node condition.

## What I verified, and a scope correction I only caught by testing the fix, not just the bug

Realm is parsed through the identical `Integer.parseInt` pattern, and my first pass at this (including an earlier version of this PR's own test) assumed realm was affected the same way account is. **It isn't**, and I only found that out because the CI run for my first version of this fix failed on exactly that assumption: `EntityId` independently bounds realm to 16 bits (`REALM_MASK = 65,535`), so a realm value anywhere near `2^31` is already rejected by `EntityId.of` regardless of whether it was parsed as `int` or `long`. A test asserting realm `2^31` should parse successfully correctly fails against real code — `EntityId` throws on it either way. The reachable defect is the **account field only**. I'm disclosing this correction rather than quietly dropping it, because it's exactly the kind of overreach a careful reviewer catches immediately, and I'd rather be right about a narrower claim than wrong about a broader one.

Current reachability, disclosed per the program's "Functionality Not Active in Production" clause: this triggers only once an account number exceeds `2^31`. Mainnet account numbers are presently several orders of magnitude below that, and an account's number grows by sequential network activity, not by anything an attacker can cheaply force. I'm not claiming current exploitability — I'm asserting the impact *type* is Medium, with reachability as a severity-calibration matter the program's own clause says stays in scope and is encouraged to report: "Bug reports of this nature remain in scope and are encouraged… any downgrade under this clause reflects only the current exploitability of the impact in production and not the validity of the bug report itself."

## Second defect, found while verifying the first

`EntityId.of` rejects out-of-range IDs by throwing `InvalidEntityException`. That class extends `MirrorNodeException` directly. It is a sibling of `InvalidDatasetException`, not a subtype. `CsvBalanceFileReader` deliberately skips bad lines one at a time (`catch (InvalidDatasetException ex) { log.error(...); return null; }`), and the existing tests `readValidWhenFileHasBadTrailingLines` / `readValidWhenFileHasLinesWithDifferentShardNum` lock that contract in. But an `InvalidEntityException` from a single line never reaches that catch. It escapes `read()` and **aborts the whole balance file**.

This reproduces on unpatched `main`: a file with valid lines plus one line with realm `65536` fails entirely with `InvalidEntityException: Invalid entity ID: 0.65536.3` instead of skipping that line. The same applies to an out-of-range token ID inside a V2 line's token-balance field.

It also matters for the first fix. An earlier revision of this branch parsed account as `long` without addressing this. That made account values from 2^31 to 2^38−1 parse correctly, but it turned values ≥ 2^38 from a one-line skip into a whole-file abort. CI caught that regression before this PR, and the final revision fixes both.

Reachability is the same as for the first defect, stated plainly. Balance files come from consensus nodes and are signature-verified, so producing an out-of-range ID in one isn't something an outside attacker can do today. This is an error-handling correctness fix that keeps one malformed record from taking down an entire balance snapshot. It is not an exploit.

## Fix

Parse the account field as `long` in both `AccountBalanceLineParserV1` and `AccountBalanceLineParserV2`, matching `EntityId.of`'s own `long` domain. Realm is widened to `long` too for signature symmetry, though — per the correction above — it doesn't change any observable behavior on its own. The existing non-negative and `shardNum != 0` guards are untouched. Both parsers now also catch `InvalidEntityException` and convert it to `InvalidDatasetException`, so out-of-range account, realm, or token IDs are skipped one line at a time like every other malformed line.

Tests added: parser rows for realm `65536` and account `2^38` expecting `InvalidDatasetException`; a V2 test for an out-of-range token ID in the token-balance field; and a file-level `readValidWhenFileHasLinesWithOutOfRangeEntityId` in `CsvBalanceFileReaderTest`, which runs for both V1 and V2 readers, asserting the file still reads with the bad lines skipped.

CI comparison of the same tests across three code states: unpatched `main` has 28 failures (whole-file abort plus the 2^31 drop). The earlier revision of this branch also has 28 failures (whole-file abort for account ≥ 2^38). The final revision passes 95/95.

Regression rows added to each parser's existing parameterized test assert that an account number of `2^31` now parses correctly where it previously threw.

## Why I'm asking for bounty review here, directly

This is a new, test-verified finding, not a resubmission of a stalled advisory. I'm asking directly: please review this PR for Medium-tier bounty eligibility under "Incorrect or missing records exported to mirror nodes." The fix is complete, minimal, and verified against real `main`.

## Scope of the claim

To be precise: I'm claiming a Medium-category missing-authoritative-record defect, not reachable on mainnet today, disclosed under the program's own "not yet active in production" provision — not fund loss, not consensus impact, not an authorization issue.
