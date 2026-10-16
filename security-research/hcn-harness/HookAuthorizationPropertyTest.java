// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.transfer.hooks;

// =====================================================================================================================
// HOOK AUTHORIZATION PROPERTIES (hiero-consensus-node, allowance hooks / HIP-1195)
//
// In CryptoTransfer preHandle, a debit that carries an allowance hook needs NO signature from the debited account:
// authorization is delegated entirely to the hook. These properties check, against the real code, that this
// delegation cannot be satisfied by anything other than the owner's hook explicitly returning true:
//
//  1. outcomeGatesAuthorization: HookDispatchUtils.dispatchExecution must accept only when the hook dispatch
//     finished with SUCCESS *and* its return data ABI-decodes to `true`. Any other status, a `false` return, or
//     empty/short return data must be rejected with REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK. Non-canonical encodings
//     (e.g. 0x...02, trailing bytes) are reported as LENIENT, not failed: they still require the owner's own hook
//     code to return them.
//  2. gateIsComplete: CryptoTransferValidator.validateSemantics must reject a hook in ANY of the six hook positions
//     with HOOKS_NOT_ENABLED when hooks are disabled, and with HOOKS_EXECUTIONS_REQUIRE_TOP_LEVEL_CRYPTO_TRANSFER for
//     any category other than USER. A hook that slips past this gate would skip the debited account's signature
//     without running a hook in a context that enforces it.
//
// Iterations / seed: env HOOK_AUTH_ITERATIONS / CONSERVATION_SEED (Gradle does not forward -D to the test JVM).
// =====================================================================================================================

import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOKS_EXECUTIONS_REQUIRE_TOP_LEVEL_CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOKS_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.esaulpaugh.headlong.abi.Function;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.EvmHookCall;
import com.hedera.hapi.node.base.HookCall;
import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.hooks.HookExecution;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.entityid.impl.AppEntityIdFactory;
import com.hedera.node.app.service.token.HookDispatchUtils;
import com.hedera.node.app.service.token.impl.handlers.transfer.hooks.HooksABI;
import com.hedera.node.app.service.token.impl.validators.CryptoTransferValidator;
import com.hedera.node.app.service.token.records.HookDispatchStreamBuilder;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.HooksConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

public class HookAuthorizationPropertyTest {

    private static final int ITERATIONS = Integer.decode(cfg("hook.auth.iterations", "HOOK_AUTH_ITERATIONS", "20000"));
    private static final long MASTER_SEED = Long.decode(cfg("conservation.seed", "CONSERVATION_SEED", "0xC0FFEE"));
    private static final Function[] HOOK_FUNCTIONS = {HooksABI.FN_ALLOW, HooksABI.FN_ALLOW_PRE, HooksABI.FN_ALLOW_POST};

    private final Configuration config = HederaTestConfigBuilder.createConfig();
    private final AppEntityIdFactory entityIdFactory = new AppEntityIdFactory(config);
    private final AccountID owner = AccountID.newBuilder().accountNum(1234L).build();
    private final AccountID payer = AccountID.newBuilder().accountNum(5678L).build();

