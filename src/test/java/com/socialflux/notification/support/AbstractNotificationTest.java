package com.socialflux.notification.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.socialflux.notification.events.EventHandlers;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base dos testes de integração: sobe o serviço completo em porta aleatória contra um PostgreSQL REAL embutido (sem Docker)
 * e um servidor JWKS local com uma chave RSA de teste (tokens validados como em produção). Usuários, relações e postagens
 * "acontecem" como no sistema real: por eventos {@code profile.*}, {@code follow.*} e {@code post.*} entregues aos mesmos
 * tratadores que o RabbitMQ usa.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "socialflux.events.enabled=false",
                "notification.security.issuer=http://auth.test",
                "notification.maintenance.enabled=false",
                "notification.sse.sweep-interval=PT0.2S",
                "notification.sse.heartbeat=PT1S",
                "notification.sse.max-per-user=3",
                "notification.sse.backlog-limit=5",
                "spring.flyway.enabled=true"
        })
@Import(TestSupportConfig.class)
public abstract class AbstractNotificationTest {

    protected static final String ISSUER = "http://auth.test";
    protected static final String USER_AUDIENCE = "socialflux-api";

    protected static final EmbeddedPostgres POSTGRES;
    protected static final RSAKey KEY;
    private static final HttpServer JWKS;

    static {
        try {
            POSTGRES = EmbeddedPostgres.builder().start();
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            JWKS.createContext("/jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            JWKS.start();
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao preparar a infraestrutura de teste", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("notification.security.jwks-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks.json");
    }

    @Value("${local.server.port}")
    protected int port;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected EventHandlers events;

    private Http http;

    protected Http http() {
        if (http == null) {
            http = new Http(port);
        }
        return http;
    }

    // ------------------------------------------------------------------ tokens

    protected static String userToken(UUID accountId) {
        return userToken(accountId, Duration.ofHours(1));
    }

    protected static String userToken(UUID accountId, Duration ttl) {
        return sign(new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(USER_AUDIENCE).subject(accountId.toString())
                .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + ttl.toMillis()))
                .claim("roles", List.of("USER")).claim("ver", 1).build());
    }

