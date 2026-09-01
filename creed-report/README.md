# 显示 environment 所有active的值
http://localhost:48080/report/api/environment
http://localhost:48080/report/environment

# 显示 environment 转为 properties 分格 和 yaml分格
http://localhost:48080/report/api/environment/rendered
http://localhost:48080/report/environment/rendered

# Environment Inspector — 需求与设计
完整的需求清单、设计决策与扩展指引见 skill：`.claude/skills/env-inspector/SKILL.md`
核心实现：`creed-report/src/main/java/com/creed/report/service/EnvironmentInspectionService.java`


# 显示导出报表
http://localhost:48080/report/report

# 多国家 + 多语言（country / lang 两个独立维度）
报表页与全部导出都按「国家版本 + 语言」渲染，两个维度各用一个参数、各存一个 cookie，互不影响：

    http://localhost:48080/report/report?country=th            # 泰国版（默认泰语）
    http://localhost:48080/report/report?country=my&lang=en     # 马来西亚版，用英文看
    http://localhost:48080/report/report?country=vn&lang=vi     # 越南版
    http://localhost:48080/report/report?lang=zh-TW             # 全球版，繁体中文

国家 `global | th | my | vn`；语言取该国家支持的列表（TH: th/en，MY: ms/en，VN: vi/en，
GLOBAL: en/zh-CN/zh-TW），选了不支持的语言会回落到该国家的默认语言。

导出链接不带查询串，直接沿用 cookie 里当前的国家 + 语言：

    /report/export            # 离线 HTML
    /report/export/pdf        # PDF
    /report/export/excel?type=server

每个国家的差异：表格只包含该国家的服务器；日期/数字按该国家格式（泰国用佛历 2569、
马来西亚 12 小时制 PG/PTG、越南用 `.` 分组）；正文的 HTML 片段与样式各有一套。

一个国家版本 = 一个 code 下的四个文件，**按路径加载**（不是按片段名拼接）：

    templates/country/<code>/report.html        # 浏览器页面片段  ~{${profile.contentTemplate} :: notice}
    templates/country/<code>/report-pdf.html    # PDF 片段
    static/css/country/<code>/style.css         # 浏览器样式      @{${profile.styleSheet}}
    static/css/country/<code>/style-pdf.css     # PDF 样式（内联）

片段名在各国家之间是相同的，所以一个国家可以随意增加片段而不会与别人冲突；
每次只加载当前国家的一份 css，因此国家样式里不需要 `.country-<code>` 前缀。
新增国家 = 加一个 `ReportCountry` 常量 + 上面四个文件 + 对应的 region 语言包，
没有任何公共块需要修改；样式文件缺失会在**启动时**报错（`CountryStyles`）。

**页头与页尾在所有国家版本中完全一致** —— 它们来自
`templates/fragments/report-chrome.html`，不读取任何国家字段、也不加载任何国家文件。


# 动态表格报表（表头 + 数据都由调用方传入）
    http://localhost:48080/report/dynamic

表头由 `headers` 用 `,` 分割得到，数据由 `data`（JSON 数组）填充，同样支持 country 与多语言。

    /report/dynamic?headers=host,ip,app,uptimeDays&data=[{"host":"creed-th-gw-01","ip":"10.30.1.11","app":"creed-gateway","uptimeDays":1234}]
    /report/dynamic?headers=host,ip,app&data=[...]&country=vn&lang=vi

**headers 传的是列的 key，不是显示文本**：每个 key 会按 `report.col.<key>` 去语言包取标题，
所以 `host,ip,app` 在中/泰/马来/越南语下自动翻译；语言包里没有的 key 原样显示（`uptimeDays`），
也可以写成 `key:自定义标题` 直接指定。

**data 支持两种 JSON 形状**：

    [{"host":"a","ip":"b"}]     # 对象：按列 key 取值，字段顺序无所谓，缺字段则为空单元格
    [["a","b"]]                 # 数组：按表头顺序取值，多余丢弃、不足补空

单元格按**国家**格式化：数字用该国家的分组符（越南 `1.234`）、布尔值用当前语言的是/否；
字符串原样输出（不猜日期，避免把像日期的 ID 改坏）。

导出（三种格式，与页面完全一致；页面上的导出按钮是 **POST 表单**，因为 `data` 往往塞不进 URL）：

    POST /report/dynamic/export        headers, data, title   # 离线 HTML
    POST /report/dynamic/export/pdf    headers, data, title   # PDF
    POST /report/export/excel          type=dynamic, headers, data, title

GET 同样可用，方便把一整张报表做成一个链接分享。
`headers` 缺失、JSON 解析失败、或超过 `creed.report.dynamic.max-columns` / `max-rows` 时返回 **400**。
