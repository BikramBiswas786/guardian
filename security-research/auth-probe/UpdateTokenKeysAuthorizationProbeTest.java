// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

// =====================================================================================================================
// DYNAMIC authorization probe for the token key-rotation path (relates to GHSA-q776-f4m7-g642).
//
// Question answered by EXECUTION (not by reading the source): when an UNPRIVILEGED payer — an account holding none of
// a token's keys — submits a TokenUpdate that rotates the token's supply key (and, separately, its admin key), does
// the REAL TokenUpdateHandler.preHandle require a non-payer key (the admin/role key) so the transaction cannot be
// authorized by the payer alone?
//
//   - If preHandle requires the admin/role key  -> an unauthorized caller is blocked. q776 is NOT an auth bypass
//     (it is the response-code/validation defect its own advisory describes), and does NOT reach the program's
//     High tier ("authorizing transactions without approval from signers/owners").
//   - If preHandle requires NOTHING             -> an unauthorized caller could rotate keys with only its own
//     signature. That WOULD be a High-tier authorization bypass.
//
// This runs the real handler against FakePreHandleContext and inspects requiredNonPayerKeys().
// =====================================================================================================================

import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.asToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.handlers.TokenUpdateHandler;
import com.hedera.node.app.service.token.impl.validators.TokenUpdateValidator;
import com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.Test;

class UpdateTokenKeysAuthorizationProbeTest {

    private static Key ed(final String tag) {
        return Key.newBuilder().ed25519(Bytes.wrap(String.format("%-32s", tag).getBytes())).build();
    }

    private final AccountID payerId = asAccount(0L, 0L, 4001); // unprivileged: holds none of the token's keys
    private final AccountID treasuryId = asAccount(0L, 0L, 7777);
    private final TokenID tokenId = asToken(3000);

    private final Key payerKey = ed("PAYER");
    private final Key adminKey = ed("ADMIN");
    private final Key supplyKey = ed("SUPPLY");
    private final Key newSupplyKey = ed("NEW_SUPPLY");
    private final Key newAdminKey = ed("NEW_ADMIN");

    private FakePreHandleContext runPreHandle(final TokenUpdateTransactionBody.Builder update) throws Exception {
        final var token = Token.newBuilder()
                .tokenId(tokenId)
                .treasuryAccountId(treasuryId)
                .adminKey(adminKey)
                .supplyKey(supplyKey)
                .build();
        final var payerAccount =
                Account.newBuilder().accountId(payerId).key(payerKey).build();

        final var accountStore = mock(ReadableAccountStore.class);
        when(accountStore.getAccountById(payerId)).thenReturn(payerAccount);
        final var tokenStore = mock(ReadableTokenStore.class);
        when(tokenStore.get(tokenId)).thenReturn(token);

        final var txn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(payerId).build())
                .tokenUpdate(update.token(tokenId).build())
                .build();

        final var context = new FakePreHandleContext(accountStore, txn);
        context.registerStore(ReadableTokenStore.class, tokenStore);
        final var subject = new TokenUpdateHandler(mock(TokenUpdateValidator.class));
        subject.preHandle(context);
        return context;
    }

    /** True iff {@code target} appears anywhere in the (possibly threshold/list) key structure. */
    private static boolean structureContains(final Key key, final Key target) {
        final Deque<Key> stack = new ArrayDeque<>();
        stack.push(key);
        while (!stack.isEmpty()) {
            final var k = stack.pop();
            if (k == null) continue;
            if (k.equals(target)) return true;
            if (k.thresholdKey() != null && k.thresholdKey().keys() != null) {
                k.thresholdKey().keys().keys().forEach(stack::push);
            }
            if (k.keyList() != null && k.keyList().keys() != null) {
                k.keyList().keys().forEach(stack::push);
            }
        }
        return false;
    }

    private static boolean anyRequiredContains(final FakePreHandleContext ctx, final Key target) {
        return ctx.requiredNonPayerKeys().stream().anyMatch(k -> structureContains(k, target));
    }

    @Test
    void unprivilegedSupplyKeyRotationRequiresAdminOrSupplyKey() throws Exception {
        final var ctx = runPreHandle(TokenUpdateTransactionBody.newBuilder().supplyKey(newSupplyKey));
        // The payer holds none of these keys, so a non-empty required set means it cannot self-authorize.
        assertThat(ctx.requiredNonPayerKeys())
                .withFailMessage("AUTH BYPASS: supply-key rotation required no non-payer key")
                .isNotEmpty();
        assertThat(anyRequiredContains(ctx, adminKey) || anyRequiredContains(ctx, supplyKey))
                .withFailMessage("AUTH BYPASS: supply-key rotation did not require the admin or supply key; required=%s",
                        ctx.requiredNonPayerKeys())
                .isTrue();
    }

    @Test
    void unprivilegedAdminKeyRotationRequiresOldAdminKey() throws Exception {
        final var ctx = runPreHandle(TokenUpdateTransactionBody.newBuilder().adminKey(newAdminKey));
        assertThat(ctx.requiredNonPayerKeys())
                .withFailMessage("AUTH BYPASS: admin-key rotation required no non-payer key")
                .isNotEmpty();
        assertThat(anyRequiredContains(ctx, adminKey))
                .withFailMessage("AUTH BYPASS: admin-key rotation did not require the old admin key; required=%s",
                        ctx.requiredNonPayerKeys())
                .isTrue();
    }
}
