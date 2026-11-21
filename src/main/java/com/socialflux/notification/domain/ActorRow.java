package com.socialflux.notification.domain;

import java.util.UUID;

/** Um ator de um grupo, já com o nome e a foto atuais (cópia local do perfil). */
public record ActorRow(UUID groupId, UUID actorId, UUID replyId, String excerpt, String username, String displayName,
                       String avatarKey) {
}
