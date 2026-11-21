package com.socialflux.notification.web;

import com.socialflux.notification.service.NotificationQueryService;
import com.socialflux.notification.service.NotificationWriter;
import com.socialflux.notification.web.Dtos.PageView;
import com.socialflux.notification.web.Dtos.SeenRequest;
import com.socialflux.notification.web.Dtos.UnseenView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * API pública das notificações. Quem age vem SEMPRE do {@code sub} do token: nenhuma rota recebe usuário e não existe rota
 * de criação (as notificações nascem dos eventos). Só se lê e se marca como vistas o que é do próprio usuário.
 */
@RestController
@RequestMapping("/api/notificacoes")
public class NotificationController {

    private final NotificationQueryService query;
    private final NotificationWriter writer;

    public NotificationController(NotificationQueryService query, NotificationWriter writer) {
        this.query = query;
        this.writer = writer;
    }

    @GetMapping
    PageView list(@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limite,
                  @AuthenticationPrincipal Jwt jwt) {
        return query.page(user(jwt), cursor, limite);
    }

    @GetMapping("/nao-vistas")
    UnseenView unseen(@AuthenticationPrincipal Jwt jwt) {
        return new UnseenView(query.unseen(user(jwt)));
    }

    /** Marca como vistos os grupos até o {@code seq} que a tela exibiu; os que mudaram depois continuam novos. */
    @PostMapping("/vistas")
    ResponseEntity<Void> markSeen(@RequestBody SeenRequest request, @AuthenticationPrincipal Jwt jwt) {
        if (request == null || request.ate() == null || request.ate() < 0) {
            throw ApiException.badRequest("parametro_invalido", "Informe o campo 'ate' (número maior ou igual a zero)");
        }
        writer.markSeen(user(jwt), request.ate());
        return ResponseEntity.noContent().build();
    }

    static UUID user(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
