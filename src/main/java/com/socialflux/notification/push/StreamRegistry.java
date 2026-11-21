package com.socialflux.notification.push;

import com.socialflux.notification.config.NotificationProperties;
import com.socialflux.notification.web.ApiException;
import com.socialflux.notification.web.Dtos;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registro dos streams SSE abertos NESTA instância (D10: uma instância; ver {@link NotificationPush}).
 * <ul>
 *   <li>Cada conexão tem uma fila própria drenada por uma thread virtual por vez: a ordem dos eventos é preservada e um
 *   cliente lento nunca trava quem processa os eventos do RabbitMQ.</li>
 *   <li>Limites: {@code maxPerUser} (o mais antigo é fechado para abrir o novo, D11) e {@code maxTotal} (503).</li>
 *   <li>Uma varredura periódica manda o {@code ping} e fecha, com {@code encerrado}, quem passou do prazo (D9: no máximo
 *   15 min, ou o vencimento do token): ao reconectar, o gateway confere de novo a sessão.</li>
 * </ul>
 */
@Component
public class StreamRegistry implements NotificationPush {

    private static final Logger log = LoggerFactory.getLogger(StreamRegistry.class);
    private static final Duration SLACK = Duration.ofSeconds(30);
    private static final long RETRY_MS = 5_000;

    /** Uma linha a escrever no stream: um evento, um comentário ({@code ping}) ou o fechamento. */
    record Outgoing(String name, String id, String data, boolean comment, boolean close) {
    }

    /** Uma conexão aberta. {@code closed} vira {@code true} uma única vez. */
    public static final class Connection {
        private final UUID user;
        private final SseEmitter emitter;
        private final Instant deadline;
        private final Queue<Outgoing> queue = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean draining = new AtomicBoolean();
        private volatile Instant lastWrite;
        private volatile boolean closed;
        private final AtomicBoolean closing = new AtomicBoolean();
        private final AtomicBoolean unregistered = new AtomicBoolean();

        private Connection(UUID user, SseEmitter emitter, Instant deadline, Instant now) {
            this.user = user;
            this.emitter = emitter;
            this.deadline = deadline;
            this.lastWrite = now;
        }

        public SseEmitter emitter() {
            return emitter;
        }

        public UUID user() {
            return user;
        }
    }

    private final ConcurrentMap<UUID, List<Connection>> byUser = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final NotificationProperties.Sse config;
    private final JsonMapper mapper;
    private final Clock clock;

