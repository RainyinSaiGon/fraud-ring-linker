package io.github.rainyinsaigon.fraudlinker.domain;

import java.util.HashMap;
import java.util.Map;

/** Current global signal counts. Empty signals are never counted. */
public final class Frequencies {
    private final Map<String, Integer> email = new HashMap<>();
    private final Map<String, Integer> device = new HashMap<>();
    private final Map<String, Integer> payment = new HashMap<>();
    private final Map<String, Integer> ip = new HashMap<>();
    private final Map<String, Integer> ipPrefix = new HashMap<>();

    public int email(String key) {
        return email.getOrDefault(key, 0);
    }

    public int device(String key) {
        return device.getOrDefault(key, 0);
    }

    public int payment(String key) {
        return payment.getOrDefault(key, 0);
    }

    public int ip(String key) {
        return ip.getOrDefault(key, 0);
    }

    public int ipPrefix(String key) {
        return ipPrefix.getOrDefault(key, 0);
    }

    public void incrementEmail(String key) {
        email.merge(key, 1, Integer::sum);
    }

    public void incrementDevice(String key) {
        device.merge(key, 1, Integer::sum);
    }

    public void incrementPayment(String key) {
        payment.merge(key, 1, Integer::sum);
    }

    public void incrementIp(String key) {
        ip.merge(key, 1, Integer::sum);
    }

    public void incrementIpPrefix(String key) {
        ipPrefix.merge(key, 1, Integer::sum);
    }
}
