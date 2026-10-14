# ADR-0015：有界分页预览v2与v1只读迁入

2026-10-15；DEV-024C实施前候选。仅作为离线预览交换协议，正式分块文件/SQLite/资源包格式仍独立评审。

codec位于shared-core，输入/输出为规范DocumentRoot，不建立第二份可变对象树。NCPREVIEW v2按固定行结构保存文档ID/标题/根revision、稳定页ID与layer/尺寸/纸张/contentRevision、规范StrokeObject的身份/revision/笔刷版本/矩阵及真实采样。页序等于目录行顺序，笔迹顺序必须显式保持，浮点用可往返文本，未知版本/笔刷算法/纸张或非法矩阵拒绝，不静默改为默认样式。header只解析最多4KiB的两行元数据，不声称body合法；v1/v2分版本解析。

默认预览限制128页、5000对象、总200000真实点、每笔20000点和16000000 UTF-16单位文本；平台在读文件前仍须32MiB预检与严格UTF-8读取。逐行解析采用有限行长度和字段数检查，在分配数组前检查本笔与累计点数，不先split全文件。重复页/layer/对象、孤立对象、页revision超过根、非有限/奇异矩阵、坏转义、缺footer/多余body全部拒绝。encoder检查同一容量与样式契约，按有界chunk追加文本；encode失败不改变根、不进行半文件发布。文本builder/UTF转换及捕获快照预留charge供后续保存账本准入，不将它说成精确heap上限。

v1只读迁入复用现有严格decoder，经只读snapshot接口取规范Scene对象，首PageId固定为文档范围内page-1、layer沿用ink、尺寸595×842、空白纸。v1 changeVersion成为根revision，页contentRevision取同值；不改写原v1文件，只有后续显式保存才输出v2。保留每点时间/压力和既有对象ID，不通过public point getter重建一份丢字段的笔迹。v1和v2重复导入都由宿主提供完整新document/page/layer/object身份映射，校验fresh/完整/一一对应，样本可共享，导入根和对象版本重置为新文档初始值。

v2不持久化undo/redo、receipt journal、retired身份/epoch或session展示状态；重开不可声称恢复未确认operation或全局身份责任。宿主后续必须隔离旧session回调并分配新UUID，正式进程恢复依赖shared-data操作账本/事务。格式支持分页不等于App已能翻页；交付C先验证round-trip、v1兼容、篡改/容量和副本身份，随后D才接双端同一authority分页UI和真实保存重开。

## 已实现的交付C参考边界

PagedPreviewCodec已实现v2 encode/decode、v1只读迁入、两版本有界header和完整fresh身份映射的副本导入。v2中D行字段为ID/标题/pageCount/objectCount/rootRevision，G行记录页目录，S行保存规范对象及6项矩阵，P行保留真实采样。全局对象行顺序即绘制顺序；不按ID排序。CRLF可读取，输出统一LF；END后允许最后一个行终止符，拒绝额外空行/数据。

逐行扫描在substring/split之前限制1024字符和精确字段数；v1额外执行行长/分隔符预检再复用既有decoder，防止坏单行制造无界字段表。两版本都拒绝不成对UTF-16代理；旧预览rename在120单位边界保留完整emoji，不能截出半个代理后写成UTF-8替代字符。默认retained额度还限制为8MiB，分配本笔数组前检查累计声明charge；encoder与decoder共享页面/对象/采样/算法容量。encodingWorkByteCharge包含根与有界文本转换，decodingWorkByteCharge按输入字符及最坏样本暂存声明准入；这些是保守逻辑额度，不声称覆盖平台全部分配。

History补充稀疏membership原位置，Undo恢复非末尾对象或整页后保持全局绘制顺序。SceneSnapshot.reordered只组合已经验证的规范对象，验证完整唯一成员，不重扫采样bounds；scene/root仍一次发布，revision不另增一次。delta不保留全场景对象序列，每个变化只计有限位置映射；额外header计费纳入historyByteCharge。

新增15项codec反例与2项history绘制顺序、1项场景reorder及1项emoji边界反例，JVM合计189项/20 suites（Core140、shared-core49）。覆盖完整字段往返、空页/CRLF、header与body分离、v1真实时间/压力、身份/孤立/容量、非法矩阵/样本/版本/revision/转义/footer、Unicode、副本映射及work准入。原生编译与Android ART codec实测单列产物报告；App仍使用v1单页流程，v2实际文件保存/分页界面尚未接入，不能据此验收PAGE或正式恢复。
