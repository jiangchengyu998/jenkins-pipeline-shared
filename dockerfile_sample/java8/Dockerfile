# ————————————————————————————————
# 第一阶段：编译 (Maven + JDK 8)
# ————————————————————————————————
FROM maven:3.8.7-eclipse-temurin-8 AS builder

# 设置工作目录
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
FROM eclipse-temurin:8-jre AS runtime

# 构建参数，设置默认端口
ARG SERVER_PORT=8080

# 设置时区、工作目录和环境变量
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="" \
    SERVER_PORT=${SERVER_PORT}

WORKDIR /app

# 从构建阶段复制 jar
COPY --from=builder /build/target/*.jar app.jar

# 暴露端口
EXPOSE ${SERVER_PORT}

# 启动命令使用环境变量
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar --server.port=${SERVER_PORT}"]