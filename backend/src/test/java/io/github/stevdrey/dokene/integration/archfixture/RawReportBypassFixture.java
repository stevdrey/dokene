// ABOUTME: Deliberate violation fixture for ModuleDependencyArchitectureTest: an integration class that feeds raw reports.
// ABOUTME: Never loaded by production code; the ArchUnit negative proof imports it explicitly.
package io.github.stevdrey.dokene.integration.archfixture;

import io.github.stevdrey.dokene.messaging.application.DeliveryStatusSink;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatusReport;

@SuppressWarnings("unused")
public final class RawReportBypassFixture {
    private RawReportBypassFixture() {
    }

    static void bypass(DeliveryStatusSink sink, DeliveryStatusReport report) {
        sink.apply(report);
    }
}
