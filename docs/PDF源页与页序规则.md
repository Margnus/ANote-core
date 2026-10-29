# PDF源页与页序规则

## 交付卡（2026-10-29）

起点Core bcc41db。依据PAGE-02/PDF-04的源页只读、删除只改映射与源页码/插页后页序要求，现行计划允许隔离业务规则。范围：internal源hash/源页索引元数据规则，复用PageId/原root/原DeletePage，无IO/renderer/新authority、格式或普通App接入。M-1/C1/DEV-041、DATA-04/PDF-02等验收依赖不变。

验收：同空间/文档/元数据revision、完整枚举；源hash唯一且为64位小写hex，页数与索引有效，引用pageId属于原root且唯一。显示位置按root当前目录顺序，空白插页无源页号；源页号为物理index+1（未解析PDF PageLabels）。重排不改源索引，复制可有不同pageId引用同源页。删除计划带space/文档revision/元数据revision及before引用，交由未来同一事务原子删除页/引用，源对象保持。

删除复用原DeletePage。最后一页原本已为空白且无笔迹时，原命令会NO_CHANGE，故该有限情况只规划移除PDF背景引用，保留同一空白页，无伪造正文提交；其它情况返回原命令，原历史处理页/墨迹删除。映射与原文档必须同事务/同历史配对；单独执行文档undo不能证明PDF引用也恢复。外层始终保留operationId（含仅元数据操作），未来事务须持久化幂等回执。计划不是提交或资源GC删除权，源hash记录保留，不删除源资源。

候选最多1000源、100000源页/源、引用不超root页数；这是准入而非性能/生产格式结论。调用期间目录/快照列表须同owner稳定，输出不持有输入集合。失败只返回原因，不改输入。测试包含原authority重排/插页/删除、最后空白页、重复源页引用、坏元数据和旧版本拒绝；记录源值保持。

## 接入出口

采用后仍需正式PdfPageRef/文档schema及事务/历史原子集成、真实源资源与renderer、CropBox/rotation/UserUnit、目录能力降级/真实PageLabels、双端源页码/插页/删除/撤销/保存重开与导出验收。此规则不表示PDF产品已开发；PAGE-02保持部分实现、PDF-04保持未开发，均待验收。

## 实际结论

采用为正式PDF元数据/页序的规则输入；新增6项共同测试通过，共534项JVM XML零失败，shared本轮执行、canvas复用。[绑定报告](../reports/pdf-page-catalog-rules.json)。可移植性/架构通过；无平台App/IO/renderer变化，不重建未变化平台包或重绑旧PDF像素报告。不是文档/引用事务原子性或真实PDF验收，状态与原依赖不晋升。
