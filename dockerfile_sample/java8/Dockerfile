# ————————————————————————————————
# 第一阶段：编译 (Maven + JDK 8)
# ————————————————————————————————
FROM maven:3.8.5-openjdk-8 AS builder

WORKDIR /build

# 先复制 pom.xml 下载依赖，加快构建
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 再复制源码
COPY src ./src

# 构建 jar 包（跳过测试可提高速度）
RUN mvn clean package -DskipTests

# ————————————————————————————————
# 第二阶段：运行 (JRE 8)
# ————————————————————————————————
FROM openjdk:8-jre-slim

# 设置时区、工作目录等
ENV TZ=Asia/Shanghai \
    JAVA_OPTS=""

WORKDIR /app

# 从构建阶段复制打好的 jar
COPY --from=builder /build/target/*.jar app.jar

# Spring Boot 默认端口 8080
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]