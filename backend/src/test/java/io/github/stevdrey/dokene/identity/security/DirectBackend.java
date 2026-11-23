// ABOUTME: Test support for reaching the backend port directly (spec 0019, issue 318): pins a loopback port and the
// ABOUTME: public origin on it, and sends raw HTTP so a test can forge Host, which java.net.http refuses to set.
package io.github.stevdrey.dokene.identity.security;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.test.context.DynamicPropertyRegistry;

final class DirectBackend {

    private DirectBackend() {
    }

    /**
     * Sets {@code server.port} to a free loopback port chosen now and the public origin to that address. The login
     * callback and the back channel replay both target the configured port, so a random port chosen by the server
     * itself would be known too late.
     */
    static void registerOrigin(DynamicPropertyRegistry registry) {
        int port = freePort();
        registry.add("server.port", () -> port);
        registry.add("dokene.security.public-origin", () -> "http://127.0.0.1:" + port);
    }

    /** One request with exactly the given headers; {@code Host} defaults to the real address when not forged. */
    static Response send(int port, String method, String path, Map<String, String> headers) throws IOException {
        Map<String, String> sent = new LinkedHashMap<>();
        sent.put("Host", "127.0.0.1:" + port);
        sent.putAll(headers);
        sent.put("Content-Length", "0");
        sent.put("Connection", "close");
        StringBuilder request = new StringBuilder(method + " " + path + " HTTP/1.1\r\n");
        sent.forEach((name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
        request.append("\r\n");
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return Response.parse(new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1));
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("No free loopback port", exception);
        }
    }

    /** Header names are lower case; a repeated header keeps its first value. */
    record Response(int status, Map<String, String> headers) {

        Optional<String> header(String name) {
            return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
        }

        private static Response parse(String raw) {
            String head = raw.substring(0, raw.indexOf("\r\n\r\n"));
            String[] lines = head.split("\r\n");
            Map<String, String> headers = new LinkedHashMap<>();
            for (int index = 1; index < lines.length; index++) {
                int colon = lines[index].indexOf(':');
                headers.putIfAbsent(lines[index].substring(0, colon).toLowerCase(Locale.ROOT),
                        lines[index].substring(colon + 1).trim());
            }
            return new Response(Integer.parseInt(lines[0].split(" ")[1]), headers);
        }
    }
}
