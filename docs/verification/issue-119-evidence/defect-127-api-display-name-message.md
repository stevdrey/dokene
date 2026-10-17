Found during #118 / #119 (REG-03). Tested SHA `2e0a2549c43855b783cd8d0e318bc8c8fe662b4d`. Origin: pre-existing (not compared against older builds).

**Severity:** Low.

**Environment:** Linux, JDK 26.0.2, backend bootRun, direct API through the BFF as OPERATOR, synthetic data.

**Steps**
```http
POST /api/customers    (X-Tenant-Id: <QA Café Norte>, valid session + CSRF)
{"displayName":"  ","phones":[{"number":"+50688881200","region":"CR","primary":true}]}
```
and the same with a 300-character `displayName`.

**Expected (per #58/#72 "actionable field errors"):** the phone errors already return `{"status":400,"message":"El formato del teléfono es inválido para la región seleccionada.","field":"phones[0].number"}`. A name error should likewise identify `displayName` and explain the rule (non-blank, max 160).

**Actual:**
```
HTTP 400
{"status":400,"message":"Invalid customer display name","field":null}
```
English message, `field` is null, rule not stated; same for updates (`Customer.java` throws a generic `IllegalArgumentException` in 3 places). Other generic messages seen: "Customer version is required" (PUT without If-Match), "Invalid request payload or parameters" (bad `/contact-eligibility` query).

**Impact:** the web UI prevents this input (required field + 160 counter), so users do not see it; API consumers and future clients get a non-actionable error and mixed languages.

**Reproducibility:** 100% (3/3).

**Evidence:** sanitized request/response above. Related: #72, #58.
