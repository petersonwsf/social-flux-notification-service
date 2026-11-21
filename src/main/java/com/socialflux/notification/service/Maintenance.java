package com.socialflux.notification.service;

import com.socialflux.notification.config.NotificationProperties;
import com.socialflux.notification.domain.FollowEdgeRepository;
import com.socialflux.notification.domain.GroupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.IntSupplier;

/**
 * Rotinas agendadas: retenção das notificações (D13: 45 dias sem atividade; as solicitações de seguir pendentes não
 * expiram por idade) e poda dos túmulos de vínculos removidos.
 */
@Component
public class Maintenance {

    private static final Logger log = LoggerFactory.getLogger(Maintenance.class);

    private final GroupRepository groups;
    private final FollowEdgeRepository edges;
    private final NotificationProperties.Maintenance properties;
    private final Clock clock;

    public Maintenance(GroupRepository groups, FollowEdgeRepository edges, NotificationProperties properties, Clock clock) {
        this.groups = groups;
        this.edges = edges;
        this.properties = properties.maintenance();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${notification.maintenance.interval:PT1H}", initialDelayString = "PT10M")
    public void runScheduled() {
        if (!properties.enabled()) {
            return;
        }
        run("retenção das notificações", this::expireOld);
        run("poda de túmulos", this::pruneTombstones);
    }

    public int expireOld() {
        return groups.deleteInactiveBefore(before(properties.retention()));
    }

    public int pruneTombstones() {
        return edges.pruneRemovedBefore(before(properties.tombstoneRetention()));
    }

    private OffsetDateTime before(Duration age) {
        return OffsetDateTime.ofInstant(clock.instant().minus(age), ZoneOffset.UTC);
    }

    private static void run(String name, IntSupplier task) {
        try {
            int removed = task.getAsInt();
            if (removed > 0) {
                log.info("Rotina '{}' removeu {} registro(s)", name, removed);
            }
        } catch (RuntimeException e) {
            log.warn("Rotina de manutenção '{}' falhou (tentará de novo na próxima rodada): {}", name, e.toString());
        }
    }
}
