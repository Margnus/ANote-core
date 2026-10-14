# ADR-0006：预览笔记备份与副本恢复

2026-10-14；延续D4离线预览，解决私有目录数据无法携带的问题。此入口只交换NCPREVIEW v1单页，不是正式容器、全库备份或旧JNotes迁移。

## 身份与所有权

导出时取消未完成笔，先重试已完成但未提交的笔，再在UI owner捕获不可变snapshot。系统选择器打开后继续编辑不会改变这次备份。编码及文件IO在后台执行，不在MOVE或主线程编码全文。导出完成意味着系统provider的写入接口正常返回，不等价于云端副本或断电持久化保证。

导入完整校验格式后，用宿主生成的新UUID创建独立document/scene；保留标题、稳定笔画ID、brush、真实采样、nextObject，重置会话修订/撤销历史。重复导入创建多个独立副本，永不按备份内documentId覆盖本地笔记。新副本在本机原子写入成功后才能显示成功；未知版本、截断、错误UTF-8、非法采样、容量超限或写入失败均显式拒绝，源文件及已有笔记保持。

## 平台与边界

Android走[ACTION_CREATE_DOCUMENT/ACTION_OPEN_DOCUMENT](https://developer.android.com/training/data-storage/shared/documents-files?hl=en)，只获得用户选择的URI，不申请广泛存储权限。App级IO owner保存导出capture，Activity在文件选择器期间重建仍可继续；进程死亡会要求重新导出。取消选择器不写文件。失败导出的目标可能残缺，提示重新导出，不能自动删除provider文件。

iOS走[SwiftUI文件选择与导出](https://developer.apple.com/documentation/swiftui/view-presentation)，使用FileDocument和security-scoped URL，后台有界读取并在所有完成路径释放访问。模拟器编译单列证据；没有runtime，不能声称实际文件选择器运行通过。

两端导入最多读取32MiB，分块累积前检查上限；公共解析器另有1600万字符、500笔/20万点/单笔2万点上限。此预览仍需全文快照/解析，后续生产容器将改分块与全局内存预算；本次不加入压缩、附件、密码或网络协议。

## 验证与后续

共享反例验证副本独立身份、所有采样/brush/nextObject保留、原件不变及损坏/身份拒绝。Android运行脚本通过系统选择器验证导出逐字节一致、取消、重复导入独立副本、损坏拒绝及原件保留，结果必须匹配实际APK SHA。iOS只有构建证据，仍需设备运行。

后续继续页面/视口体验、统一关闭协调器和正式存储恢复；不能用本预览备份替代生产完整性校验、版本迁移、崩溃恢复和全库备份验收。