    // ----------------------------------------------------------------------------------------- 1. outcome gating
    @Test
    void outcomeGatesAuthorization() {
        final var context = mock(HandleContext.class);
        final var builder = mock(HookDispatchStreamBuilder.class);
        given(context.payer()).willReturn(payer);
        doReturn(builder).when(context).dispatch(any());

        final var rng = new Random(MASTER_SEED);
        final var codes = ResponseCodeEnum.values();
        final Map<String, Integer> lenientAccepted = new TreeMap<>();
        int accepted = 0;
        int rejected = 0;
        final List<String> violations = new ArrayList<>();

        for (int i = 0; i < ITERATIONS; i++) {
            final long seed = rng.nextLong();
            final var r = new Random(seed);
            final ResponseCodeEnum status = r.nextInt(3) == 0 ? codes[r.nextInt(codes.length)] : SUCCESS;
            final OutputCase oc = OutputCase.values()[r.nextInt(OutputCase.values().length)];
            final byte[] out = oc.bytes(r);
            final Function fn = HOOK_FUNCTIONS[r.nextInt(HOOK_FUNCTIONS.length)];
            given(builder.status()).willReturn(status);
            given(builder.getEvmCallResult()).willReturn(Bytes.wrap(out));

            final HookExecution execution = HookExecution.newBuilder()
                    .hookEntityId(HookEntityId.newBuilder().accountId(owner).build())
                    .call(HookCall.newBuilder()
                            .hookId(1L + r.nextInt(1000))
                            .evmHookCall(EvmHookCall.newBuilder()
                                    .gasLimit(100_000L)
                                    .data(Bytes.wrap(new byte[] {1, 2, 3, 4}))
                                    .build())
                            .build())
                    .build();

            boolean allowed;
            ResponseCodeEnum rejection = null;
            try {
                HookDispatchUtils.dispatchExecution(context, execution, fn, entityIdFactory, r.nextBoolean());
                allowed = true;
            } catch (final HandleException e) {
                allowed = false;
                rejection = e.getStatus();
            }

            // Independent oracle: the only unambiguous approval is SUCCESS + the 32-byte ABI encoding of `true`.
            final boolean mustAllow = status == SUCCESS && oc == OutputCase.CANONICAL_TRUE;
            final boolean mustReject = status != SUCCESS
                    || oc == OutputCase.CANONICAL_FALSE
                    || oc == OutputCase.EMPTY
                    || oc == OutputCase.SHORT
                    || (oc == OutputCase.RANDOM && (out.length < 32 || firstWordIsZero(out)));
            final String where = "seed=" + seed + " status=" + status + " output=" + oc + " len=" + out.length
                    + " fn=" + fn.getName();

            if (allowed) {
                accepted++;
                if (mustReject) violations.add("UNAUTHORIZED ACCEPT: " + where);
                if (!mustAllow && !mustReject) lenientAccepted.merge(oc.name(), 1, Integer::sum);
            } else {
                rejected++;
                if (mustAllow) violations.add("WRONG REJECT of canonical approval: " + where);
                if (rejection != REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK) {
                    violations.add("WRONG REJECTION CODE " + rejection + ": " + where);
                }
            }
        }
        System.out.printf(
                "[hook-outcome] %d accepted, %d rejected; lenient-but-owner-controlled accepts: %s; violations: %d (seed=0x%X)%n",
                accepted, rejected, lenientAccepted, violations.size(), MASTER_SEED);
        assertThat(violations)
                .withFailMessage("HOOK AUTHORIZATION VIOLATION (%d), first: %s", violations.size(),
                        violations.isEmpty() ? "" : violations.get(0))
                .isEmpty();
        assertThat(accepted).withFailMessage("[hook-outcome] VACUOUS: nothing was ever accepted").isPositive();
        assertThat(rejected).withFailMessage("[hook-outcome] VACUOUS: nothing was ever rejected").isPositive();
    }

    private static boolean firstWordIsZero(final byte[] out) {
        for (int i = 0; i < 32 && i < out.length; i++) {
            if (out[i] != 0) return false;
        }
        return true;
    }

    private enum OutputCase {
        CANONICAL_TRUE,
        CANONICAL_FALSE,
        EMPTY,
        SHORT,
        NON_CANONICAL_TRUE, // 32-byte word with value 2..255
        ALL_ONES_WORD,
        TRUE_WITH_TRAILING,
        RANDOM;

        byte[] bytes(final Random r) {
            return switch (this) {
                case CANONICAL_TRUE -> word((byte) 1);
                case CANONICAL_FALSE -> word((byte) 0);
                case EMPTY -> new byte[0];
                case SHORT -> Arrays.copyOf(word((byte) 1), 1 + r.nextInt(31));
                case NON_CANONICAL_TRUE -> word((byte) (2 + r.nextInt(254)));
                case ALL_ONES_WORD -> {
                    final var w = new byte[32];
                    Arrays.fill(w, (byte) 0xFF);
                    yield w;
                }
                case TRUE_WITH_TRAILING -> {
                    final var w = Arrays.copyOf(word((byte) 1), 32 + 1 + r.nextInt(64));
                    for (int i = 32; i < w.length; i++) w[i] = (byte) r.nextInt(256);
                    yield w;
                }
                case RANDOM -> {
                    final var w = new byte[r.nextInt(97)];
                    r.nextBytes(w);
                    yield w;
                }
            };
        }

        private static byte[] word(final byte last) {
            final var w = new byte[32];
            w[31] = last;
            return w;
        }
    }

