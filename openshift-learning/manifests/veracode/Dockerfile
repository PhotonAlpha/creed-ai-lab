FROM registry.access.redhat.com/ubi9/openjdk-21-runtime

# 只拷贝 jar。构建上下文是一个只装着 jar 和本文件的临时目录，见第 3 步
COPY app.jar /deployments/app.jar

# 随机 UID 运行、GID 0：/tmp 一定可写；日志目录交给环境变量 LOGGING_FILE_PATH
ENV LOGGING_FILE_PATH=/tmp/logs \
    OTEL_SDK_DISABLED=true \
    JAVA_OPTS_APPEND="-XX:MaxRAMPercentage=75"

EXPOSE 18097
# 用基础镜像自带的启动脚本（run-java.sh）：它会读取 JAVA_OPTS_APPEND，并启动 /deployments 下的 jar