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
            "[::2]", "[::1", "dokene_backend", "0.0.0.0",
            // Not recognized, as documented: 0177.0.0.1 is read as decimal 177.0.0.1 and hex is refused.
            "0177.0.0.1", "0x7f.0.0.1"})
    void theWideRuleRefusesHostsThatAreNotLoopback(String host) {
        assertThat(LoopbackHosts.reachesLoopback(host)).isFalse();
        assertThat(LoopbackHosts.isExactSpelling(host)).isFalse();
    }

    /**
     * Acceptance criterion of issue 428: the rule is kept in one place, so a change to it is made once. The pattern
     * matches a rule (a host set, a loopback predicate, a comparison with a loopback spelling), not a bare literal such
     * as a default host name.
     */
    @Test
    void noOtherMainClassDeclaresItsOwnLoopbackRule() throws IOException {
        Pattern ownRule = Pattern.compile("isLoopbackAddress|LOOPBACK_HOSTS|boolean\\s+isLoopback\\w*\\s*\\("
                + "|Set\\.of\\(\\s*\"(localhost|127\\.0\\.0\\.1|\\[::1\\])\""
                + "|\\.(equals|endsWith|startsWith|contains)\\(\\s*"
                + "\"(localhost|\\.localhost|127\\.|127\\.0\\.0\\.1|\\[::1\\]|::1)\"");
        Path helper = Path.of("io/github/stevdrey/dokene/http/LoopbackHosts.java");
        // Gradle runs tests in backend/, an IDE may run them in the repository root; any other place fails here.
        Path main = Stream.of(Path.of("src/main/java"), Path.of("backend/src/main/java"))
                .filter(root -> Files.isRegularFile(root.resolve(helper)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("backend/src/main/java not found from "
                        + Path.of("").toAbsolutePath()));
        List<Path> offenders;
        try (Stream<Path> sources = Files.walk(main)) {
            offenders = sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.endsWith(helper))
                    .filter(path -> {
                        try {
                            return ownRule.matcher(Files.readString(path)).find();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
        }
        assertThat(offenders).as("classes with their own loopback rule; use LoopbackHosts").isEmpty();
    }
}
