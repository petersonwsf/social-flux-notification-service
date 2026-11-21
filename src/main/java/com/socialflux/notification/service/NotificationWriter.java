package com.socialflux.notification.service;

import com.socialflux.events.EventIds;
import com.socialflux.notification.domain.ActorRepository;
import com.socialflux.notification.domain.ActorSnapshot;
import com.socialflux.notification.domain.GroupRepository;
import com.socialflux.notification.domain.GroupRow;
import com.socialflux.notification.domain.GroupView;
import com.socialflux.notification.push.NotificationPush;
import com.socialflux.notification.push.PushEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Todas as mudanças nas notificações passam por aqui, na transação do evento que as causou. Cada mudança avisa o dono pelo
 * tempo real DEPOIS do commit (um evento que falha não empurra nada).
 * <p>Regras: ninguém notifica a si mesmo; quem age de uma conta excluída (ou para uma conta excluída) não gera notificação;
 * o mesmo ator não entra duas vezes num grupo (D5); um ator NOVO faz o grupo subir ao topo e voltar a "não visto" (D6);
 * desfazer a ação tira o ator, visto ou não (D4), e o grupo some quando fica sem atores.
 */
@Service
public class NotificationWriter {

    public static final String LIKE = "LIKE";
    public static final String REPLY = "REPLY";
    public static final String FOLLOW = "FOLLOW";
    public static final String FOLLOW_REQUEST = "FOLLOW_REQUEST";
    public static final String FOLLOW_ACCEPTED = "FOLLOW_ACCEPTED";

    private final GroupRepository groups;
    private final ActorRepository actors;
    private final NotificationQueryService query;
    private final NotificationPush push;
    private final Clock clock;

    public NotificationWriter(GroupRepository groups, ActorRepository actors, NotificationQueryService query,
                              NotificationPush push, Clock clock) {
        this.groups = groups;
        this.actors = actors;
        this.query = query;
        this.push = push;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ chaves dos grupos

    public static String likeKey(UUID postId) {
        return "LIKE:" + postId;
    }

    public static String replyKey(UUID postId) {
        return "REPLY:" + postId;
    }

    public static String requestKey(UUID requester) {
        return "FOLLOW_REQUEST:" + requester;
    }

    public static String acceptedKey(UUID accepter) {
        return "FOLLOW_ACCEPTED:" + accepter;
    }

    /** Novos seguidores se agrupam por dia (UTC). */
    public String followKey() {
        return "FOLLOW:" + LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ escrita

    /** Põe {@code actor} no grupo de {@code recipient}, criando o grupo se preciso. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void addActor(UUID recipient, String type, String key, UUID postId, UUID actor, UUID replyId, String excerpt) {
        if (recipient.equals(actor) || !canNotify(actor) || !canNotify(recipient)) {
            return;
        }
        OffsetDateTime at = now();
        UUID groupId = groups.upsertGroup(EventIds.v7(clock), recipient, type, key, postId, at);
        if (groups.addActor(groupId, actor, replyId, excerpt, at)) {
            groups.bumpAfterAdd(groupId, at);
            pushGroup(recipient, groupId);
        }
    }

    /** Tira o ator do grupo (desfazer a ação). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void removeActor(UUID recipient, String key, UUID actor) {
        refresh(groups.removeActorFromKey(recipient, key, actor));
    }

    /** Tira o ator de todos os grupos do tipo do destinatário (FOLLOW tem um grupo por dia). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void removeActorFromType(UUID recipient, String type, UUID actor) {
        refresh(groups.removeActorFromType(recipient, type, actor));
    }

    /** Apaga o grupo inteiro (solicitação aceita, recusada ou cancelada). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void removeGroup(UUID recipient, String key) {
        groups.deleteByKey(recipient, key).ifPresent(id -> pushRemoved(recipient, id));
    }

    /** A postagem foi excluída: some o que a tem como alvo e os atores cuja resposta era ela. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPostDeleted(UUID postId) {
        for (GroupRepository.Gone gone : groups.deleteByPost(postId)) {
            pushRemoved(gone.recipientId(), gone.id());
        }
        refresh(groups.removeActorsByReply(postId));
    }

    /** Conta excluída: some tudo dela (como destinatária e como atora) e os streams dela são fechados. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void purgeAccount(UUID account) {
        groups.deleteAllOfRecipient(account);
        refresh(groups.removeActorEverywhere(account));
        push.closeAfterCommit(account, "conta_excluida");
    }

    /** Marca como vistos os grupos até {@code seq} e avisa as outras abas. */
    @Transactional
    public void markSeen(UUID recipient, long seq) {
        if (groups.markSeen(recipient, seq, now()) > 0) {
            push.afterCommit(recipient, List.of(PushEvent.unseen(query.unseen(recipient))));
        }
    }

    // ------------------------------------------------------------------ avisos

    /** Recalcula os grupos afetados por uma remoção: sem atores, o grupo some; com, é reenviado. */
    private void refresh(Collection<UUID> groupIds) {
        for (UUID id : new LinkedHashSet<>(groupIds)) {
            Optional<GroupRow> before = groups.find(id);
            if (before.isEmpty()) {
                continue;
            }
            UUID recipient = before.get().recipientId();
            if (groups.recount(id).orElse(0) == 0) {
                groups.delete(id);
                pushRemoved(recipient, id);
            } else {
                pushGroup(recipient, id);
            }
        }
    }

    private void pushGroup(UUID recipient, UUID groupId) {
        Optional<GroupView> view = query.view(groupId);
        List<PushEvent> events = new ArrayList<>();
        view.ifPresent(v -> events.add(PushEvent.group(v)));
        events.add(PushEvent.unseen(query.unseen(recipient)));
        push.afterCommit(recipient, events);
    }

    private void pushRemoved(UUID recipient, UUID groupId) {
        push.afterCommit(recipient, List.of(PushEvent.removed(groupId), PushEvent.unseen(query.unseen(recipient))));
    }

    /** Conta que existe (ou ainda não conhecida) e não está excluída. */
    private boolean canNotify(UUID account) {
        return actors.find(account).map(ActorSnapshot::isActive).orElse(true);
    }

    private OffsetDateTime now() {
        // O PostgreSQL guarda microssegundos: truncar aqui mantém o cursor de paginação idêntico ao valor gravado.
        return OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
