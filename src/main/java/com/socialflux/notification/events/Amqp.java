package com.socialflux.notification.events;

import com.socialflux.events.EventQueues;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Filas de consumo deste serviço (ele não publica nada, D12). O exchange {@code topic} compartilhado
 * ({@code socialflux.events}) e a fila de mensagens mortas são declarados pela biblioteca {@code socialflux-events}.
 */
@Configuration
@ConditionalOnProperty(prefix = "socialflux.events", name = "enabled", havingValue = "true")
public class Amqp {

    public static final String PROFILE_QUEUE = "notification-service.profile-events";
    public static final String FOLLOW_QUEUE = "notification-service.follow-events";
    public static final String POST_QUEUE = "notification-service.post-events";

    /** Perfis de quem age (nome, foto, privacidade, exclusão), publicados pelo profile_service. */
    @Bean
    Queue profileQueue() {
        return EventQueues.durable(PROFILE_QUEUE);
    }

    @Bean
    Binding profileBinding(@Qualifier("profileQueue") Queue queue, @Qualifier(EventQueues.EXCHANGE_BEAN) TopicExchange exchange) {
        return EventQueues.bind(queue, exchange, "profile.#");
    }

    /** Seguir, solicitações e deixar de seguir, publicados pelo social_graph_service. */
    @Bean
    Queue followQueue() {
        return EventQueues.durable(FOLLOW_QUEUE);
    }

    @Bean
    Binding followBinding(@Qualifier("followQueue") Queue queue, @Qualifier(EventQueues.EXCHANGE_BEAN) TopicExchange exchange) {
        return EventQueues.bind(queue, exchange, "follow.#");
    }

    /** Curtidas, respostas e exclusões, publicadas pelo post_service. */
    @Bean
    Queue postQueue() {
        return EventQueues.durable(POST_QUEUE);
    }

    @Bean
    Binding postBinding(@Qualifier("postQueue") Queue queue, @Qualifier(EventQueues.EXCHANGE_BEAN) TopicExchange exchange) {
        return EventQueues.bind(queue, exchange, "post.#");
    }
}
