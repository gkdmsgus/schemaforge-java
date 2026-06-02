# ── Stage 1: Frontend build ────────────────────────────────────────
FROM node:22-slim AS frontend-builder

WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci
COPY frontend/ ./frontend/
COPY public/   ./public/
COPY index.html tsconfig.json vite.config.ts postcss.config.js eslint.config.js ./
RUN npm run build
# Output: src/main/resources/static/

# ── Stage 2: Java build ────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-jammy AS java-builder

WORKDIR /app

# Gradle wrapper
COPY gradlew gradlew.bat ./
COPY gradle/ ./gradle/
RUN chmod +x gradlew

# Dependencies (cached layer)
COPY build.gradle settings.gradle ./
RUN ./gradlew dependencies --no-daemon 2>/dev/null || true

# Source + built frontend static files
COPY src/ ./src/
COPY --from=frontend-builder /app/src/main/resources/static/ ./src/main/resources/static/
RUN ./gradlew bootJar --no-daemon -x test

# ── Stage 3: Runtime ───────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN apt-get update && apt-get install -y python3 python3-pip python3-venv && \
    ln -sf python3 /usr/bin/python && \
    rm -rf /var/lib/apt/lists/*

COPY requirements.txt ./
RUN python3 -m venv /venv && /venv/bin/pip install --no-cache-dir -r requirements.txt
ENV PATH="/venv/bin:$PATH"

RUN mkdir -p outputs

COPY --from=java-builder /app/build/libs/*.jar app.jar

EXPOSE 8080
# 512MB 무료 플랜 메모리 제한에 맞게 JVM 힙 제한
CMD ["java", "-Xmx300m", "-Xss256k", "-jar", "app.jar"]
