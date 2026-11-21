package com.socialflux.notification.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Uma linha de {@code notification_group}: o resumo de uma notificação agrupada. */
public record GroupRow(UUID id, UUID recipientId, String type, UUID postId, int actorCount, OffsetDateTime lastActivity,
                       long activitySeq, boolean seen) {
}
