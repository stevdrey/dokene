Found during the full re-execution of QA suite #119 (REG-02), tested SHA `90045a1e935c51facf39bf7422484d43513abbff`. Origin: pre-existing (also observed in the earlier run on `2e0a254`).

**Severity:** Low (wording).

**Steps**
1. Log in as OPERATOR in two browser tabs (same browser profile).
2. In tab 2 click "Cerrar sesión".
3. In tab 1 perform any action (open Clientes → Nuevo cliente → Crear cliente, or reload data).

**Expected:** a message that matches the real cause: the session ended (signed out elsewhere or invalidated), asking the user to sign in again.

**Actual:** tab 1 shows "Tu sesión ha expirado por inactividad. Por favor inicia sesión nuevamente." The same text appears when the server invalidated all sessions (backend restart) or the user signed out in another tab; no inactivity was involved. Behavior is otherwise correct (401 handled, form values kept, login button shown; no misleading permission message, #70).

**Impact:** minor confusion; support/debugging may chase an idle-timeout problem that does not exist.

**Suggested direction:** use a neutral message ("Tu sesión ya no está activa…") for 401 responses, or distinguish idle timeout if the BFF can signal it.

**Reproducibility:** 100% (3/3). Related: #70, #118, #119.
