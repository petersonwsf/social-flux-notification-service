package com.socialflux.notification.service;

import com.socialflux.events.EventEnvelope;
import com.socialflux.notification.domain.ActorRepository;
import com.socialflux.notification.domain.ActorSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.UUID;

/**
 * Transforma os eventos {@code post.*} do post_service em notificações: curtida (LIKE) e resposta (REPLY) para o autor da
 * postagem; descurtir e excluir desfazem (D4). Os demais eventos são ignorados.
 */
@Service
public class PostSyncService {

    public static final String POST_CREATED = "post.created.v1";
    public static final String POST_DELETED = "post.deleted.v1";
    public static final String POST_LIKED = "post.liked.v1";
    public static final String POST_UNLIKED = "post.unliked.v1";

    private static final Logger log = LoggerFactory.getLogger(PostSyncService.class);

    private final NotificationWriter writer;
    private final ActorRepository actors;

    public PostSyncService(NotificationWriter writer, ActorRepository actors) {
        this.writer = writer;
        this.actors = actors;
    }

    @Transactional
    public void apply(EventEnvelope event) {
        switch (event.type()) {
            case POST_LIKED -> liked(event.data());
            case POST_UNLIKED -> unliked(event.data());
            case POST_CREATED -> created(event.data());
            case POST_DELETED -> writer.onPostDeleted(uuid(event.data(), "postId"));
            default -> log.debug("Evento ignorado: {}", event.type());
        }
    }

    private void liked(JsonNode data) {
        UUID postId = uuid(data, "postId");
        writer.addActor(uuid(data, "postAuthorId"), NotificationWriter.LIKE, NotificationWriter.likeKey(postId), postId,
                uuid(data, "userId"), null, null);
    }

    private void unliked(JsonNode data) {
        writer.removeActor(uuid(data, "postAuthorId"), NotificationWriter.likeKey(uuid(data, "postId")), uuid(data, "userId"));
    }

    /** Só respostas notificam (postagem raiz não tem destinatário). */
    private void created(JsonNode data) {
        if (data.path("parentId").isMissingNode() || data.path("parentId").isNull()) {
            return;
        }
        UUID parentId = uuid(data, "parentId");
        UUID recipient = uuid(data, "parentAuthorId");
        UUID author = uuid(data, "authorId");
        if (!canSee(recipient, author)) {
            return;
        }
        JsonNode excerpt = data.path("excerpt");
        writer.addActor(recipient, NotificationWriter.REPLY, NotificationWriter.replyKey(parentId), parentId, author,
                uuid(data, "postId"), excerpt.isMissingNode() || excerpt.isNull() ? null : excerpt.asString());
    }

    /**
     * D7: a resposta de uma conta privada que o destinatário não segue não notifica (o post_service não a mostraria a ele).
     * Sem a cópia do perfil de quem respondeu não dá para saber se é privada: na dúvida, não notifica.
     */
    private boolean canSee(UUID recipient, UUID author) {
        Optional<ActorSnapshot> snapshot = actors.find(author);
        if (snapshot.isEmpty()) {
            return false;
        }
        return !snapshot.get().isPrivate() || actors.follows(recipient, author);
    }

    private static UUID uuid(JsonNode data, String field) {
        JsonNode node = data.path(field);
        if (node.isMissingNode() || node.isNull() || node.asString().isBlank()) {
            throw new IllegalArgumentException("Evento de postagem sem o campo obrigatório '" + field + "'");
        }
        return UUID.fromString(node.asString());
    }
}
