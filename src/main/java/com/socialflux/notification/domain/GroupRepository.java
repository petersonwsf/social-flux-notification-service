package com.socialflux.notification.domain;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL dos grupos de notificação ({@code notification_group}) e dos atores ({@code notification_actor}). Toda escrita num
 * grupo passa por {@link #upsertGroup} (que trava a linha) ou por um {@code update}/{@code delete} direto nela, então dois
 * eventos simultâneos para o mesmo grupo nunca perdem uma contagem.
 */
@Repository
public class GroupRepository {

    private static final String SEQ = "nextval('notification_activity_seq')";
    private static final String COLUMNS =
            "id, recipient_id, type, post_id, actor_count, last_activity, activity_seq, (seen_at is not null) as seen";
    private static final RowMapper<GroupRow> GROUP = (rs, n) -> new GroupRow(
            rs.getObject("id", UUID.class),
            rs.getObject("recipient_id", UUID.class),
            rs.getString("type"),
            rs.getObject("post_id", UUID.class),
            rs.getInt("actor_count"),
            rs.getObject("last_activity", OffsetDateTime.class),
            rs.getLong("activity_seq"),
            rs.getBoolean("seen"));
    private static final RowMapper<ActorRow> ACTOR = (rs, n) -> new ActorRow(
            rs.getObject("group_id", UUID.class),
            rs.getObject("actor_id", UUID.class),
            rs.getObject("reply_id", UUID.class),
            rs.getString("excerpt"),
            rs.getString("username"),
            rs.getString("display_name"),
            rs.getString("avatar_key"));

    private final JdbcClient jdbc;

    public GroupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ escrita

    /** Cria o grupo (vazio) ou devolve o existente, travando a linha até o fim da transação. */
    public UUID upsertGroup(UUID newId, UUID recipient, String type, String key, UUID postId, OffsetDateTime at) {
        return jdbc.sql("insert into notification_group (id, recipient_id, type, group_key, post_id, actor_count, "
                        + "last_activity, activity_seq, seen_at, created_at) values (:id, :recipient, :type, :key, :post, 0, "
                        + ":at, " + SEQ + ", null, :at) on conflict (recipient_id, group_key) do update "
                        + "set group_key = notification_group.group_key returning id")
                .param("id", newId).param("recipient", recipient).param("type", type).param("key", key)
                .param("post", postId).param("at", at)
                .query(UUID.class).single();
    }

    /** Põe o ator no grupo. {@code false} se ele já estava (D5). */
    public boolean addActor(UUID groupId, UUID actorId, UUID replyId, String excerpt, OffsetDateTime at) {
        return jdbc.sql("insert into notification_actor (group_id, actor_id, reply_id, excerpt, acted_at) "
                        + "values (:group, :actor, :reply, :excerpt, :at) on conflict (group_id, actor_id) do nothing")
                .param("group", groupId).param("actor", actorId).param("reply", replyId).param("excerpt", excerpt)
                .param("at", at).update() > 0;
    }

    /** Entrou um ator NOVO: o grupo sobe ao topo e volta a "não visto". */
    public void bumpAfterAdd(UUID groupId, OffsetDateTime at) {
        jdbc.sql("update notification_group set actor_count = actor_count + 1, last_activity = :at, activity_seq = " + SEQ
                        + ", seen_at = null where id = :id")
                .param("id", groupId).param("at", at).update();
    }

    public boolean removeActor(UUID groupId, UUID actorId) {
        return jdbc.sql("delete from notification_actor where group_id = :group and actor_id = :actor")
                .param("group", groupId).param("actor", actorId).update() > 0;
    }

    /** Tira o ator do grupo (pela chave) do destinatário. Devolve os grupos afetados (0 ou 1). */
    public List<UUID> removeActorFromKey(UUID recipient, String key, UUID actorId) {
        return jdbc.sql("delete from notification_actor where actor_id = :actor and group_id in "
                        + "(select id from notification_group where recipient_id = :recipient and group_key = :key) returning group_id")
                .param("actor", actorId).param("recipient", recipient).param("key", key)
                .query(UUID.class).list();
    }

    /** Tira o ator de todos os grupos do tipo do destinatário (ex.: FOLLOW, que tem um grupo por dia). */
    public List<UUID> removeActorFromType(UUID recipient, String type, UUID actorId) {
        return jdbc.sql("delete from notification_actor where actor_id = :actor and group_id in "
                        + "(select id from notification_group where recipient_id = :recipient and type = :type) returning group_id")
                .param("actor", actorId).param("recipient", recipient).param("type", type)
                .query(UUID.class).list();
    }

    /** Tira o ator de todos os grupos (conta excluída). */
    public List<UUID> removeActorEverywhere(UUID actorId) {
        return jdbc.sql("delete from notification_actor where actor_id = :actor returning group_id")
                .param("actor", actorId).query(UUID.class).list();
    }

    /** Tira os atores cuja resposta é esta postagem (a resposta foi excluída). */
    public List<UUID> removeActorsByReply(UUID replyId) {
        return jdbc.sql("delete from notification_actor where reply_id = :reply returning group_id")
                .param("reply", replyId).query(UUID.class).list();
    }

    /** Recalcula o total de atores depois de uma remoção e marca a mudança. Devolve o total novo (0 = grupo vazio). */
    public Optional<Integer> recount(UUID groupId) {
        return jdbc.sql("update notification_group g set actor_count = (select count(*) from notification_actor a "
                        + "where a.group_id = g.id), activity_seq = " + SEQ + " where g.id = :id returning g.actor_count")
                .param("id", groupId).query(Integer.class).optional();
    }

    public void delete(UUID groupId) {
        jdbc.sql("delete from notification_group where id = :id").param("id", groupId).update();
    }

    /** Apaga o grupo pela chave e devolve o id (para avisar o destinatário). */
    public Optional<UUID> deleteByKey(UUID recipient, String key) {
        return jdbc.sql("delete from notification_group where recipient_id = :recipient and group_key = :key returning id")
                .param("recipient", recipient).param("key", key).query(UUID.class).optional();
    }

    public record Gone(UUID id, UUID recipientId) {
    }

    /** Apaga os grupos cujo alvo é a postagem (curtidas e respostas dela). */
    public List<Gone> deleteByPost(UUID postId) {
        return jdbc.sql("delete from notification_group where post_id = :post returning id, recipient_id")
                .param("post", postId)
                .query((rs, n) -> new Gone(rs.getObject("id", UUID.class), rs.getObject("recipient_id", UUID.class)))
                .list();
    }

    /** Conta excluída: apaga todos os grupos dela como destinatária. */
    public int deleteAllOfRecipient(UUID recipient) {
        return jdbc.sql("delete from notification_group where recipient_id = :recipient").param("recipient", recipient).update();
    }

    /** Retenção (D13): grupos sem atividade desde o limite, exceto solicitações de seguir pendentes. */
    public int deleteInactiveBefore(OffsetDateTime limit) {
        return jdbc.sql("delete from notification_group where last_activity < :limit and type <> 'FOLLOW_REQUEST'")
                .param("limit", limit).update();
    }

    public int markSeen(UUID recipient, long upTo, OffsetDateTime at) {
        return jdbc.sql("update notification_group set seen_at = :at where recipient_id = :recipient and seen_at is null "
                        + "and activity_seq <= :upTo")
                .param("recipient", recipient).param("upTo", upTo).param("at", at).update();
    }

    // ------------------------------------------------------------------ leitura

    public Optional<GroupRow> find(UUID groupId) {
        return jdbc.sql("select " + COLUMNS + " from notification_group where id = :id")
                .param("id", groupId).query(GROUP).optional();
    }

    /** Página por cursor (mais recentes primeiro). Pede {@code limit + 1} para saber se há mais. */
    public List<GroupRow> page(UUID recipient, Cursor cursor, int limit) {
        String sql = "select " + COLUMNS + " from notification_group where recipient_id = :recipient"
                + (cursor == null ? "" : " and (last_activity, id) < (:at, :id)")
                + " order by last_activity desc, id desc limit :limit";
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("recipient", recipient).param("limit", limit);
        if (cursor != null) {
            spec = spec.param("at", cursor.at()).param("id", cursor.id());
        }
        return spec.query(GROUP).list();
    }

    /** Grupos que mudaram depois de {@code seq} (reenvio ao reconectar), do mais antigo para o mais novo. */
    public List<GroupRow> since(UUID recipient, long seq, int limit) {
        return jdbc.sql("select " + COLUMNS + " from notification_group where recipient_id = :recipient "
                        + "and activity_seq > :seq order by activity_seq limit :limit")
                .param("recipient", recipient).param("seq", seq).param("limit", limit).query(GROUP).list();
    }

    /** Os 2 atores mais recentes de cada grupo (só de contas ativas), do mais recente para o mais antigo. */
    public List<ActorRow> recentActors(Collection<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select group_id, actor_id, reply_id, excerpt, username, display_name, avatar_key from (
                            select a.group_id, a.actor_id, a.reply_id, a.excerpt, s.username, s.display_name, s.avatar_key,
                                   row_number() over (partition by a.group_id order by a.acted_at desc, a.actor_id) as rn
                              from notification_actor a
                              join actor_snapshot s on s.account_id = a.actor_id and s.status = 'ACTIVE'
                             where a.group_id in (:ids)) t
                         where rn <= 2 order by group_id, rn""")
                .param("ids", groupIds).query(ACTOR).list();
    }

    /** Grupos não vistos (limitado a 100: o frontend mostra "99+"). */
    public int countUnseen(UUID recipient) {
        return jdbc.sql("select count(*) from (select 1 from notification_group where recipient_id = :recipient "
                        + "and seen_at is null limit 100) t")
                .param("recipient", recipient).query(Integer.class).single();
    }
}
