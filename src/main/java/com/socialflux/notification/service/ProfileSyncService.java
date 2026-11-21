package com.socialflux.notification.service;

import com.socialflux.events.EventEnvelope;
import com.socialflux.notification.domain.ActorRepository;
import com.socialflux.notification.domain.ActorSnapshot;
import com.socialflux.notification.domain.FollowEdgeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Mantém a cópia local dos perfis ({@code actor_snapshot}) a partir dos eventos {@code profile.*}: o nome e a foto mostrados
 * nas notificações são sempre os atuais. A exclusão da conta deixa um "túmulo" (um {@code created} atrasado não a revive) e
 * apaga as notificações dela, como destinatária e como atora. Versões iguais ou mais antigas que a gravada são ignoradas.
 */
@Service
public class ProfileSyncService {

    public static final String PROFILE_CREATED = "profile.created.v1";
    public static final String PROFILE_UPDATED = "profile.updated.v1";
    public static final String PROFILE_PRIVACY_CHANGED = "profile.privacy-changed.v1";
    public static final String PROFILE_DELETED = "profile.deleted.v1";

    private static final Logger log = LoggerFactory.getLogger(ProfileSyncService.class);

    private final ActorRepository actors;
    private final FollowEdgeRepository edges;
    private final NotificationWriter writer;
    private final Clock clock;

    public ProfileSyncService(ActorRepository actors, FollowEdgeRepository edges, NotificationWriter writer, Clock clock) {
        this.actors = actors;
        this.edges = edges;
        this.writer = writer;
        this.clock = clock;
    }

    @Transactional
    public void apply(EventEnvelope event) {
        switch (event.type()) {
            case PROFILE_CREATED, PROFILE_UPDATED -> upsert(event);
            case PROFILE_PRIVACY_CHANGED -> changePrivacy(event);
            case PROFILE_DELETED -> delete(event);
            default -> log.debug("Evento ignorado: {}", event.type());
        }
    }

    private void upsert(EventEnvelope event) {
        UUID id = event.aggregateId();
        Optional<ActorSnapshot> current = actors.findForUpdate(id);
        if (current.isPresent() && current.get().profileVersion() >= event.aggregateVersion()) {
            return;   // duplicado ou mais antigo que o já aplicado (inclui o "túmulo" de uma conta excluída)
        }
        JsonNode data = event.data();
        ActorSnapshot next = new ActorSnapshot(id, data.path("username").asString(), data.path("displayName").asString(),
                nullable(data.path("avatarKey")), data.path("isPrivate").asBoolean(), ActorSnapshot.ACTIVE,
                event.aggregateVersion());
        if (current.isPresent()) {
            actors.update(next, now());
        } else {
            actors.insert(next, now());
        }
    }

    private void changePrivacy(EventEnvelope event) {
        Optional<ActorSnapshot> current = actors.findForUpdate(event.aggregateId());
        // Mesma versão do profile.updated que acompanha este evento: só ignora se for estritamente mais antigo.
        if (current.isEmpty() || !current.get().isActive() || current.get().profileVersion() > event.aggregateVersion()) {
            return;
        }
        ActorSnapshot before = current.get();
        boolean isPrivate = event.data().path("isPrivate").asBoolean();
        if (before.isPrivate() == isPrivate) {
            return;
        }
        actors.update(new ActorSnapshot(before.accountId(), before.username(), before.displayName(), before.avatarKey(),
                isPrivate, before.status(), event.aggregateVersion()), now());
    }

    private void delete(EventEnvelope event) {
        UUID id = event.aggregateId();
        Optional<ActorSnapshot> current = actors.findForUpdate(id);
        if (current.isPresent() && !current.get().isActive()) {
            return;   // já excluída
        }
        long version = Math.max(event.aggregateVersion(), current.map(ActorSnapshot::profileVersion).orElse(0L));
        if (current.isPresent()) {
            ActorSnapshot s = current.get();
            actors.update(new ActorSnapshot(id, s.username(), s.displayName(), null, s.isPrivate(),
                    ActorSnapshot.DELETED, version), now());
        } else {
            // Chegou antes do profile.created: guarda um "túmulo" para que um created atrasado (versão menor) não reviva a conta.
            actors.insert(new ActorSnapshot(id, "excluido-" + id, "", null, false, ActorSnapshot.DELETED, version), now());
        }
        edges.deleteAllOf(id);
        writer.purgeAccount(id);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String nullable(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }
}
