package com.moundou.bank.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fast suite. Sending through Brevo's API (MB-18, ADR-010). This is the specification for
 * BrevoEmailGateway.
 *
 * A small HTTP server on this machine stands in for Brevo: it records each request and
 * answers as Brevo would, so nothing leaves the machine and no key is needed. What Brevo
 * expects is from its documentation: POST /v3/smtp/email, an api-key header, a JSON body
 * with sender, to, subject and htmlContent, and 201 on success.
 */
class BrevoEmailGatewayTests {

    static final String KEY = "xkeysib-test-0123456789";
    static final EmailMessage EMAIL = new EmailMessage(
            "ben@example.com", "Ama needs you to approve a record", "<p>Ama says they lent you \"$20.00\"</p>");

    private HttpServer brevo;
    private final List<Request> received = new ArrayList<>();
    private volatile int status = 201;
    private volatile String answer = "{\"messageId\":\"<1@relay.example>\"}";
    private volatile long delayMillis;

    record Request(String method, String path, String apiKey, String contentType, String contentLength,
                   String body) {
    }

    @BeforeEach
    void startBrevo() throws IOException {
        brevo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        brevo.setExecutor(Executors.newCachedThreadPool());
        brevo.createContext("/", this::answer);
        brevo.start();
    }

    @AfterEach
    void stopBrevo() {
        brevo.stop(0);
    }

    private void answer(HttpExchange exchange) throws IOException {
        received.add(new Request(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("api-key"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                exchange.getRequestHeaders().getFirst("Content-Length"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void sendsOneEmailAsBrevoExpectsIt() throws Exception {
        gateway(KEY, "alerts.moundou@gmail.com").send(EMAIL);

        assertThat(received).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/v3/smtp/email");
            assertThat(request.apiKey()).isEqualTo(KEY);
            assertThat(request.contentType()).startsWith("application/json");
            // A body of known length, not a chunked stream: the simplest request for any
            // server to read. Found by a smoke test whose stand-in server could not read
            // a chunked body (MB-18).
            assertThat(request.contentLength())
                    .isEqualTo(String.valueOf(request.body().getBytes(StandardCharsets.UTF_8).length));
        });
        JsonNode body = new ObjectMapper().readTree(received.get(0).body());
        assertThat(body.at("/sender/name").asText()).isEqualTo("Moundou's Bank");
        assertThat(body.at("/sender/email").asText()).isEqualTo("alerts.moundou@gmail.com");
        assertThat(body.at("/to")).hasSize(1);
        assertThat(body.at("/to/0/email").asText()).isEqualTo("ben@example.com");
        assertThat(body.at("/subject").asText()).isEqualTo("Ama needs you to approve a record");
        assertThat(body.at("/htmlContent").asText()).isEqualTo("<p>Ama says they lent you \"$20.00\"</p>");
    }

    @Test
    void aRefusalIsAFailedSendThatSaysWhyButNeverShowsTheKey() {
        status = 401;
        answer = "{\"code\":\"unauthorized\",\"message\":\"Key not found\"}";

        assertThatThrownBy(() -> gateway(KEY, "alerts.moundou@gmail.com").send(EMAIL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("Key not found")
                .hasMessageNotContaining(KEY);
    }

    @Test
    void aServerErrorIsAFailedSend() {
        status = 503;
        answer = "{\"message\":\"Service unavailable\"}";

        assertThatThrownBy(() -> gateway(KEY, "alerts.moundou@gmail.com").send(EMAIL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("503");
    }

    @Test
    void aLongErrorIsShortenedForTheLog() {
        status = 400;
        answer = "{\"message\":\"" + "x".repeat(5000) + "\"}";

        assertThatThrownBy(() -> gateway(KEY, "alerts.moundou@gmail.com").send(EMAIL))
                .satisfies(e -> assertThat(e.getMessage()).hasSizeLessThan(400));
    }

    @Test
    void aBrevoThatDoesNotAnswerFailsAtTheTimeoutInsteadOfHanging() {
        delayMillis = 3_000;
        BrevoEmailGateway gateway = gateway(KEY, "alerts.moundou@gmail.com", Duration.ofMillis(300));
        long start = System.nanoTime();

        assertThatThrownBy(() -> gateway.send(EMAIL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be reached");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2_000));
    }

    @Test
    void aBrevoThatCannotBeReachedIsAFailedSend() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        BrevoEmailGateway gateway = new BrevoEmailGateway(URI.create("http://127.0.0.1:" + closedPort),
                KEY, "alerts.moundou@gmail.com", "Moundou's Bank", Duration.ofSeconds(2));

        assertThatThrownBy(() -> gateway.send(EMAIL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be reached");
    }

    @Test
    void withoutAKeyOrASenderNothingIsSentAndTheErrorNamesTheMissingSetting() {
        for (String missing : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> gateway(missing, "alerts.moundou@gmail.com").send(EMAIL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("BREVO_API_KEY");
            assertThatThrownBy(() -> gateway(KEY, missing).send(EMAIL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ALERT_SENDER_EMAIL");
        }
        assertThat(received).isEmpty();
    }

    private BrevoEmailGateway gateway(String apiKey, String sender) {
        return gateway(apiKey, sender, Duration.ofSeconds(5));
    }

    private BrevoEmailGateway gateway(String apiKey, String sender, Duration timeout) {
        URI url = URI.create("http://127.0.0.1:" + brevo.getAddress().getPort());
        return new BrevoEmailGateway(url, apiKey, sender, "Moundou's Bank", timeout);
    }
}
