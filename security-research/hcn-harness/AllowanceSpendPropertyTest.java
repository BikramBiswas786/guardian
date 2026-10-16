// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.AMOUNT_EXCEEDS_ALLOWANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SPENDER_DOES_NOT_HAVE_ALLOWANCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.AccountCryptoAllowance;
import com.hedera.hapi.node.state.token.AccountFungibleTokenAllowance;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.token.impl.handlers.transfer.AdjustFungibleTokenChangesStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.AdjustHbarChangesStep;
import com.hedera.node.app.service.token.impl.handlers.transfer.TransferContextImpl;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Randomized differential tests for the two CryptoTransfer steps that move value and spend allowances:
 * {@link AdjustHbarChangesStep} and {@link AdjustFungibleTokenChangesStep}.
 *
 * <p>Each iteration builds a fresh random world (balances, crypto/token allowances incl. decoys, frozen/KYC flags), a
 * random zero-sum transfer list with random {@code isApproval} flags, and a random top-level payer. An independent
 * oracle written from the HIP-336 rules predicts accept/reject:
 * <ul>
 *   <li>every resulting balance must be non-negative;</li>
 *   <li>an approved debit must be covered by an allowance from the debited owner to the top-level payer (for tokens:
 *       for this token), and the aggregated approved debits of an owner may not exceed it;</li>
 *   <li>credits flagged {@code isApproval} and non-approved debits never touch allowances;</li>
 *   <li>(tokens) every listed relation must be unfrozen and KYC-granted.</li>
 * </ul>
 * The real step is then run. Any disagreement fails: an ACCEPT the oracle rejects (balance or authorization bypass),
 * a REJECT the oracle accepts, a reject code that matches none of the predicted reasons, or — on accept — any balance,
 * allowance or positive-balance counter that differs from the oracle's exact expected state.
 *
 * <p>Iterations: env {@code ALLOWANCE_ITERATIONS} (default 20000); seed: env {@code CONSERVATION_SEED}.
 */
class AllowanceSpendPropertyTest extends StepsBase {
    // Realistic caps: six accounts can never hold more than the total supply between them, so per-account balances
    // are capped at 1/6 of it (hbar: 50B hbar = 5e18 tinybar; tokens: Long.MAX_VALUE max supply).
    private static final long HBAR_CAP = 5_000_000_000_000_000_000L / 6;
    private static final long TOKEN_CAP = Long.MAX_VALUE / 6;

    private List<AccountID> accts;
    private final Map<AccountID, Account> pristine = new HashMap<>();
    private TokenRelation relTemplate;

    @Override
    @BeforeEach
    public void setUp() {
        super.setUp();
        refreshWritableStores();
        writableTokenStore.put(nonFungibleToken.copyBuilder().kycKey((Key) null).build());
        givenStoresAndConfig(handleContext);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        given(handleContext.savepointStack()).willReturn(stack);
        given(handleContext.dispatchMetadata()).willReturn(HandleContext.DispatchMetadata.EMPTY_METADATA);
        accts = List.of(payerId, ownerId, treasuryId, spenderId, transferAccountId, delegatingSpenderId);
        for (final var id : accts) {
            pristine.put(id, writableAccountStore.get(id));
        }
        relTemplate = writableTokenRelStore.get(ownerId, fungibleTokenId);
    }

    // ------------------------------------------------------------------------------------------------- world

    private record World(
            AccountID payer,
            Map<AccountID, Long> hbar,
            Map<AccountID, Long> tokenBal,
            Map<AccountID, Boolean> frozen,
            Map<AccountID, Boolean> kyc,
            Map<AccountID, Integer> numPositive,
            Map<AccountID, List<AccountCryptoAllowance>> crypto,
            Map<AccountID, List<AccountFungibleTokenAllowance>> tokens) {}

    private long randomAmount(final Random r, final long cap) {
        return switch (r.nextInt(6)) {
            case 0 -> 0L;
            case 1 -> 1L + r.nextInt(10);
            case 2 -> 1L + r.nextInt(1_000_000);
            case 3 -> 1L + (r.nextLong() >>> 1) % cap;
            case 4 -> cap - r.nextInt(1000); // at the cap
            default -> 1L + r.nextInt(1000);
        };
    }

