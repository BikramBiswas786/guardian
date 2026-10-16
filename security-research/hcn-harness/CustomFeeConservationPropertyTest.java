// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.transfer.customfees;

// =====================================================================================================================
// VALUE-CONSERVATION PROPERTY HARNESS for the custom-fee assessors (hiero-consensus-node, in-scope Immunefi asset)
// Covers: CustomFractionalFeeAssessor, CustomFixedFeeAssessor, CustomRoyaltyFeeAssessor (exchanged-value path).
//
// STATUS: written against the real APIs at main (HEAD 75bc55e, 2026-10-16). NOT executed by its author: the audit
// sandbox has no Maven/Gradle artifact access (the agent proxy returns 404/429 for Maven Central and plugins.gradle.org),
// so neither Gradle nor a manual javac classpath can compile the module there. RUN IT where the repo builds:
//     ./gradlew :app-service-token-impl:test --tests "*CustomFeeConservationPropertyTest"
// Place at: hedera-node/hedera-token-service-impl/src/test/java/com/hedera/node/app/service/token/impl/test/handlers/transfer/customfees/
// Override iteration count / seed with -Dconservation.iterations / -Dconservation.seed, or the env vars
// CONSERVATION_ITERATIONS / CONSERVATION_SEED (Gradle does not forward -D flags to the test JVM; env is inherited).
//
// INVARIANT (value conservation): a custom fee only MOVES value, never creates or destroys it. After assessment, for
// every token touched, the net of all adjustments (mutable input legs + newly assessed fee legs) must still equal the
// net the input started with (0 here). A non-zero net = units minted or burned = theft/inflation = a bounty-grade bug.
//
// These tests assert conservation holds. They FAIL (printing the exact seed + inputs) only if the REAL code breaks it.
// A failure is a lead to reproduce and root-cause, never a finished finding. No value-creation is ever asserted.
// =====================================================================================================================

import static com.hedera.hapi.node.base.TokenType.FUNGIBLE_COMMON;
import static com.hedera.hapi.node.base.TokenType.NON_FUNGIBLE_UNIQUE;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.asToken;
import static com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AssessmentResult.HBAR_TOKEN_ID;
import static com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase.withFixedFee;
import static com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase.withFractionalFee;
import static com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase.withRoyaltyFee;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TransferUtil.asAccountAmount;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TransferUtil.asTokenTransferList;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Fraction;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.hapi.node.transaction.FractionalFee;
import com.hedera.hapi.node.transaction.RoyaltyFee;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AssessmentResult;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.CustomFixedFeeAssessor;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.CustomFractionalFeeAssessor;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.CustomRoyaltyFeeAssessor;
import com.hedera.node.app.spi.workflows.HandleException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

public class CustomFeeConservationPropertyTest {

    private static final int ITERATIONS = Integer.decode(cfg("conservation.iterations", "CONSERVATION_ITERATIONS", "200000"));
    private static final long MASTER_SEED = Long.decode(cfg("conservation.seed", "CONSERVATION_SEED", "0xC0FFEE"));
    /** Upper bound for "large" amounts: big enough to drive fraction/multiply overflow paths, small enough that legs sum. */
    private static final long BIG = Long.MAX_VALUE / 4;

    private static String cfg(final String prop, final String env, final String dflt) {
        final String p = System.getProperty(prop);
        if (p != null && !p.isBlank()) return p.trim();
        final String e = System.getenv(env);
        return (e != null && !e.isBlank()) ? e.trim() : dflt;
    }

    private final TokenID feeTokenId = asToken(3000); // the token that carries the custom fee
    private final TokenID payTokenId = asToken(3001); // a distinct fungible token used as exchange value for royalty
    private final AccountID sender = asAccount(0L, 0L, 4001);
    private final AccountID receiver = asAccount(0L, 0L, 5001);
    private final AccountID collector = asAccount(0L, 0L, 6666);
    // Treasury must NOT be the payer: the treasury is exempt from its own token's custom fees, which would make every case vacuous.
    private final AccountID treasury = asAccount(0L, 0L, 7777);
    private final AccountID[] others = {asAccount(0L, 0L, 8001), asAccount(0L, 0L, 8002), asAccount(0L, 0L, 8003)};

