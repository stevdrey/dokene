// ABOUTME: Provider side of the frozen messaging v1 contract: shared fixtures must round-trip through the Java records.
// ABOUTME: A renamed, removed or retyped member, or a changed enum, fails here and in the frontend fixture test.
package io.github.stevdrey.dokene.messaging.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.ApprovalRequest;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.CancellationRequest;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.MessageEventsResponse;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.MessagePageResponse;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.MessageResponse;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.MessagingProblem;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.ResolutionOutcome;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.ResolutionRequest;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.SubmitMessageRequest;
import io.github.stevdrey.dokene.messaging.domain.ApprovalDecision;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.messaging.domain.MessageActorKind;
import io.github.stevdrey.dokene.messaging.domain.MessageChannel;
import io.github.stevdrey.dokene.messaging.domain.MessageEventType;
import io.github.stevdrey.dokene.messaging.domain.MessageOrigin;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.SendAttemptOutcome;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class MessagingContractFixturesTest {
    private static final String ROOT = "/contracts/messaging/v1/";

    /** The request reader of the messaging controllers must be at least this strict (ADR 0024 §1). */
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    @Test
    void enumFixturesEqualTheJavaEnums() {
        JsonNode enums = fixture("enums.json");
        Map<String, Class<? extends Enum<?>>> expected = new LinkedHashMap<>();
        expected.put("MessageStatus", MessageStatus.class);
        expected.put("MessageChannel", MessageChannel.class);
        expected.put("MessageOrigin", MessageOrigin.class);
        expected.put("ApprovalDecision", ApprovalDecision.class);
        expected.put("ResolutionOutcome", ResolutionOutcome.class);
        expected.put("SendAttemptOutcome", SendAttemptOutcome.class);
        expected.put("FailureCategory", FailureCategory.class);
        expected.put("MessageEventType", MessageEventType.class);
        expected.put("MessageActorKind", MessageActorKind.class);
        expected.put("MessagingErrorCode", MessagingErrorCode.class);
        expected.put("SemanticAction", SemanticAction.class);
        expected.put("SemanticTemplateIntent", SemanticTemplateIntent.class);

        assertThat(enums.propertyNames()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((name, type) -> assertThat(texts(enums.get(name)))
                .as(name)
                .containsExactlyElementsOf(Arrays.stream(type.getEnumConstants()).map(Enum::name).toList()));
    }

    @TestFactory
    Stream<DynamicTest> messageResponsesRoundTripUnchanged() {
        JsonNode messages = fixture("messages.json");
        var tests = new ArrayList<DynamicTest>();
        named(messages.get("details")).forEach((name, node) ->
                tests.add(dynamicTest("details." + name, () -> assertRoundTrip(node, MessageResponse.class))));
        named(messages.get("pages")).forEach((name, node) ->
                tests.add(dynamicTest("pages." + name, () -> assertRoundTrip(node, MessagePageResponse.class))));
        named(messages.get("events")).forEach((name, node) ->
                tests.add(dynamicTest("events." + name, () -> assertRoundTrip(node, MessageEventsResponse.class))));
        return tests.stream();
    }

    @Test
    void fixturesCoverEveryMessageStatusAndProviderOutcome() {
        JsonNode details = fixture("messages.json").get("details");
        var statuses = new ArrayList<MessageStatus>();
        var outcomes = new ArrayList<SendAttemptOutcome>();
        for (JsonNode node : named(details).values()) {
            MessageResponse response = mapper.treeToValue(node, MessageResponse.class);
            statuses.add(response.status());
            response.attempts().forEach(attempt -> outcomes.add(attempt.outcome()));
        }
        assertThat(statuses).containsAll(List.of(MessageStatus.values()));
        assertThat(outcomes).contains(SendAttemptOutcome.ACCEPTED, SendAttemptOutcome.FAILED_PERMANENT,
                SendAttemptOutcome.FAILED_TRANSIENT, SendAttemptOutcome.OUTCOME_UNKNOWN);
    }

    @TestFactory
    Stream<DynamicTest> validRequestsRoundTripAndInvalidRequestsAreRejected() {
        JsonNode requests = fixture("requests.json");
        Map<String, Class<?>> types = Map.of("submit", SubmitMessageRequest.class, "approval", ApprovalRequest.class,
                "cancellation", CancellationRequest.class, "resolution", ResolutionRequest.class);
        assertThat(requests.propertyNames()).containsExactlyInAnyOrderElementsOf(types.keySet());
        var tests = new ArrayList<DynamicTest>();
        types.forEach((operation, type) -> {
            named(requests.get(operation).get("valid")).forEach((name, node) -> tests.add(
                    dynamicTest(operation + ".valid." + name, () -> assertRoundTrip(node, type))));
            named(requests.get(operation).get("invalid")).forEach((name, node) -> tests.add(
                    dynamicTest(operation + ".invalid." + name, () -> assertThatThrownBy(
                            () -> mapper.treeToValue(node, type)).isInstanceOf(RuntimeException.class))));
        });
        return tests.stream();
    }

    @Test
    void everyErrorCodeHasExactlyOneProblemWithItsFixedStatus() {
        Map<String, JsonNode> valid = named(fixture("problems.json").get("valid"));
        assertThat(valid.keySet()).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(MessagingErrorCode.values()).map(Enum::name).toList());
        for (var entry : valid.entrySet()) {
            assertRoundTrip(entry.getValue(), MessagingProblem.class);
            MessagingErrorCode code = MessagingErrorCode.valueOf(entry.getKey());
            assertThat(mapper.treeToValue(entry.getValue(), MessagingProblem.class))
                    .isEqualTo(MessagingProblem.of(code));
        }
    }

    @TestFactory
    Stream<DynamicTest> invalidProblemsAreRejected() {
        return named(fixture("problems.json").get("invalid")).entrySet().stream()
                .map(entry -> dynamicTest(entry.getKey(), () -> assertThatThrownBy(
                        () -> mapper.treeToValue(entry.getValue(), MessagingProblem.class))
                        .isInstanceOf(RuntimeException.class)));
    }

    /** Requests may omit optional members; responses must emit every member, null included. */
    private void assertRoundTrip(JsonNode fixture, Class<?> type) {
        Object value = mapper.treeToValue(fixture, type);
        JsonNode written = mapper.readTree(mapper.writeValueAsString(value));
        boolean request = !type.getSimpleName().endsWith("Response") && type != MessagingProblem.class;
        assertThat(request ? withoutNulls(written) : written).isEqualTo(request ? withoutNulls(fixture) : fixture);
    }

    private JsonNode withoutNulls(JsonNode node) {
        if (node.isObject()) {
            var copy = mapper.createObjectNode();
            node.properties().forEach(entry -> {
                if (!entry.getValue().isNull()) {
                    copy.set(entry.getKey(), withoutNulls(entry.getValue()));
                }
            });
            return copy;
        }
        return node;
    }

    private JsonNode fixture(String name) {
        try (InputStream in = getClass().getResourceAsStream(ROOT + name)) {
            assertThat(in).as("fixture %s", name).isNotNull();
            return mapper.readTree(in);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read fixture " + name, exception);
        }
    }

    private static Map<String, JsonNode> named(JsonNode object) {
        var result = new LinkedHashMap<String, JsonNode>();
        object.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        assertThat(result).isNotEmpty();
        return result;
    }

    private static List<String> texts(JsonNode array) {
        var result = new ArrayList<String>();
        array.forEach(item -> result.add(item.asString()));
        return result;
    }
}
