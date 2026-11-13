// ABOUTME: Gives integration tests the PostgreSQL image pinned in the repository's compose.yaml (issue 323, audit L13),
// ABOUTME: so tests and the local stack run the same reviewed image and Dependabot has one line to update.
package io.github.stevdrey.dokene.integration.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testcontainers.utility.DockerImageName;

/**
 * No test names an image itself: {@code scripts/check-image-pins.sh} fails any image literal in Java. The file is the
 * one in the system property {@code dokene.test.compose-file}, which {@code backend/build.gradle} sets; without it (a
 * test run from an IDE) it is {@code compose.yaml} in the working directory, then in its parent.
 */
public final class ComposeImages {

    static final String COMPOSE_FILE_PROPERTY = "dokene.test.compose-file";

    private static final Pattern PINNED_POSTGRES_LINE = Pattern.compile(
            "^\\s*image:\\s*[\"']?postgres:[^\\s\"'@]+@(sha256:[0-9a-f]{64})[\"']?\\s*(#.*)?$");

    private ComposeImages() {
    }

    /** The pinned PostgreSQL image, by digest alone: the digest selects the content, so the tag is dropped. */
    public static DockerImageName postgres() {
        return Holder.POSTGRES;
    }

    static String postgresReference(Path composeFile) {
        List<String> lines;
        try {
            lines = Files.readAllLines(composeFile);
        } catch (NoSuchFileException exception) {
            throw new IllegalStateException("Compose file not found: " + composeFile, exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Compose file could not be read: " + composeFile, exception);
        }
        List<String> digests = lines.stream()
                .map(PINNED_POSTGRES_LINE::matcher)
                .filter(Matcher::matches)
                .map(matcher -> matcher.group(1))
                .toList();
        if (digests.size() != 1) {
            throw new IllegalStateException("Expected exactly one postgres image line pinned as "
                    + "postgres:<tag>@sha256:<digest> in " + composeFile + ", found " + digests.size());
        }
        return "postgres@" + digests.getFirst();
    }

    private static Path composeFile() {
        String configured = System.getProperty(COMPOSE_FILE_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        Path workingDirectory = Path.of(System.getProperty("user.dir"));
        Path here = workingDirectory.resolve("compose.yaml");
        if (Files.exists(here) || workingDirectory.getParent() == null) {
            return here;
        }
        return workingDirectory.getParent().resolve("compose.yaml");
    }

    private static final class Holder {
        private static final DockerImageName POSTGRES = DockerImageName.parse(postgresReference(composeFile()));
    }
}