    // ---------------------------------------------------------------------------------------------- FRACTIONAL (non-net)
    @Test
    void fractionalFeeConservesValue() {
        fractional("fractional", false, 10_000_000L, 1_000_000L);
    }

    @Test
    void fractionalNetOfTransfersConservesValue() {
        fractional("fractional-net", true, 10_000_000L, 1_000_000L);
    }

    @Test
    void fractionalLargeAmountsConserveValue() {
        fractional("fractional-large", false, BIG, 1_000_000_000_000L);
    }

    @Test
    void fractionalNetOfTransfersLargeAmountsConserveValue() {
        fractional("fractional-net-large", true, BIG, 1_000_000_000_000L);
    }

    private void fractional(final String name, final boolean netOfTransfers, final long maxTrigger, final long maxDen) {
        final var subject = new CustomFractionalFeeAssessor(new CustomFixedFeeAssessor());
        runProperty(name, (local, seed) -> {
            final long trigger = 1L + Math.floorMod(local.nextLong(), maxTrigger);
            final int n = 1 + local.nextInt(others.length);
            final long[] credits = splitPositive(trigger, n, local);
            final var legs = new ArrayList<AccountAmount>();
            legs.add(asAccountAmount(sender, -trigger));
            for (int r = 0; r < n; r++) legs.add(asAccountAmount(others[r], credits[r]));

            final long den = 1L + Math.floorMod(local.nextLong(), maxDen);
            final long num = Math.floorMod(local.nextLong(), den + 1);
            final long min = Math.floorMod(local.nextLong(), 1_000L);
            final long max = local.nextBoolean() ? 0L : min + Math.floorMod(local.nextLong(), 1_000_000L);
            final var fee = FractionalFee.newBuilder()
                    .netOfTransfers(netOfTransfers)
                    .minimumAmount(min)
                    .maximumAmount(max)
                    .fractionalAmount(Fraction.newBuilder().numerator(num).denominator(den).build())
                    .build();
            final Token token = fungibleWith(List.of(withFractionalFee(fee, collector, netOfTransfers)));
            final var result = new AssessmentResult(List.of(asTokenTransferList(feeTokenId, legs)), List.of());

            subject.assessFractionalFees(token, sender, result);
            return new Check(result, List.of(feeTokenId), "net=" + netOfTransfers + " trigger=" + trigger + " num=" + num + " den=" + den
                    + " min=" + min + " max=" + max + " credits=" + Arrays.toString(credits));
        });
    }

    // ------------------------------------------------------------------------------------------------------ FIXED FEE
    @Test
    void fixedFeeConservesValue() {
        fixed("fixed", 1_000_000_000L);
    }

    @Test
    void fixedFeeLargeAmountsConserveValue() {
        fixed("fixed-large", BIG);
    }

