package com.socialflux.notification.domain;

import java.util.UUID;

/** Cópia local do perfil público de quem age (alimentada pelos eventos profile.*). Sem nenhum dado pessoal. */
public record ActorSnapshot(UUID accountId, String username, String displayName, String avatarKey, boolean isPrivate,
                            String status, long profileVersion) {

    public static final String ACTIVE = "ACTIVE";
    public static final String DELETED = "DELETED";

    public boolean isActive() {
        return ACTIVE.equals(status);
    }
}
