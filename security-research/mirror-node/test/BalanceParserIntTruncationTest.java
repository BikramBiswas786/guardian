// SPDX-License-Identifier: Apache-2.0
package org.hiero.mirror.importer.reader.balance.line;

// Evidence test for the realm/account int-truncation defect (see ISSUE-balance-parser-int-overflow.md).
// Runs the REAL AccountBalanceLineParserV2/V1 and shows that an account number above Integer.MAX_VALUE causes the
// whole balance line to be rejected (InvalidDatasetException) -> that account's authoritative balance is dropped
// from the snapshot. This is the "missing authoritative record" shape; see the issue for scope/severity framing.

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hiero.mirror.importer.exception.InvalidDatasetException;
import org.junit.jupiter.api.Test;

class BalanceParserIntTruncationTest {

    private static final long TIMESTAMP = 1596340377922333444L;
    private static final long INT_MAX = 2147483647L; // 2^31 - 1
    private static final long ABOVE_INT_MAX = 2147483648L; // 2^31

    private final AccountBalanceLineParserV2 v2 = new AccountBalanceLineParserV2();
    private final AccountBalanceLineParserV1 v1 = new AccountBalanceLineParserV1();

    @Test
    void v2_accountNumberAtIntMaxParses_butAboveIntMaxIsDropped() {
        // control: the largest int-representable account number parses fine
        final var ok = v2.parse("0,0," + INT_MAX + ",100", TIMESTAMP);
        assertThat(ok.getId().getAccountId().getNum()).isEqualTo(INT_MAX);

        // defect: one above Integer.MAX_VALUE is a valid int64 account, yet the line is rejected -> balance dropped
        assertThatThrownBy(() -> v2.parse("0,0," + ABOVE_INT_MAX + ",100", TIMESTAMP))
                .isInstanceOf(InvalidDatasetException.class);
    }

    @Test
    void v1_accountNumberAboveIntMaxIsDropped() {
        assertThatThrownBy(() -> v1.parse("0,0," + ABOVE_INT_MAX + ",100", TIMESTAMP))
                .isInstanceOf(InvalidDatasetException.class);
    }
}
