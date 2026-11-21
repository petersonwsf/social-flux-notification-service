package com.socialflux.notification.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/** Cliente SSE mínimo para os testes: lê o stream numa thread e guarda os eventos recebidos. */
public class SseClient implements AutoCloseable {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public record Event(String name, String id, String data) {

        public JsonNode json() {
            return MAPPER.readTree(data);
        }
    }

    public final List<Event> events = new CopyOnWriteArrayList<>();
    public final List<String> comments = new CopyOnWriteArrayList<>();
    public volatile int status;
    public volatile boolean ended;
    private final Thread reader;
    private volatile InputStream body;

    public SseClient(String baseUrl, String path, String token, String lastEventId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Accept", "text/event-stream");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (lastEventId != null) {
            builder.header("Last-Event-ID", lastEventId);
        }
        HttpRequest request = builder.GET().build();
        reader = Thread.ofVirtual().start(() -> read(request));
    }

    private void read(HttpRequest request) {
        try {
            HttpResponse<InputStream> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
            status = response.statusCode();
            body = response.body();
            if (status != 200) {
                return;
            }
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String name = null;
                String id = null;
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (name != null) {
                            events.add(new Event(name, id, data.toString()));
                        }
                        name = null;
                        id = null;
                        data.setLength(0);
                    } else if (line.startsWith(":")) {
                        comments.add(line.substring(1).trim());
                    } else if (line.startsWith("event:")) {
                        name = line.substring(6).trim();
                    } else if (line.startsWith("id:")) {
                        id = line.substring(3).trim();
                    } else if (line.startsWith("data:")) {
                        data.append(line.substring(5).trim());
                    }
                }
            }
        } catch (IOException | InterruptedException e) {
            // conexão encerrada pelo teste ou pelo servidor
        } finally {
            ended = true;
        }
    }

    /** Espera um evento que satisfaça a condição; falha o teste se não chegar a tempo. */
    public Event await(String name, Predicate<Event> condition, Duration timeout) {
        long limit = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < limit) {
            for (Event event : events) {
                if (event.name().equals(name) && condition.test(event)) {
                    return event;
                }
            }
            sleep(20);
        }
        throw new AssertionError("Evento '" + name + "' não chegou em " + timeout + ". Recebidos: " + events);
    }

    public Event await(String name) {
        return await(name, event -> true, Duration.ofSeconds(5));
    }

    /** Espera um tempo e confirma que nenhum evento do nome (com a condição) chegou. */
    public boolean noneAfter(String name, Predicate<Event> condition, Duration wait) {
        sleep(wait.toMillis());
        return events.stream().noneMatch(event -> event.name().equals(name) && condition.test(event));
    }

    public boolean awaitEnd(Duration timeout) {
        long limit = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < limit) {
            if (ended) {
                return true;
            }
            sleep(20);
        }
        return ended;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try {
            if (body != null) {
                body.close();
            }
        } catch (IOException e) {
            // já fechado
        }
        reader.interrupt();
    }
}
