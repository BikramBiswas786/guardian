# Authorization-bypass audit — hiero-consensus-node token service

Goal: find a handler where `handle` mutates state that `preHandle` does not
require the authorizing key for — i.e. a transaction authorized without the
signer/owner approval the program's **High** tier ($3–10k) covers.

Method: for each state-mutating token/crypto handler, compare the key(s)
`preHandle` requires against the mutation and the handle-time guards. Read
against `main` `a00481b`.

## Result: no authorization gap found. The surface is uniformly sound.

Two correct patterns are used consistently:

### Pattern A — require-if-present, backstopped in `handle`
`preHandle` requires the role key only when the token has one; `handle` then
rejects the no-key case with a specific code. Both halves are present, so an
unprivileged caller cannot act on a token that lacks the key.

| Handler | preHandle requires | handle backstop |
|---|---|---|
| TokenMint | supplyKey if present | `!isEmpty(supplyKey)` → TOKEN_HAS_NO_SUPPLY_KEY |
| TokenBurn | supplyKey if present | `!isEmpty(supplyKey)` → TOKEN_HAS_NO_SUPPLY_KEY |
| TokenAccountWipe | wipeKey if present | `!isEmpty(wipeKey)` → TOKEN_HAS_NO_WIPE_KEY |
| TokenPause | pauseKey if present | `!isEmpty(pauseKey)` → TOKEN_HAS_NO_PAUSE_KEY |
| TokenUnpause | pauseKey if present | `!isEmpty(pauseKey)` → TOKEN_HAS_NO_PAUSE_KEY |
| TokenDelete | adminKey if present | `!isEmpty(adminKey)` → TOKEN_IS_IMMUTABLE |

### Pattern B — hard-required in `preHandle`
`preHandle` throws if the key is absent, or requires the owner/target key
directly.

| Handler | preHandle requirement |
|---|---|
| TokenFreeze/Unfreeze | freezeKey, else TOKEN_HAS_NO_FREEZE_KEY |
| TokenGrantKyc/RevokeKyc | kycKey, else TOKEN_HAS_NO_KYC_KEY |
| TokenFeeScheduleUpdate | feeScheduleKey (+ collector receiver-sig) |
| TokenUpdate | admin/role keys per field present (HIP-540) |
| TokenUpdateNfts | metadata-or-supply key per HIP-657 branch |
| CryptoApproveAllowance | owner key when owner != payer (all 3 allowance kinds) |
| CryptoUpdate | target account key + new key (unless waived) |
| CryptoDelete | deleted account key + transfer target receiver-sig |
| TokenReject | owner key when owner present |
| TokenClaimAirdrop | receiver (claimer) key |
| TokenCancelAirdrop | sender key |

## Bearing on the two open advisories

This audit reinforces the verdict in FINDINGS.md: the authorization layer is
not bypassable in these handlers, so **q776 is correctly "not a privilege
escalation"** (as its own text says) — it is a response-code/validation
defect, not the High-tier "authorizing transactions without approval."

## Bounty-program mapping (Immunefi Hedera, verified 2026-10-17)

- Critical ($10–30k): fund freeze / theft / network shutdown / history
  tampering. Value-conservation harness = clean (~24M cases). No lead.
- High ($3–10k): "authorizing transactions without approval from
  signers/owners." This audit = clean. No lead.
- Medium ($3k): incorrect consensus-critical mirror records. q776's mirror
  record is correct (keys genuinely unchanged); only the EVM response code
  is wrong. Does not map.
- No Low tier; informational/best-practice out of scope → jfxq and q776 do
  not map to a paid tier.

Conclusion: no bounty-qualifying finding in the audited surface. The two
filed advisories remain valid correctness bugs worth merging, but are below
this program's paid floor as written.
