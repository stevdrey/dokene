import com.sun.net.httpserver.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/** QA fault-injection stub of the OpenAI Responses API (suite #121). Controlled through files in /stub. NOT live-model evidence. */
public class Stub {
  static final Path DIR = Paths.get("/stub");
  static String read(String n, String d) { try { return Files.readString(DIR.resolve(n), StandardCharsets.UTF_8).strip(); } catch (Exception e) { return d; } }
  static final AtomicInteger seq = new AtomicInteger();
  public static void main(String[] a) throws Exception {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 18080), 0);
    s.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    s.createContext("/", ex -> {
      byte[] in = ex.getRequestBody().readAllBytes();
      String body = new String(in, StandardCharsets.UTF_8);
      String kind = body.contains("follow_up_message_draft") ? "draft" : "rec";
      int n = seq.incrementAndGet();
      Files.writeString(DIR.resolve("requests.log"), Instant.now() + " #" + n + " " + ex.getRequestMethod() + " " + ex.getRequestURI() + " kind=" + kind + " bytes=" + in.length + "\n",
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      Files.writeString(DIR.resolve("last-" + kind + ".json"), body);
      try { long d = Long.parseLong(read("delay-" + kind + ".ms", read("delay.ms", "0"))); if (d > 0) Thread.sleep(d); } catch (Exception e) { }
      String fail = read("fail", "");
      if (!fail.isEmpty()) { // "STATUS:COUNT"
        String[] p = fail.split(":"); int left = Integer.parseInt(p[1]);
        if (left > 0) { Files.writeString(DIR.resolve("fail"), p[0] + ":" + (left - 1));
          byte[] b = "{\"error\":{\"message\":\"stub failure\",\"type\":\"server_error\"}}".getBytes(); ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(Integer.parseInt(p[0]), b.length); ex.getResponseBody().write(b); ex.close(); return; } }
      String text = read(kind + ".json", "{}");
      String esc = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
      String out = "{\"id\":\"resp_stub" + n + "\",\"object\":\"response\",\"created_at\":1700000000,\"status\":\"completed\",\"model\":\"stub\",\"output\":[{\"type\":\"message\",\"id\":\"msg_stub" + n + "\",\"status\":\"completed\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"" + esc + "\",\"annotations\":[]}]}],\"parallel_tool_calls\":false,\"tool_choice\":\"auto\",\"tools\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2,\"input_tokens_details\":{\"cached_tokens\":0},\"output_tokens_details\":{\"reasoning_tokens\":0}}}";
      byte[] b = out.getBytes(StandardCharsets.UTF_8); ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
    });
    s.start(); System.out.println("stub listening 127.0.0.1:18080");
  }
}
