# ADR-0005：先交付离线手写预览包

2026-10-14；用户要求持续推进直到有初步打包运行版本，因此提前交付D4窄范围竖切。它不代表D3/C1完整评审通过，不冻结生产App、格式或后端API。

## 最小范围

共享PreviewNotebook使用现有唯一SceneSnapshot/MemorySceneCoordinator作为规范笔迹，UI只缓存派生Path；没有第二份可变业务笔迹模型。首包包含新建/打开/重命名、单页单线笔、四种颜色/三种粗细、撤销重做、手指开关、后台保存与重开。页坐标固定1000×1400并按viewport适配，转屏取消未完成笔，已提交笔不受影响。

Android原生View消费真实与historical点，iOS UIKit消费真实/coalesced点；此预览不绘制预测、不支持压力笔刷/橡皮尾端/套索/PDF/同步。原始pressure只作0–1预览归一，完整设备capability校准和手掌检测仍待D4。真实触笔与手机/iPad适配不能由模拟器鼠标测试替代。

Android选AGP8.10.1/API36/JDK17，与固定KMP2.2.21和Gradle8.14组合实际编译；[AGP兼容说明](https://developer.android.com/build/releases/agp-8-10-0-release-notes)与[KMP兼容表](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)为依据。Android模块通过enableAndroidTargets显式启用，不给commonMain引入平台依赖。iOS同一core导出NoteCanvasPrototype framework，由SwiftUI列表和UIKit画布使用；本机Xcode27高于该KMP兼容表所列版本，必须单列实际编译/运行结果，不能推断正式支持。

## 预览数据与保存

NCPREVIEW v1是独立、可拒绝未知版本的文本预览协议，保存docId/title、稳定笔画ID、颜色/宽度、真实点/pressure/time及完整END。不导入旧格式，不把录音位置当触笔时间，也不宣称它就是正式NoteCore容器。恢复时检查版本、ID、数值、数量、截断和尾随数据；失败保留原文件。

capture生成不可变Scene投影，后台编码/IO，MOVE不编码整个文档。Android App级串行IO和最新完整snapshot合并避免逐笔产生无界保存任务；AtomicFile负责原子替换，成功回调才显示本机已保存；失败内容留在App会话供重试。View关闭不会shutdownNow丢掉已排队保存。title索引是可重建派生文件。iOS使用serial IO与原子文件替换，源代码与实际构建状态单列。

PreviewNotebook内存authority私有且同步，无外部未完成attempt；其内存dedup journal受128条/4MiB声明样本charge约束，满时仅退休已确认epoch、保留原canonical snapshot并重新提交本次明确被拒绝的patch。此限制不适用于D3公开提交/恢复协议：生产异步authority不能这样清空幂等台账。UP若失败，完整Add proposal仍在facade保留，重试前阻止新笔，不静默遗忘。

单页预览限500笔/总20万点/单笔2万点；超限显式提示，当前无翻页或拆分。单笔乱序/非法输入显式取消并提示未保存。redo只在当前会话保留，重开不恢复操作历史。无账号、网络、广告、厂商SDK或存储访问权限，数据留在应用私有目录；卸载会移除本机笔记，已新增[单页备份导出/副本导入](0006-preview-backup-transfer.md)，全库备份与正式迁移仍待实现。

## 验证与后续

共享测试包含11项预览契约（含长笔反复撤销的epoch边界），另加原76项；真实Gradle报告与Android APK+lint构建指纹分开保存。APK使用开发调试签名，不发商店。最终运行报告需匹配APK SHA，记录实际设备/模拟器、创建/书写/撤销重做/强停重开、截图和持久化内容核对；没有实际执行的步骤不得标通过。

原生CPU Path绘制暂由系统管理，不将D3假lease pool生硬套在没有自管GPU buffer的View上。统一关闭协调、生产arena/全局预算、真正tile/索引/几何、SQLite/恢复、未知事务结果和双端真机性能仍是后续门禁。先交付可体验包收集问题，再按证据替换reference实现。
