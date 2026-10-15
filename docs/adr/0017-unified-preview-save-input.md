# ADR-0017：shared-core统一预览保存输入

2026-10-15；DEV-024D2内存/IO适配候选，接续ADR-0016。双端原生分页UI仍未接入。

## 模块与不可变输入

PreviewSaveLedger及原12项测试从canvas-core迁入shared-core，暂保留原Kotlin包名供宿主过渡；总测试未因迁移减少。shared-core依赖canvas-core，Core不import文档域。新增封闭PreviewSaveSnapshot协议，只有LegacyPreviewSaveSnapshot适配器及PagedPreviewSnapshot两种实现；字段包括documentId/title/version、retained/encoding work charge与encode。它们持有不可变capture，不持有可变Notebook、原生View、文件句柄或owner集合。原平台IO capability继续独立在worker执行。

旧Core快照不反向依赖协议，由shared-core adapter包裹，额外声明128字节wrapper charge。分页snapshot直接实现协议。两者共用一份latest/in-flight/failed状态机与attempt身份；不同文档可以并行在途，同文档最多一份在途旧版和一份latest，两者保守计费。完整新版可以替换未启动/失败旧版；旧在途完成不能删除新版。后端拒绝返回unstarted，失败内容保留到显式重试；旧/跨账本attempt不能释放新epoch责任。

## 同版本内容与确认

同version只有exact content相同才允许重试，始终保留账本原snapshot，不以另一对象替换正在写的输入。规范root/scene引用相同为快路径；失败重开从文件/留存内容重建的等价snapshot也可比较并重试，不要求wrapper或private coordinator revision相等。重建路径可能比较全部规范对象/采样，不将其描述成常量时间或实际UI延迟已达标。

分页比较根ID/revision/标题、顺序页目录和有序规范对象，包含页面尺寸/纸张/contentRevision、对象ID/layer/revision、笔刷/矩阵及真实点。旧格式还比较nextObjectSequence；仅看当前可见笔迹会遗漏下一笔身份计数器差异。同版本不同内容返回VERSION_CONFLICT，不更改retryRequired、原输入、flight或charge，双端提示保留当前内容并导出备份。更高版本替换仍以前提“一个文档一个canonical owner”为契约，不是多分支合并或同步冲突解决器。

双端旧App保存写入已通过adapter接同一账本。Android AtomicFile writer接统一协议，因此可以写v2完整根，但App打开/目录/UI仍单页v1，不能据helper测试称为已支持原生分页。Swift保存worker通过可抛错协议编码，异常保留到原执行器OutcomeUnknown/账本失败路径。确认回调若仍有pending完成笔，不覆盖为“已保存”；Swift另检查当前capture与已写输入内容一致。

## 验证与剩余

新增9项common反例：旧页写入期间新页修改、等价分页重开重试、同版本标题/纸张/绘制顺序/样式/矩阵/对象revision分支、同时保留预算拒绝、旧/新输入共用账本、旧格式private coordinator重建、旧格式不同笔迹、旧attempt与新epoch、可见笔迹相同但next身份不同。合计209项/22 suites，Core128、shared-core81；原12项账本测试已迁移，不是删除后凭数量补齐。

Android实际ART新增paged_snapshot_storage：同一writer写v2双页并逐规范字段读回；旧页完成保留新版、阻挡目录真实失败保留latest、修复后等价重开重试写入成功。独立报告记录真实产物SHA及合成缓存来源；它不代替原生分页界面验收。旧App完整创建/笔迹/保存重开/备份流程和iOS实际编译仍须与最终产物绑定。

下一步D3把Android活跃owner、目录/v1-v2读取、导入映射、页面按钮/尺寸/矩阵呈现接到共享分页会话，worker只产出不可变root，UI owner创建/复用会话；D4再接Swift同一接口。完整跨页文件重开、切页/异步结果、真实iOS启动、history/receipt持久化与C1仍未验收。
