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

国家样式是**通用样式 + 国家特定样式**两层，国家层在后所以同优先级下它赢：

    static/css/report.css       # 浏览器通用（页面 <link>，导出时内联）
    static/css/report-pdf.css   # PDF 通用（CountryStyles 拼在国家 PDF 样式前面一起内联）

片段名在各国家之间是相同的，所以一个国家可以随意增加片段而不会与别人冲突；
每次只加载当前国家的一份 css，因此国家样式里不需要 `.country-<code>` 前缀。
两个 PDF 文件是**可选的**：`country/<code>/` 下找不到就自动降级到 `country/default/` 这一份

    templates/country/default/report-pdf.html   # 默认 PDF 片段（必须覆盖国家可能定义的所有片段名）
    static/css/country/default/style-pdf.css    # 默认 PDF 国家样式（不是通用层，通用层是 report-pdf.css）

`default` 是一份**替补版本，不是国家**：没有对应的 `ReportCountry` 常量，切换器里不出现，
`?country=default` 也无效；它放在 `country/` 下只是因为它顶的是国家那一层。

降级不是静默的：启动日志会列出哪些国家在用默认 PDF 样式。所以一个新国家可以先只上浏览器版，
PDF 的专属外观以后再补。**浏览器的两个文件仍然是必需的** —— 那是用户真正打开的版本。

新增国家 = 加一个 `ReportCountry` 常量 + 上面的文件 + 对应的 region 语言包，
没有任何公共块需要修改；浏览器样式缺失会在**启动时**报错（`CountryStyles`）。

**页头与页尾在所有国家版本中完全一致** —— 它们来自
`templates/fragments/report-chrome.html`，不读取任何国家字段、也不加载任何国家文件。
页头里的国家/语言切换按钮链接回**当前这一页**（`header(...)` 的 `page` 参数），
所以在 `/dynamic` 上切语言不会跳到 `/report`。

# PDF 的页眉页脚与 logo
PDF 导出的页眉栏、页脚注和 logo 图片**每一页都有**（不只首页）。这件事有**两种**可用机制，
分别用在不同的位置：

**一、`.page-frame` 表格**（页眉都用它）—— 正文包进一张表格，页眉页脚是它的 `<thead>` /
`<tfoot>`，靠 `-fs-table-paginate` 逐页重复。页码由 `@page` 的页边距框打印，因为
`counter(page)` 只在页边距框里可用。

**二、running element**（对账单版式的页脚用它）：

    .statement-footer { position: running(statementfoot); }
    @page { @bottom-center { content: element(statementfoot); vertical-align: top; } }

区别在于**能到达的位置**：`<tfoot>` 只能到达**内容的底部**，内容不满一页时它就跟在最后一行下面；
running element 被画在**页边距**里，所以页脚**始终贴着每一页的物理底部**，哪怕最后一页只有两行。
需要页脚钉在页底时，这是唯一可行的办法（把 `.page-frame` 撑满整页的两种写法都失败：
`height: 100%` 被忽略，写死 `height: 247mm` 会把 3 行的报表撑成 3 页且首页空白）。

两个必须知道的坑：

* **`@page` 的底边距要够深**（对账单版式是 `34mm`）—— 页边距框不会把边距撑大，超出的部分直接裁掉；
* **那个框要写 `vertical-align: top`** —— 底对齐时，比文字高的浮动印章会挂在最后一行基线之下，
  被页面边缘切掉。

还有一条反直觉但很有用的结论：**`counter(page)` 在 running element 内部是可以解析的**（因为该元素
本身就排版在页边距框里）。页边距的底边只有一条横带、两个框无法上下堆叠，所以「页脚在上、页码在下」
这种两层结构，只有靠把两层都放进同一个 running element 才能表达 —— 页码那行是空 `<p>`，文字由
`:after { content: counter(page) ... counter(pages) }` 生成，中间的 " of " 仍走语言包。

图片有三张，都可用配置或环境变量替换成自己的：

    creed.report.pdf.logo          CREED_REPORT_PDF_LOGO           # 深色版，用在白底的页脚
    creed.report.pdf.logo-inverse  CREED_REPORT_PDF_LOGO_INVERSE   # 反白版，用在深色页眉栏
    creed.report.pdf.stamp         CREED_REPORT_PDF_STAMP          # 印章，用在对账单版式的页脚