    private World randomWorld(final Random r) {
        final var payer = accts.get(r.nextInt(accts.size()));
        final var w = new World(
                payer,
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                new HashMap<>(),
                new HashMap<>(),
                new HashMap<>(),
                new HashMap<>(),
                new HashMap<>());
        for (final var id : accts) {
            w.hbar().put(id, randomAmount(r, HBAR_CAP));
            w.tokenBal().put(id, randomAmount(r, TOKEN_CAP));
            w.frozen().put(id, r.nextInt(25) == 0);
            w.kyc().put(id, r.nextInt(25) != 0);
            w.numPositive().put(id, (w.tokenBal().get(id) > 0 ? 1 : 0) + r.nextInt(3));
            final var crypto = new ArrayList<AccountCryptoAllowance>();
            final var tokens = new ArrayList<AccountFungibleTokenAllowance>();
            // Decoys first or last, to exercise lookup order.
            final boolean decoysFirst = r.nextBoolean();
            if (decoysFirst) addDecoys(r, id, payer, crypto, tokens);
            if (!id.equals(payer) && r.nextInt(5) < 3) { // an owner can never approve itself
                crypto.add(AccountCryptoAllowance.newBuilder()
                        .spenderId(payer)
                        .amount(Math.max(1L, randomAmount(r, TOKEN_CAP)))
                        .build());
            }
            if (!id.equals(payer) && r.nextInt(5) < 3) {
                tokens.add(AccountFungibleTokenAllowance.newBuilder()
                        .tokenId(fungibleTokenId)
                        .spenderId(payer)
                        .amount(Math.max(1L, randomAmount(r, TOKEN_CAP)))
                        .build());
            }
            if (!decoysFirst) addDecoys(r, id, payer, crypto, tokens);
            w.crypto().put(id, crypto);
            w.tokens().put(id, tokens);
        }
        return w;
    }

    private void addDecoys(
            final Random r,
            final AccountID owner,
            final AccountID payer,
            final List<AccountCryptoAllowance> crypto,
            final List<AccountFungibleTokenAllowance> tokens) {
        // Allowances that must never be consumed: other spenders, or the payer but for another token.
        for (final var other : accts) {
            if (other.equals(payer) || other.equals(owner) || r.nextInt(3) != 0) continue;
            crypto.add(AccountCryptoAllowance.newBuilder()
                    .spenderId(other)
                    .amount(1L + r.nextInt(1_000_000))
                    .build());
            tokens.add(AccountFungibleTokenAllowance.newBuilder()
                    .tokenId(fungibleTokenId)
                    .spenderId(other)
                    .amount(1L + r.nextInt(1_000_000))
                    .build());
        }
        if (!owner.equals(payer) && r.nextBoolean()) {
            tokens.add(AccountFungibleTokenAllowance.newBuilder()
                    .tokenId(fungibleTokenIDB)
                    .spenderId(payer)
                    .amount(1L + r.nextInt(1_000_000))
                    .build());
        }
    }

    private void install(final World w) {
        for (final var id : accts) {
            writableAccountStore.put(pristine.get(id)
                    .copyBuilder()
                    .tinybarBalance(w.hbar().get(id))
                    .numberPositiveBalances(w.numPositive().get(id))
                    .cryptoAllowances(w.crypto().get(id))
                    .tokenAllowances(w.tokens().get(id))
                    .build());
            writableTokenRelStore.put(relTemplate
                    .copyBuilder()
                    .accountId(id)
                    .tokenId(fungibleTokenId)
                    .balance(w.tokenBal().get(id))
                    .frozen(w.frozen().get(id))
                    .kycGranted(w.kyc().get(id))
                    .build());
        }
    }

