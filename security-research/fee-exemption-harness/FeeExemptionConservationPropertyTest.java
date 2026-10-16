// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.transfer.customfees;

// =====================================================================================================================
// FEE-EXEMPTION + VALUE-CONSERVATION PROPERTY HARNESS for the custom-fee assessors (hiero-consensus-node).
// Companion to CustomFeeConservationPropertyTest; focuses on the exemption policy (CustomFeeExemptions):
//   - a token's treasury is exempt from all its custom fees
//   - a fee collector is exempt from the fee it collects
//   - with allCollectorsAreExempt=true, a collector of ANY fee on the token is exempt from that fee
//
// Two invariants are asserted on the REAL code over randomized inputs. They FAIL (printing the seed + inputs) only if
// the real code breaks them; neither asserts that value is ever created.
//   (1) CONSERVATION: after assessment, the net of all adjustments for every token touched is unchanged (0 here).
//   (2) EXEMPTION FIDELITY: the harness independently computes, from the published policy, whether the payer is exempt
//       from each fee; an exempt payer must never be newly debited, and a non-exempt trigger must leave conservation
//       intact. A debit to a payer the policy exempts = a fee charged to someone who should pay nothing = a finding.
//
// Place at: hedera-node/hedera-token-service-impl/src/test/java/.../transfer/customfees/
// Config via -D or env: EXEMPT_ITERATIONS / EXEMPT_SEED.
// =====================================================================================================================

import static com.hedera.hapi.node.base.TokenType.FUNGIBLE_COMMON;
import static com.hedera.hapi.node.base.TokenType.NON_FUNGIBLE_UNIQUE;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.asToken;
import static com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AssessmentResult.HBAR_TOKEN_ID;
import static com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase.withFixedFee;
import static com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase.withRoyaltyFee;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TransferUtil.asAccountAmount;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TransferUtil.asTokenTransferList;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Fraction;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.hapi.node.transaction.RoyaltyFee;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AssessmentResult;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.CustomFixedFeeAssessor;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.CustomRoyaltyFeeAssessor;
import com.hedera.node.app.spi.workflows.HandleException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

public class FeeExemptionConservationPropertyTest {

    private static final int ITERATIONS = Integer.decode(cfg("exempt.iterations", "EXEMPT_ITERATIONS", "200000"));
    private static final long MASTER_SEED = Long.decode(cfg("exempt.seed", "EXEMPT_SEED", "0xE7E111"));

    private static String cfg(final String prop, final String env, final String dflt) {
        final String p = System.getProperty(prop);
        if (p != null && !p.isBlank()) return p.trim();
        final String e = System.getenv(env);
        return (e != null && !e.isBlank()) ? e.trim() : dflt;
    }

    private final TokenID feeTokenId = asToken(3000);
    private final TokenID payTokenId = asToken(3001);
    private final AccountID treasury = asAccount(0L, 0L, 7777);
    // Seed accounts carry the net-zero baseline transfer; they are never a candidate payer, so the payer
    // starts at 0 on feeTokenId and ANY debit observed on the payer is purely a fee the assessor added.
    private final AccountID seedSrc = asAccount(0L, 0L, 9001);
    private final AccountID seedDst = asAccount(0L, 0L, 9002);
    // Candidate payers: treasury, the two fee collectors, and a plain stranger.
    private final AccountID collector1 = asAccount(0L, 0L, 6001);
    private final AccountID collector2 = asAccount(0L, 0L, 6002);
    private final AccountID stranger = asAccount(0L, 0L, 8001);

