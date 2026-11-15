package io.github.rainyinsaigon.fraudlinker.takeover;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiPredicate;

/**
 * The takeover detectors. Each returns every signal it saw, with its time, so the
 * scorer can combine signals that happen close together.
 */
final class TakeoverDetectors {
    static final double EARTH_RADIUS_KM = 6371.0;

    /** Earlier first, then by ID, so the order never depends on input order. */
    static final Comparator<Login> BY_TIME = Comparator.comparing(Login::occurredAt).thenComparing(Login::loginId);

    private TakeoverDetectors() {
    }

    /**
     * Successful logins from a device the account had never logged in from, once
     * it has {@code minHistory} earlier successful logins.
     *
     * @param logins one account's logins, sorted by {@link #BY_TIME}
     */
    static List<TakeoverReason> newDevices(List<Login> logins, TakeoverConfig config) {
        List<TakeoverReason> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int prior = 0;
        for (Login login : logins) {
            if (!login.success()) {
                continue;
            }
            String device = login.deviceId();
            if (device != null && seen.add(device) && prior >= config.minHistory()) {
                found.add(new TakeoverReason.NewDevice(login.occurredAt(), device, prior));
            }
            prior++;
        }
        return found;
    }

    /**
     * Consecutive located successful logins further apart than {@code maxSpeedKmh}
     * allows. Jumps shorter than {@code minTravelKm} are ignored.
     *
     * @param logins one account's logins, sorted by {@link #BY_TIME}
     */
    static List<TakeoverReason> impossibleTravel(List<Login> logins, TakeoverConfig config) {
        List<TakeoverReason> found = new ArrayList<>();
        Login previous = null;
        for (Login login : logins) {
            if (!login.success() || !login.hasLocation()) {
                continue;
            }
            if (previous != null) {
                double km = distanceKm(previous, login);
                TakeoverReason.ImpossibleTravel jump = new TakeoverReason.ImpossibleTravel(login.occurredAt(), km,
                        Duration.between(previous.occurredAt(), login.occurredAt()));
                if (km >= config.minTravelKm() && jump.kmh() > config.maxSpeedKmh()) {
                    found.add(jump);
                }
            }
            previous = login;
        }
        return found;
    }

    /** Great-circle distance (haversine). */
    static double distanceKm(Login a, Login b) {
        double lat1 = Math.toRadians(a.latitude());
        double lat2 = Math.toRadians(b.latitude());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b.longitude() - a.longitude());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /**
     * Failed-login bursts. A success with at least {@code burstFailures} failures in
     * the {@code burstWindow} before it is a burst then success; those failures are
     * used up. Each remaining window of that many failures is a burst with no success.
     *
     * @param logins one account's logins, sorted by {@link #BY_TIME}
     */
    static List<TakeoverReason> bursts(List<Login> logins, TakeoverConfig config) {
        List<TakeoverReason> found = new ArrayList<>();
        List<Instant> unused = new ArrayList<>();
        for (Login login : logins) {
            if (!login.success()) {
                unused.add(login.occurredAt());
                continue;
            }
            Instant earliest = login.occurredAt().minus(config.burstWindow());
            List<Instant> inWindow = unused.stream().filter(t -> !t.isBefore(earliest)).toList();
            if (inWindow.size() >= config.burstFailures()) {
                found.add(new TakeoverReason.BurstThenSuccess(login.occurredAt(), inWindow.size(),
                        Duration.between(inWindow.get(0), inWindow.get(inWindow.size() - 1))));
                unused.removeAll(inWindow);
            }
        }
        int start = 0;
        for (int end = 0; end < unused.size(); end++) {
            while (Duration.between(unused.get(start), unused.get(end)).compareTo(config.burstWindow()) > 0) {
                start++;
            }
            boolean windowEndsHere = end + 1 == unused.size()
                    || Duration.between(unused.get(start), unused.get(end + 1)).compareTo(config.burstWindow()) > 0;
            int count = end - start + 1;
            if (windowEndsHere && count >= config.burstFailures()) {
                found.add(new TakeoverReason.BurstOnly(unused.get(end), count,
                        Duration.between(unused.get(start), unused.get(end))));
                start = end + 1;
            }
        }
        return found;
    }

    /**
     * Devices that logged in successfully to at least {@code sharedDeviceAccounts}
     * unrelated accounts within {@code sharedDeviceWindow}. Each of those logins
     * gets a signal on its account.
     *
     * @param logins every login, sorted by {@link #BY_TIME}
     * @param related true when two accounts belong together (one cluster); related accounts count once
     * @return signals by account ID
     */
    static Map<String, List<TakeoverReason>> sharedDevices(List<Login> logins, BiPredicate<String, String> related,
                                                           TakeoverConfig config) {
        Map<String, List<Login>> byDevice = new TreeMap<>();
        for (Login login : logins) {
            if (login.success() && login.deviceId() != null) {
                byDevice.computeIfAbsent(login.deviceId(), k -> new ArrayList<>()).add(login);
            }
        }
        Map<String, TakeoverReason.SharedDevice> byLogin = new HashMap<>();
        Map<String, String> accountOfLogin = new HashMap<>();
        for (List<Login> uses : byDevice.values()) {
            int start = 0;
            for (int end = 0; end < uses.size(); end++) {
                while (Duration.between(uses.get(start).occurredAt(), uses.get(end).occurredAt())
                        .compareTo(config.sharedDeviceWindow()) > 0) {
                    start++;
                }
                List<Login> window = uses.subList(start, end + 1);
                int groups = unrelatedGroups(window, related);
                if (groups < config.sharedDeviceAccounts()) {
                    continue;
                }
                Duration span = Duration.between(window.get(0).occurredAt(), window.get(window.size() - 1).occurredAt());
                for (Login login : window) {
                    TakeoverReason.SharedDevice signal = new TakeoverReason.SharedDevice(login.occurredAt(),
                            login.deviceId(), groups, span);
                    TakeoverReason.SharedDevice earlier = byLogin.get(login.loginId());
                    if (earlier == null || earlier.accounts() < groups) {
                        byLogin.put(login.loginId(), signal);
                        accountOfLogin.put(login.loginId(), login.accountId());
                    }
                }
            }
        }
        Map<String, List<TakeoverReason>> byAccount = new HashMap<>();
        byLogin.forEach((loginId, signal) ->
                byAccount.computeIfAbsent(accountOfLogin.get(loginId), k -> new ArrayList<>()).add(signal));
        return byAccount;
    }

    /** Distinct accounts, counting accounts that are related to one already counted only once. */
    static int unrelatedGroups(List<Login> window, BiPredicate<String, String> related) {
        Set<String> accounts = new LinkedHashSet<>();
        window.forEach(login -> accounts.add(login.accountId()));
        List<String> representatives = new ArrayList<>();
        for (String account : accounts) {
            if (representatives.stream().noneMatch(r -> related.test(r, account))) {
                representatives.add(account);
            }
        }
        return representatives.size();
    }
}