    /** A random zero-sum list over distinct accounts (as pure checks guarantee), with random approval flags. */
    private List<AccountAmount> randomTransfers(final Random r, final Map<AccountID, Long> balances) {
        final var shuffled = new ArrayList<>(accts);
        java.util.Collections.shuffle(shuffled, r);
        final int n = 2 + r.nextInt(accts.size() - 1);
        final var parts = shuffled.subList(0, n);
        final int nDebits = 1 + r.nextInt(n - 1);
        final var out = new ArrayList<AccountAmount>();
        long totalDebit = 0;
        for (int i = 0; i < nDebits; i++) {
            final var id = parts.get(i);
            final long bal = balances.get(id);
            final long debit = switch (r.nextInt(4)) {
                case 0 -> Math.max(1L, bal); // drain
                case 1 -> Math.max(1L, bal / 2);
                case 2 -> bal + 1 + r.nextInt(5); // overdraw
                default -> 1L + r.nextInt(1000);
            };
            if (totalDebit > Long.MAX_VALUE - debit) break;
            totalDebit += debit;
            out.add(AccountAmount.newBuilder()
                    .accountID(id)
                    .amount(-debit)
                    .isApproval(r.nextBoolean())
                    .build());
        }
        // Split the total among the credited accounts.
        final int nCredits = n - out.size();
        long remaining = totalDebit;
        for (int i = 0; i < nCredits; i++) {
            final var id = parts.get(out.size() + i);
            final long credit = (i == nCredits - 1) ? remaining : (remaining == 0 ? 0 : (r.nextLong() >>> 1) % (remaining + 1));
            remaining -= credit;
            if (credit == 0 && i < nCredits - 1) continue;
            out.add(AccountAmount.newBuilder()
                    .accountID(id)
                    .amount(credit)
                    .isApproval(r.nextInt(10) == 0) // approval flag on a credit must be ignored
                    .build());
        }
        java.util.Collections.shuffle(out, r);
        return out;
    }

    // ------------------------------------------------------------------------------------------------- oracle

    private static BigInteger big(final long v) {
        return BigInteger.valueOf(v);
    }

