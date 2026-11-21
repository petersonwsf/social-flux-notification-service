package com.socialflux.notification.domain;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** Acesso à cópia local dos perfis ({@code actor_snapshot}) e das arestas de seguir ({@code follow_edge}, só para o D7). */
@Repository
public class ActorRepository {

    private static final String COLUMNS = "account_id, username, display_name, avatar_key, is_private, status, profile_version";
    private static final RowMapper<ActorSnapshot> MAPPER = (rs, n) -> new ActorSnapshot(
            rs.getObject("account_id", UUID.class),
            rs.getString("username"),
            rs.getString("display_name"),
            rs.getString("avatar_key"),
            rs.getBoolean("is_private"),
            rs.getString("status"),
            rs.getLong("profile_version"));

    private final JdbcClient jdbc;

    public ActorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ActorSnapshot> find(UUID accountId) {
        return jdbc.sql("select " + COLUMNS + " from actor_snapshot where account_id = :id")
                .param("id", accountId).query(MAPPER).optional();
    }

    /** Lê travando a linha para ESCRITA (aplicação de eventos de perfil). */
    public Optional<ActorSnapshot> findForUpdate(UUID accountId) {
        return jdbc.sql("select " + COLUMNS + " from actor_snapshot where account_id = :id for update")
                .param("id", accountId).query(MAPPER).optional();
    }

    /** O seguidor segue o seguido? (aresta aceita na cópia local do grafo) */
    public boolean follows(UUID follower, UUID followee) {
        return jdbc.sql("select exists (select 1 from follow_edge where follower_id = :f and followee_id = :t)")
                .param("f", follower).param("t", followee).query(Boolean.class).single();
    }

    public void insert(ActorSnapshot s, OffsetDateTime now) {
        jdbc.sql("insert into actor_snapshot (account_id, username, display_name, avatar_key, is_private, status, "
                        + "profile_version, updated_at) values (:id, :username, :display, :avatar, :private, :status, "
                        + ":version, :now)")
                .param("id", s.accountId()).param("username", s.username()).param("display", s.displayName())
                .param("avatar", s.avatarKey()).param("private", s.isPrivate()).param("status", s.status())
                .param("version", s.profileVersion()).param("now", now)
                .update();
    }

    public void update(ActorSnapshot s, OffsetDateTime now) {
        jdbc.sql("update actor_snapshot set username = :username, display_name = :display, avatar_key = :avatar, "
                        + "is_private = :private, status = :status, profile_version = :version, updated_at = :now "
                        + "where account_id = :id")
                .param("id", s.accountId()).param("username", s.username()).param("display", s.displayName())
                .param("avatar", s.avatarKey()).param("private", s.isPrivate()).param("status", s.status())
                .param("version", s.profileVersion()).param("now", now)
                .update();
    }
}
