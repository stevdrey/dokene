// ABOUTME: Pins how an approved Graph API template's components and parameter_format become a schema (issue 218).
// ABOUTME: Body text parameters only; components needing values are unsupported and malformed metadata is unreadable.
package io.github.stevdrey.dokene.integration.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.template.domain.ProviderTemplateSchema;
import io.github.stevdrey.dokene.template.domain.ProviderTemplateSchema.BodyParameters;
import io.github.stevdrey.dokene.template.domain.ProviderTemplateSchema.Unreadable;
import io.github.stevdrey.dokene.template.domain.ProviderTemplateSchema.Unsupported;
import io.github.stevdrey.dokene.template.domain.TemplateParameterFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class GraphApiTemplateSchemaReaderTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BODY = "{\"type\":\"BODY\",\"text\":\"Hola {{1}}, tu pedido {{2}}\"}";

    private static ProviderTemplateSchema read(String template) {
        return GraphApiTemplateSchemaReader.read(JSON.readTree(template));
    }

    private static ProviderTemplateSchema withComponents(String... components) {
        return read("{\"components\":[" + String.join(",", components) + "]}");
    }

    @Test
    void readsPositionalBodyPlaceholdersByDefault() {
        assertThat(withComponents(BODY))
                .isEqualTo(new BodyParameters(TemplateParameterFormat.POSITIONAL, List.of("1", "2")));
        assertThat(read("{\"parameter_format\":null,\"components\":[" + BODY + "]}"))
                .isEqualTo(new BodyParameters(TemplateParameterFormat.POSITIONAL, List.of("1", "2")));
        assertThat(read("{\"parameter_format\":\"POSITIONAL\",\"components\":[{\"type\":\"BODY\",\"text\":\"Hola\"}]}"))
                .isEqualTo(new BodyParameters(TemplateParameterFormat.POSITIONAL, List.of()));
    }

    @Test
    void readsNamedBodyPlaceholders() {
        assertThat(read("{\"parameter_format\":\"NAMED\",\"components\":[{\"type\":\"BODY\","
                + "\"text\":\"Hola {{customer_name}}: {{message_body}}\"}]}"))
                .isEqualTo(new BodyParameters(TemplateParameterFormat.NAMED, List.of("customer_name", "message_body")));
    }

    @Test
    void acceptsComponentsThatNeedNoValue() {
        assertThat(withComponents("{\"type\":\"HEADER\",\"format\":\"TEXT\",\"text\":\"Aviso\"}", BODY,
                "{\"type\":\"FOOTER\",\"text\":\"Gracias\"}",
                "{\"type\":\"BUTTONS\",\"buttons\":[{\"type\":\"QUICK_REPLY\",\"text\":\"Si\"},"
                        + "{\"type\":\"PHONE_NUMBER\",\"text\":\"Llamar\",\"phone_number\":\"+50600000000\"},"
                        + "{\"type\":\"URL\",\"text\":\"Ver\",\"url\":\"https://example.test/catalogo\"}]}"))
                .isInstanceOf(BodyParameters.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"type\":\"HEADER\",\"format\":\"TEXT\",\"text\":\"Hola {{1}}\"}",
            "{\"type\":\"HEADER\",\"format\":\"IMAGE\"}",
            "{\"type\":\"HEADER\",\"format\":\"VIDEO\"}",
            "{\"type\":\"HEADER\",\"format\":\"DOCUMENT\"}",
            "{\"type\":\"HEADER\",\"format\":\"LOCATION\"}",
            "{\"type\":\"FOOTER\",\"text\":\"Codigo {{1}}\"}",
            "{\"type\":\"BUTTONS\",\"buttons\":[{\"type\":\"URL\",\"text\":\"Ver\",\"url\":\"https://example.test/{{1}}\"}]}",
            "{\"type\":\"BUTTONS\",\"buttons\":[{\"type\":\"COPY_CODE\",\"example\":\"X\"}]}",
            "{\"type\":\"BUTTONS\",\"buttons\":[{\"type\":\"OTP\",\"otp_type\":\"COPY_CODE\"}]}",
            "{\"type\":\"BUTTONS\",\"buttons\":[{\"type\":\"FLOW\",\"text\":\"Abrir\"}]}",
            "{\"type\":\"BUTTONS\",\"buttons\":[{\"type\":\"CATALOG\",\"text\":\"Ver\"}]}",
            "{\"type\":\"CAROUSEL\",\"cards\":[]}",
            "{\"type\":\"LIMITED_TIME_OFFER\",\"limited_time_offer\":{}}",
            "{\"type\":\"SOMETHING_NEW\"}",
            "{\"type\":\"body\",\"text\":\"lowercase type\"}"})
    void anyComponentThatNeedsAValueOrIsUnknownIsUnsupported(String component) {
        assertThat(withComponents(BODY, component)).isEqualTo(new Unsupported());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"components\":{}}",
            "{\"components\":[]}",
            "{\"components\":\"BODY\"}",
            "{\"parameter_format\":\"named\",\"components\":[" + BODY + "]}",
            "{\"parameter_format\":1,\"components\":[" + BODY + "]}",
            "{\"parameter_format\":\"MIXED\",\"components\":[" + BODY + "]}",
            "{\"components\":[{\"type\":\"FOOTER\",\"text\":\"Sin cuerpo\"}]}",
            "{\"components\":[" + BODY + "," + BODY + "]}",
            "{\"components\":[{\"type\":\"BODY\"}]}",
            "{\"components\":[{\"type\":\"BODY\",\"text\":5}]}",
            "{\"components\":[{\"type\":\"BODY\",\"text\":\"Hola {{1\"}]}",
            "{\"components\":[{\"type\":\"BODY\",\"text\":\"Hola {{}}\"}]}",
            "{\"components\":[\"BODY\"]}",
            "{\"components\":[{\"text\":\"no type\"}]}",
            "{\"components\":[{\"type\":3}]}",
            "{\"components\":[" + BODY + ",{\"type\":\"HEADER\",\"text\":\"no format\"}]}",
            "{\"components\":[" + BODY + ",{\"type\":\"FOOTER\"}]}",
            "{\"components\":[" + BODY + ",{\"type\":\"BUTTONS\",\"buttons\":[]}]}",
            "{\"components\":[" + BODY + ",{\"type\":\"BUTTONS\",\"buttons\":{}}]}",
            "{\"components\":[" + BODY + ",{\"type\":\"BUTTONS\",\"buttons\":[{\"text\":\"no type\"}]}]}",
            "{\"components\":[" + BODY + ",{\"type\":\"BUTTONS\",\"buttons\":[{\"type\":\"URL\"}]}]}"})
    void missingOrMalformedMetadataIsUnreadable(String template) {
        assertThat(read(template)).isEqualTo(new Unreadable());
    }

    @Test
    void unreadableTakesPrecedenceOverUnsupported() {
        assertThat(withComponents("{\"type\":\"HEADER\",\"format\":\"IMAGE\"}", BODY, "{\"type\":\"FOOTER\"}"))
                .isEqualTo(new Unreadable());
        assertThat(withComponents("{\"type\":\"CAROUSEL\"}", "{\"type\":\"BODY\",\"text\":\"{{\"}"))
                .isEqualTo(new Unreadable());
    }

    @Test
    void untrustedMetadataIsBounded() {
        String footer = ",{\"type\":\"FOOTER\",\"text\":\"x\"}";
        assertThat(read("{\"components\":[" + BODY + footer.repeat(9) + "]}")).isInstanceOf(BodyParameters.class);
        assertThat(read("{\"components\":[" + BODY + footer.repeat(10) + "]}")).isEqualTo(new Unreadable());
        String quickReply = ",{\"type\":\"QUICK_REPLY\",\"text\":\"x\"}";
        assertThat(withComponents(BODY, "{\"type\":\"BUTTONS\",\"buttons\":[" + quickReply.substring(1)
                + quickReply.repeat(10) + "]}")).isEqualTo(new Unreadable());
        assertThat(withComponents("{\"type\":\"BODY\",\"text\":\"" + "a".repeat(4097) + "\"}"))
                .isEqualTo(new Unreadable());
    }
}
