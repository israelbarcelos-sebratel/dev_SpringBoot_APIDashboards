# Dockerfile parametrizado — serve matrix-api ou native-api a partir do mesmo build.
# Escolha o módulo e a porta via build args (ver docker-compose.yml):
#   docker build --build-arg MODULE=matrix-api --build-arg PORT=8091 -t api:local .

# ---------- Build stage ----------
FROM maven:3.9.6-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY common ./common
COPY matrix-api ./matrix-api
COPY native-api ./native-api

# Qual módulo empacotar (matrix-api | native-api). O módulo common entra via -am.
ARG MODULE=matrix-api
RUN mvn -q -DskipTests -pl ${MODULE} -am package \
    && cp ${MODULE}/target/*.jar /app/app.jar

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/app.jar app.jar

# Porta não ortodoxa por padrão (8091). Spring lê SERVER_PORT (ver application.yml).
ARG PORT=8091
ENV SERVER_PORT=${PORT}
EXPOSE ${PORT}

ENTRYPOINT ["java", "-jar", "app.jar"]
