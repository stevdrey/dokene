// ABOUTME: Tests the accepted and refused forms of DOKENE_PUBLIC_ORIGIN (spec 0019 AC-4, issue 318), and the login
// ABOUTME: callback and logout return addresses built from it.
package io.github.stevdrey.dokene.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PublicOriginTest {

    /** covers: AC-4. */
    @ParameterizedTest
    @ValueSource(strings = {
            "https://dokene.example",
            "https://dokene.example:8443",
            "https://app.dokene.example:1",
            "https://app.dokene.example:65535",
            "https://xn--dokne-csa.example",
            "https://192.0.2.10",
            "https://[2001:db8::1]:8443",
            "https://[2001:db8::]",
            "https://[::]",
            "https://[2001:db8:0:0:0:0:0:1]",
            "https://[::ffff:192.0.2.1]",
            "https://[0:0:0:0:0:0:0:1]",
            "http://localhost",
            "http://localhost:8080",
            "http://127.0.0.1:38080",
            "http://[::1]",
            "http://[::1]:8080"
    })
    void acceptsAnOriginInTheDocumentedFormAndUsesItExactlyAsWritten(String value) {
        PublicOrigin origin = new PublicOrigin(value);

        assertThat(origin.value()).isEqualTo(value);
    }

    /** covers: AC-4. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void refusesAnUnsetOrBlankOrigin(String value) {
        assertRefused(value, "must be set");
    }

    /** covers: AC-4. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "https://dokene.example/                 | path, query or fragment",
            "http://localhost:8080/                  | path, query or fragment",
            "https://dokene.example/app              | path, query or fragment",
            "https://dokene.example?x=1              | path, query or fragment",
            "https://dokene.example#top              | path, query or fragment",
            "https://user@dokene.example             | user info",
            "https://user:secret@dokene.example      | user info",
            "HTTPS://dokene.example                  | lower case",
            "Https://dokene.example                  | lower case",
            "https://Dokene.example                  | lower case",
            "http://LOCALHOST:8080                   | lower case",
            "https://dokene.example:443              | default port",
            "http://localhost:80                     | default port",
            "https://dokene.example:0                | port",
            "https://dokene.example:65536            | port",
            "https://dokene.example:08443            | port",
            "https://dokene.example:                 | port",
            "https://dokene.example:abc              | port",
            "https://dökene.example                  | punycode",
            "http://dokene.example                   | plain http",
            "http://app.localhost:8080               | plain http",
            "http://192.0.2.10                       | plain http",
            "http://127.0.0.2                        | plain http",
            "ftp://dokene.example                    | scheme",
            "ws://localhost:8080                     | scheme",
            "dokene.example                          | scheme",
            "//dokene.example                        | scheme",
            "https://                                | host",
            "https://:8443                           | host",
            "https://dokene..example                 | host",
            "https://-dokene.example                 | host",
            "https://dokene_example                  | host",
            "https://[2001:db8::1                    | host",
            "https://dokene.example.                 | host",
            "https://[]                              | host",
            "https://[:]                             | host",
            "https://[::1::]                         | host",
            "https://[:::1]                          | host",
            "https://[1:2:3:4:5:6:7:8:9]             | host",
            "https://[1:2:3:4:5:6:7:8::]             | host",
            "https://[1:2:3:4:5:6:7]                 | host",
            "https://[12345::1]                      | host",
            "https://[2001:db8::g]                   | host",
            "https://[::256.0.2.1]                   | host",
            "https://[::192.0.2.1:1]                 | host",
            "https://[fe80::1%eth0]                  | host",
            "https://[2001:DB8::1]                   | lower case",
            "http://[0:0:0:0:0:0:0:1]                | plain http",
            "http://[0:0:0:0:0:0:0:1]:8080           | plain http",
            "https://2001:db8::1                     | host",
            "https://127.1                           | IPv4",
            "https://10.1                            | IPv4",
            "https://1.2.3                           | IPv4",
            "https://999.1.1.1                       | IPv4",
            "https://0x7f.1                          | IPv4",
            "https://1.0x7f                          | IPv4",
            "https://192.0.2.010                     | IPv4",
            "https://1.2.3.4.5                       | IPv4",
            "https://dokene.123                      | IPv4",
            "https://2130706433                      | IPv4"
    })
    void refusesEveryOtherFormNamingTheRuleItBreaks(String value, String rule) {
        assertRefused(value, rule);
    }

    /** covers: AC-4. A DNS label holds at most 63 characters. */
    @Test
    void acceptsALabelOf63CharactersAndRefusesOneOf64() {
        String longest = "a".repeat(63);

        assertThat(new PublicOrigin("https://" + longest + ".example").value())
                .isEqualTo("https://" + longest + ".example");
        assertRefused("https://" + longest + "a.example", "host");
    }

    /** covers: AC-4. A host name holds at most 253 characters. */
    @Test
    void acceptsAHostOf253CharactersAndRefusesOneOf254() {
        String label = "a".repeat(63);
        String longest = label + "." + label + "." + label + "." + "a".repeat(61);

        assertThat(longest).hasSize(253);
        assertThat(new PublicOrigin("https://" + longest).value()).isEqualTo("https://" + longest);
        assertRefused("https://" + longest + "a", "253");
    }

    /** covers: AC-4. Only a numeric last label makes a browser read the host as an address. */
    @ParameterizedTest
    @ValueSource(strings = {"https://1dokene.example", "https://127.1.example", "https://10.0x7g", "https://1.x7f"})
    void acceptsDigitsOutsideANumericLastLabel(String value) {
        assertThat(new PublicOrigin(value).value()).isEqualTo(value);
    }

    /** covers: AC-4. A value with surrounding space would not be the origin Keycloak registered. */
    @ParameterizedTest
    @ValueSource(strings = {" https://dokene.example", "https://dokene.example ", "https://dokene .example"})
    void refusesWhitespaceInsteadOfTrimmingIt(String value) {
        assertThatThrownBy(() -> new PublicOrigin(value))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DOKENE_PUBLIC_ORIGIN");
    }

    /** covers: AC-4. User info may hold a password, so the refusal never repeats the value. */
    @Test
    void theRefusalNeverRepeatsTheConfiguredValue() {
        assertThatThrownBy(() -> new PublicOrigin("https://admin:hunter2@dokene.example"))
                .hasMessageNotContaining("hunter2")
                .hasMessageNotContaining("admin");
    }

    /** covers: AC-1, AC-2, AC-6. */
    @Test
    void buildsTheCallbackAndLogoutReturnFromTheOriginAlone() {
        PublicOrigin local = new PublicOrigin("http://localhost:8080");
        PublicOrigin tunnel = new PublicOrigin("https://dokene.example");

        assertThat(local.loginCallbackTemplate()).isEqualTo("http://localhost:8080/login/oauth2/code/{registrationId}");
        assertThat(local.loginCallback("dokene")).isEqualTo("http://localhost:8080/login/oauth2/code/dokene");
        assertThat(local.logoutReturn()).isEqualTo("http://localhost:8080/");
        assertThat(local.isHttps()).isFalse();
        assertThat(tunnel.isHttps()).isTrue();
    }

    private static void assertRefused(String value, String rule) {
        assertThatThrownBy(() -> new PublicOrigin(value))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DOKENE_PUBLIC_ORIGIN")
                .hasMessageContaining(rule);
    }
}
