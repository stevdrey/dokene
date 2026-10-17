// ABOUTME: Test stand-in for the provider send port (issue 138): the test scripts the answer and can act mid call.
// ABOUTME: Counts every call, so a test can assert that a refused or replayed send never reached the provider.
package io.github.stevdrey.dokene.tenant.security;

import io.github.stevdrey.dokene.messaging.application.MessagingProvider;
import io.github.stevdrey.dokene.messaging.application.OutboundSendCommand;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

final class ScriptedMessagingProvider implements MessagingProvider {
    private final List<OutboundSendCommand> calls = new CopyOnWriteArrayList<>();
    volatile Supplier<ProviderSendResult> next = () -> new ProviderSendResult.Accepted("wamid.SYNTHETIC-" + nonce());
    /** Runs inside send, after the call is counted: lets a test change state or fail while the call is in flight. */
    volatile Runnable duringSend = () -> { };

    @Override
    public ProviderSendResult send(OutboundSendCommand command) {
        calls.add(command);
        duringSend.run();
        return next.get();
    }

    List<OutboundSendCommand> calls() {
        return List.copyOf(calls);
    }

    void reset() {
        calls.clear();
        next = () -> new ProviderSendResult.Accepted("wamid.SYNTHETIC-" + nonce());
        duringSend = () -> { };
    }

    private static String nonce() {
        return Long.toHexString(System.nanoTime());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        @Primary
        ScriptedMessagingProvider scriptedMessagingProvider() {
            return new ScriptedMessagingProvider();
        }
    }
}
