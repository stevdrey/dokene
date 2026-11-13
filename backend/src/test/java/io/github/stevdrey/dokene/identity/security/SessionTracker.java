// ABOUTME: Test only listener that counts every HttpSession the embedded server creates and keeps the live ones
// ABOUTME: readable, so tests can assert on a session's idle timeout and attributes (issue 311).
package io.github.stevdrey.dokene.identity.security;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestComponent;

@TestComponent
class SessionTracker implements HttpSessionListener {

    private final AtomicInteger created = new AtomicInteger();
    /** Sessions, not ids: login rotates the id of the same session object. */
    private final Set<HttpSession> live = ConcurrentHashMap.newKeySet();

    @Override
    public void sessionCreated(HttpSessionEvent event) {
        created.incrementAndGet();
        live.add(event.getSession());
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        live.remove(event.getSession());
    }

    int created() {
        return created.get();
    }

    /** The live session that currently has this id, whichever filter or dispatch created it. */
    Optional<HttpSession> find(String sessionId) {
        return live.stream().filter(session -> sessionId.equals(idOf(session))).findFirst();
    }

    private static String idOf(HttpSession session) {
        try {
            return session.getId();
        } catch (IllegalStateException invalidated) {
            return null;
        }
    }
}
