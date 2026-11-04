# DefectDojo（本地）

DefectDojo 3.4.202，在 UI 上导入 Veracode 报告、查看数据流转。`creed-resource-veracode` **不**调用它。

```bash
docker compose -f .support/defectdojo/docker-compose.yml up -d     # 首次约 3–5 分钟
open http://localhost:8380/                                       # admin / defectdojo_pw
```

| | |
|---|---|
| UI | http://localhost:8380/ （宿主机 8080 是 creed-gateway） |
| API / Swagger | http://localhost:8380/api/v2/ · `/api/v2/oa3/swagger-ui/` |
| PostgreSQL | `localhost:5434`，defectdojo / defectdojo_pw，数据在 `/Users/ethan/Desktop/workspace/database/defectdojo-postgresql` |
| 容器 | `creed-defectdojo-{nginx,uwsgi,celeryworker,celerybeat,initializer,db,redis}` |

## 在 UI 上导入 Veracode XML

1. **Products → Add Product**：名称 `creed-shop`，Product Type 选一个（没有就先在 Products → Product Types 建 `Veracode`）。
2. 进入该 Product → **Engagements → Add New Interactive Engagement**：名称 `Veracode 2.4.0`。
3. Engagement 页 → **Import Scan Results**：Scan type 选 **Veracode Scan**，文件选
   `.support/veracode/veracode-detailedreport-example.xml`，Version 填 `2.4.0`。
4. 看结果：Test 里 11 条 finding —— 10 条 SCA（`Vulnerable Component: <library>:<version>`，标签 `sca`，
   guava 那条为 **Mitigated**）+ 1 条静态扫描（Generation of Error Message …）。

**数据流转**：在同一个 Test 上用 **Re-Upload Scan** 再传一次同一个文件 → 全部 *untouched*；删掉 XML 里的一个
`<vulnerability>` 再传 → 那条变 *closed*（mitigated）；加回来再传 → *reactivated*。每次导入的明细在
Test → **Import History**。

用 API 导入（不走 UI）：`.support/defectdojo/bootstrap.sh` 取 admin 的 API key 写到 `tmp/defectdojo.env`，
脚本头部有 curl 示例（`reimport-scan` + `auto_create_context=true` 会自动建 Product / Engagement / Test）。

## 注意

- 与官方 compose 的差别见 `docker-compose.yml` 头部：镜像从 Docker Hub 拉取（官方默认的 registry.defectdojo.com
  会记录拉取 IP），版本固定，复用本机 postgres:15 / redis:7，admin 密码固定。
- `DD_ADMIN_PASSWORD` 只在**首次**初始化时生效；要重置就停掉后删除上面的 PostgreSQL 数据目录再 `up -d`。
- `DD_SECRET_KEY` / `DD_CREDENTIAL_AES_256_KEY` 是官方 compose 的公开默认值，仅限本地。
- DefectDojo 的 Veracode 解析器对 XML 要求比 creed-resource-veracode 严：根节点要有 `app_id`、`last_update_time`，
  静态扫描的 `category` 要有 `recommendations/para`，缓解要有 `mitigations/mitigation/@date`。
