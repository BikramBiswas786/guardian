## Background

I filed **GHSA-jfxq-8944-q786** against this repository in September, with a fix PR attached in the private advisory fork. Over a month later there has been no maintainer response — no review, no comment, no merge, nothing. This PR is that same fix, reopened directly against `main` because the private channel has gone quiet. I'm also requesting bounty review for this finding directly here, since the private process hasn't moved.

## The bug, in full

`DispatchForResponseCodeHtsCall` has three convenience constructors. All three default their `FailureCustomizer` argument to `STANDARD_FAILURE_CUSTOMIZER`. That customizer's job is to remap certain response codes for precompile-specific semantics — but it unconditionally remaps `INVALID_SIGNATURE` to `INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE` (response code **326**), regardless of whether the call in question has anything to do with full-prefix precompile signature handling at all.

Every other dispatch path in the system that hits a plain signature-verification failure reports the standard code: `INVALID_SIGNATURE` (response code **7**). So the *same underlying failure* — a transaction that failed signature verification — is reported as two different response codes depending only on which internal call path happened to construct the `DispatchForResponseCodeHtsCall`. Nothing in the failure itself distinguishes a genuine full-prefix precompile condition from an ordinary invalid signature; the customizer just always remaps it.

This matters to anyone building on response codes as a contract — exchanges, custody integrations, wallet software, monitoring/alerting — because code 326 and code 7 are not interchangeable in meaning, and software that branches on the specific code (not just "did it fail") gets inconsistent, call-path-dependent behavior for what is semantically one failure condition.

## What I verified by execution, not by reading the code

The existing fix PR (in the private advisory fork) shipped with a regression test. I ran it and it **passes identically on patched and unpatched `main`** — which means it proves nothing. The reason: it stubs the mock record builder's `status()` *getter* to always return `INVALID_SIGNATURE`, so the assertion reads back whatever it was told to return regardless of what the code under test actually does.

I rewrote the test to assert on the mock's `status(...)` *write* call instead:
```java
verify(recordBuilder).status(INVALID_SIGNATURE);
verify(recordBuilder, never()).status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE);
```
This version **fails on unpatched `main`** (the code actually calls `.status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)`) and **passes once this PR's fix is applied**. I ran both versions against the real code before writing anything here — not just reasoning about what the code should do.

I'm disclosing the original test's false-pass plainly because it's directly relevant to anyone reviewing the original advisory or its fix PR: that PR's own test coverage does not currently protect against regression.

## Fix

Replace `STANDARD_FAILURE_CUSTOMIZER` with the existing no-op `FailureCustomizer.NOOP_CUSTOMIZER` at all three convenience-constructor call sites in `DispatchForResponseCodeHtsCall`, and remove the now-dead `STANDARD_FAILURE_CUSTOMIZER` field and its import. Three-line functional change, plus the corrected regression test.

## Why I'm asking for bounty review here, directly

GHSA-jfxq-8944-q786 has been open and unresponded-to for over a month. I'm not routing this through additional process — I'm asking directly: please review this PR and the linked advisory for bounty eligibility under the Hedera program. The fix is complete, tested against real `main`, and ready to merge as-is.

## Scope of the claim

To be precise about what I am and am not claiming: this is a response-code consistency defect with a corrected, verified regression test — not a claim of fund loss, consensus impact, or an authorization bypass. I'm stating that directly rather than letting the PR imply more than the evidence supports, because the fix and the finding stand on their own merits.
