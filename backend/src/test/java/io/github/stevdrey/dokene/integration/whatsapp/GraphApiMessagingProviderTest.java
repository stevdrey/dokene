// ABOUTME: Drives the Meta send adapter against a loopback stub replaying synthetic Graph answers (issue 137).
// ABOUTME: Covers the request shape, every result class, uncertain outcomes, fail-closed paths and secret hygiene.
package io.github.stevdrey.dokene.integration.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.ResolvedSecret;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretReference;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Tenant;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationRepository;
import io.github.stevdrey.dokene.integration.application.WhatsAppOutboundSafetyControl;
import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.messaging.application.OutboundSendCommand;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.ParameterFormat;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult.Accepted;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult.FailedTransiently;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult.OutcomeUnknown;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult.RejectedPermanently;
import io.github.stevdrey.dokene.template.domain.TemplateParameterValues;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class GraphApiMessagingProviderTest {
    private static final String PHONE_NUMBER_ID = "100000000000001";
    private static final String TOKEN = "SYNTHETIC-NOT-A-REAL-TOKEN";
    private static final String RECIPIENT = "+50600000001";
    /** One code point, two UTF-16 units. */
    private static final String EMOJI = "\uD83C\uDF81";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode FIXTURES = loadFixtures();

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile byte[] body = new byte[0];
    private volatile long delayMillis;
    private volatile String location;
    private final TenantId tenantId = new TenantId(UUID.randomUUID());
    private final WhatsAppIntegrationRepository integrations = mock(WhatsAppIntegrationRepository.class);
    private final IntegrationSecretResolver resolver = mock(IntegrationSecretResolver.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<IntegrationSecretResolver> secrets = mock(ObjectProvider.class);
    private final TenantContextProvider contexts = mock(TenantContextProvider.class);
    private GraphApiMessagingProvider provider;
    private WhatsAppIntegration configured;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().toString());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            try (InputStream in = exchange.getRequestBody()) {
                requestBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            try {
                Thread.sleep(delayMillis);
                if (location != null) {
                    exchange.getResponseHeaders().add("Location", location);
                }
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    exchange.getResponseBody().write(body);
                }
            } catch (IOException | InterruptedException clientGone) {
                // The adapter gave up on the exchange: exactly what the deadline is for.
            } finally {
                exchange.close();
            }
        });
        server.start();
        configured = integration(true, true);
        when(integrations.findByTenant(tenantId)).thenReturn(Optional.of(configured));
        when(contexts.requireCurrent()).thenReturn(new TenantContext(tenantId, new IdentityId(UUID.randomUUID()),
                new TenantMembershipId(UUID.randomUUID()), TenantRole.ADMIN));
        when(secrets.getIfAvailable()).thenReturn(resolver);
        when(resolver.resolve(any(), any())).thenAnswer(call -> new ResolvedSecret(TOKEN.toCharArray()));
        provider = providerFor(server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void postsTheApprovedTemplateWithBoundParametersInOrderAndTheTenantCredentialAsBearerOnly() throws Exception {
        replay("accepted");

        assertThat(provider.send(command(List.of("Ana", "pedido 42")))).isEqualTo(new Accepted("wamid.SYNTHETIC-0001"));

        assertThat(requests).hasValue(1);
        assertThat(method.get()).isEqualTo("POST");
        assertThat(path.get()).isEqualTo("/v23.0/" + PHONE_NUMBER_ID + "/messages").doesNotContain(TOKEN);
        assertThat(authorization.get()).isEqualTo("Bearer " + TOKEN);
        assertThat(contentType.get()).isEqualTo("application/json");
        assertThat(JSON.readTree(requestBody.get())).isEqualTo(JSON.readTree("""
                {"messaging_product":"whatsapp","recipient_type":"individual","to":"+50600000001","type":"template",
                 "template":{"name":"repeat_purchase_v1","language":{"code":"es_MX"},
                   "components":[{"type":"body","parameters":[{"type":"text","text":"Ana"},
                                                              {"type":"text","text":"pedido 42"}]}]}}
                """));
        assertThat(requestBody.get()).doesNotContain(TOKEN);
        verify(resolver).resolve(new Tenant(tenantId), new SecretReference("synthetic-wa-token"));
    }

    @Test
    void namesEachParameterOfANamedTemplateInTheBoundOrder() throws Exception {
        replay("accepted");

        assertThat(provider.send(namedCommand(List.of("customer_name", "order_ref"), List.of("Ana", "pedido 42"))))
                .isEqualTo(new Accepted("wamid.SYNTHETIC-0001"));

        assertThat(JSON.readTree(requestBody.get())).isEqualTo(JSON.readTree("""
                {"messaging_product":"whatsapp","recipient_type":"individual","to":"+50600000001","type":"template",
                 "template":{"name":"repeat_purchase_v1","language":{"code":"es_MX"},
                   "components":[{"type":"body","parameters":[
                     {"type":"text","parameter_name":"customer_name","text":"Ana"},
                     {"type":"text","parameter_name":"order_ref","text":"pedido 42"}]}]}}
                """));
    }

    @Test
    void aNamedTemplateWithAnInvalidParameterNameIsRefusedWithoutCallingTheProvider() {
        for (String name : List.of("Customer_Name", "1st", "customer-name", "", "a".repeat(33))) {
            assertThat(provider.send(namedCommand(List.of(name), List.of("Ana"))))
                    .as(name).isEqualTo(new RejectedPermanently(FailureCategory.TEMPLATE_REJECTED));
        }
        // A repeated name would fill one placeholder twice and leave another empty.
        assertThat(provider.send(namedCommand(List.of("customer_name", "customer_name"), List.of("Ana", "Ana"))))
                .isEqualTo(new RejectedPermanently(FailureCategory.TEMPLATE_REJECTED));
        assertThat(requests).hasValue(0);
    }

    @Test
    void aPositionalTemplateNeverSendsItsBoundParameterNames() throws Exception {
        replay("accepted");

        assertThat(provider.send(command(List.of("Ana", "pedido 42")))).isEqualTo(new Accepted("wamid.SYNTHETIC-0001"));

        JsonNode parameters = JSON.readTree(requestBody.get()).at("/template/components/0/parameters");
        assertThat(parameters.size()).isEqualTo(2);
        for (JsonNode parameter : parameters) {
            assertThat(parameter.has("parameter_name")).isFalse();
        }
    }

    @Test
    void aCommandCannotLeaveAParameterUnnamed() {
        assertThatThrownBy(() -> namedCommand(List.of("customer_name"), List.of("Ana", "pedido 42")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void omitsTheBodyComponentForATemplateWithoutParameters() throws Exception {
        replay("acceptedWithoutStatus");

        assertThat(provider.send(command(List.of()))).isEqualTo(new Accepted("wamid.SYNTHETIC-0002"));
        assertThat(JSON.readTree(requestBody.get()).path("template").has("components")).isFalse();
    }

    @Test
    void heldForQualityAssessmentIsAcceptanceButAPausedOrIdlessAnswerIsUncertain() {
        replay("heldForQualityAssessment");
        assertThat(send()).isEqualTo(new Accepted("wamid.SYNTHETIC-0003"));

        replay("paused");
        assertThat(send()).isEqualTo(new OutcomeUnknown(Optional.of("wamid.SYNTHETIC-0004")));

        for (String fixture : List.of("acceptedWithoutId", "acceptedWithUnsafeId")) {
            replay(fixture);
            assertThat(send()).as(fixture).isEqualTo(new OutcomeUnknown(Optional.empty()));
        }
    }

    @Test
    void aMalformedOrOversizedSuccessIsUncertainBecauseMetaMayHaveTakenTheMessage() {
        status = 200;
        body = "{not json".getBytes(StandardCharsets.UTF_8);
        assertThat(send()).isEqualTo(new OutcomeUnknown(Optional.empty()));

        body = ("{\"messages\":[{\"id\":\"wamid.SYNTHETIC-0005\"}],\"pad\":\""
                + " ".repeat(GraphApiHttp.MAX_BODY_BYTES) + "\"}").getBytes(StandardCharsets.UTF_8);
        assertThat(send()).isEqualTo(new OutcomeUnknown(Optional.empty()));
    }

    @ParameterizedTest
    @CsvSource({
            "templateParameterMismatch, TEMPLATE_REJECTED",
            "templateMissing, TEMPLATE_REJECTED",
            "invalidParameter, TEMPLATE_REJECTED",
            "recipientUndeliverable, INVALID_RECIPIENT",
            "accountRestricted, POLICY_VIOLATION",
            "tokenExpiredWithSecrets, AUTHENTICATION",
            "permissionMissing, AUTHENTICATION"})
    void definitiveRefusalsArePermanentWithANormalizedCategory(String fixture, FailureCategory category) {
        replay(fixture);

        assertThat(send()).isEqualTo(new RejectedPermanently(category));
        assertThat(requests).as("never retried").hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({
            "throughputLimit, RATE_LIMITED",
            "appRateLimit, RATE_LIMITED",
            "unknownErrorRefused, PROVIDER_UNAVAILABLE"})
    void refusalsThatProveNothingWasSentAreTransient(String fixture, FailureCategory category) {
        replay(fixture);

        assertThat(send()).isEqualTo(new FailedTransiently(category));
        assertThat(requests).as("never retried").hasValue(1);
    }

    @Test
    void statusesWithoutAMetaErrorFallBackOnTheStatusAlone() {
        body = new byte[0];
        status = 401;
        assertThat(send()).isEqualTo(new RejectedPermanently(FailureCategory.AUTHENTICATION));
        status = 429;
        assertThat(send()).isEqualTo(new FailedTransiently(FailureCategory.RATE_LIMITED));
        status = 408;
        assertThat(send()).isEqualTo(new FailedTransiently(FailureCategory.PROVIDER_UNAVAILABLE));
        status = 418;
        assertThat(send()).isEqualTo(new RejectedPermanently(FailureCategory.UNKNOWN));
    }

    @ParameterizedTest
    @CsvSource({"genericServerError", "temporaryDowntime", "serviceUnavailable"})
    void aServerErrorIsUncertainWhateverMetaCodeItCarriesSoItIsNeverResent(String fixture) {
        replay(fixture);

        assertThat(send()).isEqualTo(new OutcomeUnknown(Optional.empty()));
        assertThat(requests).as("exactly one POST, never a second send").hasValue(1);
    }

    @ParameterizedTest
    @CsvSource({"wamid.HBgLMTY1MDM4Nzk0MzkVAgARGBI3MTE5MjVBOTE3MDk5QUVFM0YA", "invalid-id"})
    void theProviderMessageIdIsOpaqueAndNotRequiredToCarryAPrefix(String id) {
        status = 200;
        body = messageWithId(id);

        assertThat(send()).isEqualTo(new Accepted(id));
    }

    @Test
    void aMessageIdThatCannotBeStoredOrMatchedSafelyIsNotAcceptance() {
        for (String id : List.of("", "wamid. bad", "wamid.\u0001bad", "w".repeat(129))) {
            status = 200;
            body = messageWithId(id);
            assertThat(send()).as("id %s", id).isEqualTo(new OutcomeUnknown(Optional.empty()));
        }
    }

    @Test
    void anOpaqueServerErrorOrGatewayPageIsUncertainAndNeverRetried() {
        replay("unknownServerError");
        assertThat(send()).isEqualTo(new OutcomeUnknown(Optional.empty()));

        body = "<html>Bad gateway</html>".getBytes(StandardCharsets.UTF_8);
        for (int gatewayStatus : new int[] {500, 502, 503, 504}) {
            status = gatewayStatus;
            assertThat(send()).as("status %s", gatewayStatus).isEqualTo(new OutcomeUnknown(Optional.empty()));
        }
        assertThat(requests).hasValue(5);
    }

    @Test
    void aRedirectIsNotFollowedAndIsUncertainBecauseThePostAlreadyLeft() {
        status = 302;
        body = new byte[0];
        location = "https://attacker.example/steal";

        assertThat(send()).isEqualTo(new OutcomeUnknown(Optional.empty()));
        assertThat(requests).hasValue(1);
    }

    @Test
    void aTimeoutAfterSubmissionIsUncertainAndBounded() {
        replay("accepted");
        delayMillis = 2_000;

        long started = System.nanoTime();
        assertThat(send()).isEqualTo(new OutcomeUnknown(Optional.empty()));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1_500));
        assertThat(requests).hasValue(1);
    }

    @Test
    void anUnreachableProviderIsTransientBecauseNothingLeft() throws IOException {
        int port;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = closed.getLocalPort();
        }

        assertThat(providerFor(port).send(command(List.of("Ana"))))
                .isEqualTo(new FailedTransiently(FailureCategory.PROVIDER_UNAVAILABLE));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "50600000001        | repeat_purchase_v1 | es_MX  | Ana          | INVALID_RECIPIENT",
            "+50600000001       | Repeat-Purchase    | es_MX  | Ana          | TEMPLATE_REJECTED",
            "+50600000001       | repeat_purchase_v1 | es-419 | Ana          | TEMPLATE_REJECTED",
            "+50600000001       | repeat_purchase_v1 | es_MX  | ''           | TEMPLATE_REJECTED",
            "+50600000001       | repeat_purchase_v1 | es_MX  | 'a\u202Eb'  | TEMPLATE_REJECTED",
            "+50600000001       | repeat_purchase_v1 | es_MX  | 'a\tb'       | TEMPLATE_REJECTED",
            "+50600000001       | repeat_purchase_v1 | es_MX  | 'a     b'    | TEMPLATE_REJECTED"})
    void anInvalidCommandIsRefusedWithoutCallingTheProvider(String recipient, String template, String language,
            String parameter, FailureCategory category) {
        OutboundSendCommand invalid = new OutboundSendCommand(recipient, template, language,
                ParameterFormat.POSITIONAL, List.of("customer_name"), List.of(parameter), "es-419", UUID.randomUUID(),
                WhatsAppOutboundSafetyControl.senderRevision(configured));

        assertThat(provider.send(invalid)).isEqualTo(new RejectedPermanently(category));
        assertThat(requests).hasValue(0);
    }

    @Test
    void tooManyOrOverlongOrMultilineParametersAreRefusedWithoutCallingTheProvider() {
        List<List<String>> invalid = List.of(
                java.util.Collections.nCopies(11, "x"),
                List.of("x".repeat(TemplateParameterValues.MAX_LENGTH + 1)),
                List.of(EMOJI.repeat(TemplateParameterValues.MAX_LENGTH + 1)),
                List.of("line\nbreak"));
        for (List<String> parameters : invalid) {
            assertThat(provider.send(command(parameters)))
                    .isEqualTo(new RejectedPermanently(FailureCategory.TEMPLATE_REJECTED));
        }
        assertThat(requests).hasValue(0);
    }

    @Test
    void parameterLengthIsCountedInCodePointsLikeTheApprovalContract() throws Exception {
        replay("accepted");
        String emoji = EMOJI.repeat(1000);
        assertThat(emoji.length()).isGreaterThan(TemplateParameterValues.MAX_LENGTH);

        assertThat(provider.send(command(List.of(emoji)))).isEqualTo(new Accepted("wamid.SYNTHETIC-0001"));
        assertThat(JSON.readTree(requestBody.get()).at("/template/components/0/parameters/0/text").asString())
                .isEqualTo(emoji);
    }

    @Test
    void aMalformedResponseHeaderAfterThePostLeftIsUncertainNotRetryable() throws Exception {
        try (ServerSocket raw = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            AtomicInteger received = new AtomicInteger();
            Thread.ofVirtual().start(() -> {
                try (Socket client = raw.accept()) {
                    InputStream in = client.getInputStream();
                    byte[] buffer = new byte[8192];
                    StringBuilder seen = new StringBuilder();
                    // Read the headers and the whole JSON body, so the request has provably left.
                    while (!seen.toString().contains("\r\n\r\n{") || !seen.toString().endsWith("}")) {
                        int read = in.read(buffer);
                        if (read < 0) {
                            break;
                        }
                        seen.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                    received.incrementAndGet();
                    client.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: abc\r\n\r\n{}"
                                    .getBytes(StandardCharsets.UTF_8));
                    client.getOutputStream().flush();
                    Thread.sleep(1_000);
                } catch (IOException | InterruptedException ignored) {
                    // The adapter gave up on the exchange.
                }
            });

            assertThat(providerFor(raw.getLocalPort()).send(command(List.of("Ana"))))
                    .isEqualTo(new OutcomeUnknown(Optional.empty()));
            assertThat(received).hasValue(1);
        }
    }

    @Test
    void failsClosedWithoutCallingTheProviderWhenTheSenderIsNotUsable() {
        when(integrations.findByTenant(tenantId)).thenReturn(Optional.empty());
        assertThat(send()).isEqualTo(new RejectedPermanently(FailureCategory.POLICY_VIOLATION));

        when(integrations.findByTenant(tenantId)).thenReturn(Optional.of(integration(false, false)));
        assertThat(send()).isEqualTo(new RejectedPermanently(FailureCategory.POLICY_VIOLATION));

        when(integrations.findByTenant(tenantId)).thenReturn(Optional.of(integration(true, false)));
        assertThat(send()).isEqualTo(new RejectedPermanently(FailureCategory.POLICY_VIOLATION));

        when(integrations.findByTenant(tenantId)).thenReturn(Optional.of(configured));
        when(secrets.getIfAvailable()).thenReturn(null);
        assertThat(send()).isEqualTo(new RejectedPermanently(FailureCategory.AUTHENTICATION));

        when(secrets.getIfAvailable()).thenReturn(resolver);
        when(resolver.resolve(any(), any())).thenThrow(new IllegalStateException("no secret " + TOKEN));
        assertThat(send()).isEqualTo(new RejectedPermanently(FailureCategory.AUTHENTICATION));

        assertThat(requests).hasValue(0);
    }

    @Test
    void neverSendsFromASenderOtherThanTheOneTheApprovalBound() {
        OutboundSendCommand approved = command(List.of("Ana"));
        WhatsAppIntegration rotated = configured.rotateCredential(Instant.now()).verifyOwnership(Instant.now());
        WhatsAppIntegration otherNumber = configured
                .update("100000000000002", "400000000000001", null, true, "synthetic-wa-token", Instant.now())
                .verifyOwnership(Instant.now());

        for (WhatsAppIntegration current : List.of(rotated, otherNumber)) {
            when(integrations.findByTenant(tenantId)).thenReturn(Optional.of(current));
            assertThat(provider.send(approved)).isEqualTo(new RejectedPermanently(FailureCategory.POLICY_VIOLATION));
        }
        assertThat(requests).hasValue(0);
    }

    @Test
    void aNonStringMessageIdIsNotAcceptance() {
        status = 200;
        body = "{\"messages\":[{\"id\":12345,\"message_status\":\"accepted\"}]}".getBytes(StandardCharsets.UTF_8);

        assertThat(send()).isEqualTo(new OutcomeUnknown(Optional.empty()));
    }

    @Test
    void aFailedTlsHandshakeIsTransientBecauseNoRequestWasWritten() throws Exception {
        try (ServerSocket raw = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try (Socket client = raw.accept()) {
                    client.getInputStream().read(new byte[8192]); // the ClientHello
                    // A TLS fatal alert record: handshake_failure.
                    client.getOutputStream().write(new byte[] {0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28});
                    client.getOutputStream().flush();
                    Thread.sleep(500);
                } catch (IOException | InterruptedException ignored) {
                    // The adapter gave up on the exchange.
                }
            });

            assertThat(providerFor(URI.create("https://127.0.0.1:" + raw.getLocalPort() + "/v23.0"))
                    .send(command(List.of("Ana")))).isEqualTo(new FailedTransiently(FailureCategory.PROVIDER_UNAVAILABLE));
        }
    }

    @Test
    void aConnectThatHangsIsTransientBecauseNothingLeft() throws Exception {
        // A full accept queue makes Linux drop further SYNs, so the next connect hangs instead of being refused.
        try (ServerSocket full = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            List<Socket> fillers = new java.util.ArrayList<>();
            try {
                for (int i = 0; i < 4; i++) {
                    Socket filler = new Socket();
                    fillers.add(filler);
                    try {
                        filler.connect(full.getLocalSocketAddress(), 100);
                    } catch (IOException queueAlreadyFull) {
                        break;
                    }
                }
                long started = System.nanoTime();
                assertThat(providerFor(full.getLocalPort()).send(command(List.of("Ana"))))
                        .isEqualTo(new FailedTransiently(FailureCategory.PROVIDER_UNAVAILABLE));
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
            } finally {
                for (Socket filler : fillers) {
                    filler.close();
                }
            }
        }
    }

    @Test
    void anErrorBeforeSendingNeverCrossesThePort() {
        when(secrets.getIfAvailable()).thenThrow(new StackOverflowError());

        assertThat(send()).isEqualTo(new FailedTransiently(FailureCategory.PROVIDER_UNAVAILABLE));
        assertThat(requests).hasValue(0);
    }

    @Test
    void anExceptionBeforeSendingNeverCrossesThePort() {
        when(contexts.requireCurrent()).thenThrow(new IllegalStateException("No tenant context"));

        assertThat(send()).isEqualTo(new FailedTransiently(FailureCategory.PROVIDER_UNAVAILABLE));
        assertThat(requests).hasValue(0);
    }

    @Test
    void noSecretPhoneOrMessageTextFromASecretBearingErrorReachesResultsOrLogs(CapturedOutput output) {
        replay("tokenExpiredWithSecrets");

        ProviderSendResult result = provider.send(command(List.of("Hola Ana, tu pedido")));

        assertThat(result).isEqualTo(new RejectedPermanently(FailureCategory.AUTHENTICATION));
        for (String leaked : List.of(TOKEN, RECIPIENT, "50600000001", "Hola Ana", "Bearer", "SYNTHETIC-TRACE")) {
            assertThat(result.toString()).doesNotContain(leaked);
            assertThat(output.getAll()).doesNotContain(leaked);
        }
    }

    private ProviderSendResult send() {
        return provider.send(command(List.of("Ana")));
    }

    /** A positional template: its names are bound but never sent. */
    private OutboundSendCommand command(List<String> parameters) {
        List<String> names = IntStream.range(0, parameters.size()).mapToObj(index -> "param_" + index).toList();
        return new OutboundSendCommand(RECIPIENT, "repeat_purchase_v1", "es_MX", ParameterFormat.POSITIONAL, names,
                parameters, "es-419", UUID.randomUUID(), WhatsAppOutboundSafetyControl.senderRevision(configured));
    }

    private OutboundSendCommand namedCommand(List<String> names, List<String> parameters) {
        return new OutboundSendCommand(RECIPIENT, "repeat_purchase_v1", "es_MX", ParameterFormat.NAMED, names,
                parameters, "es-419", UUID.randomUUID(), WhatsAppOutboundSafetyControl.senderRevision(configured));
    }

    private GraphApiMessagingProvider providerFor(int port) {
        return providerFor(URI.create("http://127.0.0.1:" + port + "/v23.0"));
    }

    private GraphApiMessagingProvider providerFor(URI baseUrl) {
        return new GraphApiMessagingProvider(integrations, secrets, contexts,
                new GraphApiHttp(new GraphApiProperties(baseUrl, Duration.ofMillis(500), List.of())));
    }

    private WhatsAppIntegration integration(boolean enabled, boolean verified) {
        WhatsAppIntegration created = WhatsAppIntegration.create(tenantId, PHONE_NUMBER_ID, "400000000000001", null,
                true, "synthetic-wa-token", Instant.now());
        WhatsAppIntegration proven = verified ? created.verifyOwnership(Instant.now()) : created;
        return enabled ? proven : proven.update(PHONE_NUMBER_ID, "400000000000001", null, false,
                "synthetic-wa-token", Instant.now());
    }

    private static byte[] messageWithId(String id) {
        return JSON.writeValueAsBytes(JSON.createObjectNode().set("messages",
                JSON.createArrayNode().add(JSON.createObjectNode().put("id", id).put("message_status", "accepted"))));
    }

    private void replay(String fixture) {
        JsonNode entry = FIXTURES.path(fixture);
        assertThat(entry.isMissingNode()).as("fixture %s", fixture).isFalse();
        status = entry.path("status").intValue();
        body = JSON.writeValueAsBytes(entry.path("body"));
    }

    private static JsonNode loadFixtures() {
        try (InputStream in = GraphApiMessagingProviderTest.class
                .getResourceAsStream("/contracts/whatsapp/graph-send-responses.json")) {
            return JSON.readTree(in);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
