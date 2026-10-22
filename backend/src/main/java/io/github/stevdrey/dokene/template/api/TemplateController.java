package io.github.stevdrey.dokene.template.api;

import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.http.StrictRequests;
import io.github.stevdrey.dokene.template.api.TemplateApiContract.InvalidRequestException;
import io.github.stevdrey.dokene.template.api.TemplateApiContract.TemplateContentResponse;
import io.github.stevdrey.dokene.template.api.TemplateApiContract.TemplateListResponse;
import io.github.stevdrey.dokene.template.api.TemplateApiContract.TemplateRequest;
import io.github.stevdrey.dokene.template.api.TemplateApiContract.TemplateResponse;
import io.github.stevdrey.dokene.template.application.TemplateErrorCode;
import io.github.stevdrey.dokene.template.application.TemplateRefusedException;
import io.github.stevdrey.dokene.template.application.TemplateRegistryService;
import io.github.stevdrey.dokene.template.application.TemplateRegistryService.Create;
import io.github.stevdrey.dokene.template.application.TemplateRegistryService.Entry;
import io.github.stevdrey.dokene.template.application.TemplateRegistryService.Precondition;
import io.github.stevdrey.dokene.template.application.TemplateRegistryService.Version;
import io.github.stevdrey.dokene.template.domain.TemplateApprovalStatus;
import io.github.stevdrey.dokene.template.domain.TemplateContent;
import io.github.stevdrey.dokene.template.domain.TemplateMapping;
import io.github.stevdrey.dokene.tenant.application.RequiredPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Tenant template mappings (ADR 0023 §3.2, ADR 0025). The intent comes from the closed enum, never from AI text. */
@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final TemplateRegistryService registry;

    public TemplateController(TemplateRegistryService registry) {
        this.registry = registry;
    }

    @RequiredPermission(TenantPermission.TEMPLATE_READ)
    @GetMapping
    public TemplateListResponse list(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return new TemplateListResponse(registry.list().stream().map(TemplateController::toResponse).toList());
    }

    @RequiredPermission(TenantPermission.TEMPLATE_WRITE)
    @PutMapping("/{templateIntent}")
    public TemplateResponse put(@PathVariable String templateIntent,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            @RequestBody JsonNode body, HttpServletResponse response) {
        SemanticTemplateIntent intent = intent(templateIntent);
        TemplateRequest request = TemplateApiContract.read(body);
        Entry saved = registry.put(intent, precondition(ifMatch, ifNoneMatch), request.providerTemplateName(),
                request.language(), request.parameterNames(), request.enabled());
        return respond(saved, response);
    }

    /** Asks the provider for the template's current state; the only way approval evidence is ever recorded. */
    @RequiredPermission(TenantPermission.TEMPLATE_WRITE)
    @PostMapping("/{templateIntent}/approval-refresh")
    public TemplateResponse refreshApproval(@PathVariable String templateIntent,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
            HttpServletResponse response) {
        SemanticTemplateIntent intent = intent(templateIntent);
        if (ifNoneMatch != null || !(precondition(ifMatch, null) instanceof Version(long expectedVersion))) {
            throw new InvalidRequestException();
        }
        return respond(registry.refreshApproval(intent, expectedVersion), response);
    }

    /** Unknown intents are {@code 404}, as for any resource the tenant cannot see. */
    private static SemanticTemplateIntent intent(String raw) {
        try {
            return SemanticTemplateIntent.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            throw new TemplateRefusedException(TemplateErrorCode.NOT_FOUND);
        }
    }

    /**
     * The ETag is the mapping version, the same revision the approval fingerprint binds. Evidence and {@code sendable}
     * change without it, so responses are never cacheable and never answered with {@code 304}.
     */
    private static TemplateResponse respond(Entry entry, HttpServletResponse response) {
        TemplateResponse body = toResponse(entry);
        response.setHeader("ETag", StrictRequests.strongEtag(body.version()));
        response.setHeader("Cache-Control", "no-store");
        return body;
    }

    private static TemplateResponse toResponse(Entry entry) {
        if (entry.mapping().isEmpty()) {
            return new TemplateResponse(entry.intent(), false, false, null, null, List.of(),
                    TemplateApprovalStatus.UNVERIFIED, null, null, false, null);
        }
        TemplateMapping mapping = entry.mapping().get();
        return new TemplateResponse(entry.intent(), true, mapping.enabled(), mapping.providerTemplateName(),
                mapping.language(), mapping.parameterNames(), mapping.approval().status(),
                mapping.approval().checkedAt(), content(mapping.approval().content()), entry.sendable(),
                mapping.version());
    }

    private static TemplateContentResponse content(TemplateContent content) {
        return content == null ? null
                : new TemplateContentResponse(content.header(), content.body(), content.footer());
    }

    /** Exactly one strong canonical numeric precondition, as for the integration endpoint. */
    private static Precondition precondition(String ifMatch, String ifNoneMatch) {
        if ((ifMatch == null) == (ifNoneMatch == null)) {
            throw new InvalidRequestException();
        }
        if (ifNoneMatch != null) {
            if (!"*".equals(ifNoneMatch)) {
                throw new InvalidRequestException();
            }
            return new Create();
        }
        return new Version(StrictRequests.strongNumericVersion(ifMatch).orElseThrow(InvalidRequestException::new));
    }
}
