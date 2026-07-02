# Spring Profiles & Config Import 最佳实践

`spring.profiles.group` / `spring.profiles.active` / `spring.profiles.include` / `spring.config.import`
经常被混用，核心区别在于**加载时机**和**作用**。

关联阅读：[配置加载管道：location / additional-location / import](spring-config-locations.md)

## 一张表看懂区别

| 属性 | 作用 | 是否激活 profile | 可放位置 |
|------|------|----------------|---------|
| `spring.profiles.active` | 激活哪些 profile | ✅ 激活 | 只能在**非 profile 专属**文档 / 外部（命令行、env） |
| `spring.profiles.group` | 定义"一个 profile 展开成多个"的别名组 | ✅ 间接激活组内成员 | 只能在非 profile 专属文档 |
| `spring.profiles.include` | 无条件追加 profile（总是叠加，不能被覆盖） | ✅ 追加 | 非 profile 专属文档 |
| `spring.config.import` | 导入**额外的配置源**（文件 / configserver / vault…） | ❌ 不激活 profile | 任意文档，含 profile 专属 |

关键点：`active` / `group` **不能**写在某个 profile 专属的文档块里（如 `application-prod.yml`
或 `on-profile: prod` 段）——Spring Boot 2.4+ 会直接报错。`include` 允许但受限。

## profile 专属 vs 非 profile 专属文档

一个配置**文件**可以被切成多个**文档（document）**。判断一段文档是不是"profile 专属"，
看它有没有绑定 `on-profile` 条件。

**方式一：文件名带 profile 后缀**

```
application.yml          ← 非专属（永远加载）
application-prod.yml     ← prod 专属（只有 prod 激活时才加载）
application-dev.yml      ← dev 专属
```

**方式二：同一个文件里用 `---` 分段 + `on-profile`**

```yaml
# application.yml

# ↓↓↓ 文档 A：非 profile 专属（没有 on-profile，永远生效）
spring:
  profiles:
    active: dev          # ✅ 允许写在这里
    group:
      prod: "cloud,metrics"

---
# ↓↓↓ 文档 B：prod 专属（用 on-profile 绑定了 prod）
spring:
  config:
    activate:
      on-profile: prod   # 这段只在 prod 激活时生效
  datasource:
    url: jdbc:mysql://prod-db:3306/app
    # active: xxx        # ❌ 报错！不能在专属段里写 active
```

**为什么规则是这样**：`active` / `group` 的作用是"决定激活哪些 profile"，必须在
"还不知道有哪些 profile 被激活"的阶段就被读到，所以只能放在**无条件加载**的文档里。
把 `active: prod` 写进"只有 prod 激活才加载"的段落里会形成先有鸡还是先有蛋的死循环，
Spring Boot 2.4+ 直接禁止，启动会抛 `InactiveConfigDataAccessException`。

| | 判断标准 | 能放 `active`/`group` 吗 |
|---|---|---|
| **非 profile 专属文档** | 文件名无后缀 + 段落无 `on-profile` | ✅ 能 |
| **profile 专属文档** | 文件名带 `-xxx` 或段落有 `on-profile: xxx` | ❌ 不能（`import` 可以） |

## 各自的推荐用法

### 1. `spring.profiles.group` —— 组合式激活（推荐）

把"技术维度"的 profile 组合成"环境维度"的一个开关，是 2.4 之后替代滥用 `include` 的首选。

```yaml
spring:
  profiles:
    group:
      prod: "prod-db,prod-mq,cloud,metrics"
      local: "local-db,h2,debug-log"
```

启动时只需 `--spring.profiles.active=prod`，自动展开成 4 个 profile。
好处：环境入口单一，技术切面解耦。

### 2. `spring.profiles.active` —— 只从外部设定，不硬编码

主 `application.yml` 里不要写死 active，交给外部：

```bash
java -jar app.jar --spring.profiles.active=prod
# 或
SPRING_PROFILES_ACTIVE=prod
```

代码库里写死 active 会导致"打包产物依赖代码分支"，违反 12-factor。
真要给默认值，用 `spring.profiles.default`（只有当没有任何 active 时才生效），语义更清楚。

### 3. `spring.profiles.include` —— 谨慎使用

`include` 是**无条件、不可被外部覆盖**地追加 profile。适合"任何环境都必须叠加的基础层"
（如 `base`、`security-common`）。但因为不可覆盖、容易造成隐藏的激活链，多数场景请优先用 `group`。

### 4. `spring.config.import` —— 导入配置源（不是激活 profile）

2.4 之后取代了老的 `spring.cloud.config.uri` bootstrap 方式。常见用法：

```yaml
spring:
  config:
    import:
      - "optional:file:./config/"           # optional: 缺失不报错
      - "configserver:https://config:8443"  # 从 config server 拉
      - "optional:configtree:/etc/secrets/" # k8s secret 挂载目录
```

要点：
- 用 `optional:` 前缀避免文件缺失导致启动失败。
- **导入顺序 = 优先级**，靠后的覆盖靠前的；且 import 进来的源优先级高于当前文件自身。
- 可放在 profile 专属块里做"按环境导入不同源"。

## 组合最佳实践（一个典型骨架）

```yaml
# application.yml —— 不写 active，只定义 group + 通用导入
spring:
  profiles:
    group:
      prod: "cloud,metrics,prod-secrets"
      dev:  "h2,debug"
  config:
    import: "optional:configserver:"   # 具体 uri 交给 config-client

---
# 环境专属文件用独立 yml，而不是塞进主文件
# application-prod.yml
spring:
  config:
    import: "configserver:https://config-server:8443/config-server"
```

## 核心原则

1. `active` 永远从外部注入，代码里最多给 `default`。
2. 用 `group` 做环境编排，尽量少用 `include`。
3. `config.import` 只管"从哪读"，不管"激活谁"——两件事别混。
4. 外部源 / import 的优先级永远高于打进 jar 的默认值。