    private void fixed(final String name, final long maxAmount) {
        final var subject = new CustomFixedFeeAssessor();
        runProperty(name, (local, seed) -> {
            final long amount = 1L + Math.floorMod(local.nextLong(), maxAmount);
            final int mode = local.nextInt(3); // 0=hbar, 1=self-denominated HTS, 2=other-denominated HTS
            final FixedFee.Builder fb = FixedFee.newBuilder().amount(amount);
            final List<TokenID> tokensOfInterest = new ArrayList<>();
            if (mode == 0) {
                tokensOfInterest.add(HBAR_TOKEN_ID);
            } else if (mode == 1) {
                fb.denominatingTokenId(feeTokenId);
                tokensOfInterest.add(feeTokenId);
            } else {
                fb.denominatingTokenId(payTokenId);
                tokensOfInterest.add(payTokenId);
            }
            final Token token = fungibleWith(List.of(withFixedFee(fb.build(), collector, false)));
            // Seed a net-zero input on feeTokenId so the AssessmentResult has a mutable map for self-denominated fees.
            final var result = new AssessmentResult(
                    List.of(asTokenTransferList(
                            feeTokenId,
                            List.of(asAccountAmount(sender, -100L), asAccountAmount(receiver, 100L)))),
                    List.of(asAccountAmount(sender, -100L), asAccountAmount(receiver, 100L)));

            subject.assessFixedFee(token, sender, token.customFees().get(0), result);
            // self-denominated fee mutates feeTokenId's input map; always include feeTokenId + hbar in the check.
            final List<TokenID> check = new ArrayList<>(tokensOfInterest);
            check.add(feeTokenId);
            check.add(HBAR_TOKEN_ID);
            return new Check(result, check, "amount=" + amount + " mode=" + mode);
        });
    }

    // ---------------------------------------------------------------------------------------- ROYALTY (exchanged value)
    @Test
    void royaltyFeeExchangedValueConservesValue() {
        royalty("royalty", 10_000_000L, 1_000L);
    }

    @Test
    void royaltyFeeLargeAmountsConserveValue() {
        royalty("royalty-large", BIG, 1_000_000_000_000L);
    }

    private void royalty(final String name, final long maxExchanged, final long maxDen) {
        final var subject = new CustomRoyaltyFeeAssessor(new CustomFixedFeeAssessor());
        runProperty(name, (local, seed) -> {
            // NFT sender receives fungible value in exchange; royalty takes a fraction of that credit.
            final long exchanged = 1L + Math.floorMod(local.nextLong(), maxExchanged);
            final long den = 1L + Math.floorMod(local.nextLong(), maxDen);
            final long num = Math.floorMod(local.nextLong(), den + 1); // 0..den, so royalty <= exchanged
            final boolean hbarExchange = local.nextBoolean();

            final var royalty = RoyaltyFee.newBuilder()
                    .exchangeValueFraction(Fraction.newBuilder().numerator(num).denominator(den).build())
                    .build();
            final Token nft = Token.newBuilder()
                    .tokenId(feeTokenId)
                    .tokenType(NON_FUNGIBLE_UNIQUE)
                    .treasuryAccountId(treasury)
                    .customFees(List.of(withRoyaltyFee(royalty, collector)))
                    .build();

            final List<TokenID> check = new ArrayList<>();
            final AssessmentResult result;
            if (hbarExchange) {
                result = new AssessmentResult(
                        List.of(),
                        List.of(asAccountAmount(receiver, -exchanged), asAccountAmount(sender, exchanged)));
                check.add(HBAR_TOKEN_ID);
            } else {
                result = new AssessmentResult(
                        List.of(asTokenTransferList(
                                payTokenId,
                                List.of(asAccountAmount(receiver, -exchanged), asAccountAmount(sender, exchanged)))),
                        List.of());
                check.add(payTokenId);
            }
            subject.assessRoyaltyFees(nft, sender, receiver, result);
            return new Check(result, check, "exchanged=" + exchanged + " num=" + num + " den=" + den
                    + " hbar=" + hbarExchange);
        });
    }

    // ------------------------------------------------------------------------------------------------------- plumbing
    @FunctionalInterface
    private interface Scenario {
        Check run(Random local, long seed);
    }

    private record Check(AssessmentResult result, List<TokenID> tokens, String desc) {}

