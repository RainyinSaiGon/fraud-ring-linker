package io.github.rainyinsaigon.fraudlinker.takeover;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.clustering.BatchLinker;
import io.github.rainyinsaigon.fraudlinker.clustering.ClusteringConfig;
import io.github.rainyinsaigon.fraudlinker.clustering.Constraints;
import io.github.rainyinsaigon.fraudlinker.clustering.LinkState;
import io.github.rainyinsaigon.fraudlinker.domain.ClustersOutput;
import io.github.rainyinsaigon.fraudlinker.mules.MuleScorer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

import static io.github.rainyinsaigon.fraudlinker.Fixtures.account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TakeoverScorerTest {
    static final Instant T0 = Instant.parse("2026-11-03T08:00:00Z");
    static final TakeoverScorer SCORER = new TakeoverScorer(TakeoverConfig.defaults());
    static final BiPredicate<String, String> NOBODY_RELATED = (a, b) -> false;
    /** Sydney, and Lagos about 15,600 km away. */
    static final double[] HOME = {-33.87, 151.21};
    static final double[] FAR = {6.52, 3.38};

    private int seq;

    Login login(String account, String device, boolean success, Duration after, double[] place) {
        return new Login("l" + seq++, account, device, "10.0.0.1", success, T0.plus(after),
                place == null ? null : place[0], place == null ? null : place[1]);
    }

    /** Ten days of the customer logging in from their own phone at home, at 8 am. */
    List<Login> customer(String account) {
        List<Login> logins = new ArrayList<>();
        for (int day = 0; day < 10; day++) {
            logins.add(login(account, "phone-" + account, true, Duration.ofDays(day), HOME));
        }
        return logins;
    }

    static Duration day(int d, int minutes) {
        return Duration.ofDays(d).plusMinutes(minutes);
    }

    @Test
    void aPhishedLoginFromANewDeviceFarAwayIsFlagged() {
        List<Login> logins = customer("alice");
        // Alice logs in at home; 90 minutes later someone logs in as her from 15,600 km away.
        logins.add(login("alice", "phone-alice", true, day(10, 0), HOME));
        logins.add(login("alice", "attacker", true, day(10, 90), FAR));

        TakeoverScore alice = SCORER.scoreAll(logins, NOBODY_RELATED).get("alice");

        assertThat(alice.flagged()).isTrue();
        assertThat(alice.score()).isEqualTo(0.82); // 1 - 0.6 * 0.3
        assertThat(alice.reasons()).extracting(TakeoverReason::code).containsExactly("new_device", "impossible_travel");
        assertThat(alice.reasons().get(0).describe()).isEqualTo("first login from a new device after 11 logins");
        assertThat(alice.reasons().get(1).describe()).startsWith("15,").contains(" km in 1 h (10,").endsWith(" km/h)");
        assertThat(alice.reachedAt()).isEqualTo(T0.plus(day(10, 90)));
    }

    @Test
    void aGuessedPasswordFromANewDeviceIsFlagged() {
        List<Login> logins = customer("bob");
        for (int i = 0; i < 6; i++) {
            logins.add(login("bob", "attacker", false, day(12, i), null));
        }
        logins.add(login("bob", "attacker", true, day(12, 7), null));

        TakeoverScore bob = SCORER.scoreAll(logins, NOBODY_RELATED).get("bob");

        assertThat(bob.reasons()).extracting(TakeoverReason::code)
                .containsExactly("new_device", "failed_burst_then_success");
        assertThat(bob.reasons().get(1).describe()).isEqualTo("6 failed logins in 5 min, then a success");
        assertThat(bob.flagged()).isTrue();
    }

    @Test
    void credentialStuffingFlagsEveryAccountTheBotGotInto() {
        List<Login> logins = new ArrayList<>();
        for (String victim : List.of("v1", "v2", "v3", "v4")) {
            logins.addAll(customer(victim));
            logins.add(login(victim, "bot", true, day(11, logins.size() % 40), null));
        }

        Map<String, TakeoverScore> scores = SCORER.scoreAll(logins, NOBODY_RELATED);

        for (String victim : List.of("v1", "v2", "v3", "v4")) {
            assertThat(scores.get(victim).reasons()).as(victim).extracting(TakeoverReason::code)
                    .containsExactly("new_device", "shared_device");
            assertThat(scores.get(victim).flagged()).as(victim).isTrue();
        }
        assertThat(scores.get("v1").reasons().get(1).describe()).startsWith("its device logged in to 4 unrelated accounts in ");
    }

    @Test
    void ordinaryEventsOnTheirOwnAreNotFlagged() {
        List<Login> newPhone = customer("carol");
        newPhone.add(login("carol", "new-phone", true, day(10, 0), HOME));
        List<Login> holiday = customer("dan");
        // Sydney to Lagos in two days: a long trip, but possible.
        holiday.add(login("dan", "phone-dan", true, day(11, 0), FAR));
        List<Login> forgotten = customer("erin");
        for (int i = 0; i < 5; i++) {
            forgotten.add(login("erin", "phone-erin", false, day(10, 60 + i), HOME));
        }
        forgotten.add(login("erin", "phone-erin", true, day(10, 70), HOME));
        List<Login> all = new ArrayList<>(newPhone);
        all.addAll(holiday);
        all.addAll(forgotten);

        Map<String, TakeoverScore> scores = SCORER.scoreAll(all, NOBODY_RELATED);

        assertThat(scores.get("carol").score()).isEqualTo(0.4);
        assertThat(scores.get("dan").score()).isZero();
        assertThat(scores.get("dan").reachedAt()).isNull();
        // A forgotten password on the customer's own phone is a burst then success, nothing else.
        assertThat(scores.get("erin").reasons()).extracting(TakeoverReason::code)
                .containsExactly("failed_burst_then_success");
        assertThat(scores.values()).noneMatch(TakeoverScore::flagged);
    }

    @Test
    void signalsCombineOnlyWithinOneIncident() {
        List<Login> logins = customer("fay");
        logins.add(login("fay", "new-phone", true, day(10, 0), HOME));
        // Weeks later, a burst then success on the new phone: two ordinary events, not one incident.
        for (int i = 0; i < 5; i++) {
            logins.add(login("fay", "new-phone", false, day(30, i), HOME));
        }
        logins.add(login("fay", "new-phone", true, day(30, 6), HOME));

        TakeoverScore fay = SCORER.scoreAll(logins, NOBODY_RELATED).get("fay");

        assertThat(fay.score()).isEqualTo(0.7);
        assertThat(fay.flagged()).isFalse();
        assertThat(fay.reasons()).extracting(TakeoverReason::code).containsExactly("failed_burst_then_success");

        // The same two events 23 hours apart are one incident.
        List<Login> close = customer("gus");
        close.add(login("gus", "new-phone", true, day(10, 60), HOME));
        for (int i = 0; i < 5; i++) {
            close.add(login("gus", "new-phone", false, day(11, i), HOME));
        }
        close.add(login("gus", "new-phone", true, day(11, 6), HOME));
        TakeoverScore gus = SCORER.scoreAll(close, NOBODY_RELATED).get("gus");
        assertThat(gus.reasons()).extracting(TakeoverReason::code)
                .containsExactly("new_device", "failed_burst_then_success");
        assertThat(gus.reachedAt()).isEqualTo(T0.plus(day(11, 6)));
    }

    @Test
    void aTripAndAMuchLaterNewPhoneAreNotOneIncident() {
        List<Login> logins = customer("hal");
        // A VPN makes day 2 look like a jump to Lagos; weeks later Hal gets a new phone.
        logins.add(login("hal", "phone-hal", true, day(2, 120), FAR));
        logins.add(login("hal", "new-phone", true, day(30, 0), HOME));

        TakeoverScore hal = SCORER.scoreAll(logins, NOBODY_RELATED).get("hal");

        assertThat(hal.score()).isEqualTo(0.7);
        assertThat(hal.reasons()).extracting(TakeoverReason::code).containsExactly("impossible_travel");
    }

    @Test
    void keepsTheEarliestOfEquallyBadIncidentsAndListsReasonsInAFixedOrder() {
        TakeoverScorer scorer = SCORER;
        List<TakeoverReason> signals = List.of(
                new TakeoverReason.BurstOnly(T0, 5, Duration.ZERO),
                new TakeoverReason.BurstOnly(T0.plus(Duration.ofDays(3)), 5, Duration.ZERO),
                new TakeoverReason.SharedDevice(T0.plus(Duration.ofDays(9)), "bot", 4, Duration.ZERO),
                new TakeoverReason.NewDevice(T0.plus(Duration.ofDays(9)), "bot", 7));

        TakeoverScore worst = scorer.worstIncident("x", signals);

        assertThat(worst.reasons()).extracting(TakeoverReason::code).containsExactly("new_device", "shared_device");
        assertThat(worst.reachedAt()).isEqualTo(T0.plus(Duration.ofDays(9)));
        TakeoverScore tie = scorer.worstIncident("x", signals.subList(0, 2));
        assertThat(tie.reachedAt()).isEqualTo(T0);
        assertThat(tie.score()).isEqualTo(0.3);
    }

    @Test
    void anIncidentWindowEdgeStillCounts() {
        TakeoverReason first = new TakeoverReason.NewDevice(T0, "d", 6);
        TakeoverReason atEdge = new TakeoverReason.BurstThenSuccess(T0.plus(Duration.ofHours(24)), 5, Duration.ZERO);
        TakeoverReason past = new TakeoverReason.BurstThenSuccess(T0.plus(Duration.ofHours(24)).plusSeconds(1), 5,
                Duration.ZERO);
        assertThat(SCORER.worstIncident("x", List.of(first, atEdge)).score()).isEqualTo(0.82);
        assertThat(SCORER.worstIncident("x", List.of(first, past)).score()).isEqualTo(0.7);
    }

    @Test
    void scoringNeverChangesTheClustersAndADeviceSharedInOneClusterIsNotShared() {
        LinkState state = BatchLinker.build(List.of(
                account("mum", "mum@example.com", "", "10.1.0.1", "family-card", T0),
                account("dad", "dad@example.com", "", "10.2.0.1", "family-card", T0),
                account("kid", "kid@example.com", "", "10.3.0.1", "family-card", T0),
                account("gran", "gran@example.com", "", "10.4.0.1", "family-card", T0)),
                Constraints.of(List.of()), ClusteringConfig.defaults());
        ClustersOutput before = state.clusters();
        List<Login> logins = new ArrayList<>();
        for (String person : List.of("mum", "dad", "kid", "gran")) {
            logins.add(login(person, "family-tablet", true, Duration.ofMinutes(logins.size()), null));
        }

        Map<String, TakeoverScore> withClusters = SCORER.scoreAll(logins, MuleScorer.sameCluster(state));
        Map<String, TakeoverScore> withoutClusters = SCORER.scoreAll(logins, NOBODY_RELATED);

        assertThat(state.clusters()).isEqualTo(before);
        state.validateInvariants();
        assertThat(withClusters.values()).allMatch(score -> score.reasons().isEmpty());
        assertThat(withoutClusters.get("mum").reasons()).extracting(TakeoverReason::code).containsExactly("shared_device");
    }

    @Test
    void rejectsDuplicateLoginIdsAndDoesNotDependOnInputOrder() {
        List<Login> logins = customer("alice");
        logins.add(login("alice", "phone-alice", true, day(10, 0), HOME));
        logins.add(login("alice", "attacker", true, day(10, 90), FAR));
        List<Login> reversed = new ArrayList<>(logins);
        Collections.reverse(reversed);
        assertThat(SCORER.scoreAll(reversed, NOBODY_RELATED)).isEqualTo(SCORER.scoreAll(logins, NOBODY_RELATED));

        Login one = new Login("same", "a", null, "ip", true, T0, null, null);
        Login two = new Login("same", "b", null, "ip", true, T0, null, null);
        assertThatThrownBy(() -> SCORER.scoreAll(List.of(one, two), NOBODY_RELATED))
                .isInstanceOf(LinkerException.class).hasMessage("duplicate login_id same");
    }

    @Test
    void appliesCustomWeightsAndRoundsScores() {
        TakeoverScorer custom = new TakeoverScorer(new TakeoverConfig(5, 900, 300, 5, Duration.ofMinutes(15), 4,
                Duration.ofHours(24), Duration.ofHours(24), 0.123456, 0, 0, 0, 0, 0.12));
        List<Login> logins = customer("carol");
        logins.add(login("carol", "new-phone", true, day(10, 0), HOME));
        TakeoverScore carol = custom.scoreAll(logins, NOBODY_RELATED).get("carol");
        assertThat(carol.score()).isEqualTo(0.1235);
        assertThat(carol.flagged()).isTrue();
    }

    @Test
    void aScoreExactlyAtTheThresholdIsFlagged() {
        TakeoverScorer atThreshold = new TakeoverScorer(new TakeoverConfig(5, 900, 300, 5, Duration.ofMinutes(15), 4,
                Duration.ofHours(24), Duration.ofHours(24), 0.4, 0.7, 0.7, 0.3, 0.7, 0.82));
        List<Login> logins = customer("alice");
        logins.add(login("alice", "phone-alice", true, day(10, 0), HOME));
        logins.add(login("alice", "attacker", true, day(10, 90), FAR));
        assertThat(atThreshold.scoreAll(logins, NOBODY_RELATED).get("alice").flagged()).isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "'' | a | a login needs a login_id",
            "l | ' ' | login l needs an account",
    })
    void rejectsLoginsWithoutIds(String id, String account, String message) {
        assertThatThrownBy(() -> new Login(id, account, null, "ip", true, T0, null, null)).hasMessage(message);
    }

    @Test
    void validatesLogins() {
        assertThatThrownBy(() -> new Login(null, "a", null, "ip", true, T0, null, null))
                .hasMessage("a login needs a login_id");
        assertThatThrownBy(() -> new Login("l", null, null, "ip", true, T0, null, null))
                .hasMessage("login l needs an account");
        assertThatThrownBy(() -> new Login("l", "a", null, "ip", true, null, null, null))
                .hasMessage("login l needs a time");
        assertThatThrownBy(() -> new Login("l", "a", null, "ip", true, T0, 1.0, null))
                .hasMessage("login l needs both a latitude and a longitude, or neither");
        assertThatThrownBy(() -> new Login("l", "a", null, "ip", true, T0, null, 1.0))
                .hasMessage("login l needs both a latitude and a longitude, or neither");
        assertThatThrownBy(() -> new Login("l", "a", null, "ip", true, T0, 90.1, 0.0))
                .hasMessage("login l has a location outside the globe");
        assertThatThrownBy(() -> new Login("l", "a", null, "ip", true, T0, 0.0, -180.1))
                .hasMessage("login l has a location outside the globe");
        assertThatThrownBy(() -> new Login("l", "a", null, "ip", true, T0, Double.NaN, 0.0))
                .hasMessage("login l has a location outside the globe");
        Login edge = new Login("l", "a", " ", "ip", true, T0, -90.0, 180.0);
        assertThat(new Login("l", "a", null, "ip", true, T0, 90.0, -180.0).hasLocation()).isTrue();
        assertThat(edge.deviceId()).isNull();
        assertThat(edge.hasLocation()).isTrue();
        assertThat(new Login("l", "a", "d", "ip", true, T0, null, null).hasLocation()).isFalse();
    }

    @Test
    void validatesConfig() {
        Duration w = Duration.ofMinutes(15);
        Duration d = Duration.ofHours(24);
        assertThatThrownBy(() -> new TakeoverConfig(0, 900, 300, 5, w, 4, d, d, .4, .7, .7, .3, .7, .8))
                .hasMessage("a device can only be new after at least 1 earlier login");
        assertThatThrownBy(() -> new TakeoverConfig(5, 0, 300, 5, w, 4, d, d, .4, .7, .7, .3, .7, .8))
                .hasMessageContaining("travel speed must be above 0");
        assertThatThrownBy(() -> new TakeoverConfig(5, 900, -1, 5, w, 4, d, d, .4, .7, .7, .3, .7, .8))
                .hasMessageContaining("minimum distance not negative");
        assertThatThrownBy(() -> new TakeoverConfig(5, 900, 300, 1, w, 4, d, d, .4, .7, .7, .3, .7, .8))
                .hasMessage("a failed-login burst needs at least 2 failures");
        assertThatThrownBy(() -> new TakeoverConfig(5, 900, 300, 5, w, 1, d, d, .4, .7, .7, .3, .7, .8))
                .hasMessage("a shared device needs at least 2 accounts");
        assertThatThrownBy(() -> new TakeoverConfig(5, 900, 300, 5, Duration.ZERO, 4, d, d, .4, .7, .7, .3, .7, .8))
                .hasMessage("burst window must be longer than zero");
        assertThatThrownBy(() -> new TakeoverConfig(5, 900, 300, 5, w, 4, null, d, .4, .7, .7, .3, .7, .8))
                .hasMessage("shared-device window must be longer than zero");
        assertThatThrownBy(() -> new TakeoverConfig(5, 900, 300, 5, w, 4, d, Duration.ofSeconds(-1), .4, .7, .7, .3,
                .7, .8)).hasMessage("incident window must be longer than zero");
        assertThatThrownBy(() -> new TakeoverConfig(5, 900, 300, 5, w, 4, d, d, -.1, .7, .7, .3, .7, .8))
                .hasMessage("new-device weight must be between 0 and 1, got -0.1");
        assertThatThrownBy(() -> new TakeoverConfig(5, 900, 300, 5, w, 4, d, d, .4, 1.1, .7, .3, .7, .8))
                .hasMessage("impossible-travel weight must be between 0 and 1, got 1.1");
        assertThatThrownBy(() -> new TakeoverConfig(5, 900, 300, 5, w, 4, d, d, .4, .7, 2, .3, .7, .8))
                .hasMessage("burst-then-success weight must be between 0 and 1, got 2.0");
        assertThatThrownBy(() -> new TakeoverConfig(5, 900, 300, 5, w, 4, d, d, .4, .7, .7, -1, .7, .8))
                .hasMessage("burst-only weight must be between 0 and 1, got -1.0");
        assertThatThrownBy(() -> new TakeoverConfig(5, 900, 300, 5, w, 4, d, d, .4, .7, .7, .3, 1.5, .8))
                .hasMessage("shared-device weight must be between 0 and 1, got 1.5");
        assertThatThrownBy(() -> new TakeoverConfig(5, 900, 300, 5, w, 4, d, d, .4, .7, .7, .3, .7, 0))
                .hasMessage("flag threshold must be between 0 and 1, got 0.0");
        // Edges that are allowed.
        new TakeoverConfig(1, 0.001, 0, 2, Duration.ofNanos(1), 2, Duration.ofNanos(1), Duration.ofNanos(1),
                0, 1, 0, 1, 0, 1);
    }

    @Test
    void defaultsMatchTheIssueAndNoSignalFlagsAlone() {
        TakeoverConfig c = TakeoverConfig.defaults();
        assertThat(c.minHistory()).isEqualTo(5);
        assertThat(c.maxSpeedKmh()).isEqualTo(900);
        assertThat(c.burstFailures()).isEqualTo(5);
        assertThat(c.burstWindow()).isEqualTo(Duration.ofMinutes(15));
        assertThat(c.sharedDeviceAccounts()).isEqualTo(4);
        assertThat(c.sharedDeviceWindow()).isEqualTo(Duration.ofHours(24));
        assertThat(List.of(c.newDeviceWeight(), c.impossibleTravelWeight(), c.burstThenSuccessWeight(),
                c.burstOnlyWeight(), c.sharedDeviceWeight())).allMatch(w -> w < c.flagThreshold());
    }

    @ParameterizedTest
    @CsvSource({"0, 0 min", "59, 59 min", "60, 1 h", "2879, 47 h", "2880, 2 d"})
    void describesDurationsCompactly(long minutes, String text) {
        assertThat(TakeoverReason.duration(Duration.ofMinutes(minutes))).isEqualTo(text);
    }

    @Test
    void reasonsHaveStableCodesAndPlainDescriptions() {
        assertThat(new TakeoverReason.ImpossibleTravel(T0, 6900, Duration.ofHours(1)).describe())
                .isEqualTo("6,900 km in 1 h (6,900 km/h)");
        assertThat(new TakeoverReason.BurstOnly(T0, 6, Duration.ofMinutes(9)).describe())
                .isEqualTo("6 failed logins in 9 min");
        assertThat(new TakeoverReason.SharedDevice(T0, "bot", 5, Duration.ofHours(3)).describe())
                .isEqualTo("its device logged in to 5 unrelated accounts in 3 h");
        assertThat(List.of(new TakeoverReason.NewDevice(T0, "d", 1), new TakeoverReason.ImpossibleTravel(T0, 1, Duration.ZERO),
                new TakeoverReason.BurstThenSuccess(T0, 1, Duration.ZERO), new TakeoverReason.BurstOnly(T0, 1, Duration.ZERO),
                new TakeoverReason.SharedDevice(T0, "d", 1, Duration.ZERO))).extracting(TakeoverReason::code)
                .containsExactly("new_device", "impossible_travel", "failed_burst_then_success", "failed_burst",
                        "shared_device");
    }
}