    // ---------------------------------------------------------------------------------------------- 2. gate is complete
    @Test
    void gateIsComplete() {
        final var validator = new CryptoTransferValidator(entityIdFactory);
        final var categories = HandleContext.TransactionCategory.values();
        // Built once: constructing a full node configuration is slow.
        final Configuration enabledConfig = HederaTestConfigBuilder.create()
                .withValue("hooks.hooksEnabled", true)
                .getOrCreateConfig();
        final Configuration disabledConfig = HederaTestConfigBuilder.create()
                .withValue("hooks.hooksEnabled", false)
                .getOrCreateConfig();
        final var rng = new Random(MASTER_SEED ^ 0x5A5AL);
        final List<String> violations = new ArrayList<>();
        int hookCases = 0;
        int noHookCases = 0;

        for (int i = 0; i < ITERATIONS; i++) {
            final long seed = rng.nextLong();
            final var r = new Random(seed);
            final boolean enabled = r.nextBoolean();
            final var category = categories[r.nextInt(categories.length)];
            final int position = r.nextInt(7); // 0..5 = a hook position, 6 = no hook
            final var op = opWithHookAt(position, r);
            final Configuration testConfig = enabled ? enabledConfig : disabledConfig;

            ResponseCodeEnum outcome = null;
            try {
                validator.validateSemantics(
                        op,
                        testConfig.getConfigData(LedgerConfig.class),
                        testConfig.getConfigData(AccountsConfig.class),
                        testConfig.getConfigData(HooksConfig.class),
                        category,
                        payer);
            } catch (final HandleException e) {
                outcome = e.getStatus();
            }

            final String where = "seed=" + seed + " position=" + position + " enabled=" + enabled + " category="
                    + category + " outcome=" + outcome;
            if (position < 6) {
                hookCases++;
                if (!enabled && outcome != HOOKS_NOT_ENABLED) {
                    violations.add("HOOK PASSED DISABLED GATE: " + where);
                } else if (enabled
                        && category != HandleContext.TransactionCategory.USER
                        && outcome != HOOKS_EXECUTIONS_REQUIRE_TOP_LEVEL_CRYPTO_TRANSFER) {
                    violations.add("HOOK PASSED TOP-LEVEL GATE: " + where);
                }
            } else {
                noHookCases++;
                if (outcome == HOOKS_NOT_ENABLED || outcome == HOOKS_EXECUTIONS_REQUIRE_TOP_LEVEL_CRYPTO_TRANSFER) {
                    violations.add("FALSE POSITIVE on hook-free op: " + where);
                }
            }
        }
        System.out.printf(
                "[hook-gate] %d hook cases, %d hook-free cases; violations: %d (seed=0x%X)%n",
                hookCases, noHookCases, violations.size(), MASTER_SEED);
        assertThat(violations)
                .withFailMessage("HOOK GATE VIOLATION (%d), first: %s", violations.size(),
                        violations.isEmpty() ? "" : violations.get(0))
                .isEmpty();
        assertThat(hookCases).withFailMessage("[hook-gate] VACUOUS: no hook cases").isPositive();
    }

    /** A net-zero transfer with one hook call placed at {@code position} (0..5), or none (6). */
    private CryptoTransferTransactionBody opWithHookAt(final int position, final Random r) {
        final var from = AccountID.newBuilder().accountNum(2000L + r.nextInt(100)).build();
        final var to = AccountID.newBuilder().accountNum(3000L + r.nextInt(100)).build();
        final long amount = 1 + r.nextInt(1_000_000);
        final var hook = HookCall.newBuilder()
                .hookId(1L + r.nextInt(1000))
                .evmHookCall(EvmHookCall.newBuilder()
                        .gasLimit(10_000_000L)
                        .data(Bytes.wrap(new byte[] {1}))
                        .build())
                .build();

        final var debit = AccountAmount.newBuilder().accountID(from).amount(-amount);
        final var credit = AccountAmount.newBuilder().accountID(to).amount(amount);
        final var nft = NftTransfer.newBuilder()
                .senderAccountID(from)
                .receiverAccountID(to)
                .serialNumber(1 + r.nextInt(100));
        final var token = TokenID.newBuilder().tokenNum(9000L).build();
        final var nftToken = TokenID.newBuilder().tokenNum(9001L).build();

        final var op = CryptoTransferTransactionBody.newBuilder();
        switch (position) {
            case 0 -> op.transfers(TransferList.newBuilder()
                    .accountAmounts(debit.preTxAllowanceHook(hook).build(), credit.build())
                    .build());
            case 1 -> op.transfers(TransferList.newBuilder()
                    .accountAmounts(debit.prePostTxAllowanceHook(hook).build(), credit.build())
                    .build());
            case 2 -> op.tokenTransfers(TokenTransferList.newBuilder()
                    .token(token)
                    .transfers(debit.preTxAllowanceHook(hook).build(), credit.build())
                    .build());
            case 3 -> op.tokenTransfers(TokenTransferList.newBuilder()
                    .token(nftToken)
                    .nftTransfers(nft.preTxSenderAllowanceHook(hook).build())
                    .build());
            case 4 -> op.tokenTransfers(TokenTransferList.newBuilder()
                    .token(nftToken)
                    .nftTransfers(nft.prePostTxReceiverAllowanceHook(hook).build())
                    .build());
            case 5 -> op.tokenTransfers(TokenTransferList.newBuilder()
                    .token(nftToken)
                    .nftTransfers(nft.preTxReceiverAllowanceHook(hook).build())
                    .build());
            default -> op.transfers(TransferList.newBuilder()
                    .accountAmounts(debit.build(), credit.build())
                    .build());
        }
        return op.build();
    }

    private static String cfg(final String property, final String env, final String fallback) {
        final String p = System.getProperty(property);
        if (p != null && !p.isBlank()) return p;
        final String e = System.getenv(env);
        return e != null && !e.isBlank() ? e : fallback;
    }
}
