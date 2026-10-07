FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /build

# 走阿里云 Maven 仓库，否则国内构建会非常慢
COPY settings.xml /root/.m2/settings.xml

COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline -DskipTests || true

COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
LABEL service="api-gateway"
WORKDIR /app
COPY --from=builder /build/target/api-gateway*.jar app.jar

# MaxRAMPercentage 让 JVM 感知容器内存上限，避免被 cgroup 直接杀掉
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080
HEALTHCHECK --interval=20s --timeout=5s --start-period=90s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health >/dev/null 2>&1 || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
