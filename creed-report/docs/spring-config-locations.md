# Spring Boot 配置加载管道：location / additional-location / import

`spring.config.location`、`spring.config.additional-location`、`spring.config.import`
属于同一条配置加载管道（Spring Boot 2.4+ 的 Config Data 机制），角色不同、层级递进。
creed-report 的 Environment Inspector 回放的就是这条管道，这三个属性正是它的三个输入。

关联阅读：[Spring Profiles & Config Import 最佳实践](spring-profiles-config-import.md)

## spring.config.import 解决什么问题

一句话：**"配置文件想引入别的配置来源，但以前没有一个统一、声明式的入口"**。
它是 Spring Boot 2.4 重写配置加载机制（Config Data API）时引入的，针对四个痛点：

1. **配置拆分/复用只能靠外部手段**。2.4 之前想加载额外配置只能改启动参数
   （`--spring.config.additional-location=...`）或环境变量，配置文件自己没法声明
   "我还需要那个文件"。有了 `import`，应用自包含，不依赖启动脚本传参。
2. **加载顺序不可预测**。老机制里 profile 文件、外部文件、additional-location 之间
   的优先级规则很绕。新规则很简单：被 import 的内容插在声明它的文档"上面"
   （优先级更高），多个 import 靠后的赢。
3. **干掉 bootstrap.yml**。以前接 Config Server / Vault 这类"启动前就要拿到的远程配置"
   必须搞独立的 bootstrap 上下文。现在统一成
   `spring.config.import: optional:configserver:https://...`，远程源和本地文件走同一条管道。
4. **支持新的配置形态**。`configtree:` 导入 k8s secret 挂载目录；第三方可通过
   `ConfigDataLocationResolver` / `ConfigDataLoader` SPI 注册自己的前缀
   （`vault:`、`aws-parameterstore:` 等）。

可以把它理解成配置界的 `import` 语句：以前"加载哪些配置"是框架和启动参数说了算，
现在配置文件自己就能声明依赖。

## 三者的分工

| 属性 | 作用 | 能写在哪 |
|------|------|---------|
| `spring.config.location` | **替换**默认搜索位置（`classpath:/`、`classpath:/config/`、`file:./`、`file:./config/`、`file:./config/*/` 整套作废） | 只能命令行 / 环境变量 / 系统属性 |
| `spring.config.additional-location` | 在默认（或 location 指定的）位置**之外追加**，不替换 | 只能命令行 / 环境变量 / 系统属性 |
| `spring.config.import` | 由**配置文件自己声明**再拉入其他来源，可递归、可导远程 | 配置文件内部，也可命令行 |

前两个必须在配置加载**开始之前**就确定（它们定义"去哪找"），写在 application.yml
里无效；`import` 是加载**过程中**发现的，所以可以写在文件里。

## 优先级（从低到高，后者覆盖前者）

```
1. 默认位置 / spring.config.location 指定的位置
      （多个位置之间：越靠后的越优先，file:./config/ > classpath:/）
2. spring.config.additional-location 的位置
      （追加在上面所有位置"之后"，所以能覆盖它们）
3. spring.config.import 导入的内容
      （被导入的覆盖声明导入的那个文件；同一处多个 import，靠后的赢）
```

横切规则：**profile 专属文件（`application-{profile}.yml`）作为一组整体压在所有
非 profile 文件之上**——即使 profile 文件来自 classpath、非 profile 文件来自
`file:./config/`，profile 的仍然赢。

容易踩的点：

- **`location` 是替换语义**：一旦设置，classpath 里的 application.yml 都不再加载，
  除非手动把默认位置列回去。想"多加一个目录"用 `additional-location`。
- **import 的优先级是相对声明者的**：它插在声明它的文档"正上方"，不是全局最高。
  如果 `file:./config/application.yml`（更高优先级位置）里也定义了同一个 key，
  classpath application.yml 里 import 进来的值依然会被它盖掉。
- **import 去重**：同一个位置无论被声明多少次，只导入一次。

一句话总结：`location` 决定地图，`additional-location` 往地图上加点，`import` 是
地图上的文件自己再往外引线；覆盖顺序 = import 的内容 > 声明它的文件 >
additional-location > location/默认位置，profile 文件永远压同层非 profile 文件一头。

## import 目录：可以，但必须以 `/` 结尾

```yaml
spring:
  config:
    import: file:/path/to/configs/creed-resource-catalog/   # 结尾斜杠必须有
```

没有结尾斜杠时 Spring Boot 把它当"文件"，又因为没有扩展名，启动报错：
`File extension is not known to any PropertySourceLoader...
If the location is meant to reference a directory, it must end in '/'`。

目录导入的规则：

1. **只加载名字匹配 `spring.config.name`（默认 `application`）的文件**，即目录下的
   `application.properties/yml/yaml`，以及激活 profile 对应的 `application-{profile}.yml`。
2. 任意命名的文件（如 `my-profile-catalog.yml`）**不会被目录导入加载**，
   要么按单文件导入，要么改名成 `application-{profile}.yml` 配合 profile 激活。

```yaml
spring:
  config:
    import:
      - "file:.../creed-resource-catalog/"                        # 目录：按 application* 规则加载
      - "file:.../creed-resource-catalog/my-profile-catalog.yml"  # 单文件：任意名字都行
```

两个相关写法：

- `optional:file:.../dir/` —— 目录不存在时不报错。
- `configtree:/etc/config/` —— 另一种"目录导入"，语义完全不同：每个**文件名是 key、
  文件内容是 value**（k8s 挂载 secret 的风格），不解析 yml 内容。
