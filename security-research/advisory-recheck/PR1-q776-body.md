## Summary

`updateTokenKeys` accepts malformed key-update inputs at both `0x167` (`UpdateCommonDecoder`) and `0x16c` (`address_0x16c/UpdateDecoder`, which overrides key decoding independently of the common decoder) and still returns `SUCCESS`, without applying any meaningful key change. Degenerate inputs that hit this path:

- an empty key list
- a `keyType` of `0`
- a `keyType` with bits set outside the address's supported mask
- duplicate/overlapping `keyType` entries in the same call

Relates to **GHSA-q776-f4m7-g642**, filed in September. The advisory's existing fix only covered the `0x167` decode path — `address_0x16c/UpdateDecoder.java` overrides key decoding independently and bypassed that guard entirely, and the supported mask didn't check keyType widths beyond 32 bits. This PR closes both gaps with a single shared check applied at both call sites.

## What I verified, not assumed

I did not treat the original advisory's severity as settled and build on top of it. Before writing this fix I ran the actual degenerate inputs through the real decoder on unpatched `main` and inspected the resulting `TransactionBody`:

- No key fields (`adminKey`/`kycKey`/`freezeKey`/`wipeKey`/`supplyKey`/`feeScheduleKey`/`pauseKey`/`metadataKey`) get set from a degenerate input — `handle()` is a no-op, not a key-state corruption.
- An unprivileged caller attempting this through `preHandle` is still blocked by existing authorization (`requireKey`) — there is no auth bypass here.

So: this is a **correctness bug** — malformed input is silently accepted and ignored instead of being rejected — not a key-state-corruption or authorization-bypass vulnerability. I'm stating that plainly rather than inflating it, because the fix is worth taking either way: silently accepting no-op input that should be rejected is exactly the kind of gap that becomes a real problem once something else changes nearby.

## Fix

Adds `isMeaningfulKeyUpdate(Tuple[] tokenKeysTuples, int supportedMask)`, checked at both the `0x167` and `0x16c` call sites, that rejects:
- an empty key list
- a non-positive `keyType`, or one wider than 32 bits (checked on the full uint256 before any `int` narrowing)
- any `keyType` bit outside the address's supported key mask
- duplicate/overlapping `keyType` entries

## Why now

GHSA-q776-f4m7-g642 and its linked fix PR have had no maintainer response in over a month. This PR is the completed fix for that advisory's remaining gap, opened directly since the private channel has stalled.
