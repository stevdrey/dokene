// ABOUTME: QA fault-injection stub of the Meta Graph API for suite #149 (sends, template lookups, phone-number pages).
// ABOUTME: Controlled through files in /stub; logs every call. Stub evidence is never real-provider evidence.

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Control files (all optional, read on every request):
 * send          accept | paused | error500 | error401canary | rate429 | malformed | hang
 * templates     approved | rejected | paused | deleted | absent | otherLanguage | otherName | error500 | redirect | canaryError | hang
 * phones        owned | notOwned | error500 | hang
 * params        comma separated placeholder names the APPROVED template body carries (default: customer_name)
 * phones.list   comma separated phone number ids the phone_numbers page lists as owned (default: none)
 * tokens        lines TAG=value; the log names the TAG whose value equals the bearer, else "other" or "none"
 * delay.ms      sleep before answering any request; hang modes sleep 60 s regardless
 * Output: requests.log (one line per call), last-send.json (the last send payload), last-templates.query.
 */
public class GraphStub {
    static final Path DIR = Paths.get("/stub");
    static final AtomicInteger seq = new AtomicInteger();

    static String read(String name, String fallback) {
        try {
            return Files.readString(DIR.resolve(name), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return fallback;
        }
    }

    static void append(String name, String line) throws IOException {
        Files.writeString(DIR.resolve(name), line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    static String tokenTag(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return "none";
        }
        String bearer = auth.substring(7);
        for (String line : read("tokens", "").split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0 && line.substring(eq + 1).strip().equals(bearer)) {
                return line.substring(0, eq).strip();
            }
        }
        return "other";
    }

    static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    static void reply(HttpExchange ex, int status, String body, String... headers) throws IOException {
        for (int i = 0; i + 1 < headers.length; i += 2) {
            ex.getResponseHeaders().add(headers[i], headers[i + 1]);
        }
        if (body == null) {
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    static String error(int code, String message, String details) {
        return "{\"error\":{\"message\":\"" + message + "\",\"type\":\"OAuthException\",\"code\":" + code
                + ",\"error_data\":{\"messaging_product\":\"whatsapp\",\"details\":\"" + details
                + "\"},\"fbtrace_id\":\"SYNTHETIC-TRACE\"}}";
    }

    static String template(String name, String language, String status) {
        StringBuilder text = new StringBuilder("Hola");
        for (String p : read("params", "customer_name").split(",")) {
            if (!p.isBlank()) {
                text.append(" {{").append(p.strip()).append("}}");
            }
        }
        return "{\"name\":\"" + name + "\",\"language\":\"" + language + "\",\"status\":\"" + status
                + "\",\"category\":\"UTILITY\",\"id\":\"300000000000001\",\"parameter_format\":\"NAMED\","
                + "\"components\":[{\"type\":\"BODY\",\"text\":\"" + text + "\"}]}";
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 18080), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            String path = ex.getRequestURI().getPath();
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            String kind = path.endsWith("/messages") ? "send"
                    : path.endsWith("/message_templates") ? "templates"
                    : path.endsWith("/phone_numbers") ? "phones" : "unknown";
            int n = seq.incrementAndGet();
            append("requests.log", Instant.now() + " #" + n + " " + ex.getRequestMethod() + " " + path
                    + (q.isEmpty() ? "" : " " + q) + " kind=" + kind + " token=" + tokenTag(ex) + " bytes=" + in.length);
            if (kind.equals("send")) {
                Files.write(DIR.resolve("last-send.json"), in);
            }
            long delay = Long.parseLong(read("delay.ms", "0"));
            if (delay > 0) {
                sleep(delay);
            }
            String mode = read(kind, kind.equals("send") ? "accept" : kind.equals("templates") ? "approved" : "owned");
            if (mode.equals("hang")) {
                sleep(60_000);
                reply(ex, 200, "{}");
                return;
            }
            switch (kind) {
                case "send" -> {
                    switch (mode) {
                        case "accept" -> reply(ex, 200, "{\"messaging_product\":\"whatsapp\",\"contacts\":[{\"input\":\""
                                + "+50600000001\",\"wa_id\":\"50600000001\"}],\"messages\":[{\"id\":\"wamid.SYNTHETIC-"
                                + n + "\",\"message_status\":\"accepted\"}]}");
                        // An id with a non accepted status: the outcome is unknown, but webhooks can match the id.
                        case "paused" -> reply(ex, 200, "{\"messaging_product\":\"whatsapp\",\"messages\":[{\"id\":"
                                + "\"wamid.SYNTHETIC-" + n + "\",\"message_status\":\"paused\"}]}");
                        case "error500" -> reply(ex, 500, error(1, "An unknown error occurred", "stub"));
                        case "rate429" -> reply(ex, 429, error(130429, "Rate limit hit", "stub"));
                        case "error401canary" -> reply(ex, 401, error(190,
                                "Error validating access token CANARY-TOKEN for CANARY-PHONE: Session has expired",
                                "Authorization: Bearer CANARY-TOKEN; text=CANARY-BODY"));
                        case "malformed" -> reply(ex, 200, "<html>not json CANARY-TOKEN</html>");
                        default -> reply(ex, 500, error(1, "unknown stub mode " + mode, "stub"));
                    }
                }
                case "templates" -> {
                    String name = q.getOrDefault("name", "?");
                    String language = q.getOrDefault("language", "?");
                    Files.writeString(DIR.resolve("last-templates.query"), name + " " + language);
                    switch (mode) {
                        case "approved" -> reply(ex, 200, "{\"data\":[" + template(name, language, "APPROVED") + "],\"paging\":{}}");
                        case "rejected", "paused", "deleted" -> reply(ex, 200, "{\"data\":["
                                + template(name, language, mode.toUpperCase()) + "],\"paging\":{}}");
                        case "absent" -> reply(ex, 200, "{\"data\":[],\"paging\":{}}");
                        case "otherLanguage" -> reply(ex, 200, "{\"data\":["
                                + template(name, language.equals("es") ? "en_US" : "es", "APPROVED") + "],\"paging\":{}}");
                        case "otherName" -> reply(ex, 200, "{\"data\":[" + template(name + "_other", language, "APPROVED")
                                + "],\"paging\":{}}");
                        case "error500" -> reply(ex, 500, error(1, "An unknown error occurred", "stub"));
                        case "redirect" -> reply(ex, 302, null, "Location", "http://127.0.0.1:18080/redirected");
                        case "canaryError" -> reply(ex, 400, error(100,
                                "Unsupported get request with token CANARY-TOKEN", "CANARY-BODY CANARY-PHONE"));
                        default -> reply(ex, 500, error(1, "unknown stub mode " + mode, "stub"));
                    }
                }
                case "phones" -> {
                    switch (mode) {
                        case "owned" -> {
                            StringBuilder data = new StringBuilder();
                            for (String id : read("phones.list", "").split(",")) {
                                if (!id.isBlank()) {
                                    data.append(data.length() == 0 ? "" : ",").append("{\"id\":\"").append(id.strip()).append("\"}");
                                }
                            }
                            reply(ex, 200, "{\"data\":[" + data + "],\"paging\":{}}");
                        }
                        case "notOwned" -> reply(ex, 200, "{\"data\":[],\"paging\":{}}");
                        case "error500" -> reply(ex, 500, error(1, "An unknown error occurred", "stub"));
                        default -> reply(ex, 500, error(1, "unknown stub mode " + mode, "stub"));
                    }
                }
                default -> reply(ex, 404, error(803, "Unsupported path", path));
            }
        });
        server.start();
        System.out.println("GraphStub listening on 127.0.0.1:18080, control dir " + DIR);
    }
}
