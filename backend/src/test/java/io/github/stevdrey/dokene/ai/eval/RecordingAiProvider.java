package io.github.stevdrey.dokene.ai.eval;

import io.github.stevdrey.dokene.ai.application.AiDraftRequest;
import io.github.stevdrey.dokene.ai.application.AiDraftResponse;
import io.github.stevdrey.dokene.ai.application.AiFailureCategory;
import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.application.AiProviderException;
import io.github.stevdrey.dokene.ai.application.AiRecommendationRequest;
import io.github.stevdrey.dokene.ai.application.AiRecommendationResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Decorator that records every invocation (including failures) reaching the underlying provider. */
public final class RecordingAiProvider implements AiProvider {
    private final AiProvider delegate;
    private final List<EvalProviderCall> calls = new CopyOnWriteArrayList<>();

    public RecordingAiProvider(AiProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public AiRecommendationResponse recommend(AiRecommendationRequest request) {
        String name = request.context().untrusted().displayName();
        long start = System.nanoTime();
        try {
            AiRecommendationResponse response = delegate.recommend(request);
            calls.add(new EvalProviderCall(name, AiOperation.NEXT_BEST_ACTION, request.context(), response.outcome(),
                    null, response.metadata(), System.nanoTime() - start));
            return response;
        } catch (AiProviderException ex) {
            calls.add(new EvalProviderCall(name, AiOperation.NEXT_BEST_ACTION, request.context(), null,
                    ex.category(), ex.metadata(), System.nanoTime() - start, null, null, ex.rejections()));
            throw ex;
        } catch (RuntimeException ex) {
            // Untyped failures are normalized to UNAVAILABLE by the resilience layer; the attempt still happened.
            calls.add(new EvalProviderCall(name, AiOperation.NEXT_BEST_ACTION, request.context(), null,
                    AiFailureCategory.UNAVAILABLE, null, System.nanoTime() - start));
            throw ex;
        }
    }

    @Override
    public AiDraftResponse draft(AiDraftRequest request) {
        String name = request.context().customerContext().untrusted().displayName();
        long start = System.nanoTime();
        try {
            AiDraftResponse response = delegate.draft(request);
            calls.add(new EvalProviderCall(name, AiOperation.MESSAGE_DRAFT, request.context().customerContext(),
                    response.outcome(), null, response.metadata(), System.nanoTime() - start,
                    request.context().action(), request.context().templateIntent()));
            return response;
        } catch (AiProviderException ex) {
            calls.add(new EvalProviderCall(name, AiOperation.MESSAGE_DRAFT, request.context().customerContext(),
                    null, ex.category(), ex.metadata(), System.nanoTime() - start, request.context().action(),
                    request.context().templateIntent(), ex.rejections()));
            throw ex;
        } catch (RuntimeException ex) {
            calls.add(new EvalProviderCall(name, AiOperation.MESSAGE_DRAFT, request.context().customerContext(),
                    null, AiFailureCategory.UNAVAILABLE, null, System.nanoTime() - start,
                    request.context().action(), request.context().templateIntent()));
            throw ex;
        }
    }

    @Override
    public Duration defaultTimeout() {
        return delegate.defaultTimeout();
    }

    @Override
    public Duration maxTimeout() {
        return delegate.maxTimeout();
    }

    /** Calls recorded for one synthetic customer (keyed by its unique display name). */
    public List<EvalProviderCall> callsFor(String displayName) {
        List<EvalProviderCall> result = new ArrayList<>();
        for (EvalProviderCall call : calls) {
            if (call.displayName().equals(displayName)) {
                result.add(call);
            }
        }
        return result;
    }
}
