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
import java.lang.reflect.RecordComponent;
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
            // Null stripping below would hide a new optional member, so the valid examples must name every member.
            tests.add(dynamicTest(operation + ".valid examples cover every member", () -> {
                var members = new LinkedHashSet<String>();
                named(requests.get(operation).get("valid")).values()
                        .forEach(node -> members.addAll(node.propertyNames()));
                assertThat(members).containsExactlyInAnyOrderElementsOf(Arrays.stream(type.getRecordComponents())
                        .map(RecordComponent::getName).toList());
            }));
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

    /**
     * JSON round trips cannot see a retype that serializes the same ({@code UUID} to {@code String}, {@code Instant}
     * to {@code String}), so the Java component types are pinned here; every record of the contract must be listed.
     */
    @Test
    void recordComponentTypesArePinned() {
        String contract = MessagingApiContract.class.getName() + "$";
        String domain = "io.github.stevdrey.dokene.messaging.domain.";
        String ai = "io.github.stevdrey.dokene.ai.domain.";
        Map<Class<?>, List<String>> expected = new LinkedHashMap<>();
        expected.put(SubmitMessageRequest.class, List.of("contactId: java.util.UUID",
                "action: " + ai + "SemanticAction", "templateIntent: " + ai + "SemanticTemplateIntent",
                "body: java.lang.String", "locale: java.lang.String", "origin: " + domain + "MessageOrigin"));
        expected.put(ApprovalRequest.class, List.of("decision: " + domain + "ApprovalDecision",
                "note: java.lang.String"));
        expected.put(CancellationRequest.class, List.of("note: java.lang.String"));
        expected.put(ResolutionRequest.class, List.of("outcome: " + contract + "ResolutionOutcome",
                "note: java.lang.String"));
        expected.put(MessageResponse.class, List.of("messageId: java.util.UUID", "customerId: java.util.UUID",
                "contactId: java.util.UUID", "contactRemoved: boolean", "recipientPhone: java.lang.String",
                "channel: " + domain + "MessageChannel", "status: " + domain + "MessageStatus",
                "outcomeUnknown: boolean", "failureCategory: " + domain + "FailureCategory",
                "action: " + ai + "SemanticAction", "templateIntent: " + ai + "SemanticTemplateIntent",
                "locale: java.lang.String", "origin: " + domain + "MessageOrigin", "body: java.lang.String",
                "sourcePolicyVersion: long", "approval: " + contract + "ApprovalSummary",
                "attempts: java.util.List<" + contract + "AttemptSummary>", "providerMessageId: java.lang.String",
                "sentAt: java.time.Instant", "deliveredAt: java.time.Instant", "readAt: java.time.Instant",
                "createdByMembershipId: java.util.UUID", "createdAt: java.time.Instant",
                "updatedAt: java.time.Instant", "version: long"));
        expected.put(MessagingApiContract.ApprovalSummary.class, List.of(
                "decision: " + domain + "ApprovalDecision", "decidedAt: java.time.Instant",
                "decidedByMembershipId: java.util.UUID"));
        expected.put(MessagingApiContract.AttemptSummary.class, List.of("attemptNumber: int",
                "startedAt: java.time.Instant", "outcome: " + domain + "SendAttemptOutcome",
                "failureCategory: " + domain + "FailureCategory"));
        expected.put(MessagePageResponse.class, List.of("items: java.util.List<" + contract + "MessageListItem>",
                "nextCursor: java.lang.String"));
        expected.put(MessagingApiContract.MessageListItem.class, List.of("messageId: java.util.UUID",
                "customerId: java.util.UUID", "contactRemoved: boolean", "status: " + domain + "MessageStatus",
                "outcomeUnknown: boolean", "action: " + ai + "SemanticAction",
                "templateIntent: " + ai + "SemanticTemplateIntent", "origin: " + domain + "MessageOrigin",
                "createdAt: java.time.Instant", "updatedAt: java.time.Instant", "version: long"));
        expected.put(MessageEventsResponse.class, List.of("messageId: java.util.UUID",
                "events: java.util.List<" + contract + "MessageEventItem>"));
        expected.put(MessagingApiContract.MessageEventItem.class, List.of("sequenceNumber: long",
                "type: " + domain + "MessageEventType", "statusFrom: " + domain + "MessageStatus",
                "statusTo: " + domain + "MessageStatus", "applied: boolean",
                "actorKind: " + domain + "MessageActorKind", "failureCategory: " + domain + "FailureCategory",
                "attemptNumber: java.lang.Integer", "occurredAt: java.time.Instant"));
        expected.put(MessagingProblem.class, List.of("type: java.lang.String", "title: java.lang.String",
                "status: int", "code: " + domain + "MessagingErrorCode"));

        assertThat(Arrays.stream(MessagingApiContract.class.getDeclaredClasses()).filter(Class::isRecord).toList())
                .containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((type, components) -> assertThat(Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName() + ": " + component.getGenericType().getTypeName()).toList())
                .as(type.getSimpleName())
                .containsExactlyElementsOf(components));
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
