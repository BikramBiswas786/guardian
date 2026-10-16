// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.staking;

import static com.hedera.node.app.service.token.impl.handlers.staking.StakingUtilities.roundedToHbar;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.handlers.staking.StakeInfoHelper;
import com.hedera.node.app.service.token.impl.handlers.staking.StakePeriodManager;
import com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsDistributor;
import com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsHandlerImpl;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Randomized test of the HIP-406 "staked to me" bookkeeping in
 * {@link StakingRewardsHandlerImpl#adjustStakedToMeForAccountStakees}.
 *
 * <p>Invariant: for every live (non-deleted) account Y, {@code Y.stakedToMe} equals the sum of
 * {@code roundedToHbar(X.tinybarBalance)} over all accounts X whose {@code stakedAccountId} is Y. A drift inflates or
 * deflates the stake that earns rewards and the stake weight of Y's node.
 *
 * <p>Each iteration builds a random staking graph (accounts staked to other accounts, to nodes, or not at all, plus some
 * already-deleted stakees) in which the invariant holds, applies random changes to a random subset of accounts the way
 * a transaction would leave them in the store (balance changes, re-staking account->account / account->node /
 * node->account / to none, new accounts, deletions that zero the balance), runs the real method, and checks the
 * invariant exactly for every account that is live after the transaction. Deleted stakees are excluded by design: since
 * #27178 their stakedToMe is intentionally left untouched.
 *
 * <p>Iterations: env {@code STAKING_ITERATIONS} (default 20000); seed: env {@code CONSERVATION_SEED}.
 */
class StakedToMePropertyTest extends CryptoTokenHandlerTestBase {
    private static final long HBAR = 100_000_000L;
    private static final int N = 14;

    private StakingRewardsHandlerImpl subject;

    @Override
    @BeforeEach
    public void setUp() {
        super.setUp();
        refreshWritableStores();
        // adjustStakedToMeForAccountStakees uses none of these collaborators.
        subject = new StakingRewardsHandlerImpl(
                mock(StakingRewardsDistributor.class),
                mock(StakePeriodManager.class),
                mock(StakeInfoHelper.class),
                mock(EntityIdFactory.class));
    }

    private static long randomBalance(final Random r) {
        return switch (r.nextInt(6)) {
            case 0 -> 0L;
            case 1 -> r.nextInt((int) HBAR); // under one hbar: rounds to 0
            case 2 -> HBAR * (1 + r.nextInt(1000)); // exact hbars
            case 3 -> HBAR * (1 + r.nextInt(1000)) + 1 + r.nextInt((int) HBAR - 1); // hbars plus a fraction
            case 4 -> (r.nextLong() >>> 1) % 800_000_000_000_000_000L; // large, within supply / 6
            default -> 1L + r.nextInt(10_000);
        };
    }

    /** Random stake target: -1 = none, -2 - n = node n, otherwise index of the stakee account. */
    private static int randomTarget(final Random r, final int self, final int n) {
        final int k = r.nextInt(10);
        if (k < 2) return -1;
        if (k < 4) return -2 - r.nextInt(3);
        int t = r.nextInt(n);
        return t == self ? -1 : t;
    }

    /**
     * Applies a stake target. "None" uses one of the encodings production leaves behind: never set, a null staked
     * account id (CryptoUpdate's 0.0.0 sentinel), or staked node id -1 (the node sentinel).
     */
    private static Account withTarget(
            final Account.Builder b, final int target, final List<AccountID> ids, final Random r) {
        if (target >= 0) b.stakedAccountId(ids.get(target));
        else if (target <= -2) b.stakedNodeId((long) (-2 - target));
        else if (r.nextBoolean()) b.stakedAccountId((AccountID) null);
        else b.stakedNodeId(-1L);
        return b.build();
    }

    /** Independent reading of the stake target: -1 none, -2 - n node n, else stakee index. */
    private static int targetOf(final Account a, final Map<AccountID, Integer> index) {
        switch (a.stakedId().kind()) {
            case STAKED_ACCOUNT_ID -> {
                final AccountID id = a.stakedAccountId();
                if (id == null) return -1;
                final Integer i = index.get(id);
                return i == null ? -1 : i;
            }
            case STAKED_NODE_ID -> {
                final long n = a.stakedNodeIdOrElse(-1L);
                return n < 0 ? -1 : -2 - (int) n;
            }
            default -> {
                return -1;
            }
        }
    }

    @Test
    @DisplayName("STAKING: stakedToMe equals the rounded balances staked to each live account after any change")
    void stakedToMeMatchesStakers() {
        final int iterations = Integer.decode(cfg("STAKING_ITERATIONS", "20000"));
        final long seed = Long.decode(cfg("CONSERVATION_SEED", "0xC0FFEE"));
        final var rng = new Random(seed);
        final List<AccountID> ids = new ArrayList<>();
        final Map<AccountID, Integer> index = new HashMap<>();
        for (int i = 0; i < N + 4; i++) {
            final var id = idFactory.newAccountId(900_000L + i);
            ids.add(id);
            index.put(id, i);
        }
        // Stub once and swap the backing state per iteration: re-stubbing a mock 300k times is very slow.
        final var current = new java.util.concurrent.atomic.AtomicReference<
                com.swirlds.state.test.fixtures.MapWritableKVState<AccountID, Account>>();
        given(writableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willAnswer(inv -> current.get());
        int checkedAccounts = 0;
        int changedStakedToMe = 0;
        final Map<String, Integer> mutations = new TreeMap<>();
        // Time cap so a slow environment cannot hang CI; the summary reports how many iterations ran.
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
        int done = 0;
        for (int it = 0; it < iterations && System.nanoTime() < deadline; it++) {
            done++;
            final long iterSeed = rng.nextLong();
            final var r = new Random(iterSeed);

            // 1. A consistent random world over the first N ids (the last 4 are reserved for new accounts).
            final long[] bal = new long[N];
            final int[] target = new int[N];
            final boolean[] deleted = new boolean[N];
            for (int i = 0; i < N; i++) {
                deleted[i] = r.nextInt(12) == 0;
                bal[i] = deleted[i] ? 0 : randomBalance(r);
                target[i] = randomTarget(r, i, N);
            }
            final long[] stakedToMe = new long[N];
            for (int x = 0; x < N; x++) {
                if (target[x] >= 0) stakedToMe[target[x]] += roundedToHbar(bal[x]);
            }
            final var stateBuilder = emptyWritableAccountStateBuilder();
            for (int i = 0; i < N; i++) {
                // A stakee that was deleted earlier keeps whatever stale value it had.
                final long stm = deleted[i] ? r.nextInt(1000) * HBAR : stakedToMe[i];
                final var ab = Account.newBuilder()
                        .accountId(ids.get(i))
                        .tinybarBalance(bal[i])
                        .stakedToMe(stm)
                        .deleted(deleted[i]);
                stateBuilder.value(
                        ids.get(i),
                        target[i] == -1 && r.nextInt(3) == 0 ? ab.build() : withTarget(ab, target[i], ids, r));
            }
            current.set(stateBuilder.build());
            if (it % 10_000 == 0) clearInvocations(writableStates); // keep Mockito's invocation log small
            final var store = new WritableAccountStore(writableStates, writableEntityCounters);

            // 2. Random transaction effects on a random subset of live accounts, plus maybe new accounts.
            final var desc = new StringBuilder("seed=").append(iterSeed);
            final int nChanges = 1 + r.nextInt(5);
            for (int c = 0; c < nChanges; c++) {
                final int i = r.nextInt(N);
                if (deleted[i]) continue;
                final var cur = store.get(ids.get(i));
                final var b = cur.copyBuilder();
                final String kind;
                switch (r.nextInt(5)) {
                    case 0 -> {
                        b.tinybarBalance(randomBalance(r));
                        kind = "balance";
                    }
                    case 1 -> {
                        b.tinybarBalance(randomBalance(r));
                        withTarget(b, randomTarget(r, i, N), ids, r);
                        kind = "balance+restake";
                    }
                    case 2 -> {
                        withTarget(b, randomTarget(r, i, N), ids, r);
                        kind = "restake";
                    }
                    case 3 -> {
                        b.deleted(true).tinybarBalance(0);
                        kind = "delete";
                    }
                    default -> {
                        b.tinybarBalance(cur.tinybarBalance() + 1 + r.nextInt(5)); // sub-hbar wiggle
                        kind = "wiggle";
                    }
                }
                final var next = b.build();
                store.put(next);
                mutations.merge(kind, 1, Integer::sum);
                desc.append(' ').append(kind).append('@').append(i);
            }
            final int newAccounts = r.nextInt(3);
            for (int k = 0; k < newAccounts; k++) {
                final int ni = N + k;
                store.put(withTarget(
                        Account.newBuilder()
                                .accountId(ids.get(ni))
                                .tinybarBalance(randomBalance(r))
                                .stakedToMe(0),
                        randomTarget(r, ni, N),
                        ids,
                        r));
                mutations.merge("create", 1, Integer::sum);
                desc.append(" create@").append(ni);
            }
            final Map<AccountID, Long> before = new HashMap<>();
            for (int i = 0; i < N; i++) before.put(ids.get(i), store.get(ids.get(i)).stakedToMe());

            // 3. The real bookkeeping step.
            subject.adjustStakedToMeForAccountStakees(store);

            // 4. Exact invariant for every live account after the transaction.
            final Map<Integer, Long> expected = new HashMap<>();
            final Set<Integer> live = new HashSet<>();
            for (int i = 0; i < N + 4; i++) {
                final var a = store.get(ids.get(i));
                if (a == null) continue;
                if (!a.deleted() && i < N) live.add(i);
                final int t = targetOf(a, index);
                if (t >= 0) expected.merge(t, roundedToHbar(a.tinybarBalance()), Long::sum);
            }
            for (final int y : live) {
                final var acct = store.get(ids.get(y));
                final long want = expected.getOrDefault(y, 0L);
                assertThat(acct.stakedToMe())
                        .withFailMessage(
                                "[staking-stakedtome] STAKED-TO-ME DRIFT for account index %d: stakedToMe=%d, "
                                        + "sum of rounded staker balances=%d (diff %d); %s",
                                y, acct.stakedToMe(), want, acct.stakedToMe() - want, desc)
                        .isEqualTo(want);
                checkedAccounts++;
                if (acct.stakedToMe() != before.get(ids.get(y))) changedStakedToMe++;
            }
        }
        System.out.printf(
                "[staking-stakedtome] invariant held on %d of %d iterations: %d live-account checks, %d stakedToMe updates; mutations %s (seed=0x%X)%n",
                done, iterations, checkedAccounts, changedStakedToMe, mutations, seed);
        assertThat(changedStakedToMe)
                .withFailMessage("[staking-stakedtome] VACUOUS: stakedToMe never changed")
                .isPositive();
    }

    private static String cfg(final String env, final String fallback) {
        final String e = System.getenv(env);
        return e != null && !e.isBlank() ? e : fallback;
    }
}
