package com.socialflux.notification.service;

import com.socialflux.events.EventEnvelope;
import com.socialflux.notification.domain.FollowEdgeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Transforma os eventos {@code follow.*} do grafo em notificações (D2, D3, D4) e mantém a cópia local das arestas aceitas
 * (só para a regra D7):
 * <ul>
 *   <li>{@code created} DIRECT: FOLLOW para quem foi seguido (agrupado por dia);</li>
 *   <li>{@code created} REQUEST_ACCEPTED/AUTO_ON_PUBLIC: apaga a FOLLOW_REQUEST e avisa SÓ quem pediu (FOLLOW_ACCEPTED);</li>
 *   <li>{@code requested}: FOLLOW_REQUEST (um item por pessoa, com os botões Aceitar/Recusar no frontend);</li>
 *   <li>{@code request-cancelled}/{@code request-rejected}: apaga a FOLLOW_REQUEST;</li>
 *   <li>{@code removed}: tira quem deixou de seguir do grupo FOLLOW e apaga o FOLLOW_ACCEPTED dessa relação.</li>
 * </ul>
 * Cada vínculo tem um {@code relationId}: um {@code removed} deixa um túmulo que barra o {@code created} atrasado.
 */
@Service
public class FollowSyncService {

    public static final String FOLLOW_CREATED = "follow.created.v1";
    public static final String FOLLOW_REMOVED = "follow.removed.v1";
    public static final String FOLLOW_REQUESTED = "follow.requested.v1";
    public static final String FOLLOW_REQUEST_CANCELLED = "follow.request-cancelled.v1";
    public static final String FOLLOW_REQUEST_REJECTED = "follow.request-rejected.v1";

    private static final Logger log = LoggerFactory.getLogger(FollowSyncService.class);

    private final FollowEdgeRepository edges;
    private final NotificationWriter writer;
    private final Clock clock;

    public FollowSyncService(FollowEdgeRepository edges, NotificationWriter writer, Clock clock) {
        this.edges = edges;
        this.writer = writer;
        this.clock = clock;
    }

    @Transactional
    public void apply(EventEnvelope event) {
        switch (event.type()) {
            case FOLLOW_CREATED -> created(event);
            case FOLLOW_REMOVED -> removed(event);
            case FOLLOW_REQUESTED -> requested(event.data());
            case FOLLOW_REQUEST_CANCELLED, FOLLOW_REQUEST_REJECTED -> writer.removeGroup(uuid(event.data(), "targetId", null),
                    NotificationWriter.requestKey(uuid(event.data(), "requesterId", null)));
            default -> log.debug("Evento ignorado: {}", event.type());
        }
    }

    private void requested(JsonNode data) {
        UUID requester = uuid(data, "requesterId", null);
        writer.addActor(uuid(data, "targetId", null), NotificationWriter.FOLLOW_REQUEST,
                NotificationWriter.requestKey(requester), null, requester, null, null);
    }

    private void created(EventEnvelope event) {
        JsonNode data = event.data();
        UUID relationId = uuid(data, "relationId", event.aggregateId());
        if (edges.isRemoved(relationId)) {
            return;   // o vínculo já foi desfeito: o created chegou atrasado
        }
        UUID follower = uuid(data, "followerId", null);
        UUID followee = uuid(data, "followeeId", null);
        edges.upsert(relationId, follower, followee, createdAt(data));

        if ("DIRECT".equals(data.path("origin").asString("DIRECT"))) {
            writer.addActor(followee, NotificationWriter.FOLLOW, writer.followKey(), null, follower, null, null);
            return;
        }
        // Solicitação aceita (à mão ou porque a conta virou pública): a solicitação some e só quem pediu é avisado (D3).
        writer.removeGroup(followee, NotificationWriter.requestKey(follower));
        writer.addActor(follower, NotificationWriter.FOLLOW_ACCEPTED, NotificationWriter.acceptedKey(followee), null,
                followee, null, null);
    }

    private void removed(EventEnvelope event) {
        JsonNode data = event.data();
        UUID relationId = uuid(data, "relationId", event.aggregateId());
        UUID follower = uuid(data, "followerId", null);
        UUID followee = uuid(data, "followeeId", null);
        edges.deleteIfRelation(relationId, follower, followee);
        edges.markRemoved(relationId, now());
        writer.removeActorFromType(followee, NotificationWriter.FOLLOW, follower);
        writer.removeGroup(follower, NotificationWriter.acceptedKey(followee));
    }

    private OffsetDateTime createdAt(JsonNode data) {
        JsonNode node = data.path("createdAt");
        return node.isMissingNode() || node.isNull()
                ? now() : OffsetDateTime.ofInstant(Instant.parse(node.asString()), ZoneOffset.UTC);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static UUID uuid(JsonNode data, String field, UUID fallback) {
        JsonNode node = data.path(field);
        if (node.isMissingNode() || node.isNull()) {
            if (fallback != null) {
                return fallback;
            }
            throw new IllegalArgumentException("Evento de relação sem o campo obrigatório '" + field + "'");
        }
        return UUID.fromString(node.asString());
    }
}
