# bin/*.sh 的本机设置样例。拷成 bin/env.local.sh 再改（env.local.sh 不进 git）：
#   cp bin/env.example.sh bin/env.local.sh
# 只写和默认值不一样的；默认值见 bin/_lib.sh 开头。

# macOS 必填：包里自带的是 Linux JDK，用本机 JDK 21+（Linux 上不要设，用自带的 jdk/）
# OPENSEARCH_JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home

# 解压目录不在本目录下时
# OS_HOME=/opt/logstack/opensearch-3.10.0
# OSD_HOME=/opt/logstack/opensearch-dashboards-3.10.0

# OpenSearch / Dashboards 的 pid 和控制台日志放哪（默认 /data/opensearch，没有就用本目录下的 run/）
# RUN_DIR=/data/opensearch

# fluent-bit 可执行文件（默认按 /opt/fluent-bit/bin → /usr/local/bin → fluent-bit-pkg/ 里解开的 顺序找）
# FB=/usr/local/bin/fluent-bit

# aggregator.yaml / forwarder.yaml 所在目录（Linux 业务服务器上是 /opt/logstack-forwarder）
# FB_DIR=/opt/logstack-forwarder

# forwarder 写进每条日志的 host 字段（默认 hostname -s）
# FORWARDER_HOSTNAME=ethan-mac
