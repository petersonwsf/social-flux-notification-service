package com.socialflux.notification.push;

import com.socialflux.notification.domain.GroupView;
import com.socialflux.notification.web.Dtos;

import java.util.UUID;

/**
 * Um evento a empurrar pelo SSE. {@code id} (quando existe) é o {@code seq} do grupo: o navegador o devolve em
 * {@code Last-Event-ID} e o servidor reenvia o que mudou depois dele.
 */
public record PushEvent(String name, Long id, Object payload) {

    public static final String GROUP = "notificacao";
    public static final String REMOVED = "removida";
    public static final String UNSEEN = "contagem";
    public static final String CLOSING = "encerrado";
    public static final String RELOAD = "recarregar";

    /** Grupo novo ou alterado: o frontend substitui pelo {@code id} do grupo e o sobe ao topo. */
    public static PushEvent group(GroupView view) {
        return new PushEvent(GROUP, view.group().activitySeq(), Dtos.Notificacao.of(view));
    }

    public static PushEvent removed(UUID groupId) {
        return new PushEvent(REMOVED, null, new Dtos.Removed(groupId.toString()));
    }

    public static PushEvent unseen(int total) {
        return new PushEvent(UNSEEN, null, new Dtos.UnseenEvent(total));
    }

    /** Perdeu-se mais do que o reenvio comporta: o frontend recarrega a lista. */
    public static PushEvent reload() {
        return new PushEvent(RELOAD, null, new java.util.LinkedHashMap<String, Object>());
    }
}
