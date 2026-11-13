package io.github.rainyinsaigon.fraudlinker.normalize;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class IpAddressesTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "203.0.113.7          | 203.0.113.7       | 203.0.113.0/24",
            "' 10.0.0.1 '         | 10.0.0.1          | 10.0.0.0/24",
            "0.0.0.0              | 0.0.0.0           | 0.0.0.0/24",
            "255.255.255.255      | 255.255.255.255   | 255.255.255.0/24",
            "::ffff:198.51.100.9  | 198.51.100.9      | 198.51.100.0/24",
            "::FFFF:C633:6409     | 198.51.100.9      | 198.51.100.0/24",
            "2001:DB8::1          | 2001:db8::1       | 2001:db8::/64",
            "2001:db8:0:0:1:0:0:1 | 2001:db8::1:0:0:1 | 2001:db8::/64",
            "2001:db8:a:b:c:d:e:f | 2001:db8:a:b:c:d:e:f | 2001:db8:a:b::/64",
            "::                   | ::                | ::/64",
            "::1                  | ::1               | ::/64",
            "1::                  | 1::               | 1::/64",
            "::1.2.3.4            | ::102:304         | ::/64",
            "fe80::1%eth0         | fe80::1%eth0      | fe80::/64",
            "2001:db8:0:1:0:0:0:1 | 2001:db8:0:1::1   | 2001:db8:0:1::/64",
    })
    void parsesValidAddresses(String raw, String address, String prefix) {
        IpAddresses.Parsed parsed = IpAddresses.parse(raw);
        assertThat(parsed).isNotNull();
        assertThat(parsed.address()).isEqualTo(address);
        assertThat(parsed.prefix()).isEqualTo(prefix);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {
            "", "not-an-ip", "01.2.3.4", "1.2.3", "1.2.3.4.5", "256.1.1.1", "1.2.3.-4", "1..2.3",
            "1.2.3.4 5", "1:2:3:4:5:6:7:8:9", "1::2::3", ":1:2", "1:2:", "12345::", "g::1",
            "1:2:3:4:5:6:7:8::", "fe80::1%", "1.2.3.4:80", "::1.2.3", "1:2:3:4:5:6:7:1.2.3.4",
            "example.com", "localhost"
    })
    void rejectsMalformedAddressesWithoutDnsLookup(String raw) {
        assertThat(IpAddresses.parse(raw)).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"203.0.113.7, 203.0.113.99", "::ffff:203.0.113.1, 203.0.113.250"})
    void addressesInSameSlash24ShareAPrefix(String left, String right) {
        assertThat(IpAddresses.parse(left).prefix()).isEqualTo(IpAddresses.parse(right).prefix());
    }
}
