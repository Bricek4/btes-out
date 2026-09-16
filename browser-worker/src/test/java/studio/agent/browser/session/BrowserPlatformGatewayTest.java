package studio.agent.browser.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BrowserPlatformGatewayTest {
  private HttpServer server;

  @AfterEach void stop() { if (server != null) server.stop(0); }

  @Test void usesBrowserTokenTypedLocatorsChecksumReservationAndStablePublicationKey() throws Exception {
    UUID task = UUID.randomUUID(); UUID profile = UUID.randomUUID(); UUID artifact = UUID.randomUUID(); UUID reservation = UUID.randomUUID();
    var seen = new ArrayList<String>();
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/internal/worker-context/browser/" + task, e -> { seen.add("context:" + auth(e)); json(e, "{\"loginProfiles\":[{\"id\":\""+profile+"\",\"reference\":\"admin\"}]}"); });
    server.createContext("/internal/tasks/" + task + "/login-profiles/" + profile + "/credential", e -> { seen.add("credential:" + auth(e)); json(e, "{\"loginPath\":\"/login\",\"username\":\"user@example.test\",\"password\":\"secret\",\"usernameLocator\":{\"kind\":\"label\",\"role\":null,\"name\":\"Email\"},\"passwordLocator\":{\"kind\":\"label\",\"role\":null,\"name\":\"Password\"},\"submitLocator\":{\"kind\":\"role\",\"role\":\"button\",\"name\":\"Sign in\"}}"); });
    server.createContext("/internal/tasks/" + task + "/artifacts/presign", e -> { String body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); seen.add("presign:"+auth(e)+":"+field(body,"idempotencyKey")); json(e, "{\"artifactId\":\""+artifact+"\",\"reservationId\":\""+reservation+"\",\"idempotencyKey\":\""+field(body,"idempotencyKey")+"\",\"putUrl\":\"http://127.0.0.1:"+server.getAddress().getPort()+"/put\"}"); });
    server.createContext("/put", e -> {
      seen.add("put:"+e.getRequestHeaders().getFirst("x-amz-checksum-sha256"));
      assertThat(e.getRequestHeaders().getFirst("Content-Type")).isEqualTo("image/png");
      assertThat(e.getRequestHeaders().getFirst("Content-Length")).isEqualTo("3");
      e.getRequestBody().readAllBytes(); e.sendResponseHeaders(200,-1); e.close();
    });
    server.createContext("/internal/tasks/" + task + "/artifacts/" + artifact + "/complete", e -> { String body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); seen.add("complete:"+auth(e)+":"+field(body,"reservationId")); json(e,"{\"artifactId\":\""+artifact+"\",\"completed\":true}"); });
    server.start();
    var gateway = new BrowserPlatformGateway("http://127.0.0.1:" + server.getAddress().getPort(), "browser-token");
    var credential = gateway.resolve(task,"admin");
    assertThat(credential.usernameLocator()).isEqualTo(LocatorSpec.label("Email"));
    assertThat(credential.submitLocator()).isEqualTo(LocatorSpec.role("button","Sign in"));
    var published = new PublishedArtifact("artifact://"+task+"/admin-marker/image.png", new byte[]{1,2,3}, "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81");
    gateway.publish(published); gateway.publish(published);
    assertThat(seen).allMatch(value -> !value.startsWith("put:") || value.length() > 4).contains("context:Bearer browser-token", "credential:Bearer browser-token", "complete:Bearer browser-token:"+reservation);
    List<String> keys = seen.stream().filter(v -> v.startsWith("presign:")).toList();
    assertThat(keys).hasSize(2).allMatch(v -> v.startsWith("presign:Bearer browser-token:")).allMatch(v -> !v.substring(v.lastIndexOf(':')+1).isBlank());
    assertThat(keys.get(0)).isEqualTo(keys.get(1));
  }
  private static String auth(HttpExchange e){return e.getRequestHeaders().getFirst("Authorization");}
  private static String field(String json,String key){String prefix="\""+key+"\":\"";int start=json.indexOf(prefix)+prefix.length();return json.substring(start,json.indexOf('"',start));}
  private static void json(HttpExchange e,String body)throws java.io.IOException{byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().add("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();}
}
