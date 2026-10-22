# ADR-0020：v1只读兼容与单页预览收口

日期：2026-10-19。状态：本地并行开发历史记录，已由现行 ADR-0020（0020-read-only-legacy-and-measurement-first.md）及当前开发状态取代。

合并说明：远端 LegacyPreviewReader 及固定字节兼容测试是现行实现；本记录曾保留的旧编辑器/写入器测试夹具已随合并移除。后文描述原节点，不作为当前实现或决策入口。

## 问题

DEV-024D3/D4之后，Android与Swift的活跃编辑入口都已使用PagedPreviewNotebook。旧PreviewNotebook实际只被PagedPreviewCodec的v1读取、LegacyPreviewSaveSnapshot适配器及测试调用。此前评估以名称子串计数误把PagedPreviewNotebook也算作旧类引用，“两端仍有双编辑authority”的判断不成立。

仍有实际的收口工作：为读取旧文件而在canvas-core发布完整单页编辑器、撤销状态和v1写入器，会扩大框架API和维护面。

## 决策

- 生产commonMain移除PreviewNotebook、PreviewNotebookSnapshot与LegacyPreviewSaveSnapshot。旧编辑器及其历史契约测试移到shared-core/commonTest，只作为历史v1样本生产夹具，不随App或Apple framework发布。
- PagedPreviewCodec直接读取有界v1字段，生成DocumentRoot，不创建第二个可变owner。旧1000×1400纸张、真实采样、笔刷、绘制顺序、标题和内容版本保留；未知格式、非法身份、容量超限及坏数据拒绝。
- v1的nextObject只用于校验旧对象身份，不迁入新的身份分配器；分页会话由新session nonce与已存在身份集合防重。
- 保存账本只接受分页快照。原账本失败、重试、预算、过期回执等测试迁到分页输入；v1迁入后保存输入为规范v2。原生宿主对“未修改旧文件保持原字节”的既有策略保留。
- Android/iOS宿主测试用固定v1文本代替已移出的框架编辑API。共享兼容测试同时使用固定历史文本与旧生产器对照，避免仅验证同一新读写器的自洽性。
- 架构检查禁止旧编辑API重新进入生产commonMain。公共预览API未冻结，删除旧API不构成正式发布兼容承诺。

## 验证与限制

实际测试结果见进度与reports/verification.json。本次修改后，旧Android/iOS产物与运行报告只代表2026-10-15的历史版本，不能直接用于证明当前源码；需要重新构建及运行。Windows不能验证Swift编译、iOS触摸、选择器及强停重开。

报告收集另修复Python局部变量覆盖digest函数，以及Windows路径分隔符与Gradle stamp不一致的问题；必须由真实Gradle测试和stamp生成新报告。不能只改报告中的测试计数。

## 后续

按[测量交付计划](../测量交付计划.md)推进M-1/M-2/M-3。模块DAG和单根Scene约定以当前实现说明为准，D5时再决定是否拆canvas-session；本轮不预先创建空模块。
