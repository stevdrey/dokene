// ABOUTME: Provider side of the shared body-format cases (contracts/messaging/v1/body-format.json): the backend rule
// ABOUTME: must refuse and accept exactly what the frontend compose mirror (bodyFormat.ts) is tested against.
package io.github.stevdrey.dokene.template.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class TemplateParameterBodyFormatTest {

    @TestFactory
    List<DynamicTest> sharedCasesMatchTheParameterRule() throws IOException {
        JsonNode cases;
        try (InputStream in = getClass().getResourceAsStream("/contracts/messaging/v1/body-format.json")) {
            assertThat(in).as("shared body-format fixture").isNotNull();
            cases = new ObjectMapper().readTree(in);
        }
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode item : cases.get("refused")) {
            String value = item.get("value").asString();
            tests.add(dynamicTest("refuses " + item.get("name").asString(),
                    () -> assertThat(TemplateParameterValues.acceptable(value)).isFalse()));
        }
        for (JsonNode item : cases.get("accepted")) {
            String value = item.get("value").asString();
            tests.add(dynamicTest("accepts " + item.get("name").asString(),
                    () -> assertThat(TemplateParameterValues.acceptable(value)).isTrue()));
        }
        assertThat(tests).hasSizeGreaterThan(10);
        return tests;
    }
}