仓库里自带的三张都是**占位图**（`static/img/creed-logo*.png`、`creed-stamp.png`）。替换时注意三点：

* 只支持 **PNG / JPEG / GIF**，**SVG 不行** —— 渲染器（openpdf-html / Flying Saucer）没有 SVG 支持；
* 图片由 `PdfExportService` 读一次后以 `data:` URI 内联，因为渲染器拿到的是字符串、没有 base URL，
  写相对路径或 `/img/x.png` 都解析不到；
* 文件缺失或格式不支持只会打一条 WARN 并**不画这张图**，不会让导出失败；`logo-inverse` 缺失时
  自动回落到 `logo`（深色 logo 放在深色栏上会看不清，但不至于没有页眉）。**印章不回落**到 logo ——
  没有印章就不画，不能拿品牌标志去占印章的位置。


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

    POST /report/dynamic/export        headers, data, title             # 离线 HTML
    POST /report/dynamic/export/pdf    headers, data, title, template   # PDF
    POST /report/dynamic/preview/pdf   同上                              # PDF 版式的 HTML 预览
    POST /report/export/excel          type=dynamic, headers, data, title

GET 同样可用，方便把一整张报表做成一个链接分享。
`headers` 缺失、JSON 解析失败、或超过 `creed.report.dynamic.max-columns` / `max-rows` 时返回 **400**。

## PDF 多版式（`template=`）
PDF 可以选版式，页面上有下拉框，接口上是 `template=` 参数：

    template=form        # 默认。银行表单式，A4 横向
    template=statement   # 对账单式，A4 纵向；页脚贴页底 + 居中页码，见上一节

不传就是 `form`（和没有这个参数时的行为一致）；传了未知值返回 **400**，不会悄悄换一种版式给你。
版式是「一个枚举常量 + 一个 `*-pdf.html` + 一套 chrome 片段」，新增一种会自动出现在页面下拉框里。
离线 HTML 导出**不吃**这个参数 —— 它是页面的孪生体，没有「页」可排。


sample
```json
[{"host":"a","ip":"10.0.0.1","uptimeDays":1234},{"host":"b","ip":"127.0.0.1","uptimeDays":5678}]
```


# 审批状态列表（固定版式 API，数据写死在 controller 里）
把 `docs/template.jpg` 那份打印件 1:1 做成 PDF 的接口。**不接受任何入参**：

    GET|POST /report/approval-status/export/pdf     # PDF 下载
    GET|POST /report/approval-status/preview/pdf    # 同一份 markup 以 text/html 返回，给 devtools 用

    curl -o approval.pdf http://localhost:48080/report/approval-status/export/pdf

数据是 `ApprovalStatusReportController.SAMPLE_JSON` —— 一段 JSON 文本块，由 Jackson 解析进
`model/ApprovalStatusReport`。**13 条记录是刻意的**：和原件的 "13 Record(s)" / "1 of 2" 对齐，
文档必须是两页，重复的页眉、页脚、印章、页码才有得可验。

这个接口存在的意义是**钉住版面**，不是做数据源 —— 相隔一周调两次，除了导出时间戳以外输出完全一致，
所以它既是参照渲染件也是回归测试。要接真实数据的话，复用同一个模板
`approval-status-export-pdf.html`，把 model 在别处组装好传进去即可。

版面分四段：

    logo 独占一行，下方细线                                  ← 页眉，每页重复（<thead>）
    报表名，品牌蓝粗体，上下各一条线                          ← 正文，只出现一次
    筛选条件块（4 列一行）→ 记录数 → 数据表（浅蓝表头带）      ← 正文
    上层：左 = 导出日期 | 导出时间 + 报表名，右 = 印章          ← 页脚，每页重复（页边距框）
    下层：页码「1 of 2」居中

筛选条件块用 `<table>` 排（这个渲染器既没有 grid 也没有 flexbox），条件在模型里是**有序 List**
而不是 Map —— 顺序本身就是版面。Account 单元格是**多行数据**（公司名 / 账号 / 币种），每行一个
`<p>`：换行是数据，不交给渲染器去猜。这两点也是它没有复用动态表格的原因 —— 一个字符串矩形
表达不了「带标签的键值对，四个一行」和「这个格子的四行必须待在一起」。

