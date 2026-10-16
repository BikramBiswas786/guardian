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

    // =================================================================================================================
    // RANDOMIZED multi-level conservation: random fee schedules on the fungible token and the NFT, run through the real
    // CustomFeeAssessmentStep. Every accepted assessment must net to 0 per asset across all levels. Rejections
    // (HandleException, e.g. insufficient balance or an unassociated collector) are legitimate and only counted.
    // Override with env CONSERVATION_MULTILEVEL_ITERATIONS / CONSERVATION_SEED (Gradle does not forward -D flags to the
    // test JVM; the environment is inherited), or the matching -D system properties when running the JVM directly.
    // =================================================================================================================
    @Test
    @DisplayName("CONSERVATION: randomized fee schedules conserve value across all assessment levels")
    void conservationRandomizedMultiLevel() {
        final int iterations = Integer.decode(multiLevelCfg(
                "conservation.multilevel.iterations", "CONSERVATION_MULTILEVEL_ITERATIONS", "3000"));
        final long masterSeed = Long.decode(multiLevelCfg("conservation.seed", "CONSERVATION_SEED", "0xC0FFEE"));
        // Reuse the op setUp() already built: calling getReplacedOp() again re-runs alias resolution against stores
        // that setUp() already mutated, which fails with ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS before any assessment.
        final CryptoTransferTransactionBody replacedOp;
        try {
            final var opField = CustomFeeAssessmentStep.class.getDeclaredField("op");
            opField.setAccessible(true);
            replacedOp = (CryptoTransferTransactionBody) opField.get(subject);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError("Cannot read the op prepared by setUp()", e);
        }
        final var rng = new java.util.Random(masterSeed);
        int accepted = 0;
        int rejected = 0;
        int maxLevels = 0;
        int charged = 0; // accepted assessments where at least one fee actually moved value
        final java.util.Map<String, Integer> rejectionCodes = new java.util.TreeMap<>();
        for (int i = 0; i < iterations; i++) {
            final long seed = rng.nextLong();
            final var r = new java.util.Random(seed);
            final var desc = new StringBuilder("seed=").append(seed);

            final var fungibleFees = new java.util.ArrayList<com.hedera.hapi.node.transaction.CustomFee>();
            final int nFees = r.nextInt(4); // 0..3 fees on the fungible token
            for (int f = 0; f < nFees; f++) {
                switch (r.nextInt(4)) {
                    case 0 -> {
                        final long amt = 1 + r.nextInt(5_000);
                        fungibleFees.add(withFixedFee(hbarFixedFee.copyBuilder().amount(amt).build()));
                        desc.append(" fixedHbar=").append(amt);
                    }
                    case 1 -> {
                        final long amt = 1 + r.nextInt(200);
                        fungibleFees.add(withFixedFee(htsFixedFee.copyBuilder().amount(amt).build()));
                        desc.append(" fixedHtsSelf=").append(amt);
                    }
                    case 2 -> {
                        final long amt = 1 + r.nextInt(200);
                        fungibleFees.add(withFixedFee(htsFixedFee
                                .copyBuilder()
                                .amount(amt)
                                .denominatingTokenId(fungibleTokenIDB)
                                .build()));
                        desc.append(" fixedHtsOther=").append(amt);
                    }
                    default -> {
                        final long den = 1 + r.nextInt(1_000);
                        final long num = r.nextInt((int) den + 1);
                        final long min = r.nextInt(51);
                        final long max = r.nextBoolean() ? 0 : min + r.nextInt(500);
                        final boolean net = r.nextBoolean();
                        fungibleFees.add(withFractionalFee(fractionalFee
                                .copyBuilder()
                                .fractionalAmount(com.hedera.hapi.node.base.Fraction.newBuilder()
                                        .numerator(num)
                                        .denominator(den)
                                        .build())
                                .minimumAmount(min)
                                .maximumAmount(max)
                                .netOfTransfers(net)
                                .build()));
                        desc.append(" fractional=").append(num).append('/').append(den)
                                .append(",min=").append(min).append(",max=").append(max).append(",net=").append(net);
                    }
                }
            }

            final var nftFees = new java.util.ArrayList<com.hedera.hapi.node.transaction.CustomFee>();
            if (r.nextBoolean()) {
                final long den = 1 + r.nextInt(100);
                final long num = r.nextInt((int) den + 1);
                final var royalty = royaltyFee.copyBuilder()
                        .exchangeValueFraction(com.hedera.hapi.node.base.Fraction.newBuilder()
                                .numerator(num)
                                .denominator(den)
                                .build());
                switch (r.nextInt(3)) {
                    case 0 -> {
                        royalty.fallbackFee((FixedFee) null);
                        desc.append(" royalty=").append(num).append('/').append(den).append(",noFallback");
                    }
                    case 1 -> {
                        final long amt = 1 + r.nextInt(5_000);
                        royalty.fallbackFee(hbarFixedFee.copyBuilder().amount(amt).build());
                        desc.append(" royalty=").append(num).append('/').append(den).append(",hbarFallback=").append(amt);
                    }
                    default -> {
                        final long amt = 1 + r.nextInt(200);
                        royalty.fallbackFee(htsFixedFee.copyBuilder().amount(amt).build());
                        desc.append(" royalty=").append(num).append('/').append(den).append(",htsFallback=").append(amt);
                    }
                }
                nftFees.add(withRoyaltyFee(royalty.build()));
            }

            writableTokenStore.put(fungibleWithNoKyc.copyBuilder().customFees(fungibleFees).build());
            writableTokenStore.put(nonFungibleWithNoKyc.copyBuilder().customFees(nftFees).build());

            final List<CryptoTransferTransactionBody> ops;
            try {
                // A fresh step per iteration: CustomFeeAssessmentStep keeps a level counter that is never reset.
                ops = new CustomFeeAssessmentStep(replacedOp)
                        .assessCustomFees(new TransferContextImpl(handleContext, false, false));
            } catch (final com.hedera.node.app.spi.workflows.HandleException e) {
                rejected++;
                rejectionCodes.merge(e.getStatus().protoName(), 1, Integer::sum);
                continue;
            }
            try {
                assertConservedAcrossLevels(ops, 0L);
            } catch (final AssertionError e) {
                throw new AssertionError("Randomized multi-level conservation break: " + desc, e);
            }
            accepted++;
            maxLevels = Math.max(maxLevels, ops.size());
            // A fee was charged if assessment added a level or changed the given op (fractional fees edit level 0).
            if (ops.size() > 1 || !ops.get(0).equals(replacedOp)) {
                charged++;
            }
        }
        System.out.printf(
                "[multilevel-random] conservation held across %d accepted assessments, %d charging a fee (%d rejected: %s; max levels=%d; seed=0x%X)%n",
                accepted, charged, rejected, rejectionCodes, maxLevels, masterSeed);
        // Guard against a vacuous pass: enough schedules must be assessed, and many must actually charge a fee
        // (e.g. a payer that is the token treasury is exempt from its fees, which would make every case trivial).
        assertThat(accepted)
                .withFailMessage("[multilevel-random] VACUOUS: only %d of %d schedules accepted", accepted, iterations)
                .isGreaterThan(iterations / 10);
        assertThat(charged)
                .withFailMessage("[multilevel-random] VACUOUS: only %d accepted assessments charged a fee", charged)
                .isGreaterThan(accepted / 10);
    }

    private static String multiLevelCfg(final String property, final String env, final String fallback) {
        final String p = System.getProperty(property);
        if (p != null && !p.isBlank()) return p;
        final String e = System.getenv(env);
        return e != null && !e.isBlank() ? e : fallback;
    }
