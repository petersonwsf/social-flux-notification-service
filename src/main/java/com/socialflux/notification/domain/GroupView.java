package com.socialflux.notification.domain;

import java.util.List;

/**
 * Um grupo pronto para exibir: o resumo e os atores mais recentes (no máximo 2, só de contas ativas). A resposta e o trecho
 * (REPLY) são os do ator mais recente.
 */
public record GroupView(GroupRow group, List<ActorRow> recent) {

    public ActorRow latest() {
        return recent.isEmpty() ? null : recent.get(0);
    }
}
