package io.github.rainyinsaigon.fraudlinker.takeover;

import io.github.rainyinsaigon.fraudlinker.LinkerException;

import java.time.Instant;

/**
 * One login attempt on an account.
 *
 * @param deviceId  may be null when the device is unknown; an unknown device is never "new" or "shared"
 * @param latitude  degrees, with {@code longitude}; both null when upstream gave no location
 * @param longitude degrees, with {@code latitude}
 */
public record Login(String loginId, String accountId, String deviceId, String ip, boolean success, Instant occurredAt,
                    Double latitude, Double longitude) {

    public Login {
        if (loginId == null || loginId.isBlank()) {
            throw new LinkerException("a login needs a login_id");
        }
        if (accountId == null || accountId.isBlank()) {
            throw new LinkerException("login " + loginId + " needs an account");
        }
        if (occurredAt == null) {
            throw new LinkerException("login " + loginId + " needs a time");
        }
        if ((latitude == null) != (longitude == null)) {
            throw new LinkerException("login " + loginId + " needs both a latitude and a longitude, or neither");
        }
        if (latitude != null && (!(latitude >= -90 && latitude <= 90) || !(longitude >= -180 && longitude <= 180))) {
            throw new LinkerException("login " + loginId + " has a location outside the globe");
        }
        if (deviceId != null && deviceId.isBlank()) {
            deviceId = null;
        }
    }

    public boolean hasLocation() {
        return latitude != null;
    }
}
