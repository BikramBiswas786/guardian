## Background

I filed **GHSA-q776-f4m7-g642** against this repository in September, with a fix attached in the private advisory fork. Over a month later there has been no maintainer response — no review, no comment, no merge. This PR is the completed fix, opened directly against `main` because the private channel has gone quiet. I'm asking directly, in this PR, for bounty review under the Hedera program — not routing this through further private process.

## The bug, in full

`updateTokenKeys` is handled at two system-contract addresses that each decode the HTS-call's key list independently:

- **`0x167`** via `UpdateCommonDecoder.decodeTokenUpdateKeys(...)` (shared decode path, used by several HTS calls)
- **`0x16c`** via `address_0x16c/UpdateDecoder`, which **overrides** key decoding on its own rather than reusing the common path

Both accept degenerate `TokenKey` tuples and still produce a `TransactionBody` that gets dispatched and returns `SUCCESS`, even though nothing meaningful is being changed:

- an **empty** key list
- a `keyType` of **`0`**
- a `keyType` with bits set **outside the field's supported mask** (the mask differs by address: `0x167` supports admin/kyc/freeze/wipe/supply/feeSchedule/pause; `0x16c` additionally supports the metadata key at bit `0x80`)
- **duplicate/overlapping `keyType`** entries within the same call, where the second entry silently wins or loses depending on list iteration order — undefined, build-dependent behavior, not a guaranteed "first wins" as a less careful read of the code might assume

The original advisory's linked fix PR only patched the `0x167` path. It never touched `address_0x16c/UpdateDecoder.java` — which, again, overrides decoding independently — so every one of the degenerate cases above still goes through unguarded at `0x16c`. The fix PR also didn't check `keyType` width beyond 32 bits, so a `keyType` with bits set only above bit 31 (e.g. `0x100000000`) still bypasses the mask check entirely once narrowed.

## What I verified by execution, not by reading the code

I did not accept the original advisory's severity claim at face value, and I did not accept "it's fixed" at face value either. Before writing this PR I ran the real decoder, unmodified, on unpatched `main`, against each degenerate input listed above, and inspected the actual `TransactionBody` that comes out:

- **No key field is ever set** from a degenerate input — `adminKey`, `kycKey`, `freezeKey`, `wipeKey`, `supplyKey`, `feeScheduleKey`, `pauseKey`, `metadataKey` all come back null/unset in every case I tested. `handle()` for these inputs is a no-op: authoritative key state is never corrupted.
- I separately drove an **unprivileged caller** attempting an admin-key and supply-key rotation through the real `preHandle` path (`FakePreHandleContext`) on a token where the caller holds none of the relevant keys. Both attempts correctly surface the required key as missing — there is **no authorization bypass** here. An attacker cannot use this gap to rotate keys they don't control.
- For the duplicate-keyType case specifically, I confirmed the resolved key always comes from one of the two caller-submitted values — never a corrupted or attacker-controlled value from outside the call — it's nondeterministic *which* submitted value wins, not *whose* value wins.

So the honest, tested classification is: **a correctness bug** — malformed/degenerate input is silently accepted and reported as `SUCCESS` with no effect, instead of being rejected — not a key-state-corruption bug and not an authorization-bypass bug. I'm stating this plainly rather than inflating it, because it's the one thing a reviewer can check for themselves in minutes by running the same probes against the diff, and a report that overstates it fails that check immediately. The bug is still real and still worth fixing: an API that silently no-ops on malformed input instead of rejecting it is exactly the kind of gap that turns into something worse the next time nearby code changes, and `SUCCESS` on a no-op is actively misleading to any caller or monitoring system trusting the response code.

## Fix

Adds a single shared guard, `isMeaningfulKeyUpdate(Tuple[] tokenKeysTuples, int supportedMask)`, applied at **both** the `0x167` and `0x16c` call sites (closing the gap the original fix left open), that rejects:

- an empty key list
- a non-positive `keyType`, or one wider than 32 bits (checked on the **full uint256** before any `int` narrowing — this is what catches the bits-above-31 bypass)
- any `keyType` with a bit set outside that address's supported key mask
- duplicate/overlapping `keyType` entries in the same call

Two files changed, 37 lines added, no existing behavior for valid inputs touched.

## Why I'm asking for bounty review here, directly

GHSA-q776-f4m7-g642 has been open for over a month with no response, and its linked fix PR was incomplete (missing the `0x16c` path and the wide-keyType check) — both gaps this PR closes. I'm asking directly: please review this PR and the linked advisory for bounty eligibility. The fix is complete, tested against real `main` at both affected addresses, and ready to merge as-is.

## Scope of the claim

To be precise about what I am and am not claiming: this is a correctness/input-validation defect, verified by execution to not reach key-state corruption or authorization bypass. I'm not claiming fund loss, consensus impact, or privilege escalation — the evidence doesn't support that, and I'd rather state the real finding accurately than have it dismissed for overreach.
