# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build
# Contexto do build = RAIZ do repositório (docker-compose.yml), para incluir a biblioteca compartilhada de eventos.
WORKDIR /build
COPY libs/socialflux-events/pom.xml libs/socialflux-events/pom.xml
COPY libs/socialflux-events/src libs/socialflux-events/src
RUN mvn -q -B -f libs/socialflux-events/pom.xml -DskipTests install
COPY notification_service/pom.xml notification_service/pom.xml
RUN mvn -q -B -f notification_service/pom.xml dependency:go-offline
COPY notification_service/src notification_service/src
RUN mvn -q -B -f notification_service/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runner
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/notification_service/target/notification-service-*.jar app.jar
USER app

EXPOSE 8085
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8085/actuator/health >/dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
