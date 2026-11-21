package com.socialflux.notification.web;

import com.socialflux.notification.config.NotificationProperties;
import com.socialflux.notification.domain.GroupRepository;
import com.socialflux.notification.domain.GroupRow;
import com.socialflux.notification.push.PushEvent;
import com.socialflux.notification.push.StreamRegistry;
import com.socialflux.notification.service.NotificationQueryService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Tempo real (SSE). Ao conectar: {@code contagem} (ressincroniza o badge) e, se o cliente diz até onde viu
 * ({@code Last-Event-ID} ou {@code ?desde=}), os grupos que mudaram depois. Depois, {@code notificacao}, {@code removida} e
 * {@code contagem} conforme os eventos chegam.
 * <p>O stream NÃO renova a sessão e termina em {@code min(exp do token, agora + 15 min ± 1 min)} com o evento
 * {@code encerrado}: ao reconectar, o gateway confere de novo o token (é o que limita a exposição de uma sessão revogada).
 */
@RestController
@RequestMapping("/api/notificacoes")
public class StreamController {

    private final StreamRegistry registry;
    private final NotificationQueryService query;
    private final GroupRepository groups;
    private final NotificationProperties.Sse config;
    private final Clock clock;

    public StreamController(StreamRegistry registry, NotificationQueryService query, GroupRepository groups,
                            NotificationProperties properties, Clock clock) {
        this.registry = registry;
        this.query = query;
        this.groups = groups;
        this.config = properties.sse();
        this.clock = clock;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
                      @RequestParam(name = "desde", required = false) String since,
                      @AuthenticationPrincipal Jwt jwt, HttpServletResponse response) {
        UUID user = NotificationController.user(jwt);
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");   // proxies que seguem o nginx não devem guardar o stream

        StreamRegistry.Connection connection = registry.open(user, deadline(jwt));
        registry.send(connection, PushEvent.unseen(query.unseen(user)));
        resend(connection, user, parseSeq(lastEventId != null ? lastEventId : since));
        return connection.emitter();
    }

    /** Reenvia o que mudou depois de {@code seq}; se foi muita coisa, manda o cliente recarregar a lista. */
    private void resend(StreamRegistry.Connection connection, UUID user, long seq) {
        if (seq < 0) {
            return;
        }
        List<GroupRow> rows = groups.since(user, seq, config.backlogLimit() + 1);
        if (rows.size() > config.backlogLimit()) {
            registry.send(connection, PushEvent.reload());
            return;
        }
        query.views(rows).forEach(view -> registry.send(connection, PushEvent.group(view)));
    }

    private Instant deadline(Jwt jwt) {
        long jitter = config.jitter().toMillis();
        long offset = jitter <= 0 ? 0 : ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
        Instant byDuration = clock.instant().plus(config.maxDuration()).plus(Duration.ofMillis(offset));
        Instant expires = jwt.getExpiresAt();
        return expires != null && expires.isBefore(byDuration) ? expires : byDuration;
    }

    /** {@code -1} = o cliente não informou (ou informou lixo): só a contagem. */
    private static long parseSeq(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            return Math.max(0, Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
