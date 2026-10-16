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
        // Assessment reads tokens via the readable store; point it at the writable store holding the schedules below.
        given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(writableTokenStore);
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
        // Assessment reads tokens via the readable store; point it at the writable store holding the schedules below.
        given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(writableTokenStore);
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
    // RANDOMIZED multi-level conservation. Random fee schedules on fungible tokens A, B, C and the NFT, with fixed fees
    // denominated across tokens (A->B->C->A chains and cycles), so assessment runs 3+ levels deep and also hits the
    // depth limit. Each accepted assessment is checked against three invariants:
    //   1. conservation: every asset nets to 0 across all returned levels;
    //   2. no unexpected beneficiary: only the fee collector may end up better off than in the given op;
    //   3. record == ledger: per (collector, asset), the assessed fees put in the record equal what the collector
    //      actually received.
    // HandleException rejections are legitimate and only counted; any other exception is reported as UNEXPECTED.
    // Override with env CONSERVATION_MULTILEVEL_ITERATIONS / CONSERVATION_SEED (or the matching -D properties).
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
        // Assessment reads tokens through the readable store; point it at the writable store we put schedules into
        // (as the sibling tests do). Without this the random schedules are never seen.
        given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(writableTokenStore);
        final var baseNets = netsByAssetAndAccount(List.of(replacedOp));

        final var rng = new java.util.Random(masterSeed);
        int accepted = 0;
        int rejected = 0;
        int unexpected = 0;
        int maxLevels = 0;
        int deep = 0; // accepted assessments with 3+ levels
        int charged = 0;
        final java.util.Map<String, Integer> rejectionCodes = new java.util.TreeMap<>();
        final java.util.Map<Integer, Integer> levelHistogram = new java.util.TreeMap<>();
        for (int i = 0; i < iterations; i++) {
            final long seed = rng.nextLong();
            final var r = new java.util.Random(seed);
            final var desc = new StringBuilder("seed=").append(seed);

            final var feesA = randomFungibleFees(r, fungibleTokenId, desc.append(" A["));
            desc.append(']');
            final var feesB = randomFungibleFees(r, fungibleTokenIDB, desc.append(" B["));
            desc.append(']');
            final var feesC = randomFungibleFees(r, fungibleTokenIDC, desc.append(" C["));
            desc.append(']');
            final var nftFees = new java.util.ArrayList<com.hedera.hapi.node.transaction.CustomFee>();
            if (r.nextBoolean()) {
                final long den = 1 + r.nextInt(100);
                final long num = r.nextInt((int) den + 1);
                final var royalty = royaltyFee.copyBuilder()
                        .exchangeValueFraction(com.hedera.hapi.node.base.Fraction.newBuilder()
                                .numerator(num)
                                .denominator(den)
                                .build());
                desc.append(" NFT[royalty=").append(num).append('/').append(den);
                if (r.nextInt(4) == 0) {
                    royalty.fallbackFee((FixedFee) null);
                    desc.append(",noFallback]");
                } else {
                    final var fb = randomFixedFee(r, desc.append(",fallback:"));
                    royalty.fallbackFee(fb);
                    desc.append(']');
                }
                nftFees.add(withRoyaltyFee(royalty.build()));
            }

            writableTokenStore.put(fungibleWithNoKyc.copyBuilder().customFees(feesA).build());
            writableTokenStore.put(fungibleTokenB.copyBuilder().customFees(feesB).build());
            writableTokenStore.put(fungibleTokenC.copyBuilder().customFees(feesC).build());
            writableTokenStore.put(nonFungibleWithNoKyc.copyBuilder().customFees(nftFees).build());

            final var transferContext = new TransferContextImpl(handleContext, false, false);
            final List<CryptoTransferTransactionBody> ops;
            try {
                // A fresh step per iteration: CustomFeeAssessmentStep keeps a level counter that is never reset.
                ops = new CustomFeeAssessmentStep(replacedOp).assessCustomFees(transferContext);
            } catch (final com.hedera.node.app.spi.workflows.HandleException e) {
                rejected++;
                rejectionCodes.merge(e.getStatus().protoName(), 1, Integer::sum);
                continue;
            } catch (final RuntimeException e) {
                unexpected++;
                if (unexpected <= 5) {
                    System.out.printf("[multilevel-random] UNEXPECTED %s: %s (%s)%n", e.getClass().getName(),
                            e.getMessage(), desc);
                }
                continue;
            }
            try {
                assertConservedAcrossLevels(ops, 0L);
                assertNoUnexpectedBeneficiary(baseNets, netsByAssetAndAccount(ops));
                assertRecordMatchesLedger(baseNets, netsByAssetAndAccount(ops), transferContext.getAssessedCustomFees());
            } catch (final AssertionError e) {
                throw new AssertionError("Randomized multi-level invariant break: " + desc, e);
            }
            accepted++;
            maxLevels = Math.max(maxLevels, ops.size());
            levelHistogram.merge(ops.size(), 1, Integer::sum);
            if (ops.size() >= 3) {
                deep++;
            }
            if (!transferContext.getAssessedCustomFees().isEmpty()) {
                charged++;
            }
        }
        System.out.printf(
                "[multilevel-random] conservation held across %d accepted assessments, %d charging a fee, %d with 3+ levels"
                        + " (levels: %s; %d rejected: %s; %d unexpected exceptions; max levels=%d; seed=0x%X)%n",
                accepted, charged, deep, levelHistogram, rejected, rejectionCodes, unexpected, maxLevels, masterSeed);
        // Guard against a vacuous pass: schedules must be accepted, many must charge, some must go 3+ levels deep,
        // and some must be rejected (cycles past the depth limit), which proves the random schedules are being read.
        assertThat(accepted)
                .withFailMessage("[multilevel-random] VACUOUS: only %d of %d schedules accepted", accepted, iterations)
                .isGreaterThan(iterations / 10);
        assertThat(charged)
                .withFailMessage("[multilevel-random] VACUOUS: only %d accepted assessments charged a fee", charged)
                .isGreaterThan(accepted / 10);
        assertThat(deep)
                .withFailMessage("[multilevel-random] VACUOUS: no assessment reached 3+ levels (levels: %s)", levelHistogram)
                .isPositive();
        assertThat(rejected)
                .withFailMessage("[multilevel-random] VACUOUS: no schedule was rejected; random schedules may be ignored")
                .isPositive();
    }

    /** 0..3 random fees for a fungible token: fixed (hbar or any of A/B/C) or fractional; ~1 in 8 amounts are huge. */
    private java.util.List<com.hedera.hapi.node.transaction.CustomFee> randomFungibleFees(
            final java.util.Random r, final TokenID self, final StringBuilder desc) {
        final var fees = new java.util.ArrayList<com.hedera.hapi.node.transaction.CustomFee>();
        final int n = r.nextInt(4);
        for (int f = 0; f < n; f++) {
            if (r.nextInt(3) < 2) {
                fees.add(withFixedFee(randomFixedFee(r, desc)));
            } else {
                final long den = 1 + r.nextInt(1_000);
                final long num = r.nextInt((int) den + 1);
                final long min = r.nextInt(51);
                final long max = r.nextBoolean() ? 0 : min + r.nextInt(500);
                final boolean net = r.nextBoolean();
                fees.add(withFractionalFee(fractionalFee
                        .copyBuilder()
                        .fractionalAmount(com.hedera.hapi.node.base.Fraction.newBuilder()
                                .numerator(num)
                                .denominator(den)
                                .build())
                        .minimumAmount(min)
                        .maximumAmount(max)
                        .netOfTransfers(net)
                        .build()));
                desc.append(" frac=").append(num).append('/').append(den).append(",min=").append(min)
                        .append(",max=").append(max).append(",net=").append(net);
            }
        }
        return fees;
    }

    /** A fixed fee in hbar or in one of A/B/C. */
    private FixedFee randomFixedFee(final java.util.Random r, final StringBuilder desc) {
        final long amt = r.nextInt(8) == 0 ? (Long.MAX_VALUE >> (1 + r.nextInt(8))) : 1 + r.nextInt(5_000);
        final var b = FixedFee.newBuilder().amount(amt);
        switch (r.nextInt(4)) {
            case 0 -> desc.append(" fixed=").append(amt).append("hbar");
            case 1 -> {
                b.denominatingTokenId(fungibleTokenId);
                desc.append(" fixed=").append(amt).append("A");
            }
            case 2 -> {
                b.denominatingTokenId(fungibleTokenIDB);
                desc.append(" fixed=").append(amt).append("B");
            }
            default -> {
                b.denominatingTokenId(fungibleTokenIDC);
                desc.append(" fixed=").append(amt).append("C");
            }
        }
        return b.build();
    }

    /** Net amount per "asset|account" summed over all ops (asset keys match assertConservedAcrossLevels). */
    private static Map<String, Long> netsByAssetAndAccount(final List<CryptoTransferTransactionBody> ops) {
        final Map<String, Long> nets = new HashMap<>();
        for (final var op : ops) {
            if (op.transfers() != null) {
                for (final AccountAmount aa : op.transfers().accountAmounts()) {
                    nets.merge(HBAR_KEY + "|" + aa.accountID(), aa.amount(), Math::addExact);
                }
            }
            for (final var ttl : op.tokenTransfers()) {
                for (final AccountAmount aa : ttl.transfers()) {
                    nets.merge("TOKEN:" + ttl.token() + "|" + aa.accountID(), aa.amount(), Math::addExact);
                }
            }
        }
        return nets;
    }

    /** Only the fee collector may end up better off than in the given op. */
    private void assertNoUnexpectedBeneficiary(final Map<String, Long> base, final Map<String, Long> after) {
        final String collectorSuffix = "|" + feeCollectorId;
        for (final var e : after.entrySet()) {
            final long delta = e.getValue() - base.getOrDefault(e.getKey(), 0L);
            if (delta > 0 && !e.getKey().endsWith(collectorSuffix)) {
                throw new AssertionError("UNEXPECTED BENEFICIARY: " + e.getKey() + " gained " + delta
                        + " beyond the given op. base=" + base + " after=" + after);
            }
        }
    }

    /** Per (collector, asset), the assessed fees in the record must equal what the collector actually received. */
    private void assertRecordMatchesLedger(
            final Map<String, Long> base,
            final Map<String, Long> after,
            final List<com.hedera.hapi.node.transaction.AssessedCustomFee> assessed) {
        final Map<String, Long> recorded = new HashMap<>();
        for (final var fee : assessed) {
            final String asset = fee.tokenId() == null ? HBAR_KEY : "TOKEN:" + fee.tokenId();
            recorded.merge(asset + "|" + fee.feeCollectorAccountId(), fee.amount(), Math::addExact);
        }
        final java.util.Set<String> keys = new java.util.HashSet<>(recorded.keySet());
        for (final var k : after.keySet()) {
            if (k.endsWith("|" + feeCollectorId)) keys.add(k);
        }
        for (final var k : keys) {
            final long received = after.getOrDefault(k, 0L) - base.getOrDefault(k, 0L);
            final long inRecord = recorded.getOrDefault(k, 0L);
            if (received != inRecord) {
                throw new AssertionError("RECORD/LEDGER MISMATCH for " + k + ": record says " + inRecord
                        + ", ledger moved " + received + ". recorded=" + recorded + " assessed=" + assessed);
            }
        }
    }

    private static String multiLevelCfg(final String property, final String env, final String fallback) {
        final String p = System.getProperty(property);
        if (p != null && !p.isBlank()) return p;
        final String e = System.getenv(env);
        return e != null && !e.isBlank() ? e : fallback;
    }