    protected static String sign(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ usuários (por eventos de perfil)

    /** Um usuário de teste. {@code version} é o {@code aggregateVersion} do último evento de perfil enviado. */
    protected static final class TestUser {
        public final UUID id = UUID.randomUUID();
        public final String username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        public final String token = userToken(id);
        public final Map<UUID, UUID> relations = new HashMap<>();   // seguido -> relationId
        public long version = 0;
        public boolean isPrivate;
    }

    /** Usuário com token válido cujo perfil o serviço ainda não conhece. */
    protected TestUser unknownUser() {
        return new TestUser();
    }

    protected TestUser newUser() {
        return newUser(false);
    }

    protected TestUser newUser(boolean isPrivate) {
        TestUser user = new TestUser();
        user.isPrivate = isPrivate;
        user.version = 1;
        deliverProfile("profile.created.v1", user);
        return user;
    }

    protected void setPrivate(TestUser user, boolean isPrivate) {
        user.isPrivate = isPrivate;
        user.version++;
        deliverProfile("profile.updated.v1", user);
        deliverProfile("profile.privacy-changed.v1", user);
    }

    protected void deleteProfile(TestUser user) {
        user.version++;
        deliverProfile("profile.deleted.v1", user);
    }

    protected void deliverProfile(String type, TestUser user) {
        events.handleProfile(profileEventJson(UUID.randomUUID(), type, user));
    }

    protected static String profileEventJson(UUID messageId, String type, TestUser user) {
        return """
                {"id":"%s","type":"%s","occurredAt":"2026-01-01T00:00:00Z","aggregateId":"%s","aggregateVersion":%d,
                 "data":{"accountId":"%s","username":"%s","displayName":"Pessoa %s","bio":null,
                         "avatarKey":null,"isPrivate":%s,"createdAt":"2026-01-01T00:00:00Z"}}
                """.formatted(messageId, type, user.id, user.version, user.id, user.username, user.username, user.isPrivate);
    }

    // ------------------------------------------------------------------ eventos do grafo

    /** Cria um vínculo com a origem dada (DIRECT, REQUEST_ACCEPTED ou AUTO_ON_PUBLIC). */
    protected UUID followCreated(TestUser follower, TestUser followee, String origin) {
        UUID relationId = UUID.randomUUID();
        follower.relations.put(followee.id, relationId);
        events.handleFollow(followJson(UUID.randomUUID(), "follow.created.v1", relationId,
                "\"relationId\":\"%s\",\"followerId\":\"%s\",\"followeeId\":\"%s\",\"origin\":\"%s\",\"createdAt\":\"%s\""
                        .formatted(relationId, follower.id, followee.id, origin, Instant.now())));
        return relationId;
    }

    protected UUID follow(TestUser follower, TestUser followee) {
        return followCreated(follower, followee, "DIRECT");
    }

    protected void unfollow(TestUser follower, TestUser followee) {
        UUID relationId = follower.relations.remove(followee.id);
        events.handleFollow(followJson(UUID.randomUUID(), "follow.removed.v1", relationId,
                "\"relationId\":\"%s\",\"followerId\":\"%s\",\"followeeId\":\"%s\",\"reason\":\"UNFOLLOW\""
                        .formatted(relationId, follower.id, followee.id)));
    }

    protected UUID followRequested(TestUser requester, TestUser target) {
        return followRequestEvent("follow.requested.v1", requester, target);
    }

    protected UUID followRequestEvent(String type, TestUser requester, TestUser target) {
        UUID requestId = UUID.randomUUID();
        events.handleFollow(followJson(UUID.randomUUID(), type, requestId,
                "\"requestId\":\"%s\",\"requesterId\":\"%s\",\"targetId\":\"%s\"".formatted(requestId, requester.id, target.id)));
        return requestId;
    }

    private static String followJson(UUID messageId, String type, UUID aggregateId, String data) {
        return """
                {"id":"%s","type":"%s","occurredAt":"2026-01-01T00:00:00Z","aggregateId":"%s","aggregateVersion":1,
                 "data":{%s}}
                """.formatted(messageId, type, aggregateId, data);
    }

    // ------------------------------------------------------------------ eventos de postagens

    protected void liked(TestUser liker, TestUser postAuthor, UUID postId) {
        deliverPost("post.liked.v1", postId, "\"postId\":\"%s\",\"postAuthorId\":\"%s\",\"userId\":\"%s\""
                .formatted(postId, postAuthor.id, liker.id));
    }

    protected void unliked(TestUser liker, TestUser postAuthor, UUID postId) {
        deliverPost("post.unliked.v1", postId, "\"postId\":\"%s\",\"postAuthorId\":\"%s\",\"userId\":\"%s\""
                .formatted(postId, postAuthor.id, liker.id));
    }

    /** {@code replier} responde à postagem {@code parentId} de {@code parentAuthor}. Devolve o id da resposta. */
    protected UUID replied(TestUser replier, TestUser parentAuthor, UUID parentId, String excerpt) {
        UUID replyId = UUID.randomUUID();
        deliverPost("post.created.v1", replyId,
                "\"postId\":\"%s\",\"authorId\":\"%s\",\"parentId\":\"%s\",\"parentAuthorId\":\"%s\",\"mediaKind\":null,\"excerpt\":\"%s\",\"createdAt\":\"%s\""
                        .formatted(replyId, replier.id, parentId, parentAuthor.id, excerpt, Instant.now()));
        return replyId;
    }

    protected void rootPostCreated(TestUser author, UUID postId) {
        deliverPost("post.created.v1", postId,
                "\"postId\":\"%s\",\"authorId\":\"%s\",\"parentId\":null,\"parentAuthorId\":null,\"mediaKind\":null,\"excerpt\":\"oi\",\"createdAt\":\"%s\""
                        .formatted(postId, author.id, Instant.now()));
    }

    protected void postDeleted(TestUser author, UUID postId) {
        deliverPost("post.deleted.v1", postId, "\"postId\":\"%s\",\"authorId\":\"%s\"".formatted(postId, author.id));
    }

    private void deliverPost(String type, UUID aggregateId, String data) {
        events.handlePost("""
                {"id":"%s","type":"%s","occurredAt":"2026-01-01T00:00:00Z","aggregateId":"%s","aggregateVersion":1,
                 "data":{%s}}
                """.formatted(UUID.randomUUID(), type, aggregateId, data));
    }

    // ------------------------------------------------------------------ leitura

    protected Http.Response list(TestUser viewer) {
        return http().get("/api/notificacoes", viewer.token);
    }

    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> items(Http.Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return (List<Map<String, Object>>) response.json().get("itens");
    }

    /** As notificações do usuário, da mais recente para a mais antiga. */
    protected List<Map<String, Object>> notifications(TestUser viewer) {
        return items(list(viewer));
    }

    protected int unseen(TestUser viewer) {
        Http.Response response = http().get("/api/notificacoes/nao-vistas", viewer.token);
        assertThat(response.status()).isEqualTo(200);
        return ((Number) response.json().get("total")).intValue();
    }

    protected Http.Response markSeen(TestUser viewer, long upTo) {
        return http().send("POST", "/api/notificacoes/vistas", "{\"ate\":" + upTo + "}", viewer.token);
    }

    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> actorsOf(Map<String, Object> notification) {
        return (List<Map<String, Object>>) notification.get("atores");
    }

    protected static long seqOf(Map<String, Object> notification) {
        return ((Number) notification.get("seq")).longValue();
    }

    protected long count(String sql, Object id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }

    protected SseClient stream(TestUser user, String lastEventId) {
        return new SseClient(http().baseUrl(), "/api/notificacoes/stream", user.token, lastEventId);
    }
}