    public StreamRegistry(NotificationProperties properties, JsonMapper mapper, Clock clock) {
        this.config = properties.sse();
        this.mapper = mapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ abrir

    /** Abre um stream que termina em {@code deadline}. Fecha o mais antigo do usuário se ele já está no limite (D11). */
    public Connection open(UUID user, Instant deadline) {
        if (total.get() >= config.maxTotal()) {
            throw ApiException.unavailable("servidor_ocupado", "Muitas conexões abertas no momento. Tente novamente em instantes.");
        }
        Instant now = clock.instant();
        long timeout = Math.max(1, Duration.between(now, deadline).plus(SLACK).toMillis());
        Connection connection = new Connection(user, new SseEmitter(timeout), deadline, now);
        connection.emitter.onCompletion(() -> remove(connection));
        connection.emitter.onTimeout(() -> remove(connection));
        connection.emitter.onError(error -> remove(connection));

        List<Connection> list = byUser.computeIfAbsent(user, key -> new CopyOnWriteArrayList<>());
        while (list.size() >= config.maxPerUser()) {
            Connection oldest = list.get(0);
            close(oldest, "substituido");
            unregister(oldest);   // o evento de fechamento ainda é escrito; a vaga é liberada já
        }
        connection.queue.add(new Outgoing(null, null, String.valueOf(RETRY_MS), false, false));   // `retry:` do cliente
        list.add(connection);
        total.incrementAndGet();
        return connection;
    }

    /** Escreve na fila da conexão (primeira linha do stream: {@code retry}, depois o que o controller enviar). */
    public void send(Connection connection, PushEvent event) {
        enqueue(connection, outgoing(event));
    }

    public int openCount() {
        return total.get();
    }

    // ------------------------------------------------------------------ NotificationPush

    @Override
    public void afterCommit(UUID recipient, List<PushEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        Runnable deliver = () -> {
            List<Connection> connections = byUser.get(recipient);
            if (connections == null || connections.isEmpty()) {
                return;
            }
            for (PushEvent event : events) {
                Outgoing out = outgoing(event);
                connections.forEach(connection -> enqueue(connection, out));
            }
        };
        runAfterCommit(deliver);
    }

    @Override
    public void closeAfterCommit(UUID user, String reason) {
        runAfterCommit(() -> {
            List<Connection> connections = byUser.get(user);
            if (connections != null) {
                connections.forEach(connection -> close(connection, reason));
            }
        });
    }

    private static void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    // ------------------------------------------------------------------ manutenção do stream

    /** Manda o {@code ping} de quem está calado há muito tempo e fecha, com {@code encerrado}, quem passou do prazo. */
    @Scheduled(fixedDelayString = "${notification.sse.sweep-interval:PT5S}")
    void sweep() {
        Instant now = clock.instant();
        for (List<Connection> connections : byUser.values()) {
            for (Connection connection : connections) {
                if (!now.isBefore(connection.deadline)) {
                    close(connection, "renovar");
                } else if (connection.queue.isEmpty()
                        && Duration.between(connection.lastWrite, now).compareTo(config.heartbeat()) >= 0) {
                    enqueue(connection, new Outgoing(null, null, "ping", true, false));
                }
            }
        }
    }

    @PreDestroy
    void shutdown() {
        byUser.values().forEach(list -> list.forEach(connection -> {
            connection.closed = true;
            connection.emitter.complete();   // os navegadores reconectam (outra instância ou depois do deploy)
        }));
        byUser.clear();
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ internos

    private Outgoing outgoing(PushEvent event) {
        return new Outgoing(event.name(), event.id() == null ? null : String.valueOf(event.id()),
                mapper.writeValueAsString(event.payload()), false, false);
    }

    private void close(Connection connection, String reason) {
        if (!connection.closing.compareAndSet(false, true)) {
            return;
        }
        enqueue(connection, new Outgoing(PushEvent.CLOSING, null, mapper.writeValueAsString(new Dtos.Closing(reason)), false, true));
    }

    private void enqueue(Connection connection, Outgoing out) {
        if (connection.closed) {
            return;
        }
        connection.queue.add(out);
        drain(connection);
    }

    private void drain(Connection connection) {
        if (!connection.draining.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> {
            try {
                Outgoing out;
                while ((out = connection.queue.poll()) != null) {
                    if (!write(connection, out)) {
                        break;
                    }
                }
            } finally {
                connection.draining.set(false);
                if (!connection.queue.isEmpty() && !connection.closed) {
                    drain(connection);
                }
            }
        });
    }

    /** Escreve uma linha. {@code false} se o stream terminou (fechamento pedido ou cliente que foi embora). */
    private boolean write(Connection connection, Outgoing out) {
        if (connection.closed) {
            return false;
        }
        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event();
            if (out.comment()) {
                event.comment(out.data());
            } else if (out.name() == null) {
                event.reconnectTime(Long.parseLong(out.data()));
            } else {
                if (out.id() != null) {
                    event.id(out.id());
                }
                event.name(out.name()).data(out.data());
            }
            connection.emitter.send(event);
            connection.lastWrite = clock.instant();
        } catch (IOException | IllegalStateException e) {
            log.debug("Stream de {} terminou: {}", connection.user, e.toString());
            finish(connection);
            return false;
        }
        if (out.close()) {
            finish(connection);
            return false;
        }
        return true;
    }

    private void finish(Connection connection) {
        remove(connection);
        try {
            connection.emitter.complete();
        } catch (RuntimeException e) {
            log.debug("Falha ao completar o stream de {}: {}", connection.user, e.toString());
        }
    }

    private void remove(Connection connection) {
        connection.closed = true;
        unregister(connection);
    }

    private void unregister(Connection connection) {
        if (!connection.unregistered.compareAndSet(false, true)) {
            return;
        }
        List<Connection> list = byUser.get(connection.user);
        if (list != null && list.remove(connection)) {
            total.decrementAndGet();
            if (list.isEmpty()) {
                byUser.remove(connection.user, list);
            }
        }
    }
}
