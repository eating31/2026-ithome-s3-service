# === Stage 1: 編譯階段 (Build Stage) ===
# 使用帶有 Maven 3.9 與 Temurin JDK 21 的 Alpine 輕量基礎鏡像進行編譯
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# 技巧 1：先複製 pom.xml 並預先下載依賴 (Layer Caching)
# 只要 pom.xml 沒變，Docker 會直接重用此層快取，大幅加快第二次以後的 Build 速度！
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 複製專案原始碼並進行編譯（跳過單元測試以加快打包）
COPY src ./src
RUN mvn clean package -DskipTests

# === Stage 2: 執行階段 (Runtime Stage) ===
# 技巧 2：丟棄 Maven 與 JDK！僅使用極輕量 JRE 21 Alpine 鏡像（約 200MB）
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# 建立非 Root 安全使用者（資安最佳實踐，防止容器逃逸）
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# 技巧 3：COPY 時直接指定 --chown，避免額外的 chown layer 把 jar 複製兩次
# 錯誤做法：先 COPY 再 RUN chown → jar 在 Docker layer 裡被存了兩份，白白多 60MB！
COPY --from=builder --chown=appuser:appgroup /build/target/*.jar app.jar

USER appuser

# 暴露 Spring Boot 的預設通訊埠
EXPOSE 8080

# 優化 JVM 記憶體參數以適應容器環境：
# -XX:+UseG1GC：使用 G1 垃圾回收器，適合容器短暫 heap 分配
# -XX:MaxRAMPercentage=75.0：允許 JVM 使用容器記憶體上限的 75%，避免 OOM kill
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
