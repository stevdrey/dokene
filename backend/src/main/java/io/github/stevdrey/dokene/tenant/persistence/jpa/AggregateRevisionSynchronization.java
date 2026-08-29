package io.github.stevdrey.dokene.tenant.persistence.jpa;

import java.util.IdentityHashMap;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

final class AggregateRevisionSynchronization {

    private static final Object RESOURCE_KEY = new Object();

    private AggregateRevisionSynchronization() {
    }

    static void synchronize(
            Object aggregate,
            OptionalLong previousRevision,
            LongConsumer synchronizeRevision,
            Consumer<OptionalLong> restoreRevision,
            long persistedRevision
    ) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            rollbackRevisions().capture(aggregate, previousRevision, restoreRevision);
        }
        synchronizeRevision.accept(persistedRevision);
    }

    private static RollbackRevisions rollbackRevisions() {
        RollbackRevisions revisions = (RollbackRevisions) TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        if (revisions != null) {
            return revisions;
        }

        revisions = new RollbackRevisions();
        TransactionSynchronizationManager.registerSynchronization(revisions);
        TransactionSynchronizationManager.bindResource(RESOURCE_KEY, revisions);
        return revisions;
    }

    private static final class RollbackRevisions implements TransactionSynchronization {

        private final IdentityHashMap<Object, RollbackRevision> originalRevisions = new IdentityHashMap<>();

        private void capture(Object aggregate, OptionalLong previousRevision, Consumer<OptionalLong> restoreRevision) {
            originalRevisions.putIfAbsent(aggregate, new RollbackRevision(previousRevision, restoreRevision));
        }

        @Override
        public void suspend() {
            TransactionSynchronizationManager.unbindResource(RESOURCE_KEY);
        }

        @Override
        public void resume() {
            TransactionSynchronizationManager.bindResource(RESOURCE_KEY, this);
        }

        @Override
        public void afterCompletion(int status) {
            try {
                if (status != STATUS_COMMITTED) {
                    originalRevisions.values().forEach(RollbackRevision::restore);
                }
            } finally {
                TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE_KEY);
            }
        }
    }

    private record RollbackRevision(OptionalLong originalRevision, Consumer<OptionalLong> restoreRevision) {

        private void restore() {
            restoreRevision.accept(originalRevision);
        }
    }
}
