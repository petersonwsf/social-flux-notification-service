package com.socialflux.notification.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/** Toda a configuração do serviço, vinda de variáveis de ambiente (ver application.yml). Falha rápida se inválida. */
@Validated
@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(
        @Valid @NotNull Security security,
        @DefaultValue List<String> allowedOrigins,
        @Valid @NotNull Sse sse,
        @Valid @NotNull Maintenance maintenance) {

    public record Security(
            @NotBlank String jwksUri,
            @NotBlank String issuer,
            @NotBlank String audience,
            @DefaultValue("PT60S") Duration clockSkew) {
    }

    /**
     * Tempo real (D9–D11). {@code maxDuration}: o stream fecha sozinho depois disso (ou no vencimento do token, o que vier
     * antes) e o cliente reconecta passando de novo pelo gateway — é o que limita a exposição de uma sessão revogada.
     */
    public record Sse(
            @NotNull Duration maxDuration,
            @NotNull Duration jitter,
            @NotNull Duration heartbeat,
            @NotNull Duration sweepInterval,
            @Min(1) int maxPerUser,
            @Min(1) int maxTotal,
            @Min(1) int backlogLimit) {
    }

    /** {@code retention}: grupos sem atividade há mais que isso são apagados (D13); solicitações de seguir não expiram. */
    public record Maintenance(boolean enabled, @NotNull Duration interval, @NotNull Duration retention,
                              @NotNull Duration tombstoneRetention) {
    }
}
