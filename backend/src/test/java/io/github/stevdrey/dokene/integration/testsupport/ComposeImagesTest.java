// ABOUTME: Unit tests of the compose.yaml lookup behind ComposeImages (issue 323): exactly one pinned postgres line
// ABOUTME: is required, and the real compose.yaml of this repository resolves. Temporary files only, no Docker.
package io.github.stevdrey.dokene.integration.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComposeImagesTest {

    private static final String DIGEST = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String OTHER_DIGEST =
            "sha256:89abcdef0123456789abcdef0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path directory;

    @Test
    void readsTheDigestOfThePinnedPostgresLineAndDropsTheTag() throws IOException {
        Path file = compose("""
                services:
                  postgres:
                    image: postgres:17-alpine@%s
                  backend:
                    image: dokene-backend:local
                  bridge:
                    image: alpine/socat:1.8.0.3@%s
                """.formatted(DIGEST, OTHER_DIGEST));

        assertThat(ComposeImages.postgresReference(file)).isEqualTo("postgres@" + DIGEST);
    }

    @Test
    void acceptsQuotesAndATrailingComment() throws IOException {
        assertThat(ComposeImages.postgresReference(compose("    image: \"postgres:17-alpine@" + DIGEST + "\"  # db\n")))
                .isEqualTo("postgres@" + DIGEST);
        assertThat(ComposeImages.postgresReference(compose("    image: 'postgres:17@" + DIGEST + "'\n")))
                .isEqualTo("postgres@" + DIGEST);
    }

    @Test
    void failsWhenTheFileDoesNotExist() {
        Path missing = directory.resolve("missing.yaml");

        assertThatThrownBy(() -> ComposeImages.postgresReference(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(missing.toString());
    }

    @Test
    void failsWhenNoPostgresLineIsPinned() throws IOException {
        Path file = compose("""
                services:
                  backend:
                    image: dokene-backend:local
                """);

        assertThatThrownBy(() -> ComposeImages.postgresReference(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("found 0");
    }

    @Test
    void aTagOnlyLineCountsAsNoMatch() throws IOException {
        Path file = compose("    image: postgres:17-alpine\n");

        assertThatThrownBy(() -> ComposeImages.postgresReference(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("found 0");
    }

    @Test
    void failsWhenTwoPostgresLinesArePinned() throws IOException {
        Path file = compose("""
                services:
                  postgres:
                    image: postgres:17-alpine@%s
                  replica:
                    image: postgres:17-alpine@%s
                """.formatted(DIGEST, OTHER_DIGEST));

        assertThatThrownBy(() -> ComposeImages.postgresReference(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("found 2");
    }

    @Test
    void resolvesTheRealComposeFileOfThisRepository() {
        assertThat(ComposeImages.postgres().asCanonicalNameString())
                .matches("postgres@sha256:[0-9a-f]{64}");
    }

    private Path compose(String content) throws IOException {
        return Files.writeString(directory.resolve("compose.yaml"), content);
    }
}
