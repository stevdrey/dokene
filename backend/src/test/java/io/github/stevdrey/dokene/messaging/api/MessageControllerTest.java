// ABOUTME: Standalone MockMvc tests for the messaging routes: header parsing, status codes, problem bodies, counters.
// ABOUTME: Services are mocked; the interceptor and security chain are covered by the endpoint integration test.
package io.github.stevdrey.dokene.messaging.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.messaging.application.CancellationCommand;
import io.github.stevdrey.dokene.messaging.application.CommandResult;
import io.github.stevdrey.dokene.messaging.application.MessageCursor;
import io.github.stevdrey.dokene.messaging.application.MessageDetail;
import io.github.stevdrey.dokene.messaging.application.MessageListQuery;
import io.github.stevdrey.dokene.messaging.application.MessagePage;
import io.github.stevdrey.dokene.messaging.application.MessageSummary;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageCommandService;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageQueryService;
import io.github.stevdrey.dokene.messaging.application.OutboundSendService;
import io.github.stevdrey.dokene.messaging.domain.MessageActorKind;
import io.github.stevdrey.dokene.messaging.domain.MessageCancellation;
import io.github.stevdrey.dokene.messaging.domain.MessageChannel;
import io.github.stevdrey.dokene.messaging.domain.MessageEvent;
import io.github.stevdrey.dokene.messaging.domain.MessageEventType;
import io.github.stevdrey.dokene.messaging.domain.MessageOrigin;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MessageControllerTest {
    private static final UUID MESSAGE_ID = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final String SUBMIT_BODY = """
            {"contactId":"%s","action":"GENERAL_CHECK_IN","templateIntent":"GENERAL_FOLLOW_UP",
             "body":"Hola","locale":"es-419","origin":"MANUAL"}
            """.formatted(UUID.randomUUID());

    private final OutboundMessageCommandService commands = mock();
    private final OutboundSendService sends = mock();
    private final OutboundMessageQueryService queries = mock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new MessageController(commands, sends, queries))
                .setControllerAdvice(new MessagingProblemHandler(registry)).build();
    }

    @Test
    void submitAnswers201WithLocationAndEtagThen200OnReplay() throws Exception {
        when(commands.submit(any())).thenReturn(new CommandResult(message(1), Optional.empty(), true));
        when(queries.detailAfterCommand(MESSAGE_ID)).thenReturn(detail(message(1)));

        mvc.perform(submit().header("If-Match", "\"7\"").header("Idempotency-Key", "k-1"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/messages/" + MESSAGE_ID))
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.messageId").value(MESSAGE_ID.toString()))
                .andExpect(jsonPath("$.customerDisplayName").value("Cliente"))
                .andExpect(jsonPath("$.approval").value((Object) null))
                .andExpect(jsonPath("$.lastAttempt").value((Object) null))
                .andExpect(jsonPath("$.contactRemoved").value(false));

        when(commands.submit(any())).thenReturn(new CommandResult(message(1), Optional.empty(), false));
        mvc.perform(submit().header("If-Match", "\"7\"").header("Idempotency-Key", "k-1"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().string("ETag", "\"1\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"7", "W/\"7\"", "\"07\"", "\"-1\"", "\"9223372036854775808\""})
    void nonCanonicalIfMatchIsInvalidInputBeforeAnyServiceCall(String ifMatch) throws Exception {
        mvc.perform(submit().header("If-Match", ifMatch).header("Idempotency-Key", "k-1"))
                .andExpect(status().isBadRequest())
                .andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        verifyNoInteractions(commands, queries);
    }

    @Test
    void missingHeadersMalformedKeysAndBadJsonAreInvalidInput() throws Exception {
        mvc.perform(submit().header("Idempotency-Key", "k-1"))
                .andExpect(status().isBadRequest()).andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        mvc.perform(submit().header("If-Match", "\"7\"").header("Idempotency-Key", "a b"))
                .andExpect(status().isBadRequest()).andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        mvc.perform(post("/api/customers/{id}/messages", CUSTOMER_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json").header("If-Match", "\"7\"").header("Idempotency-Key", "k-1"))
                .andExpect(status().isBadRequest()).andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        mvc.perform(post("/api/customers/not-a-uuid/messages").contentType(MediaType.APPLICATION_JSON)
                        .content(SUBMIT_BODY).header("If-Match", "\"7\"").header("Idempotency-Key", "k-1"))
                .andExpect(status().isBadRequest()).andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        mvc.perform(post("/api/customers/{id}/messages", CUSTOMER_ID).contentType(MediaType.TEXT_PLAIN)
                        .content("x").header("If-Match", "\"7\"").header("Idempotency-Key", "k-1"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(problem(415, "Unsupported Media Type", "INVALID_INPUT"));
        verifyNoInteractions(commands, queries);
    }

    @Test
    void approvalsNeedADecisionAndMapEachRefusalCode() throws Exception {
        mvc.perform(approval("{\"note\":\"x\"}")).andExpect(status().isBadRequest())
                .andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        mvc.perform(approval("{\"decision\":\"MAYBE\"}")).andExpect(status().isBadRequest())
                .andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        verifyNoInteractions(commands);

        when(commands.approve(any())).thenThrow(new MessagingRefusedException(MessagingErrorCode.STALE_VERSION));
        mvc.perform(approval("{\"decision\":\"APPROVE\"}")).andExpect(status().isConflict())
                .andExpect(problem(409, "Conflict", "STALE_VERSION"));

        when(commands.reject(any())).thenThrow(new MessagingRefusedException(MessagingErrorCode.NOT_FOUND));
        mvc.perform(approval("{\"decision\":\"REJECT\"}")).andExpect(status().isNotFound())
                .andExpect(problem(404, "Not Found", "NOT_FOUND"));

        assertThat(registry.counter(MessagingProblemHandler.REFUSALS, "operation", "approval", "code", "STALE_VERSION")
                .count()).isEqualTo(1.0);
        assertThat(registry.counter(MessagingProblemHandler.REFUSALS, "operation", "approval", "code", "NOT_FOUND")
                .count()).isEqualTo(1.0);
        assertThat(registry.counter(MessagingProblemHandler.REFUSALS, "operation", "approval", "code", "INVALID_INPUT")
                .count()).isEqualTo(2.0);
    }

    @Test
    void listValidatesItsFiltersBeforeQuerying() throws Exception {
        mvc.perform(get("/api/messages").param("limit", "0")).andExpect(status().isBadRequest())
                .andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        mvc.perform(get("/api/messages").param("limit", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/messages").param("limit", "many")).andExpect(status().isBadRequest())
                .andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        mvc.perform(get("/api/messages").param("status", "")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/messages").param("status", "OPEN")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/messages").param("cursor", "***")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/messages").param("customerId", "nope")).andExpect(status().isBadRequest());
        verifyNoInteractions(queries);
    }

    @Test
    void detailCarriesTheEtagOfTheBodyVersion() throws Exception {
        when(queries.detail(MESSAGE_ID)).thenReturn(detail(message(4)));
        mvc.perform(get("/api/messages/{id}", MESSAGE_ID)).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\"")).andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.body").value("Hola"));
    }


    // covers: AC-3 (cancel route, optional body, replay), AC-2 (201 then 200)
    @Test
    void cancellationsAcceptAnEmptyBodyPassTheNoteThroughAndReplayAs200() throws Exception {
        when(commands.cancel(any())).thenReturn(new CommandResult(message(3), Optional.empty(), true));
        MessageCancellation cancellation = new MessageCancellation(UUID.randomUUID(), UUID.randomUUID(), MESSAGE_ID,
                MessageStatus.APPROVED, "cambio", NOW, UUID.randomUUID(), UUID.randomUUID());
        when(queries.detailAfterCommand(MESSAGE_ID)).thenReturn(new MessageDetail(message(3), "Cliente",
                Optional.empty(), Optional.of(cancellation), List.of(), false));

        mvc.perform(cancellation().content("{\"note\":\"cambio\"}").header("Idempotency-Key", "c-1"))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"3\""))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.cancellation.statusFrom").value("APPROVED"))
                .andExpect(jsonPath("$.cancellation.note").value("cambio"));
        verify(commands).cancel(new CancellationCommand(MESSAGE_ID, "cambio", "2", "c-1"));

        when(commands.cancel(any())).thenReturn(new CommandResult(message(3), Optional.empty(), false));
        mvc.perform(post("/api/messages/{id}/cancellations", MESSAGE_ID).header("If-Match", "\"2\"")
                        .header("Idempotency-Key", "c-1"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"3\""));
        verify(commands).cancel(new CancellationCommand(MESSAGE_ID, null, "2", "c-1"));
    }

    // covers: AC-6 (a denial raised inside the service is still the problem body, never a stack trace)
    @Test
    void aServiceSideDenialIsAForbiddenProblem() throws Exception {
        when(commands.cancel(any())).thenThrow(new TenantAccessDeniedException("needs MESSAGE_APPROVE from APPROVED"));

        mvc.perform(cancellation().header("Idempotency-Key", "c-2")).andExpect(status().isForbidden())
                .andExpect(problem(403, "Forbidden", "FORBIDDEN"));
        when(queries.detail(MESSAGE_ID)).thenThrow(new MessagingRefusedException(MessagingErrorCode.NOT_FOUND));
        mvc.perform(get("/api/messages/{id}", MESSAGE_ID)).andExpect(status().isNotFound())
                .andExpect(problem(404, "Not Found", "NOT_FOUND"));
    }

    // covers: AC-4 (idempotency key grammar), AC-5
    @ParameterizedTest
    @ValueSource(strings = {"", "a b", "ключ", "k/1", "k=1"})
    void idempotencyKeysOutsideTheGrammarAreInvalidInput(String key) throws Exception {
        mvc.perform(cancellation().header("Idempotency-Key", key)).andExpect(status().isBadRequest())
                .andExpect(problem(400, "Bad Request", "INVALID_INPUT"));
        verifyNoInteractions(commands, queries);
    }

    @Test
    void anIdempotencyKeyOf128CharactersIsTheLongestAccepted() throws Exception {
        when(commands.cancel(any())).thenReturn(new CommandResult(message(3), Optional.empty(), true));
        when(queries.detailAfterCommand(MESSAGE_ID)).thenReturn(detail(message(3)));

        mvc.perform(cancellation().header("Idempotency-Key", "k".repeat(128))).andExpect(status().isCreated());
        mvc.perform(cancellation().header("Idempotency-Key", "k".repeat(129))).andExpect(status().isBadRequest());
    }

    // covers: AC-8 (defaults, filters, cursor encoding, no body on list items)
    @Test
    void listDefaultsToFiftyPassesFiltersThroughAndEncodesTheNextCursor() throws Exception {
        UUID customerId = UUID.randomUUID();
        MessageSummary summary = new MessageSummary(MESSAGE_ID, customerId, "Cliente", UUID.randomUUID(),
                "+50688887777", MessageStatus.PENDING_APPROVAL, false, SemanticAction.GENERAL_CHECK_IN,
                SemanticTemplateIntent.GENERAL_FOLLOW_UP, "es-419", MessageOrigin.MANUAL, true, NOW, NOW, 1);
        MessageCursor next = new MessageCursor(NOW, MESSAGE_ID);
        when(queries.list(any())).thenReturn(new MessagePage(List.of(summary), Optional.of(next)));

        mvc.perform(get("/api/messages")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].messageId").value(MESSAGE_ID.toString()))
                .andExpect(jsonPath("$.items[0].customerDisplayName").value("Cliente"))
                .andExpect(jsonPath("$.items[0].contactRemoved").value(true))
                .andExpect(jsonPath("$.items[0].action").value("GENERAL_CHECK_IN"))
                .andExpect(jsonPath("$.items[0].body").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").value(next.encode()));
        verify(queries).list(new MessageListQuery(Set.of(), Optional.empty(), Optional.empty(), 50));

        when(queries.list(any())).thenReturn(new MessagePage(List.of(), Optional.empty()));
        mvc.perform(get("/api/messages").param("status", "PENDING_APPROVAL").param("status", "APPROVED")
                        .param("status", "APPROVED").param("customerId", customerId.toString())
                        .param("cursor", next.encode()).param("limit", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").value((Object) null));
        verify(queries).list(new MessageListQuery(Set.of(MessageStatus.PENDING_APPROVAL, MessageStatus.APPROVED),
                Optional.of(customerId), Optional.of(next), 100));
    }

    // covers: AC-1, AC-9 (events representation carries no note, body, phone or provider id)
    @Test
    void eventsAreRepresentedWithoutAnyFreeTextOrPhone() throws Exception {
        UUID membershipId = UUID.randomUUID();
        MessageEvent submitted = new MessageEvent(UUID.randomUUID(), UUID.randomUUID(), MESSAGE_ID, 1,
                MessageEventType.SUBMITTED, null, MessageStatus.PENDING_APPROVAL, true, MessageActorKind.MEMBER,
                membershipId, null, null, NOW);
        MessageEvent attempt = new MessageEvent(UUID.randomUUID(), UUID.randomUUID(), MESSAGE_ID, 2,
                MessageEventType.SEND_ATTEMPT_STARTED, MessageStatus.QUEUED, MessageStatus.SENDING, true,
                MessageActorKind.SYSTEM, null, null, 1, NOW.plusSeconds(1));
        when(queries.events(MESSAGE_ID)).thenReturn(List.of(submitted, attempt));

        mvc.perform(get("/api/messages/{id}/events", MESSAGE_ID)).andExpect(status().isOk())
                .andExpect(content().json("""
                        {"events":[
                          {"sequenceNumber":1,"eventType":"SUBMITTED","statusFrom":null,"statusTo":"PENDING_APPROVAL",
                           "occurredAt":"2026-10-10T12:00:00Z","actorKind":"MEMBER","actorMembershipId":"%s",
                           "applied":true,"failureCategory":null,"attemptNumber":null},
                          {"sequenceNumber":2,"eventType":"SEND_ATTEMPT_STARTED","statusFrom":"QUEUED","statusTo":"SENDING",
                           "occurredAt":"2026-10-10T12:00:01Z","actorKind":"SYSTEM","actorMembershipId":null,
                           "applied":true,"failureCategory":null,"attemptNumber":1}]}
                        """.formatted(membershipId), JsonCompareMode.STRICT));

        when(queries.events(MESSAGE_ID)).thenThrow(new MessagingRefusedException(MessagingErrorCode.NOT_FOUND));
        mvc.perform(get("/api/messages/{id}/events", MESSAGE_ID)).andExpect(status().isNotFound())
                .andExpect(problem(404, "Not Found", "NOT_FOUND"));
    }

    // covers: AC-5 (406 is also a problem body on these routes, and a mutation refuses before running the command)
    @Test
    void anUnacceptableAcceptHeaderIsAProblemBodyBeforeAnythingRuns() throws Exception {
        when(queries.detail(MESSAGE_ID)).thenReturn(detail(message(1)));

        mvc.perform(get("/api/messages/{id}", MESSAGE_ID).accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotAcceptable()).andExpect(problem(406, "Not Acceptable", "INVALID_INPUT"));
        mvc.perform(cancellation().accept(MediaType.TEXT_HTML).header("Idempotency-Key", "c-9"))
                .andExpect(status().isNotAcceptable()).andExpect(problem(406, "Not Acceptable", "INVALID_INPUT"));
        verifyNoInteractions(commands);
    }

    // covers: AC-5 (the mutation side Accept check agrees with Spring's negotiation)
    @ParameterizedTest
    @ValueSource(strings = {"*/*", "application/*", "application/json;q=0.5, text/html", "application/*+json"})
    void acceptableAcceptHeadersLetAMutationRun(String accept) throws Exception {
        when(commands.cancel(any())).thenReturn(new CommandResult(message(3), Optional.empty(), true));
        when(queries.detailAfterCommand(MESSAGE_ID)).thenReturn(detail(message(3)));

        mvc.perform(cancellation().header("Accept", accept).header("Idempotency-Key", "c-3"))
                .andExpect(status().isCreated());
    }

    @Test
    void unacceptableOrOverlongAcceptHeadersRefuseAMutationBeforeItRuns() throws Exception {
        String fiftyOneTypes = String.join(", ", java.util.Collections.nCopies(50, "text/plain")) + ", application/json";
        for (String accept : List.of("text/html", "application/problem+json", "not a media type", fiftyOneTypes)) {
            mvc.perform(cancellation().header("Accept", accept).header("Idempotency-Key", "c-4"))
                    .andExpect(status().isNotAcceptable()).andExpect(problem(406, "Not Acceptable", "INVALID_INPUT"));
        }
        verifyNoInteractions(commands);
    }

    private static MockHttpServletRequestBuilder cancellation() {
        return post("/api/messages/{id}/cancellations", MESSAGE_ID).contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "\"2\"");
    }

    private static MockHttpServletRequestBuilder submit() {
        return post("/api/customers/{id}/messages", CUSTOMER_ID).contentType(MediaType.APPLICATION_JSON)
                .content(SUBMIT_BODY);
    }

    private static MockHttpServletRequestBuilder approval(String body) {
        return post("/api/messages/{id}/approvals", MESSAGE_ID).contentType(MediaType.APPLICATION_JSON)
                .content(body).header("If-Match", "\"1\"").header("Idempotency-Key", "k-" + UUID.randomUUID());
    }

    /** Exactly four members, in order, with the problem content type. */
    private static org.springframework.test.web.servlet.ResultMatcher problem(int status, String title, String code) {
        return result -> {
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).match(result);
            content().json("{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"code\":\"%s\"}"
                    .formatted(title, status, code), JsonCompareMode.STRICT).match(result);
        };
    }

    static OutboundMessage message(long version) {
        return new OutboundMessage(MESSAGE_ID, UUID.randomUUID(), CUSTOMER_ID, UUID.randomUUID(), "+50688887777",
                MessageChannel.WHATSAPP, MessageStatus.PENDING_APPROVAL, false, SemanticAction.GENERAL_CHECK_IN,
                SemanticTemplateIntent.GENERAL_FOLLOW_UP, "es-419", MessageOrigin.MANUAL, "Hola", 7,
                UUID.randomUUID(), 0, null, null, null, null, null, UUID.randomUUID(), UUID.randomUUID(), NOW, NOW,
                version);
    }

    static MessageDetail detail(OutboundMessage message) {
        return new MessageDetail(message, "Cliente", Optional.empty(), Optional.empty(), List.of(), false);
    }
}
