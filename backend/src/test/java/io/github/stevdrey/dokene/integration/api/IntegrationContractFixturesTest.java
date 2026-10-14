// ABOUTME: Provider side of the frozen integration v1 contract: shared fixtures must round-trip through the records.
// ABOUTME: A renamed, removed or retyped member, or an accepted unknown member, fails here and in the frontend test.
package io.github.stevdrey.dokene.integration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.stevdrey.dokene.integration.api.IntegrationApiContract.IntegrationRequest;
import io.github.stevdrey.dokene.integration.api.IntegrationApiContract.IntegrationResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

class IntegrationContractFixturesTest {
    private static final Map<String, Class<? extends Throwable>> INVALID_REASONS = Map.of(
            "missingEnabled", NullPointerException.class,
            "missingSecretRef", NullPointerException.class,
            "rawCredentialSmuggled", UnrecognizedPropertyException.class);

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private final JsonNode fixture = load();

    @TestFactory
    Stream<DynamicTest> validRequestsRoundTripAndCoverEveryMember() {
        var tests = new ArrayList<DynamicTest>();
        tests.add(dynamicTest("valid examples cover every member", () -> {
            var members = new LinkedHashSet<String>();
            named(fixture.get("request").get("valid")).values().forEach(node -> members.addAll(node.propertyNames()));
            assertThat(members).containsExactlyInAnyOrderElementsOf(components(IntegrationRequest.class));
        }));
        named(fixture.get("request").get("valid")).forEach((name, node) -> tests.add(dynamicTest(
                "request.valid." + name, () -> assertThat(withoutNulls(written(node, IntegrationRequest.class)))
                        .isEqualTo(withoutNulls(node)))));
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> invalidRequestsAreRejectedForTheirOwnReason() {
        Map<String, JsonNode> invalid = named(fixture.get("request").get("invalid"));
        assertThat(invalid.keySet()).containsExactlyInAnyOrderElementsOf(INVALID_REASONS.keySet());
        return invalid.entrySet().stream().map(entry -> dynamicTest("request.invalid." + entry.getKey(), () -> {
            Throwable thrown = catchThrowable(() -> mapper.treeToValue(entry.getValue(), IntegrationRequest.class));
            assertThat(thrown).isNotNull();
            while (thrown.getCause() != null && thrown.getCause() != thrown) {
                thrown = thrown.getCause();
            }
            assertThat(thrown).isInstanceOf(INVALID_REASONS.get(entry.getKey()));
        }));
    }

    @TestFactory
    Stream<DynamicTest> responsesRoundTripUnchanged() {
        return named(fixture.get("response").get("valid")).entrySet().stream().map(entry -> dynamicTest(
                "response.valid." + entry.getKey(), () -> assertThat(written(entry.getValue(),
                        IntegrationResponse.class)).isEqualTo(entry.getValue())));
    }

    @Test
    void recordComponentTypesArePinned() {
        Map<Class<?>, List<String>> expected = new LinkedHashMap<>();
        expected.put(IntegrationRequest.class, List.of("phoneNumberId: java.lang.String",
                "businessAccountId: java.lang.String", "displayPhoneNumber: java.lang.String",
                "enabled: java.lang.Boolean", "secretRef: java.lang.String"));
        expected.put(IntegrationResponse.class, List.of("enabled: boolean", "outboundEnabled: boolean",
                "phoneNumberId: java.lang.String", "businessAccountId: java.lang.String",
                "displayPhoneNumber: java.lang.String", "secretRef: java.lang.String", "webhookVerified: boolean",
                "version: long"));
        assertThat(Arrays.stream(IntegrationApiContract.class.getDeclaredClasses()).filter(Class::isRecord).toList())
                .containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((type, members) -> assertThat(Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName() + ": " + component.getGenericType().getTypeName()).toList())
                .as(type.getSimpleName()).containsExactlyElementsOf(members));
    }

    private List<String> components(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(component -> component.getName()).toList();
    }

    private JsonNode written(JsonNode node, Class<?> type) {
        return mapper.readTree(mapper.writeValueAsString(mapper.treeToValue(node, type)));
    }

    private JsonNode withoutNulls(JsonNode node) {
        var copy = mapper.createObjectNode();
        node.properties().forEach(entry -> {
            if (!entry.getValue().isNull()) {
                copy.set(entry.getKey(), entry.getValue());
            }
        });
        return copy;
    }

    private JsonNode load() {
        try (InputStream in = getClass().getResourceAsStream("/contracts/messaging/v1/integration.json")) {
            assertThat(in).isNotNull();
            return mapper.readTree(in);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read integration fixture", exception);
        }
    }

    private static Map<String, JsonNode> named(JsonNode object) {
        var result = new LinkedHashMap<String, JsonNode>();
        object.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        assertThat(result).isNotEmpty();
        return result;
    }
}
