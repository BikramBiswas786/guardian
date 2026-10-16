// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.exec.systemcontracts.hts.update.address_0x167;

// =====================================================================================================================
// SEVERITY-DETERMINING probe for GHSA-q776-f4m7-g642, by EXECUTION.
//
// The bounty question: does a degenerate updateTokenKeys call produce an INCORRECT AUTHORITATIVE record (a wrong key
// actually written to ledger state) — which would map to the Medium "incorrect ... records exported to mirror nodes
// ... authoritative ledger state" — or only a misleading SUCCESS response code (derived/informational, out of scope)?
//
// This decodes each degenerate input with the REAL 0x167 decoder on UNPATCHED main and inspects the TransactionBody
// it produces. If the body carries NO key fields, then handle() applies nothing: the authoritative token key-state is
// unchanged (correct), and only the response code is wrong -> NOT an authoritative-record defect. If the body carries
// an unexpected key, that would be an authoritative-integrity finding.
// =====================================================================================================================

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.FUNGIBLE_TOKEN_HEADLONG_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.token.TokenUpdateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167.UpdateDecoder;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167.UpdateKeysTranslator;
import java.math.BigInteger;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UpdateKeysSeverityProbeTest {
    private static final Address ZERO_ADDRESS = Address.wrap("0x0000000000000000000000000000000000000000");

    @Mock
    private HtsCallAttempt attempt;

    @Mock
    private AddressIdConverter addressIdConverter;

    @Mock
    private HederaNativeOperations nativeOperations;

    private final UpdateDecoder subject = new UpdateDecoder();

    @BeforeEach
    void setUp() {
        lenient().when(attempt.addressIdConverter()).thenReturn(addressIdConverter);
        lenient().when(attempt.nativeOperations()).thenReturn(nativeOperations);
        lenient().when(nativeOperations.entityIdFactory()).thenReturn(entityIdFactory);
        lenient()
                .when(addressIdConverter.convert(ZERO_ADDRESS))
                .thenReturn(AccountID.newBuilder().accountNum(0).build());
        lenient().when(addressIdConverter.convert(FUNGIBLE_TOKEN_HEADLONG_ADDRESS)).thenReturn(OWNER_ID);
    }

    private static Tuple tokenKey(final long keyType, final Address contractKey) {
        return Tuple.of(
                BigInteger.valueOf(keyType), Tuple.of(Boolean.FALSE, contractKey, new byte[0], new byte[0], ZERO_ADDRESS));
    }

    /** An entry whose key material is a concrete ed25519 key (non-empty, so it does not hit the Key.DEFAULT guard). */
    private static Tuple tokenKeyEd(final long keyType, final byte fill) {
        final byte[] ed = new byte[32];
        java.util.Arrays.fill(ed, fill);
        return Tuple.of(BigInteger.valueOf(keyType), Tuple.of(Boolean.FALSE, ZERO_ADDRESS, ed, new byte[0], ZERO_ADDRESS));
    }

    private TokenUpdateTransactionBody decodeBody(final Tuple... keys) {
        final var encoded = Bytes.wrapByteBuffer(
                UpdateKeysTranslator.TOKEN_UPDATE_KEYS_FUNCTION.encodeCallWithArgs(FUNGIBLE_TOKEN_HEADLONG_ADDRESS, keys));
        lenient().when(attempt.input()).thenReturn(encoded);
        final TransactionBody txn = subject.decodeTokenUpdateKeys(attempt);
        return txn == null ? null : txn.tokenUpdateOrThrow();
    }

    private static List<Key> allKeyFields(final TokenUpdateTransactionBody op) {
        return java.util.stream.Stream.of(
                        op.adminKey(),
                        op.kycKey(),
                        op.freezeKey(),
                        op.wipeKey(),
                        op.supplyKey(),
                        op.feeScheduleKey(),
                        op.pauseKey(),
                        op.metadataKey())
                .filter(k -> k != null)
                .toList();
    }

    @Test
    void degenerateInputsCarryNoKeyChange_soAuthoritativeStateIsNotCorrupted() {
        // Each of these is a q776 "SUCCESS no-op" case on unpatched main. If the decoded body carries any key field,
        // handle() would write it to ledger state (an authoritative-record change). Assert it carries NONE.
        final long[] degenerateKeyTypes = {0L, 0x80L, 0x100L, 0x8000L};
        for (final long kt : degenerateKeyTypes) {
            final var op = decodeBody(tokenKey(kt, FUNGIBLE_TOKEN_HEADLONG_ADDRESS));
            if (op != null) {
                assertThat(allKeyFields(op))
                        .withFailMessage(
                                "AUTHORITATIVE CHANGE from degenerate keyType=0x%X: body carries key fields %s",
                                kt, allKeyFields(op))
                        .isEmpty();
            }
        }
        // Empty array: same question.
        final var emptyOp = decodeBody();
        if (emptyOp != null) {
            assertThat(allKeyFields(emptyOp))
                    .withFailMessage("AUTHORITATIVE CHANGE from empty key list: %s", allKeyFields(emptyOp))
                    .isEmpty();
        }
    }

    @Test
    void duplicateAdminKeyResolvesToAnOwnerSubmittedValue_notAttackerControlled() {
        // Two admin-key (0x01) entries with different contract-key material. Whichever wins, it must be one of the
        // two values the (authorized) caller submitted -- i.e. the stored authoritative key is not corrupted into
        // some third/unexpected value. This checks there is no authoritative-integrity escalation hiding in scenario F.
        final var k1 = tokenKeyEd(0x01, (byte) 0x11);
        final var k2 = tokenKeyEd(0x01, (byte) 0x22);
        final var op = decodeBody(k1, k2);
        if (op != null && op.adminKey() != null) {
            final var admin = op.adminKey();
            final byte[] e1 = new byte[32];
            java.util.Arrays.fill(e1, (byte) 0x11);
            final byte[] e2 = new byte[32];
            java.util.Arrays.fill(e2, (byte) 0x22);
            final boolean isOneSubmitted = admin.ed25519() != null
                    && (java.util.Arrays.equals(admin.ed25519().toByteArray(), e1)
                            || java.util.Arrays.equals(admin.ed25519().toByteArray(), e2));
            assertThat(isOneSubmitted)
                    .withFailMessage("duplicate admin resolved to a value the caller did NOT submit: %s", admin)
                    .isTrue();
        }
    }
}