页眉页脚直接复用对账单 chrome（`fragments/report-chrome-pdf.html` 的 `statement*` 片段），
自己不定义任何 chrome。原件是 UOB 的文件，这里用的是项目自己的 CREED logo 和印章 ——
交付的是版式，不是别家的标识。

## 合并导出（`/export/pdf/merged`）

把同一份文档渲染多次、合并成一个 PDF，**并把页脚的页码改成合并后的**：

    GET|POST /report/approval-status/export/pdf/merged                 # 默认 2 份 → 4 页
    GET|POST /report/approval-status/export/pdf/merged?copies=3        # 3 份 → 6 页
    GET|POST /report/approval-status/export/pdf/merged?langs=en,th     # 英文 + 泰文各一份

    curl -o merged.pdf 'http://localhost:48080/report/approval-status/export/pdf/merged?copies=2'
    curl -o mixed.pdf  'http://localhost:48080/report/approval-status/export/pdf/merged?langs=en,th'

`copies` 范围 2..10，越界是 **400**（`InvalidMergeRequestException`），不是静默截断 ——
悄悄改成别的份数等于返回了一份没人要的文档。`langs` 给出就按语言逐份渲染并忽略 `copies`；
不带 region 的标签按全模块同一套规则解析（`th` → 泰国版），所以 `langs=en,th` 真的是一份英文
加一份泰文，而不是两份英文（GLOBAL 版只出 en/zh）。

要点在页码而不在内容。两次渲染各自都是「1 of 2」「2 of 2」，直接拼起来就是一份四页、却数了两遍
二的文件 —— 比没有页码更糟：读者既不能相信它，也看不出少了哪一页。`PdfMergeService` 因此做两件事：

1. **合并**用 `PdfSmartCopy`，它会去重相同的嵌入资源。这里省得不少：每份渲染都嵌了同一套 Noto
   字体，换成普通的 `PdfCopy` 会一份带一套。
2. **改页码**：逐页找出那一页原本印的那串字（合并时知道每一部分有几页，所以知道第 3 页原来写的是
   「1 of 2」），用背景色矩形盖掉，再用同样的字体、字号、颜色在同一条基线上重画。分隔符和字体都取自
   模板渲染时用的那个 message bundle（`pdf.page.middle` / `pdf.font.family`），所以英文页脚是
   「3 of 4」，泰文页脚是「3 จาก 4」，字形也对。

### 混合语言合并

每一部分的分隔符**各自记录**（`PdfMergeService.Part(bytes, separator)`）：英文那份印的是「1 of 2」，
泰文那份印的是「1 จาก 2」，而定位靠的就是这串字 —— 只告诉它一种分隔符，另一半会找不到、打日志、
**原样留着**（宁可不改也不瞎盖，一份一半准一半猜的文档比没改过更难信）。

合并后的文档用**发起请求时那个语言**的页码：由英文和泰文各一半拼成的文件没有自己固有的语言，
这是调用方的决定而不是查表能查出来的。所以 `?langs=en,th` 出来的四页全是「N of 4」，
包括那两页泰文内容的页 —— 它们的正文、佛历日期和字体都还是泰文版的。

**盖白底的宽度不能拿新字体去量旧字符串**：解析出的 end point 停在最后一个字形的原点而不是它的
advance 之后（一串字体量得 20.9pt 的「1 of 2」解析出来只有 16.3pt），所以要补一个字形；而补多少
只能用**数字的宽度**——页码永远以数字结尾，且这几个 Noto 字面的数字 advance 几乎一致。第一版拿
Noto Sans（拉丁）去量「1 จาก 2」，泰文字形量出来是 0，白底盖窄了，泰文那个「2」就露在新的「4」旁边。

**已知代价，一并说清楚**：旧页码是被**盖住**的，不是被删除的。页面上看不到它，但它仍在文本层里
—— 复制粘贴、文本提取、读屏软件都会同时看到两串。要彻底去掉得改写页面的内容流；或者换一条路：
两份都由本模块自己渲染时，把两段 XHTML 拼成一个文档只渲染一次，页码由 Flying Saucer 自己算，
一个 PDF 都不用改。这个接口走的是通用的那条路（任何来源的 `byte[]` 都能合），所以留着这个代价。

`PdfMergeServiceTest` 钉住的就是这些：四页各自的页码、泰文 locale 的分隔符和字体、页脚与印章
在盖章后完好、单份合并不改动任何东西，以及上面那条「旧串还在文本层」的事实。
