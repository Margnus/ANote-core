# ADR-0012：预览目录行复用

2026-10-15；DEV-023。Android目录由ScrollView+逐条Button改为原生ListView+BaseAdapter，只创建当前窗口需要的行，复用滚出窗口的Button。iOS继续使用SwiftUI List。没有新增第三方UI依赖。

NoteListAdapter接收有界metadata列表的防御复制，owner线程替换；每次绑定重新写标题/日期与不可变documentId点击回调。position只用于当前列表索引，不宣称稳定身份；不能将之前行的位置或回调保留给下一条笔记。空、加载和读取失败由独立empty view/status表达，失败不伪装成没有笔记。标题/日期格式使用资源占位符，避免新引入拼接文本lint建议。

当前目录仍受DEV-022的2000个磁盘文档边界，以及最多8个未保存owner文档约束；组件不是生产分页索引，LIB-09的一万条按页加载尚未完成。行复用降低UI常驻控件数量，不代表元数据全量扫描、滚动帧率或真实heap已经达到生产指标。

独立Android Instrumentation将1000条合成metadata接入真实ListView窗口，检查实际创建控件数量小于100、只显示窗口行，滚到999再回到0，点击复用行正确返回documentId。合成目录不保存到真实笔记库；它是View实例数量/绑定正确性的证据，不是手机延迟基准。该项使窗口/ART/存储/目录报告增加到8项，完整App流程21项独立回归。核心仍135项；Apple线程/IO报告单独记录。

下一节点DEV-024先建立shared-core的页面目录与文档根契约，再接双端分页界面。稳定PageId、单文档权威修订、对象归页与删除/复制原子边界、持久化/撤销责任必须先明确，不以单页facade集合伪造完成整个PAGE-02/03。
