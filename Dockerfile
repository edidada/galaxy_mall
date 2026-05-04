FROM ccr.ccs.tencentyun.com/library/ubuntu:22.04

ARG JAR_FILE
ARG APP_PORT=8080

ENV APP_PORT=${APP_PORT}
ENV JAVA_OPTS=""
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y \
    ca-certificates \
    curl \
    gnupg \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /etc/apt/keyrings \
    && curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg \
    && echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb jammy main" | tee /etc/apt/sources.list.d/adoptium.list

RUN apt-get update && apt-get install -y temurin-17-jre -y \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY ${JAR_FILE} app.jar

EXPOSE ${APP_PORT}

ENTRYPOINT ["sh","-c","exec java -XX:+UseContainerSupport -XX:MaxRAMPercentage=75 ${JAVA_OPTS:-} -jar /app/app.jar"]