    // --------------------------------------------------------------------------- FIXED FEE, two collectors, exemptions
    @Test
    void fixedFeeExemptionIsHonoredAndConserves() {
        final var subject = new CustomFixedFeeAssessor();
        runProperty("fixed-exempt", (local, seed) -> {
            final boolean allCollectorsExempt = local.nextBoolean();
            final long amount = 1L + Math.floorMod(local.nextLong(), 1_000_000_000L);
            // Two fixed fees on the token, distinct collectors.
            final var fee1 = withFixedFee(
                    FixedFee.newBuilder().amount(amount).denominatingTokenId(feeTokenId).build(),
                    collector1,
                    allCollectorsExempt);
            final var fee2 = withFixedFee(
                    FixedFee.newBuilder().amount(amount).denominatingTokenId(feeTokenId).build(),
                    collector2,
                    allCollectorsExempt);
            final Token token = fungibleWith(List.of(fee1, fee2));
            final AccountID payer = pick(local, treasury, collector1, collector2, stranger);

            final var result = seededResult(payer);
            subject.assessFixedFees(token, payer, result);

            // Independently computed expectation per the published policy, for the token's own fee currency.
            final boolean exemptFromFee1 = policyExempt(token, fee1, payer, allCollectorsExempt);
            final boolean exemptFromFee2 = policyExempt(token, fee2, payer, allCollectorsExempt);
            // assessFixedFees also skips a fee when payer==collector (before isPayerExempt), already covered by policy.
            final boolean anyCharged = !(exemptFromFee1 && exemptFromFee2);
            return new Check(
                    result,
                    List.of(feeTokenId),
                    payer,
                    anyCharged,
                    "allColEx=" + allCollectorsExempt + " amount=" + amount + " payer=" + payer.accountNum());
        });
    }

    // ------------------------------------------------------------------------ ROYALTY FALLBACK, receiver exemption
    @Test
    void royaltyFallbackExemptionIsHonoredAndConserves() {
        final var subject = new CustomRoyaltyFeeAssessor(new CustomFixedFeeAssessor());
        runProperty("royalty-fallback-exempt", (local, seed) -> {
            final boolean allCollectorsExempt = local.nextBoolean();
            final long fallbackAmount = 1L + Math.floorMod(local.nextLong(), 1_000_000L);
            final var royalty = RoyaltyFee.newBuilder()
                    .exchangeValueFraction(Fraction.newBuilder().numerator(1).denominator(10).build())
                    .fallbackFee(FixedFee.newBuilder()
                            .amount(fallbackAmount)
                            .denominatingTokenId(feeTokenId)
                            .build())
                    .build();
            final var fee = withRoyaltyFeeExempt(royalty, collector1, allCollectorsExempt);
            final Token nft = Token.newBuilder()
                    .tokenId(feeTokenId)
                    .tokenType(NON_FUNGIBLE_UNIQUE)
                    .treasuryAccountId(treasury)
                    .customFees(List.of(fee))
                    .build();
            // Receiver gets NO fungible value -> fallback path. Receiver is the fallback payer.
            final AccountID rcv = pick(local, treasury, collector1, stranger);
            final var result = seededResult(rcv);

            subject.assessRoyaltyFees(nft, stranger, rcv, result);

            final boolean exempt = policyExempt(nft, fee, rcv, allCollectorsExempt);
            return new Check(
                    result,
                    List.of(feeTokenId),
                    rcv,
                    !exempt,
                    "allColEx=" + allCollectorsExempt + " fb=" + fallbackAmount + " rcv=" + rcv.accountNum());
        });
    }

    // ------------------------------------------------------------------------------------------------------ plumbing
    @FunctionalInterface
    private interface Scenario {
        Check run(Random local, long seed);
    }

    /** result: the assessment result; tokens: tokens to conserve-check; payer: the exemption subject;
     *  feeExpected: whether the policy says a fee should be charged somewhere. */
    private record Check(AssessmentResult result, List<TokenID> tokens, AccountID payer, boolean feeExpected, String desc) {}

    /** Mirror of CustomFeeExemptions.isPayerExempt, kept independent so a wiring regression in the real code shows up. */
    private static boolean policyExempt(
            final Token token, final CustomFee fee, final AccountID payer, final boolean allCollectorsExempt) {
        if (token.treasuryAccountIdOrThrow().equals(payer)) return true;
        if (fee.feeCollectorAccountIdOrElse(AccountID.DEFAULT).equals(payer)) return true;
        if (allCollectorsExempt) {
            for (final var f : token.customFees()) {
                if (f.feeCollectorAccountIdOrElse(AccountID.DEFAULT).equals(payer)) return true;
            }
        }
        return false;
    }

