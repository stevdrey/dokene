// ABOUTME: Bounds for recurring follow-up candidate generation (issue 460, ADR 0036): how many new candidates one
// ABOUTME: tenant scan may record. Scheduling and enablement belong to the scheduler wiring (issue 463).
package io.github.stevdrey.dokene.followup.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dokene.follow-up.candidates")
public record FollowUpCandidateProperties(Integer batchSize) {
    public static final int DEFAULT_BATCH_SIZE = 100;
    public static final int MAX_BATCH_SIZE = 1000;

    public FollowUpCandidateProperties {
        batchSize = batchSize == null ? DEFAULT_BATCH_SIZE : batchSize;
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Candidate batch size must be between 1 and " + MAX_BATCH_SIZE);
        }
    }
}
