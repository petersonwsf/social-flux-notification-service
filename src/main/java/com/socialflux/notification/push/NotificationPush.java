package com.socialflux.notification.push;

import java.util.List;
import java.util.UUID;

/**
 * Porta de saída do tempo real. A implementação atual ({@link StreamRegistry}) entrega aos streams SSE desta instância; para
 * várias instâncias, trocar por uma que passe por uma exchange fanout sem mudar quem a chama.
 */
public interface NotificationPush {

    /** Entrega depois do commit da transação atual (ou já, se não há transação): nada é empurrado se ela falhar. */
    void afterCommit(UUID recipient, List<PushEvent> events);

    /** Fecha, depois do commit, os streams do usuário (conta excluída). */
    void closeAfterCommit(UUID user, String reason);
}