    private void runProperty(final String name, final Scenario scenario) {
        final var rng = new Random(MASTER_SEED);
        long checked = 0;
        long exemptHonored = 0;
        long charged = 0;
        final List<String> unexpected = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            final long seed = rng.nextLong();
            final Check c;
            try {
                c = scenario.run(new Random(seed), seed);
            } catch (final HandleException e) {
                continue;
            } catch (final RuntimeException e) {
                if (unexpected.size() < 5) unexpected.add(e.getClass().getSimpleName() + ": " + e.getMessage() + " seed=" + seed);
                continue;
            }
            // (1) conservation on every token touched
            for (final TokenID tok : c.tokens()) {
                final long net = netFor(c.result(), tok);
                assertThat(net)
                        .withFailMessage("CONSERVATION BREAK [%s] token=%s net=%d seed=%d %s", name, tok, net, seed, c.desc())
                        .isZero();
            }
            // (2) exemption fidelity: payer starts at 0 on feeTokenId, so any negative is a fee the assessor added.
            final long payerNet = valueOf(c.result().getMutableInputBalanceAdjustments().get(feeTokenId), c.payer());
            if (!c.feeExpected()) {
                assertThat(payerNet)
                        .withFailMessage(
                                "EXEMPT PAYER DEBITED [%s] payer=%s net=%d (policy says exempt; expected 0) seed=%d %s",
                                name, c.payer(), payerNet, seed, c.desc())
                        .isZero();
                exemptHonored++;
            } else {
                // A chargeable case must actually debit the payer, or the "charged" branch is vacuous.
                assertThat(payerNet)
                        .withFailMessage(
                                "EXPECTED CHARGE MISSING [%s] payer=%s net=%d (policy says chargeable) seed=%d %s",
                                name, c.payer(), payerNet, seed, c.desc())
                        .isNegative();
                charged++;
            }
            checked++;
        }
        System.out.printf(
                "[%s] conservation held across %d cases; %d exempt-payer cases honored, %d chargeable; %d unexpected (seed=0x%X)%n",
                name, checked, exemptHonored, charged, unexpected.size(), MASTER_SEED);
        assertThat(checked).withFailMessage("[%s] VACUOUS: no case assessed", name).isPositive();
        assertThat(exemptHonored).withFailMessage("[%s] VACUOUS: no exempt-payer case exercised", name).isPositive();
        assertThat(charged).withFailMessage("[%s] VACUOUS: no chargeable case exercised", name).isPositive();
        assertThat(unexpected)
                .withFailMessage("[%s] UNEXPECTED EXCEPTION x%d, first: %s", name, unexpected.size(), unexpected)
                .isEmpty();
    }

    private static long netFor(final AssessmentResult result, final TokenID tok) {
        long net = sum(result.getMutableInputBalanceAdjustments().get(tok));
        if (tok.equals(HBAR_TOKEN_ID)) {
            net = Math.addExact(net, sum(result.getHbarAdjustments()));
        } else {
            net = Math.addExact(net, sum(result.getHtsAdjustments().get(tok)));
        }
        return net;
    }

    private static long sum(final Map<AccountID, Long> m) {
        if (m == null) return 0L;
        long s = 0L;
        for (final long v : m.values()) s = Math.addExact(s, v);
        return s;
    }

    private static long valueOf(final Map<AccountID, Long> m, final AccountID id) {
        if (m == null) return 0L;
        final Long v = m.get(id);
        return v == null ? 0L : v;
    }

    private static AccountID pick(final Random r, final AccountID... options) {
        return options[r.nextInt(options.length)];
    }

    private Token fungibleWith(final List<CustomFee> fees) {
        return Token.newBuilder()
                .tokenId(feeTokenId)
                .tokenType(FUNGIBLE_COMMON)
                .treasuryAccountId(treasury)
                .customFees(fees)
                .build();
    }

    /** Seed a net-zero transfer of feeTokenId between two NON-payer accounts, so the result has a mutable map
     *  keyed on feeTokenId while leaving the payer at 0 (any later payer debit is purely an assessed fee). */
    private AssessmentResult seededResult(final AccountID payer) {
        return new AssessmentResult(
                List.of(asTokenTransferList(
                        feeTokenId, List.of(asAccountAmount(seedSrc, -100L), asAccountAmount(seedDst, 100L)))),
                List.of(asAccountAmount(seedSrc, -100L), asAccountAmount(seedDst, 100L)));
    }

    private static CustomFee withRoyaltyFeeExempt(
            final RoyaltyFee royaltyFee, final AccountID feeCollectorId, final boolean allCollectorsExempt) {
        return CustomFee.newBuilder()
                .royaltyFee(royaltyFee)
                .feeCollectorAccountId(feeCollectorId)
                .allCollectorsAreExempt(allCollectorsExempt)
                .build();
    }
}
