// ABOUTME: Provider side of the frozen template registry v1 contract: shared fixtures must round-trip through the records.
// ABOUTME: A renamed, removed or retyped member, or an accepted unknown member, fails here and in the frontend test.
package io.github.stevdrey.dokene.template.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.stevdrey.dokene.template.api.TemplateApiContract.TemplateContentResponse;
import io.github.stevdrey.dokene.template.api.TemplateApiContract.TemplateListResponse;
import io.github.stevdrey.dokene.template.api.TemplateApiContract.TemplateRequest;
import io.github.stevdrey.dokene.template.api.TemplateApiContract.TemplateResponse;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

class TemplateContractFixturesTest {
    private static final Map<String, Class<? extends Throwable>> INVALID_REASONS = Map.of(
            "missingParameterNames", NullPointerException.class,
            "missingEnabled", NullPointerException.class,
            "providerTemplateIdSmuggled", UnrecognizedPropertyException.class,
            "approvalStatusSmuggled", UnrecognizedPropertyException.class);

    /** The production reader, so the fixtures prove what the endpoint really accepts. */
    private final ObjectMapper mapper = TemplateApiContract.strictReader();
    private final JsonNode fixture = load();

    @TestFactory
    Stream<DynamicTest> validRequestsRoundTripAndCoverEveryMember() {
        var tests = new ArrayList<DynamicTest>();
        tests.add(dynamicTest("valid examples cover every member", () -> {
            var members = new LinkedHashSet<String>();
            named(fixture.get("request").get("valid")).values().forEach(node -> members.addAll(node.propertyNames()));
            assertThat(members).containsExactlyInAnyOrderElementsOf(components(TemplateRequest.class));
        }));
        named(fixture.get("request").get("valid")).forEach((name, node) -> tests.add(dynamicTest(
                "request.valid." + name, () -> assertThat(withoutNulls(written(node, TemplateRequest.class)))
                        .isEqualTo(withoutNulls(node)))));
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> invalidRequestsAreRejectedForTheirOwnReason() {
        Map<String, JsonNode> invalid = named(fixture.get("request").get("invalid"));
        assertThat(invalid.keySet()).containsExactlyInAnyOrderElementsOf(INVALID_REASONS.keySet());
        return invalid.entrySet().stream().map(entry -> dynamicTest("request.invalid." + entry.getKey(), () -> {
            Throwable thrown = catchThrowable(() -> mapper.treeToValue(entry.getValue(), TemplateRequest.class));
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
                        TemplateResponse.class)).isEqualTo(entry.getValue())));
    }

    @TestFactory
    Stream<DynamicTest> listsRoundTripUnchanged() {
        return named(fixture.get("list").get("valid")).entrySet().stream().map(entry -> dynamicTest(
                "list.valid." + entry.getKey(), () -> assertThat(written(entry.getValue(),
                        TemplateListResponse.class)).isEqualTo(entry.getValue())));
    }

    @TestFactory
    Stream<DynamicTest> theProductionReaderRejectsWrongTypesAndShapes() {
        Map<String, String> bodies = Map.of(
                "stringBoolean", "{\"providerTemplateName\":\"t\",\"language\":\"es\",\"parameterNames\":[],"
                        + "\"enabled\":\"true\"}",
                "numericName", "{\"providerTemplateName\":123,\"language\":\"es\",\"parameterNames\":[],"
                        + "\"enabled\":true}",
                "stringParameters", "{\"providerTemplateName\":\"t\",\"language\":\"es\","
                        + "\"parameterNames\":\"a\",\"enabled\":true}",
                "jsonNull", "null",
                "jsonArray", "[]",
                "emptyObject", "{}");
        return bodies.entrySet().stream().map(entry -> dynamicTest(entry.getKey(), () -> assertThat(
                catchThrowable(() -> TemplateApiContract.read(mapper.readTree(entry.getValue()))))
                .isInstanceOf(TemplateApiContract.InvalidRequestException.class)));
    }

    @Test
    void recordComponentTypesArePinned() {
        Map<Class<?>, List<String>> expected = new LinkedHashMap<>();
        expected.put(TemplateRequest.class, List.of("providerTemplateName: java.lang.String",
                "language: java.lang.String", "parameterNames: java.util.List<java.lang.String>",
                "enabled: java.lang.Boolean"));
        expected.put(TemplateResponse.class, List.of(
                "templateIntent: io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent", "mapped: boolean",
                "enabled: boolean", "providerTemplateName: java.lang.String", "language: java.lang.String",
                "parameterNames: java.util.List<java.lang.String>",
                "approvalStatus: io.github.stevdrey.dokene.template.domain.TemplateApprovalStatus",
                "approvalCheckedAt: java.time.Instant",
                "content: io.github.stevdrey.dokene.template.api.TemplateApiContract$TemplateContentResponse",
                "sendable: boolean", "version: java.lang.Long"));
        expected.put(TemplateContentResponse.class, List.of("headerText: java.lang.String",
                "bodyText: java.lang.String", "footerText: java.lang.String"));
        expected.put(TemplateListResponse.class, List.of(
                "items: java.util.List<io.github.stevdrey.dokene.template.api.TemplateApiContract$TemplateResponse>"));
        assertThat(Arrays.stream(TemplateApiContract.class.getDeclaredClasses()).filter(Class::isRecord)
                .filter(type -> !type.getSimpleName().equals("TemplateProblem")).toList())
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
        try (InputStream in = getClass().getResourceAsStream("/contracts/messaging/v1/templates.json")) {
            assertThat(in).isNotNull();
            return mapper.readTree(in);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read template fixture", exception);
        }
    }

    private static Map<String, JsonNode> named(JsonNode object) {
        var result = new LinkedHashMap<String, JsonNode>();
        object.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        assertThat(result).isNotEmpty();
        return result;
    }
}
