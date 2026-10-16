// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.staking;

// =====================================================================================================================
// STAKING-REWARD overflow / correctness probe (paid tier: "over/underpayment of staking rewards").
//
// rewardFor() computes  totalStake/HBARS_TO_TINYBARS * (rewardSumDelta)  as UNGUARDED long*long (no safe-multiply).
// Hypothesis: a large stake across many periods overflows long and wraps -> wrong reward (over/underpayment).
//
// This runs the REAL StakeRewardCalculatorImpl.computeRewardFromDetails and compares the long result to an exact
// BigInteger computation, plus asserts reward >= 0. Two ranges:
//   (A) ATTACKER-REACHABLE: stake in [0, total HBAR supply = 50e9 HBAR], rate = network default (6849), <=365 periods.
//       If long != BigInteger here, that is a real, reachable over/underpayment finding.
//   (B) EXTREME: pushes stake far past supply to locate the actual overflow threshold and show it is unreachable.
// =====================================================================================================================

import static com.hedera.node.app.service.token.Units.HBARS_TO_TINYBARS;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.node.app.service.token.impl.handlers.staking.StakeRewardCalculatorImpl;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class StakingRewardOverflowPropertyTest {

    private static final int ITERATIONS = Integer.decode(cfg("staking.iterations", "STAKING_ITERATIONS", "500000"));
    private static final long MASTER_SEED = Long.decode(cfg("staking.seed", "STAKING_SEED", "0x57A41E"));
    private static final long TOTAL_SUPPLY_TINYBARS = 50_000_000_000L * HBARS_TO_TINYBARS; // 50e9 HBAR
    private static final long DEFAULT_PER_HBAR_RATE = 6849L; // StakingConfig.perHbarRewardRate default
    private static final int MAX_PERIODS = 365; // rewardHistory.numStoredPeriods default

    private static String cfg(String p, String e, String d) {
        final String pv = System.getProperty(p);
        if (pv != null && !pv.isBlank()) return pv.trim();
        final String ev = System.getenv(e);
        return (ev != null && !ev.isBlank()) ? ev.trim() : d;
    }

    /** Monotonic non-increasing cumulative reward-sum history (index 0 newest/largest), as built on-ledger. */
    private static List<Long> history(final Random r, final long perHbarRate, final int periods) {
        final long[] cum = new long[periods + 1];
        long acc = 0;
        // Build oldest->newest increments, then store newest-first.
        final long[] inc = new long[periods + 1];
        for (int i = 0; i <= periods; i++) inc[i] = Math.floorMod(r.nextLong(), perHbarRate + 1);
        final List<Long> newestFirst = new ArrayList<>(periods + 1);
        // cumulative from oldest; newest-first list has largest at index 0
        long running = 0;
        final long[] cumOldestFirst = new long[periods + 1];
        for (int i = 0; i <= periods; i++) {
            running += inc[i];
            cumOldestFirst[i] = running;
        }
        for (int i = periods; i >= 0; i--) newestFirst.add(cumOldestFirst[i]);
        return newestFirst;
    }

    private static long totalStake(final Account a) {
        return a.tinybarBalance() + a.stakedToMe();
    }

    /** Exact re-implementation of rewardFor in BigInteger (no overflow), for differential comparison. */
    private static BigInteger exactReward(final Account a, final List<Long> h, final long rewardFrom) {
        final BigInteger first = BigInteger.valueOf(h.get(0));
        final BigInteger fromSum = BigInteger.valueOf(h.get((int) rewardFrom));
        final BigInteger t2t = BigInteger.valueOf(HBARS_TO_TINYBARS);
        if (a.stakeAtStartOfLastRewardedPeriod() != -1) {
            final BigInteger fromMinus1 = BigInteger.valueOf(h.get((int) rewardFrom - 1));
            return BigInteger.valueOf(a.stakeAtStartOfLastRewardedPeriod())
                    .divide(t2t)
                    .multiply(fromMinus1.subtract(fromSum))
                    .add(BigInteger.valueOf(totalStake(a)).divide(t2t).multiply(first.subtract(fromMinus1)));
        } else {
            return BigInteger.valueOf(totalStake(a)).divide(t2t).multiply(first.subtract(fromSum));
        }
    }

    private long runRange(final String name, final long maxStakeTinybars, final long perHbarRate, final boolean expectMatch) {
        final Random rng = new Random(MASTER_SEED);
        long mismatches = 0;
        long firstOverflowStakeHbar = -1;
        for (int i = 0; i < ITERATIONS; i++) {
            final int periods = 2 + rng.nextInt(MAX_PERIODS - 1); // rewardFrom in [1, periods-1]
            final long rewardFrom = 1 + Math.floorMod(rng.nextLong(), periods - 1);
            final long bal = Math.floorMod(rng.nextLong(), maxStakeTinybars);
            final long stakedToMe = Math.floorMod(rng.nextLong(), Math.max(1, maxStakeTinybars - bal));
            final boolean twoStep = rng.nextBoolean();
            final long lastRewarded = twoStep ? Math.floorMod(rng.nextLong(), bal + stakedToMe + 1) : -1L;
            final long currentStakePeriod = 1000L;
            final long effectiveStart = currentStakePeriod - 1 - rewardFrom;

            final var account = Account.newBuilder()
                    .tinybarBalance(bal)
                    .stakedToMe(stakedToMe)
                    .stakeAtStartOfLastRewardedPeriod(lastRewarded)
                    .stakePeriodStart(effectiveStart)
                    .declineReward(false)
                    .stakedNodeId(0L)
                    .build();
            final var h = history(rng, perHbarRate, periods);
            final var node = StakingNodeInfo.newBuilder().nodeNumber(0).rewardSumHistory(h).build();

            final long got = StakeRewardCalculatorImpl.computeRewardFromDetails(
                    account, node, currentStakePeriod, effectiveStart);
            final BigInteger exact = exactReward(account, h, rewardFrom);

            final boolean matches = BigInteger.valueOf(got).equals(exact);
            if (!matches) {
                mismatches++;
                if (firstOverflowStakeHbar < 0) firstOverflowStakeHbar = totalStake(account) / HBARS_TO_TINYBARS;
            }
            if (expectMatch) {
                assertThat(matches)
                        .withFailMessage(
                                "STAKING OVERFLOW [%s] reachable: got=%d exact=%s totalStakeHbar=%d rewardFrom=%d",
                                name, got, exact, totalStake(account) / HBARS_TO_TINYBARS, rewardFrom)
                        .isTrue();
                assertThat(got)
                        .withFailMessage("NEGATIVE REWARD [%s]: got=%d (underpayment/sign error)", name, got)
                        .isGreaterThanOrEqualTo(0L);
            }
        }
        System.out.printf("[%s] %d iters, %d mismatches; first overflow at totalStake=%d HBAR (supply=%d HBAR)%n",
                name, ITERATIONS, mismatches, firstOverflowStakeHbar, TOTAL_SUPPLY_TINYBARS / HBARS_TO_TINYBARS);
        return mismatches;
    }

    @Test
    void attackerReachableRangeNeverOverflowsAndRewardNonNegative() {
        // stake capped at total HBAR supply, default on-ledger reward rate: this is everything an attacker could stake.
        runRange("reachable", TOTAL_SUPPLY_TINYBARS, DEFAULT_PER_HBAR_RATE, true);
    }

    @Test
    void overflowThresholdIsFarBeyondTotalSupply() {
        // Allow stake up to 200x total supply to locate where long overflow begins; assert it is beyond supply,
        // i.e. not attacker-reachable. (Does not assert match; this range intentionally includes the overflow.)
        final long mism = runRange("extreme", TOTAL_SUPPLY_TINYBARS * 200, DEFAULT_PER_HBAR_RATE, false);
        // Sanity: the extreme range should exercise the overflow at least occasionally (otherwise the probe is vacuous).
        assertThat(mism).withFailMessage("extreme range never overflowed; probe may be miscalibrated").isPositive();
    }
}
