# syntax=docker/dockerfile:1

# ---------- 构建阶段 ----------
# 关键点：先 COPY pom.xml 再 COPY src。
# 依赖层不随源码变化，改一行代码不会让 mvn dependency 那一层缓存失效。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---------- 运行阶段 ----------
# 多阶段构建：最终镜像里没有 Maven 和源码，只有 jar 和 JRE。
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN useradd -r -u 1001 appuser
COPY --from=build /app/target/*.jar app.jar
USER appuser

EXPOSE 8080
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
