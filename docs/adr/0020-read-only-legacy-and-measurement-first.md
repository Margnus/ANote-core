# ADR-0020：只读旧格式收口与测量优先

2026-10-15；依据蓝图远端e066cfb的12号评估与用户本轮指示调整。Mac锁屏涉及的桌面交互验证正式延后，不再作为独立开发的停点；未验证标记保留。

## 核对与决策

双端原生宿主在2159211已经只使用PagedPreviewNotebook，并无活跃的v1编辑/保存入口。按完整符号匹配，PagedPreviewNotebook不能被算作PreviewNotebook引用。风险实际在共享SDK：v1兼容解码依赖旧可变PreviewNotebook，旧writer/LegacyPreviewSaveSnapshot仍被公开与导出。

删除canvas-core旧PreviewNotebook、Snapshot/Header、shared-core旧保存adapter及v1 writer；仅保留shared-core内部LegacyPreviewReader。reader直接建立不可变DocumentRoot，不创建内存authority；完整迁入只通过PagedPreviewCodec对外开放，后续编辑和保存只走PagedPreviewNotebook/PagedPreviewSnapshot/v2。旧v1文件未编辑不改写的原生IO语义继续保持。

固定v1字节替代测试中即时生成旧文件；旧单页12项测试改为13项实际只读迁入反例（新增累计点上限/第十一笔缺payload的分配前拒绝），保存账本原12项改用唯一分页输入，原9项混合/冲突反例迁到迁入后规范根。已退休的可变实现不移到测试目录继续维护。架构校验使用完整符号扫描源码/测试，拒绝旧栈重新进入。

只读迁入继续限制500笔、每笔20000点、累计200000点、16M字符，行长/字段数在split前限制，点总数在分配前准入。保留对象顺序、颜色/宽度、真实时间压力、1000×1400原坐标；nextObject只验证旧身份合法性，后续分配使用新session身份，不再把旧计数器作为新保存元数据。

## 模块与证据

当前物理依赖仍是shared-core→canvas-core；宿主保留原生输入/绘制/预览IO。Core只含规范模型、几何基础、命令与参考调度/呈现状态，文档、预览会话和只读兼容在shared-core。canvas-session/RenderPlanner/平台适配产物是C1目标职责候选；先M-1/M-2/M-3测量，再决定物理拆分，不为图中方块预建空模块。

旧源码证据冻结到原记录的两个Git提交，不覆盖为另一机器的不同checkout。本机重新验证两棵树HEAD与80条原hash/anchor均匹配；另一机器缺失/失配结果仍按当地报告保留。源码证据匹配不能证明AAR等价，C0改为记录版本/差异并限制兼容承诺。

远端蓝图已修复其校验器，但ANote-core远端2159211尚无评估提及的UTF-8/LF修复。本轮补齐Core工具的显式UTF-8读写、文本LF检验口径、指纹key统一正斜杠及.gitattributes；真实APK/ZIP/NCP、图片、二进制fixture与旧源码证据保持原始字节hash。新增本机合成LF/CRLF、路径与文本IO检查，不宣称已在Windows实际构建。

## 调整后的下一交付

1. 本节点闭环单栈收口与可复现性，不新增预览功能。
2. M-1真机基线：中端Android和触笔平板，记录机型/系统/刷新率、采样/线程/内存和物理延迟测量方法；只有真实设备证据才填写指标。无设备时保持待测，不用模拟器吞吐替代触笔到光子。
3. M-2共同几何PoC：MONOLINE/PRESSURE/HIGHLIGHTER的版本化几何与覆盖规则、黄金样本、双端适配能力；先证明可表达性与成本，再选择平台后端。
4. M-3密集页索引PoC：固定S2笔迹集、uniform grid与R-tree的候选集/更新/查询对比，包含大bbox/倾斜线退化，暴力扫描作正确性oracle。桌面结果与原生/真机结果分开。
5. 按证据修订职责、估算和预算后进行D5/C1评审；通过后再推进shared-data的SQLite事务/资源/恢复与D6生产闭环。

iOS真实触摸、系统选择器、强停重开及真机性能列为延期门禁，保留已有独立API/IO/启动证据。完整PAGE/P0、生产持久化和C1均不因本轮收口而验收。