    private void runProperty(final String name, final Scenario scenario) {
        final var rng = new Random(MASTER_SEED);
        long checked = 0;
        long charged = 0;
        long rejected = 0;
        final List<String> unexpected = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            final long seed = rng.nextLong();
            final Check c;
            try {
                c = scenario.run(new Random(seed), seed);
            } catch (final HandleException e) {
                rejected++;
                continue; // legitimate rejection (e.g. CUSTOM_FEE_OUTSIDE_NUMERIC_RANGE), not a conservation break
            } catch (final RuntimeException e) {
                // Not a status-coded rejection: a raw exception escaping the assessor. Record with seed; keep going.
                if (unexpected.size() < 5) unexpected.add(e.getClass().getSimpleName() + ": " + e.getMessage() + " seed=" + seed);
                else unexpected.add("");
                continue;
            }
            for (final TokenID tok : c.tokens()) {
                final long net = netFor(c.result(), tok);
                assertThat(net)
                        .withFailMessage(
                                "CONSERVATION BREAK [%s] token=%s net=%d (expected 0). seed=%d %s",
                                name, tok, net, seed, c.desc())
                        .isZero();
            }
            checked++;
            for (final TokenID tok : c.tokens()) {
                if (collectorCredit(c.result(), tok) > 0) { charged++; break; }
            }
        }
        System.out.printf("[%s] conservation held across %d assessed cases, %d with a fee actually charged, %d rejected, %d unexpected exceptions (of %d; seed=0x%X)%n",
                name, checked, charged, rejected, unexpected.size(), ITERATIONS, MASTER_SEED);
        assertThat(unexpected)
                .withFailMessage("[%s] UNEXPECTED EXCEPTION x%d, first: %s", name, unexpected.size(),
                        unexpected.stream().filter(x -> !x.isEmpty()).limit(5).toList())
                .isEmpty();
        // Guard against a vacuous pass: every case rejected, or no fee ever reaching the collector.
        assertThat(checked).withFailMessage("[%s] VACUOUS: no case was assessed", name).isPositive();
        assertThat(charged).withFailMessage("[%s] VACUOUS: no case charged the collector (checked=%d)", name, checked).isPositive();
    }

    /** Net of every adjustment for one token: mutable input legs + newly assessed fee legs. */
    private static long netFor(final AssessmentResult result, final TokenID tok) {
        long net = sum(result.getMutableInputBalanceAdjustments().get(tok));
        if (tok.equals(HBAR_TOKEN_ID)) {
            net = Math.addExact(net, sum(result.getHbarAdjustments()));
        } else {
            net = Math.addExact(net, sum(result.getHtsAdjustments().get(tok)));
        }
        return net;
    }

    /** What the collector received for one token, across input legs and assessed fee legs. */
    private long collectorCredit(final AssessmentResult result, final TokenID tok) {
        long c = valueOf(result.getMutableInputBalanceAdjustments().get(tok), collector);
        if (tok.equals(HBAR_TOKEN_ID)) {
            c = Math.addExact(c, valueOf(result.getHbarAdjustments(), collector));
        } else {
            c = Math.addExact(c, valueOf(result.getHtsAdjustments().get(tok), collector));
        }
        return c;
    }

    private static long valueOf(final Map<AccountID, Long> m, final AccountID id) {
        if (m == null) return 0L;
        final Long v = m.get(id);
        return v == null ? 0L : v;
    }

    private static long sum(final Map<AccountID, Long> m) {
        if (m == null) return 0L;
        long s = 0L;
        for (final long v : m.values()) s = Math.addExact(s, v);
        return s;
    }

    private Token fungibleWith(final List<CustomFee> fees) {
        return Token.newBuilder()
                .tokenId(feeTokenId)
                .tokenType(FUNGIBLE_COMMON)
                .treasuryAccountId(treasury)
                .customFees(fees)
                .build();
    }

    private static long[] splitPositive(final long total, final int parts, final Random rng) {
        final long[] out = new long[parts];
        long remaining = total;
        for (int i = 0; i < parts - 1; i++) {
            final long maxHere = remaining - (parts - 1 - i);
            final long share = maxHere <= 1 ? 1L : 1L + Math.floorMod(rng.nextLong(), maxHere);
            out[i] = share;
            remaining -= share;
        }
        out[parts - 1] = remaining;
        return out;
    }
}
