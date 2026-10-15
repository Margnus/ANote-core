# NoteCore

独立笔记App与通用画布工程，2026-10-14开始实际开发。当前D4离线手写预览：Android多页编辑、整本保存/备份与缩放已在模拟器完成实际UI验证；iOS已接入同一分页会话，编译ARM64模拟器.app、安装启动及9项真实宿主API/文件/绘制测试通过，真实触摸/系统选择器仍待验证。C1生产架构仍未冻结。安装和实际验证结果见[初步运行版本](docs/初步运行版本.md)。

先读[开发执行计划](docs/开发执行计划.md)、[进度](docs/进度.md)、[原型ADR](docs/adr/0001-c1-reference-prototype.md)。完整需求和整体架构在相邻[项目蓝图](https://github.com/Margnus/AI-Note/blob/main/README.md)。

最新顺序按蓝图e066cfb与[ADR-0020](docs/adr/0020-read-only-legacy-and-measurement-first.md)调整：旧单页编辑/写出SDK已收口，v1只读迁入不创建另一owner；接下来优先C0真机基线、共同笔刷几何和密集页索引PoC，再做生产架构冻结及SQLite层。Mac锁屏相关桌面交互验证已由用户明确延后，不阻挡独立开发。

## 当前可运行内容

- [三笔刷有界路径与离屏验证](docs/共同笔刷路径与离屏验证.md)：共享231项测试、Kotlin/Native运行11项、Android/iOS实际离屏覆盖与alpha；S2成本排除了整页无限制路径缓存，下一步简化/分段与驻留预算。未接入App三笔刷。

- [三笔刷共同覆盖PoC](docs/共同笔刷几何PoC.md)：版本化候选规则、解析oracle、仿射painted bounds和手工黄金格点；未作为原生渲染后端接入。

- [密集页空间索引PoC](docs/空间索引PoC.md)：S2百万点与退化分布上的网格/STR树正确性和桌面成本测量；未接入App，生产结构尚未选定。

- 平台无关double坐标和矩阵、不可变真实采样、captured scene/layer/tool。
- 纯场景命令和patch，内存单写者的修订/幂等/容量检查；Receipt明确MEMORY_ONLY。
- 迟到frame的revision/generation检查、scene/object/算法版本的几何缓存键。
- 按字节计费的LRU参考策略及重键/淘汰/超额/溢出反例。
- [提交队列模型](docs/adr/0003-submission-ownership-and-retry.md)：计数/逻辑字节准入、失败保留、丢ACK幂等重试、旧回调拒绝；Confirmed仍为MEMORY_ONLY。
- [资源lease模型](docs/adr/0004-resource-lease-and-destruction.md)：预留/驻留/退休资源计费，最后读者后释放，未知析构结果隔离；附单线程可控调度假后端。
- [文档关闭模型](docs/adr/0008-document-close-and-recovery-handoff.md)：统一session停止、完成笔保留、队列/resource drain与两步接管，未知结果继续持有owner。
- [有界真实执行器](docs/adr/0009-bounded-owner-worker-executor.md)：JVM/Android串行worker、creator-owner线程检查、预留结果槽和显式交付重试，停止不清空已接管工作。
- commonTest、独立JVM演示、源码依赖边界和真实测试报告。
- 有界输入状态机、历史批次与预测分离、捕获目标、显式取消/关闭/拒绝；见[输入ADR](docs/adr/0002-input-replay-boundary.md)。
- [规模笔迹子集](fixtures/scale/README.md)：S1/S2/S9共120万点，按需生成并核验SHA；不代表完整场景压测。
- [旧字段映射](docs/旧格式字段映射.md)及[原生采集契约](docs/原生输入采集契约.md)：有源码证据和候选规则，转换器/采集器尚未实现。
- [合成输入轨迹](fixtures/README.md)：5类D2候选及hash，已由Kotlin回放并逐点验证；不是设备性能结果。

这些是验证架构的reference实现，API可调整；Map复制、内存journal和临时数值范围不代表生产性能或正式文件协议。已有双端有界执行器及预览保存账本；生产Native资源lease、iOS完整交互验证、完整设备采集校准、SQLite和完整几何/索引尚未实现。

## 构建与验证

验证需要JDK17和Python3（可用PYTHON_BIN指定）。规模fixture按需生成约34MB，保存在忽略目录，不修改旧工程。Gradle8.14和Kotlin2.2.21精确固定；首次执行会下载公开依赖。设置JAVA_HOME为自己的JDK17目录，Gradle User Home建议放本工程目录，不改旧工程缓存/设置。

```bash
export GRADLE_USER_HOME="$PWD/.gradle-user-home"
./gradlew --no-daemon --console=plain verifyPrototype
./gradlew --no-daemon --console=plain :canvas-core:runPrototype
python3 tools/check_architecture.py
python3 tools/collect_verification.py
```

Android包：`bash ci/package-android-preview.sh`（另需ANDROID_HOME）；iOS模拟器包：`bash ci/package-ios-preview.sh`。Android见[接入ADR](docs/adr/0018-android-paged-preview.md)与[分页界面验证](reports/android-paged-smoke.json)；iOS见[接入ADR](docs/adr/0019-ios-paged-preview.md)与[真实宿主API测试](reports/ios-host-tests.json)，不将API测试当作触摸UI验收。

同一核心验证入口可用于本地或CI：`bash ci/verify.sh`（需JAVA_HOME，Python可通过PYTHON_BIN指定）。未配置GitHub远程工作流或自动发布。

测试HTML在canvas-core/build/reports/tests/jvmTest/index.html，机器报告在[reports/verification.json](reports/verification.json)。后者由实际Gradle XML生成；代码变化后应重新运行测试再生成报告。

Apple编译入口单独开启：

```bash
./gradlew -PenableAppleTargets=true :canvas-core:linkDebugFrameworkIosSimulatorArm64
```

此入口需要Xcode、Kotlin/Native与平台环境；已实际编译framework及SwiftUI/UIKit模拟器宿主，见[打包说明](docs/初步运行版本.md)。本机已安装iOS27 ARM64 runtime，独立安装/启动证据见[运行报告](reports/ios-runtime-smoke.json)，不代表真机或完整交互验证。Android模块已建立，可用`bash ci/package-android-preview.sh`产出独立调试APK。

## 目录

```text
canvas-core/src/commonMain/   # C1契约原型，无平台/DB/网络/业务依赖
canvas-core/src/commonTest/   # 可移植语义反例
canvas-core/src/jvmMain/      # 命令行演示
docs/                       # 实施计划、进度、ADR
tools/                      # 边界校验、测试结果收集
reports/                    # 真实验证记录
```

Git仓库：[ANote-core](https://github.com/Margnus/ANote-core)。按用户授权，在验证通过的关键节点提交并推送；蓝图同步至AI-Note。旧App/DrawPanel保持独立。D2已有公共输入回放和5类JSON端到端参考验证，已有规模笔迹与旧字段候选映射，D3已有提交所有权/重试参考模型，现有lease假后端与可控调度模型，用户当前目标为先交付可打包运行版本，正在完成D4离线手写预览；关闭协调器/恢复handoff已有单owner参考实现，真实执行器/原生集成与D5架构冻结继续作为后续门禁。

Apple有界执行器与macOS真实线程对照见[ADR-0010](docs/adr/0010-apple-executor-conformance.md)。运行核心验证后使用 `python3 tools/verify_apple_executor.py`：12项Apple线程检查、9项JVM协议对照。iOS已有独立启动报告，线程协议仍为macOS证据，App保存接入见[DEV-022](docs/adr/0011-preview-owned-storage-pipeline.md)。

当前JVM209项测试/22 suites（canvas-core128、shared-core81）；Apple线程12项+Foundation IO6项为macOS证据。双端保存使用最新/在途/失败snapshot账本、有界IO和标题头读取，旧写回不能清除新内容。Android独立窗口/ART/历史/codec/会话/存储/目录报告12项，完整App流程21项，按实际产物SHA记录；不代替真机或生产恢复验收。下一步DEV-023列表复用，DEV-024分页文档域。

DEV-023新增[目录行复用](docs/adr/0012-preview-catalog-row-recycling.md)：Android原生ListView回收可见行，1000条合成目录滚动/点击绑定已验证，独立报告增至8项。下一节点为shared-core分页文档根/稳定PageId，生产一万条分页索引尚未实现。

DEV-024交付A新增[shared-core分页根](docs/adr/0013-paged-document-root.md)：一份canonical Scene与稳定页目录，单root revision、copy/delete原子桥接及PreparedCanvas幂等重放。JVM总计157项（canvas-core138、shared-core19），18 suites；真实文档owner检查单列。双端依赖/Swift出口已统一，当前App仍单页，继续delta历史/v2兼容codec及分页UI。

DEV-024B新增[有界规范delta历史](docs/adr/0014-document-delta-history.md)：同一authority原子撤销/重做、原身份恢复与对象epoch推进、history条目/字节horizon，receipt journal独立保留。新增12项common历史和1项真实JVM owner反例，当前App仍单页，继续DEV-024C有界v2兼容codec及DEV-024D分页UI。

DEV-024C新增[有界分页v2 codec](docs/adr/0015-paged-preview-codec.md)：规范字段/页序/绘制顺序往返、v1只读迁入、fresh映射副本和容量/坏格式拒绝。历史恢复保留稀疏原位置，旧标题截断保留完整emoji。JVM189项（Core140、文档域49），App仍单页v1，下一节点为DEV-024D同authority分页会话、保存账本与双端UI接入。

DEV-024D1新增[共享分页会话](docs/adr/0016-paged-preview-host-integration.md)：一份authority与只读页面projection、稳定选择、输入捕获、待重试完成笔保留和全根capture；JVM200项（Core140/文档域60）。D2保存账本适配及双端分页UI/实际文件重开尚未接入，App仍单页v1。

DEV-024D2新增[统一保存输入](docs/adr/0017-unified-preview-save-input.md)：账本及原12项测试迁入shared-core，旧单页adapter和分页snapshot共用封闭协议；同版本内容冲突拒绝，等价重建仍能重试。JVM209项（Core128/文档域81），Android真实AtomicFile v2双页/失败重试新增独立证据。原生App分页UI与目录/读取仍待D3/D4接入，当前仍单页v1。

iOS已补齐专用iOS27/iPhone17运行时，完成安装、安装二进制核对、启动存活和[首屏截图](reports/ios-preview.png)，见[运行报告](reports/ios-runtime-smoke.json)。入口为 `python3 tools/ios_runtime_smoke.py --device <专用设备UDID>`；当前仅安装/启动证据，书写、保存重开和文件选择器仍待验证。
