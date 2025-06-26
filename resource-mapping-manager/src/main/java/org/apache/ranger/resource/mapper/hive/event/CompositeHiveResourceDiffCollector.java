package org.apache.ranger.resource.mapper.hive.event;

import static org.apache.ranger.resource.mapper.hive.model.HiveDiffSourceState.EVENTS_STARTED;
import static org.apache.ranger.resource.mapper.hive.model.HiveDiffSourceState.INTERMEDIATE_EVENTS_FINISHED;
import static org.apache.ranger.resource.mapper.hive.model.HiveDiffSourceState.INTERMEDIATE_EVENTS_STARTED;
import static org.apache.ranger.resource.mapper.hive.model.HiveDiffSourceState.SNAPSHOT_STARTED;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ranger.resource.mapper.event.BaseResourceDiffCollector;
import org.apache.ranger.resource.mapper.event.ResourceDiffCollector;
import org.apache.ranger.resource.mapper.event.retry.RetryException;
import org.apache.ranger.resource.mapper.event.retry.RetrySupport;
import org.apache.ranger.resource.mapper.hive.model.HiveChangeStateStreamRecord;
import org.apache.ranger.resource.mapper.hive.model.HiveDiffSourceState;
import org.apache.ranger.resource.mapper.model.ResourceDiffStreamRecord;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

@Slf4j
public class CompositeHiveResourceDiffCollector extends BaseResourceDiffCollector {
    private final RetrySupport retrySupport;
    private final PlatformTransactionManager transactionManager;
    private final HiveIntermediateEventsResolver intermediateEventsResolver;
    private final ResourceDiffCollector delegate;

    private RecordsHandler recordsHandler;

    @Builder
    public CompositeHiveResourceDiffCollector(RetrySupport retrySupport,
                                              PlatformTransactionManager transactionManager,
                                              HiveIntermediateEventsResolver intermediateEventsResolver,
                                              ResourceDiffCollector delegate) {
        this.retrySupport = retrySupport;
        this.transactionManager = transactionManager;
        this.intermediateEventsResolver = intermediateEventsResolver;
        this.delegate = delegate;
        this.recordsHandler = new InitialRecordsHandler();
    }

    @Override
    public void handle(ResourceDiffStreamRecord record) throws Exception {
        try {
            retrySupport.withRetries(() -> handleAction(record));
        } catch (RetryException e) {
            recordsHandler.fail();
            throw e;
        }
    }

    private void handleAction(ResourceDiffStreamRecord record) throws Exception {
        if (record instanceof HiveChangeStateStreamRecord) {
            HiveDiffSourceState newState = ((HiveChangeStateStreamRecord) record).getNewState();
            recordsHandler = recordsHandler.nextHandler(newState);
            return;
        }

        recordsHandler.handle(record);
    }

    interface RecordsHandler {
        void handle(ResourceDiffStreamRecord record) throws Exception;

        RecordsHandler nextHandler(HiveDiffSourceState newState) throws Exception;

        default void fail() {
            // do nothing
        }
    }

    class InitialRecordsHandler implements RecordsHandler {
        @Override
        public void handle(ResourceDiffStreamRecord record) {
            // do nothing
        }

        @Override
        public RecordsHandler nextHandler(HiveDiffSourceState newState) {
            if (newState == SNAPSHOT_STARTED) {
                return new SnapshotRecordsHandler();
            }

            if (newState == EVENTS_STARTED) {
                return new EventsHandler();
            }

            throw new IllegalStateException("Unexpected state: " + newState);
        }
    }

    @RequiredArgsConstructor
    class SnapshotRecordsHandler implements RecordsHandler {
        private TransactionStatus transactionStatus;

        @Override
        public void handle(ResourceDiffStreamRecord record) throws Exception {
            if (transactionStatus == null) {
                transactionStatus = transactionManager.getTransaction(
                    new DefaultTransactionDefinition());
            }
            delegate.handle(record);
        }

        @Override
        public RecordsHandler nextHandler(HiveDiffSourceState newState) {
            if (newState == INTERMEDIATE_EVENTS_STARTED) {
                return new IntermediateEventsHandler(transactionStatus);
            }

            if (newState == EVENTS_STARTED) {
                transactionManager.commit(transactionStatus);
                return new EventsHandler();
            }

            return this;
        }

        @Override
        public void fail() {
            transactionManager.rollback(transactionStatus);
        }
    }

    @RequiredArgsConstructor
    class IntermediateEventsHandler implements RecordsHandler {
        private final TransactionStatus transactionStatus;

        @Override
        public void handle(ResourceDiffStreamRecord record) {
            intermediateEventsResolver.handle(record);
        }

        @Override
        public RecordsHandler nextHandler(HiveDiffSourceState newState) {
            if (newState != INTERMEDIATE_EVENTS_FINISHED) {
                return this;
            }

            intermediateEventsResolver.flush();
            transactionManager.commit(transactionStatus);
            return new EventsHandler();
        }

        @Override
        public void fail() {
            transactionManager.rollback(transactionStatus);
        }
    }

    class EventsHandler implements RecordsHandler {
        @Override
        public void handle(ResourceDiffStreamRecord record) throws Exception {
            delegate.handle(record);
        }

        @Override
        public RecordsHandler nextHandler(HiveDiffSourceState newState) {
            return this;
        }
    }
}
