package com.socialflux.notification.web;

import com.socialflux.notification.domain.ActorRow;
import com.socialflux.notification.domain.GroupRow;
import com.socialflux.notification.domain.GroupView;

import java.util.List;

/** Corpos das respostas e dos eventos SSE. Os nomes dos campos seguem o contrato do frontend (português, snake_case onde já era assim). */
public final class Dtos {

    private Dtos() {
    }

    /** Quem agiu: o id é o UUID da conta; sem nenhum dado pessoal. */
    public record Actor(String id, String username, String nome, String foto_perfil, String foto_url) {

        public static Actor of(ActorRow a) {
            return new Actor(a.actorId().toString(), a.username(), a.displayName(), a.avatarKey(),
                    a.avatarKey() == null ? null : "/media/avatars/" + a.avatarKey());
        }
    }

    /**
     * Uma notificação (grupo). {@code atores} traz no máximo os 2 mais recentes e {@code totalAtores} o total do grupo;
     * {@code resposta} e {@code trecho} são os da resposta mais recente (só REPLY); {@code seq} cresce a cada mudança do grupo.
     */
    public record Notificacao(
            String id,
            long seq,
            String tipo,
            List<Actor> atores,
            int totalAtores,
            String postagem,
            String resposta,
            String trecho,
            String atualizada_em,
            boolean vista) {

        public static Notificacao of(GroupView view) {
            GroupRow group = view.group();
            ActorRow latest = view.latest();
            return new Notificacao(group.id().toString(), group.activitySeq(), group.type(),
                    view.recent().stream().map(Actor::of).toList(), group.actorCount(),
                    group.postId() == null ? null : group.postId().toString(),
                    latest == null || latest.replyId() == null ? null : latest.replyId().toString(),
                    latest == null ? null : latest.excerpt(),
                    group.lastActivity().toInstant().toString(), group.seen());
        }
    }

    public record PageView(List<Notificacao> itens, String proximoCursor) {
    }

    /** {@code total} é limitado a 100 (o frontend mostra "99+"). */
    public record UnseenView(int total) {
    }

    /** Corpo de {@code POST /vistas}: o maior {@code seq} que a tela exibiu. */
    public record SeenRequest(Long ate) {
    }

    /** Eventos SSE. */
    public record Removed(String id) {
    }

    public record UnseenEvent(int naoVistas) {
    }

    public record Closing(String motivo) {
    }
}
