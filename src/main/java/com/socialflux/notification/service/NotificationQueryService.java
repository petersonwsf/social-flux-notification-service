package com.socialflux.notification.service;

import com.socialflux.notification.domain.ActorRow;
import com.socialflux.notification.domain.Cursor;
import com.socialflux.notification.domain.GroupRepository;
import com.socialflux.notification.domain.GroupRow;
import com.socialflux.notification.domain.GroupView;
import com.socialflux.notification.web.ApiException;
import com.socialflux.notification.web.Dtos;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Leitura das notificações do usuário (lista paginada por cursor, grupo avulso, contagem). */
@Service
public class NotificationQueryService {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    private final GroupRepository groups;

    public NotificationQueryService(GroupRepository groups) {
        this.groups = groups;
    }

    public Dtos.PageView page(UUID user, String cursor, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw ApiException.badRequest("parametro_invalido", "O limite deve estar entre 1 e " + MAX_LIMIT);
        }
        List<GroupRow> rows = groups.page(user, Cursor.decode(cursor), size + 1);
        boolean hasMore = rows.size() > size;
        List<GroupRow> pageRows = hasMore ? rows.subList(0, size) : rows;
        String next = hasMore ? new Cursor(pageRows.get(size - 1).lastActivity(), pageRows.get(size - 1).id()).encode() : null;
        return new Dtos.PageView(views(pageRows).stream().map(Dtos.Notificacao::of).toList(), next);
    }

    /** Número de grupos não vistos (limitado a 100). */
    public int unseen(UUID user) {
        return groups.countUnseen(user);
    }

    public Optional<GroupView> view(UUID groupId) {
        return groups.find(groupId).flatMap(row -> views(List.of(row)).stream().findFirst());
    }

    /** Monta a exibição de cada grupo; grupos sem nenhum ator visível (perfil ainda não chegou ou conta excluída) são omitidos. */
    public List<GroupView> views(List<GroupRow> rows) {
        Map<UUID, List<ActorRow>> actors = new LinkedHashMap<>();
        groups.recentActors(rows.stream().map(GroupRow::id).toList())
                .forEach(actor -> actors.computeIfAbsent(actor.groupId(), key -> new ArrayList<>()).add(actor));
        List<GroupView> views = new ArrayList<>();
        for (GroupRow row : rows) {
            List<ActorRow> recent = actors.get(row.id());
            if (recent != null && !recent.isEmpty()) {
                views.add(new GroupView(row, recent));
            }
        }
        return views;
    }
}
