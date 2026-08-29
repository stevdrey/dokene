package io.github.stevdrey.dokene.tenant.application;

/**
 * A context lifetime. Use in a try-with-resources block so cleanup also happens on failure.
 */
public interface TenantContextScope extends AutoCloseable {

    @Override
    void close();
}
