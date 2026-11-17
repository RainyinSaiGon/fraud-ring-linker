package io.github.rainyinsaigon.fraudlinker.service.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PiiTest {

    @ParameterizedTest
    @CsvSource(value = {
            "jane.doe@example.com | j***@example.com",
            "J@Example.COM        | J***@Example.COM",
            "a@b@c.com            | a***@c.com",
            "no-at-sign           | ***",
            "@example.com         | ***",
            "jane@                | ***",
            "''                   | ''",
    }, delimiter = '|')
    void masksEmails(String email, String masked) {
        assertThat(Pii.email(email)).isEqualTo(masked);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "203.0.113.7                  | 203.0.113.x",
            "10.0.0.255                   | 10.0.0.x",
            "2001:db8:85a3:0:0:8a2e:370:7334 | 2001:db8:85a3:x",
            "2001:db8::1                  | ***",
            "::1                          | ***",
            "not an ip                    | ***",
            "1.2.3                        | ***",
            "''                           | ''",
    }, delimiter = '|')
    void masksIps(String ip, String masked) {
        assertThat(Pii.ip(ip)).isEqualTo(masked);
    }

    @Test
    void keepsNullAsNull() {
        assertThat(Pii.email(null)).isNull();
        assertThat(Pii.ip(null)).isNull();
    }
}
