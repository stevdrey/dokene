// ABOUTME: What is being admitted (ADR 0040). The two kinds are counted apart and locked apart, and the name is the
// ABOUTME: value of tenant_budget_admissions.kind and of the kind tag on dokene.admission.decisions.
package io.github.stevdrey.dokene.admission;

enum AdmissionKind {
    SEND,
    CANDIDATE
}
