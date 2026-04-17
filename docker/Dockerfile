# Capsule Code Backend — 对外分发用 Docker image
# 构建前确保 backend/target/capsule-code-backend-*.jar 已存在（mvn package -DskipTests）
FROM eclipse-temurin:17-jre

# 系统依赖：tmux(Claude CLI 跑在 tmux 里) + libheif(HEIC→JPEG fallback) + nodejs(装 Claude CLI)
RUN apt-get update && apt-get install -y --no-install-recommends \
        tmux \
        curl \
        ca-certificates \
        libheif-examples \
        gnupg \
    && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# Claude Code CLI
RUN npm install -g @anthropic-ai/claude-code

WORKDIR /app

# 运行时路径（可被 env 覆盖）
ENV CLAUDE_WORK_DIR=/workspace \
    CAPSULE_CODE_UPLOAD_DIR=/app/uploads \
    CAPSULE_CODE_DB_DIR=/app/data \
    CAPSULE_CODE_REMOTE_LOG_DIR=/app/logs

RUN mkdir -p /workspace /app/uploads /app/data /app/logs

# 构建产物（在宿主机先 mvn package）
COPY backend/target/capsule-code-backend-*.jar /app/app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
