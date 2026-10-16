// SPDX-License-Identifier: Apache-2.0
// =====================================================================================================================
// MULTI-LEVEL CONSERVATION ADDITIONS for CustomFeeAssessmentStep  (hiero-consensus-node, in-scope Immunefi asset)
//
// HOW TO USE: this is NOT a standalone file. Paste the @Test methods and the two helpers below INTO the existing
//   hedera-node/hedera-token-service-impl/src/test/java/.../handlers/transfer/CustomFeeAssessmentStepTest.java
// (inside the class body). That test already extends StepsBase and compiles/runs with all the store/config
// scaffolding — reusing it is why these additions are trustworthy. Add these imports to that file if absent:
//
//   import static org.assertj.core.api.Assertions.assertThat;
//   import com.hedera.hapi.node.base.AccountAmount;
//   import com.hedera.hapi.node.base.TokenID;
//   import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
//   import java.util.HashMap;
//   import java.util.List;
//   import java.util.Map;
//
// Then run:
//   ./gradlew :app-service-token-impl:test --tests "*CustomFeeAssessmentStepTest"
//
// STATUS: written against main @ 75bc55e (2026-10-16); NOT executed by its author (the audit sandbox has no
// Maven/Gradle artifact access). It reuses setups the surrounding test already exercises, so the risk is low, but
// verify it compiles/runs before relying on it.
//
// INVARIANT (multi-level value conservation): assessCustomFees() returns the given op plus one op per triggered
// fee level. Every assessed fee is a debit+credit pair. Therefore, summing EVERY account amount across ALL returned
// ops — per asset (hbar, and each token id) — must still equal the net the input started with. The base's givenTxn()
// transfers are balanced (net 0 per asset), so every asset must net to 0 after assessment across all levels. A
// non-zero net means a debit or credit was dropped or duplicated while folding levels = value created/destroyed =
// a bounty-grade bug. The assertion only FAILS if the real pipeline breaks conservation; it never asserts a break.
// =====================================================================================================================

    private static final String HBAR_KEY = "HBAR";

    /** Sum every account amount across every returned op, per asset, and assert each asset nets to `expectedNet`. */
    private void assertConservedAcrossLevels(
            final List<CryptoTransferTransactionBody> ops, final long expectedNet) {
        final Map<String, Long> netByAsset = new HashMap<>();
        for (final var op : ops) {
            // hbar legs
            if (op.transfers() != null) {
                for (final AccountAmount aa : op.transfers().accountAmounts()) {
                    netByAsset.merge(HBAR_KEY, aa.amount(), Math::addExact);
                }
            }
            // fungible token legs (NFT ownership changes move no fungible units, so they don't affect conservation)
            for (final var ttl : op.tokenTransfers()) {
                final TokenID tokenId = ttl.token();
                for (final AccountAmount aa : ttl.transfers()) {
                    netByAsset.merge("TOKEN:" + tokenId, aa.amount(), Math::addExact);
                }
            }
        }
        for (final var e : netByAsset.entrySet()) {
            assertThat(e.getValue())
                    .withFailMessage(
                            "CONSERVATION BREAK across %d level(s): asset %s nets to %d, expected %d. Per-asset nets: %s",
                            ops.size(), e.getKey(), e.getValue(), expectedNet, netByAsset)
                    .isEqualTo(expectedNet);
        }
    }

    @Test
    @DisplayName("CONSERVATION: fungible fixed+fractional fee and NFT royalty+HTS fallback conserve value across levels")
    void conservationFixedFractionalRoyaltyHtsFallback() {
        givenTxn();
        // Match the sibling tests: 'customfees' is a local, not a field.
        final var customfees = List.of(withFixedFee(htsFixedFee));
        writableTokenStore.put(
                fungibleWithNoKyc.copyBuilder().customFees(customfees).build());
        writableTokenStore.put(nonFungibleWithNoKyc
                .copyBuilder()
                .customFees(List.of(withRoyaltyFee(
                        royaltyFee.copyBuilder().fallbackFee(htsFixedFee).build())))
                .build());

        final var ops = subject.assessCustomFees(new TransferContextImpl(handleContext, false, false));

        // Every asset moved by any level must still net to zero.
        assertConservedAcrossLevels(ops, 0L);
    }

    @Test
    @DisplayName("CONSERVATION: NFT royalty with hbar fallback conserves value across levels")
    void conservationRoyaltyHbarFallback() {
        givenTxn();
        final var customfees = List.of(withFixedFee(htsFixedFee));
        writableTokenStore.put(
                fungibleWithNoKyc.copyBuilder().customFees(customfees).build());
        writableTokenStore.put(nonFungibleWithNoKyc
                .copyBuilder()
                .customFees(List.of(withRoyaltyFee(
                        royaltyFee.copyBuilder().fallbackFee(hbarFixedFee).build())))
                .build());

        final var ops = subject.assessCustomFees(new TransferContextImpl(handleContext, false, false));

        assertConservedAcrossLevels(ops, 0L);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // NOTE on field names used above, all inherited from CustomFeeAssessmentStepTest / StepsBase / the base fixtures:
    //   subject                     -> CustomFeeAssessmentStep (set in setUp)
    //   handleContext               -> mocked DispatchHandleContext
    //   writableTokenStore          -> WritableTokenStore (protected in CryptoTokenHandlerTestBase)
    //   fungibleWithNoKyc,
    //   nonFungibleWithNoKyc        -> set by getReplacedOp() during setUp
    //   royaltyFee,
    //   htsFixedFee, hbarFixedFee   -> seeded FixedFee fixtures (fields). "customfees" is a LOCAL in each test (see above).
    //   withRoyaltyFee(...)         -> CryptoTokenHandlerTestBase helper
    // If any field name differs in your checkout, copy the exact one from the sibling test methods
    // (e.g. hbarFixedFeeAndRoyaltyFeeWithFallback) — they use the same fixtures.
    // ---------------------------------------------------------------------------------------------------------------
