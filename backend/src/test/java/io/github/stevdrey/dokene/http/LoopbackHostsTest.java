// ABOUTME: Pins the two loopback rules (issue 428): the exact spelling, used by the public origin and both provider
// ABOUTME: endpoint policies, and the wide rule, used by the replay port check; no other main class keeps its own.
package io.github.stevdrey.dokene.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class LoopbackHostsTest {

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "127.0.0.1", "[::1]"})
    void theExactRuleAcceptsTheThreeBrowserSpellings(String host) {
        assertThat(LoopbackHosts.isExactSpelling(host)).isTrue();
        assertThat(LoopbackHosts.reachesLoopback(host)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOCALHOST", "localhost.", "stub.localhost", "127.0.0.2", "127.1", "::1",
            "[0:0:0:0:0:0:0:1]", "[::ffff:127.0.0.1]", "localhost.evil.example", "example.com", "10.0.0.5"})
    void theExactRuleRefusesEveryOtherSpelling(String host) {
        assertThat(LoopbackHosts.isExactSpelling(host)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOCALHOST", "localhost.", "stub.localhost", "Backend.Localhost.", "127.0.0.2",
            "127.255.255.255", "::1", "[::1]", "[0:0:0:0:0:0:0:1]", "[0000::0001]"})
    void theWideRuleAcceptsAnySpellingThatReachesLoopback(String host) {
        assertThat(LoopbackHosts.reachesLoopback(host)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {".", "localhost.evil.example", "evillocalhost", "example.com", "128.0.0.1", "10.0.0.5",
            "[::2]", "[::1", "dokene_backend", "0.0.0.0"})
    void theWideRuleRefusesHostsThatAreNotLoopback(String host) {
        assertThat(LoopbackHosts.reachesLoopback(host)).isFalse();
        assertThat(LoopbackHosts.isExactSpelling(host)).isFalse();
    }

    /** Acceptance criterion of issue 428: the rule is kept in one place, so a change to it is made once. */
    @Test
    void noOtherMainClassDeclaresItsOwnLoopbackRule() throws IOException {
        Pattern ownRule = Pattern.compile(
                "\"(localhost|127\\.0\\.0\\.1|\\[::1\\]|::1|\\.localhost)\"|isLoopbackAddress|LOOPBACK_HOSTS|isLoopback\\(");
        Path main = Path.of("src/main/java");
        assertThat(main).isDirectory();
        List<Path> offenders;
        try (Stream<Path> sources = Files.walk(main)) {
            offenders = sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.endsWith(Path.of("io/github/stevdrey/dokene/http/LoopbackHosts.java")))
                    .filter(path -> {
                        try {
                            return ownRule.matcher(Files.readString(path)).find();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
        }
        assertThat(offenders).isEmpty();
    }
}
