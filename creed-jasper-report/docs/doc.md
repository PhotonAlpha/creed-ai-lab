# 三层，分别在不同的地方，容易混淆：                                                                                  …port/src/main/java/com/creed/jasper/api/ApprovalStatusJasperController.java      +85 -2

## ① 单元格内的左右内边距（文字离格子边的距离）

真正让 jr:table 的列看起来有"边距"的就是这个。

- 取值：`render/ApprovalStatusRenderer.java:118 和 :121`                                                                                                                                                       
  HEADER = CellStyle.of(FONT, 8f).bold(true)... .padding(6, 3, 3, 3)   // left=6, right=3                                                                                                                      
  CELL   = CellStyle.of(FONT, 8f).forecolor(TEXT).padding(6, 3, 4, 4)  // left=6, right=3                                                                                                                      
  `ACCOUNT_CELL`（:127）和 `STATUS_CELL`（:130）是从 `CELL` 派生的，所以同样继承 6/3。
- 模型：`dynamic/CellStyle.java:103` 的 `padding(left, right, top, bottom) → CellStyle.Padding`
- 写进元素：`dynamic/TableDesigner.java:247-248`，在 `apply()` 里                                                                                                                                                
  box.setLeftPadding(padding.left());                                                                                                                                                                          
  box.setRightPadding(padding.right());

  改左右边距改这里。注意它同时参与宽度计算——ColumnFit.measure() 量 min/max 时会加上 padding(style)（ColumnFit.java 末尾的 padding() 方法 = left + right），所以 padding 一改，自适应宽度跟着变。               


## ② 表格最外侧的左右竖线

第一列的左边线、最后一列的右边线，不是 padding 而是 pen：

- dynamic/TableDesigner.java:199-206，在 place() 里                                                                                                                                                          
  if (index == 0)          { ...getLeftPen().setLineWidth(CellStyle.RULE_WIDTH); ...setLineColor(layout.edgeColor()); }                                                                                        
  if (index == count - 1)  { ...getRightPen()... }
- 线宽 CellStyle.RULE_WIDTH = 0.5f（CellStyle.java:64），颜色 GRID = #C9CCD1（ApprovalStatusRenderer.java:99），经 TableDesign 的第三个构造参数传入（:200）

这两条线故意没放进 CellStyle：同一个 style 会落到每一列上，而"我是不是第一/最后一列"是 style 无从知道的。

## ③ 表格整体相对纸张的左右留白

- jrxml approval-status.jrxml:90 leftMargin="40" rightMargin="40"，:89 columnWidth="515"
- detail band 里的骨架值 :253 <reportElement key="listing" x="0" width="515"/>
- 但这个 515 运行时会被覆盖：TableDesigner.java:125                                                                                                                                                          
  element.setWidth(design.getColumnWidth());                                                                                                                                                                   
  剥 chrome 的导出（xlsx/csv/html）里 stripChrome() 把四个 margin 设为 0、columnWidth 设成 pageWidth，所以表格会自动撑满整页——这就是为什么这里要现读 design 而不是信任 jrxml 里写死的数。

## 需要知道的一点

jr:table 的 column 没有 margin 概念。StandardColumn.setWidth()（TableDesigner.java:140）设的宽度相加正好等于表宽，格子之间不留缝；格子里的元素又是 setX(0) +                                                 
撑满整格宽（:187-189）。所以列与列之间你看到的一切间隔，全部来自 ① 的 padding 和 ② 的框线，没有第三个来源。                                                                                                  
                                         