## Summary

`DispatchForResponseCodeHtsCall`'s convenience constructors default to `STANDARD_FAILURE_CUSTOMIZER`, which remaps a genuine `INVALID_SIGNATURE` failure to `INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE` (response code 326) instead of the standard code (7) that every other call site in the system reports for the identical underlying failure. A caller checking the response code for a signature-verification failure sees two different codes depending on which call path it went through, with nothing in the call distinguishing a real "full-prefix" precompile condition from a plain invalid signature.

Relates to **GHSA-jfxq-8944-q786**.

## What I verified, not assumed

The existing fix PR for this advisory ships a regression test that passes identically on patched and unpatched code — it stubs the mock's `status()` getter to always return `INVALID_SIGNATURE`, so the assertion never actually exercises the remap logic. I rewrote the test to verify the mock's `status(...)` **write** call instead (`verify(recordBuilder).status(INVALID_SIGNATURE)`, `verify(recordBuilder, never()).status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)`), which does fail on unpatched `main` and passes once this fix is applied. Flagging this because a false-passing regression test is worse than no test — it would have shipped with the gap it's meant to catch unprotected.

## Fix

Replaces `STANDARD_FAILURE_CUSTOMIZER` with the existing no-op `FailureCustomizer` at all three call sites in `DispatchForResponseCodeHtsCall`'s convenience constructors, and removes the now-dead `STANDARD_FAILURE_CUSTOMIZER` field/import.

## Why now

GHSA-jfxq-8944-q786 and its linked fix PR have had no maintainer response in over a month. This PR is the completed fix for that advisory, opened directly since the private channel has stalled.
