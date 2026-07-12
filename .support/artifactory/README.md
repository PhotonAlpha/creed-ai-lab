# 本地 JFrog Artifactory —— 测试 Maven SNAPSHOT 机制

用 Docker 起一个 Artifactory OSS,验证 `1.0.0-SNAPSHOT` 的发布/解析全流程:
时间戳快照、`maven-metadata.xml`、`-U` 强制刷新、release vs snapshot 仓库策略。

## 1. 启动 Artifactory

```bash
docker compose -f .support/artifactory/docker-compose.yml up -d
# 首次启动做 DB 初始化,约 1~2 分钟。盯就绪状态:
docker compose -f .support/artifactory/docker-compose.yml logs -f artifactory
```

就绪后打开 UI: <http://localhost:8082/ui/>,默认 `admin / xxx`,首登强制改密码。
> 改了密码后,记得同步到 `.support/artifactory/settings.xml` 的 `<servers>` 里。

## 2. 准备仓库

OSS 版首登会有 Onboarding 向导,选 **Maven**,它会自动建好这几个仓库:

| 仓库 | 类型 | 用途 |
|------|------|------|
| `libs-snapshot-local` | local | **SNAPSHOT 构件落地** |
| `libs-release-local`  | local | release 构件落地 |
| `libs-snapshot` / `libs-release` | virtual | 聚合 local + 远程代理,供消费方解析 |
| `maven-remote` | remote | 代理 Maven Central |

若跳过了向导,手动建:Administration → Repositories → `+ Add Repositories` → Maven,
勾选生成上面这套即可。

## 3. 接入本工程

把 `distributionManagement-snippet.xml` 的内容贴进根 `pom.xml` 的 `<project>` 下
(和 `<modules>`、`<build>` 同级)。工程版本已经是 `1.0.0-SNAPSHOT`,无需改动。

## 4. 发布 SNAPSHOT

```bash
# -s 用本目录的 settings.xml,不污染全局 ~/.m2/settings.xml
mvn -s .support/artifactory/settings.xml -DskipTests clean deploy

rm -rf ~/.m2/repository/com/yourcompany/common-lib/3.0.1-SNAPSHOT
```

成功后到 UI → Artifacts → `libs-snapshot-local` →
`com/creed/spring-creed-auth-server/1.0.0-SNAPSHOT/`,能看到带时间戳的构件:

```
spring-creed-auth-server-1.0.0-20260710.153012-1.pom
maven-metadata.xml     <-- 记录 latest / lastUpdated,SNAPSHOT 机制核心
```

[Spring Boot Artifactory](https://repo.spring.io/ui/repos/tree/General/libs-milestone)

## 5. 验证 SNAPSHOT 解析机制

再 deploy 一次(不改版本号),时间戳递增:`...-2`、`...-3`。
消费方拉取时:

```bash
# -U 强制检查远程 SNAPSHOT 更新,忽略本地 24h 缓存窗口
mvn -s .support/artifactory/settings.xml -U dependency:get \
  -Dartifact=com.creed:spring-creed-auth-server:1.0.0-SNAPSHOT
```

观察点:
- **不带 `-U`**:同一天内 Maven 用本地缓存,不重新下载(updatePolicy 默认 daily)。
- **带 `-U`**:比对 `maven-metadata.xml` 的 `lastUpdated`,拉最新时间戳构件。
- release 仓库同版本重复 deploy 默认被拒(可在仓库设置改 handle policy),
  这正是 release 与 snapshot 的关键区别。

## 6. 清理

```bash
# 仅停止,保留数据
docker compose -f .support/artifactory/docker-compose.yml down
# 连命名卷一起删,恢复空白(重新走 Onboarding)
docker compose -f .support/artifactory/docker-compose.yml down -v
```

## 排错

- **启动慢/健康检查失败**:首次初始化就是慢,`start_period` 给了 120s,耐心等日志出现
  `Artifactory ... started`。
- **`nofile` 报错**:compose 里已设 ulimit;若宿主机仍限制,Docker Desktop 一般无需额外配置。
- **deploy 401**:`settings.xml` 的账号密码和 Artifactory 里的用户对不上,或改密后没同步。
- **端口占用**:8081/8082 被占的话改 compose 的 `ports` 映射,并同步 `settings.xml` 里的 URL。