    private static Map<AccountID, Long> approvedDebits(final List<AccountAmount> xfers) {
        final Map<AccountID, Long> m = new LinkedHashMap<>();
        for (final var aa : xfers) {
            if (aa.isApproval() && aa.amount() < 0) m.merge(aa.accountID(), aa.amount(), Math::addExact);
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------------- hbar

    @Test
    @DisplayName("ALLOWANCE: hbar transfers spend exactly the payer's allowance and never overdraw")
    void hbarAllowanceSpend() {
        final int iterations = Integer.decode(cfg("ALLOWANCE_ITERATIONS", "20000"));
        final long seed = Long.decode(cfg("CONSERVATION_SEED", "0xC0FFEE"));
        final var rng = new Random(seed);
        int accepted = 0;
        int approvedSpends = 0;
        final Map<String, Integer> rejections = new TreeMap<>();
        for (int i = 0; i < iterations; i++) {
            final long iterSeed = rng.nextLong();
            final var r = new Random(iterSeed);
            final var w = randomWorld(r);
            install(w);
            final var xfers = randomTransfers(r, w.hbar());
            final var op = CryptoTransferTransactionBody.newBuilder()
                    .transfers(TransferList.newBuilder().accountAmounts(xfers).build())
                    .build();

            // Oracle.
            final var reasons = EnumSet.noneOf(ResponseCodeEnum.class);
            final Map<AccountID, BigInteger> expectedBal = new HashMap<>();
            for (final var id : accts) expectedBal.put(id, big(w.hbar().get(id)));
            for (final var aa : xfers) expectedBal.merge(aa.accountID(), big(aa.amount()), BigInteger::add);
            for (final var e : expectedBal.entrySet()) {
                if (e.getValue().signum() < 0) reasons.add(INSUFFICIENT_ACCOUNT_BALANCE);
            }
            final var approved = approvedDebits(xfers);
            final Map<AccountID, List<AccountCryptoAllowance>> expectedCrypto = new HashMap<>(w.crypto());
            for (final var e : approved.entrySet()) {
                final var list = new ArrayList<>(w.crypto().get(e.getKey()));
                int idx = -1;
                for (int k = 0; k < list.size(); k++) {
                    if (list.get(k).spenderId().equals(w.payer())) {
                        idx = k;
                        break;
                    }
                }
                if (idx < 0) {
                    reasons.add(SPENDER_DOES_NOT_HAVE_ALLOWANCE);
                    continue;
                }
                final long left = list.get(idx).amount() + e.getValue();
                if (left < 0) {
                    reasons.add(AMOUNT_EXCEEDS_ALLOWANCE);
                    continue;
                }
                if (left == 0) list.remove(idx);
                else list.set(idx, list.get(idx).copyBuilder().amount(left).build());
                expectedCrypto.put(e.getKey(), list);
            }

            final var desc = "seed=" + iterSeed + " payer=" + w.payer().accountNum() + " xfers=" + xfers;
            final var ctx = new TransferContextImpl(handleContext);
            ResponseCodeEnum status = OK;
            try {
                new AdjustHbarChangesStep(op, w.payer(), idFactory).doIn(ctx);
            } catch (final HandleException e) {
                status = e.getStatus();
            }
            if (status == OK) {
                assertThat(reasons)
                        .withFailMessage("[allowance-hbar] ACCEPTED BUT SHOULD REJECT %s: %s", reasons, desc)
                        .isEmpty();
                for (final var id : accts) {
                    final var after = writableAccountStore.get(id);
                    assertThat(big(after.tinybarBalance()))
                            .withFailMessage("[allowance-hbar] BALANCE MISMATCH for %s: %s", id.accountNum(), desc)
                            .isEqualTo(expectedBal.get(id));
                    assertThat(after.cryptoAllowances())
                            .withFailMessage("[allowance-hbar] ALLOWANCE MISMATCH for %s: expected %s got %s; %s",
                                    id.accountNum(), expectedCrypto.get(id), after.cryptoAllowances(), desc)
                            .isEqualTo(expectedCrypto.get(id));
                    assertThat(after.tokenAllowances())
                            .withFailMessage("[allowance-hbar] TOKEN ALLOWANCES TOUCHED for %s: %s", id.accountNum(), desc)
                            .isEqualTo(w.tokens().get(id));
                }
                accepted++;
                if (!approved.isEmpty()) approvedSpends++;
            } else {
                assertThat(reasons)
                        .withFailMessage("[allowance-hbar] SPURIOUS REJECTION %s: %s", status, desc)
                        .isNotEmpty();
                assertThat(reasons)
                        .withFailMessage("[allowance-hbar] UNEXPECTED REJECT CODE %s (predicted %s): %s", status, reasons, desc)
                        .contains(status);
                rejections.merge(status.protoName(), 1, Integer::sum);
            }
        }
        System.out.printf(
                "[allowance-hbar] oracle agreed on %d iterations: %d accepted (%d spending an allowance), rejected %s (seed=0x%X)%n",
                iterations, accepted, approvedSpends, rejections, seed);
        assertThat(approvedSpends).withFailMessage("[allowance-hbar] VACUOUS: no accepted allowance spends").isPositive();
        assertThat(rejections).withFailMessage("[allowance-hbar] VACUOUS: no rejections").isNotEmpty();
    }

    // ------------------------------------------------------------------------------------------------- tokens

    @Test
    @DisplayName("ALLOWANCE: fungible transfers spend exactly the payer's token allowance and never overdraw")
    void tokenAllowanceSpend() {
        final int iterations = Integer.decode(cfg("ALLOWANCE_ITERATIONS", "20000"));
        final long seed = Long.decode(cfg("CONSERVATION_SEED", "0xC0FFEE"));
        final var rng = new Random(seed);
        int accepted = 0;
        int approvedSpends = 0;
        final Map<String, Integer> rejections = new TreeMap<>();
        for (int i = 0; i < iterations; i++) {
            final long iterSeed = rng.nextLong();
            final var r = new Random(iterSeed);
            final var w = randomWorld(r);
            install(w);
            final var xfers = randomTransfers(r, w.tokenBal());
            final var ttl = TokenTransferList.newBuilder()
                    .token(fungibleTokenId)
                    .transfers(xfers)
                    .build();

            // Oracle.
            final var reasons = EnumSet.noneOf(ResponseCodeEnum.class);
            for (final var aa : xfers) {
                if (w.frozen().get(aa.accountID())) reasons.add(ACCOUNT_FROZEN_FOR_TOKEN);
                if (!w.kyc().get(aa.accountID())) reasons.add(ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN);
            }
            final Map<AccountID, BigInteger> expectedBal = new HashMap<>();
            for (final var id : accts) expectedBal.put(id, big(w.tokenBal().get(id)));
            for (final var aa : xfers) expectedBal.merge(aa.accountID(), big(aa.amount()), BigInteger::add);
            for (final var e : expectedBal.entrySet()) {
                if (e.getValue().signum() < 0) reasons.add(INSUFFICIENT_TOKEN_BALANCE);
            }
            final var approved = approvedDebits(xfers);
            final Map<AccountID, List<AccountFungibleTokenAllowance>> expectedTok = new HashMap<>(w.tokens());
            for (final var e : approved.entrySet()) {
                final var list = new ArrayList<>(w.tokens().get(e.getKey()));
                int idx = -1;
                for (int k = 0; k < list.size(); k++) {
                    final var a = list.get(k);
                    if (a.spenderId().equals(w.payer()) && a.tokenId().equals(fungibleTokenId)) {
                        idx = k;
                        break;
                    }
                }
                if (idx < 0) {
                    reasons.add(SPENDER_DOES_NOT_HAVE_ALLOWANCE);
                    continue;
                }
                final long left = list.get(idx).amount() + e.getValue();
                if (left < 0) {
                    reasons.add(AMOUNT_EXCEEDS_ALLOWANCE);
                    continue;
                }
                if (left == 0) list.remove(idx);
                else list.set(idx, list.get(idx).copyBuilder().amount(left).build());
                expectedTok.put(e.getKey(), list);
            }
            // Positive-balance counter: +1 when a relation goes 0 -> positive, -1 when positive -> 0.
            final Map<AccountID, Integer> expectedPositive = new HashMap<>(w.numPositive());
            final Map<AccountID, Long> net = new HashMap<>();
            for (final var aa : xfers) net.merge(aa.accountID(), aa.amount(), Math::addExact);
            for (final var e : net.entrySet()) {
                final long before = w.tokenBal().get(e.getKey());
                final long after = expectedBal.get(e.getKey()).longValue();
                if (e.getValue() < 0 && after == 0) expectedPositive.merge(e.getKey(), -1, Integer::sum);
                else if (e.getValue() > 0 && before == 0) expectedPositive.merge(e.getKey(), 1, Integer::sum);
            }

            final var desc = "seed=" + iterSeed + " payer=" + w.payer().accountNum() + " xfers=" + xfers;
            final var ctx = new TransferContextImpl(handleContext);
            ResponseCodeEnum status = OK;
            try {
                new AdjustFungibleTokenChangesStep(List.of(ttl), w.payer()).doIn(ctx);
            } catch (final HandleException e) {
                status = e.getStatus();
            }
            if (status == OK) {
                assertThat(reasons)
                        .withFailMessage("[allowance-token] ACCEPTED BUT SHOULD REJECT %s: %s", reasons, desc)
                        .isEmpty();
                for (final var id : accts) {
                    final var rel = writableTokenRelStore.get(id, fungibleTokenId);
                    final var acct = writableAccountStore.get(id);
                    assertThat(big(rel.balance()))
                            .withFailMessage("[allowance-token] BALANCE MISMATCH for %s: %s", id.accountNum(), desc)
                            .isEqualTo(expectedBal.get(id));
                    assertThat(acct.tokenAllowances())
                            .withFailMessage("[allowance-token] ALLOWANCE MISMATCH for %s: expected %s got %s; %s",
                                    id.accountNum(), expectedTok.get(id), acct.tokenAllowances(), desc)
                            .isEqualTo(expectedTok.get(id));
                    assertThat(acct.cryptoAllowances())
                            .withFailMessage("[allowance-token] CRYPTO ALLOWANCES TOUCHED for %s: %s", id.accountNum(), desc)
                            .isEqualTo(w.crypto().get(id));
                    assertThat(acct.numberPositiveBalances())
                            .withFailMessage("[allowance-token] POSITIVE-BALANCE COUNTER MISMATCH for %s: %s",
                                    id.accountNum(), desc)
                            .isEqualTo(expectedPositive.get(id));
                }
                accepted++;
                if (!approved.isEmpty()) approvedSpends++;
            } else {
                assertThat(reasons)
                        .withFailMessage("[allowance-token] SPURIOUS REJECTION %s: %s", status, desc)
                        .isNotEmpty();
                assertThat(reasons)
                        .withFailMessage("[allowance-token] UNEXPECTED REJECT CODE %s (predicted %s): %s", status, reasons, desc)
                        .contains(status);
                rejections.merge(status.protoName(), 1, Integer::sum);
            }
        }
        System.out.printf(
                "[allowance-token] oracle agreed on %d iterations: %d accepted (%d spending an allowance), rejected %s (seed=0x%X)%n",
                iterations, accepted, approvedSpends, rejections, seed);
        assertThat(approvedSpends).withFailMessage("[allowance-token] VACUOUS: no accepted allowance spends").isPositive();
        assertThat(rejections).withFailMessage("[allowance-token] VACUOUS: no rejections").isNotEmpty();
    }

    private static String cfg(final String env, final String fallback) {
        final String e = System.getenv(env);
        return e != null && !e.isBlank() ? e : fallback;
    }
}
