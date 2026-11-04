# Veracode 示例报告

`veracode-detailedreport-example.xml` —— 手工编写的 Veracode 详细报告（export schema 1.0，report_format_version 1.5），
不是真实扫描。应用 `creed-shop`，版本 `2.4.0`。

## 测试 Jira 创建（creed-resource-veracode，mock Jira）

```bash
mvn -pl creed-resource/creed-resource-veracode spring-boot:run -Dspring-boot.run.workingDirectory="$PWD"   # :18097
cd creed-env-matrix-design && npm run dev                                                                    # :5173/veracode
```

1. 「Jira 令牌」里随便填一个令牌（mock 接受任意令牌；`invalid` 会被拒），「测试连接」。
2. 把 XML 拖进上传区：8 条漏洞，全部「缺失」（mock Jira 有 2.4.0 版本，但该版本下没有任何 issue）。
3. 「创建全部缺失」→ 8 个 Jira（CREED-105 起），标题如 `creed-shop h2 2.1.210 CVE-2022-23221`；
   「重新检查 Jira」→ 8 条全部「已有 Jira」。mock Jira 重启后清空。

不用页面也可以：

```bash
curl -s -F file=@.support/veracode/veracode-detailedreport-example.xml localhost:18097/api/veracode/analysis/upload
```

| 组件 | 漏洞 | 用来测 |
|---|---|---|
| tomcat-embed-core 10.1.30 / h2 2.1.210 | CVE-2024-50379 / CVE-2022-23221 | Very High |
| commons-compress 1.24.0 | CVE-2024-25710 + CVE-2024-26308 | 一个组件两个 CVE → 两条 |
| jackson-databind 2.13.3 / 2.13.4 | CVE-2022-42003 | 一个 CVE 两个版本 → 一条，标题含两个组件 |
| okio-jvm 3.4.0 | SRCCLR-SID-38021 | 没有 CVE：以公告编号作标题和匹配 |
| guava 31.1-jre | CVE-2023-2976（mitigated） | 跳过，页面提示「1 mitigated vulnerability skipped」 |
| 静态扫描 OrderErrorHandler.java | CWE-209 | 本服务忽略（只读 SCA） |

想测「Jira 无此版本」：上传时「影响版本」填 `2.5.0`。同一份文件也能导入本地 DefectDojo，见 `../defectdojo/README.md`。
