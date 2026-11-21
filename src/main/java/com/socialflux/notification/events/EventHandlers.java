package com.socialflux.notification.events;

import com.socialflux.events.EventEnvelope;
import com.socialflux.events.Inbox;
import com.socialflux.notification.service.FollowSyncService;
import com.socialflux.notification.service.PostSyncService;
import com.socialflux.notification.service.ProfileSyncService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reage aos eventos consumidos ({@code profile.*}, {@code follow.*} e {@code post.*}). Idempotente (inbox por id de
 * mensagem) e tolerante a tipos desconhecidos; a lógica está nos serviços de sincronização. Os testes chamam estes métodos
 * direto, exatamente como os listeners do RabbitMQ.
 */
@Component
public class EventHandlers {

    private final ProfileSyncService profiles;
    private final FollowSyncService follows;
    private final PostSyncService posts;
    private final Inbox inbox;
    private final JsonMapper mapper;

    public EventHandlers(ProfileSyncService profiles, FollowSyncService follows, PostSyncService posts, Inbox inbox,
                         JsonMapper mapper) {
        this.profiles = profiles;
        this.follows = follows;
        this.posts = posts;
        this.inbox = inbox;
        this.mapper = mapper;
    }

    public void handleProfile(String json) {
        EventEnvelope event = EventEnvelope.parse(mapper, json);
        inbox.once(event.id(), () -> profiles.apply(event));
    }

    public void handleFollow(String json) {
        EventEnvelope event = EventEnvelope.parse(mapper, json);
        inbox.once(event.id(), () -> follows.apply(event));
    }

    public void handlePost(String json) {
        EventEnvelope event = EventEnvelope.parse(mapper, json);
        inbox.once(event.id(), () -> posts.apply(event));
    }
}
