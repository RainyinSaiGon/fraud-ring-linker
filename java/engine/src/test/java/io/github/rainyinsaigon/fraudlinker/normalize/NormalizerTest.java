package io.github.rainyinsaigon.fraudlinker.normalize;

import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Features;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizerTest {
    private static Features normalizeEmail(String email) {
        return Normalizer.normalize(new Account("a", email, null, "", null, Instant.EPOCH));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"  John.Doe+promo@GMAIL.com  ", "john.doe+other@googlemail.com"})
    void canonicalizesGmailAndGooglemail(String email) {
        Features features = normalizeEmail(email);
        assertThat(features.emailCanonical()).isEqualTo("johndoe@gmail.com");
        assertThat(features.emailBaseLocal()).isEqualTo("johndoe");
        assertThat(features.emailDomain()).isEqualTo("gmail.com");
    }

    @Test
    void preservesDotsAndPlusForNonGmailProvider() {
        Features features = normalizeEmail("  First.Last+tag@Yahoo.com  ");
        assertThat(features.emailCanonical()).isEqualTo("first.last+tag@yahoo.com");
        assertThat(features.emailBaseLocal()).isEqualTo("first.last+tag");
    }

    @Test
    void stripsPlusButKeepsDotsForOutlook() {
        Features features = normalizeEmail("first.last+promo@outlook.com");
        assertThat(features.emailCanonical()).isEqualTo("first.last@outlook.com");
    }

    @Test
    void trimsUnicodeNonBreakingSpace() {
        assertThat(normalizeEmail(" a@b.com　").emailExact()).isEqualTo("a@b.com");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"no-at-sign", "@missing-local.com", "trailing@", ""})
    void malformedEmailHasNoDomain(String email) {
        Features features = normalizeEmail(email);
        assertThat(features.emailDomain()).isEmpty();
        assertThat(features.emailCanonical()).isEqualTo(email.toLowerCase());
    }

    @Test
    void missingDeviceAndPaymentAreEmpty() {
        Features features = Normalizer.normalize(new Account("a", "a@b.com", null, "bad", "  ", Instant.EPOCH));
        assertThat(features.deviceId()).isEmpty();
        assertThat(features.payment()).isEmpty();
        assertThat(features.ip()).isNull();
        assertThat(features.ipPrefix()).isNull();
    }

    @Test
    void skeletonDropsSeparators() {
        assertThat(Normalizer.skeleton("j.o_h-n d")).isEqualTo("johnd");
    }

    @Test
    void deletionSignaturesAreUnicodeSafeAndIncludeOriginal() {
        assertThat(EditDistance.deletionSignatures("åβ猫", 8))
                .containsExactly("åβ猫", "β猫", "å猫", "åβ");
    }

    @Test
    void deletionSignaturesHandleSurrogatePairsAsOneCharacter() {
        assertThat(EditDistance.deletionSignatures("a😀", 8)).containsExactly("a😀", "😀", "a");
    }

    @Test
    void deletionSignaturesAreBoundedAndDeduplicated() {
        assertThat(EditDistance.deletionSignatures("aa", 8)).containsExactly("aa", "a");
        assertThat(EditDistance.deletionSignatures("abc", 2)).isEmpty();
        assertThat(EditDistance.deletionSignatures("", 8)).isEmpty();
    }

    @ParameterizedTest(name = "{0}: {1} vs {2} -> {3}")
    @CsvSource({
            "equal, john, john, true",
            "one substitution, john, j0hn, true",
            "one insertion, john, johhn, true",
            "one deletion, john, jon, true",
            "unicode substitution, åβ猫, åδ猫, true",
            "unicode deletion, åβ猫, å猫, true",
            "transposition is two edits, john, jhon, false",
            "two substitutions, john, jo42, false",
            "length differs by two, john, johnny, false",
    })
    void withinOneEdit(String name, String left, String right, boolean expected) {
        assertThat(EditDistance.withinOneEdit(left, right)).isEqualTo(expected);
        assertThat(EditDistance.withinOneEdit(right, left)).isEqualTo(expected);
    }
}
