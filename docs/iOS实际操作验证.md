# UI-001：iOS 实际操作与分页导航修复

2026-10-15。用户确认 Mac 已解锁后，通过 Device Hub（`com.apple.dt.Devices`）操作专用 NoteCorePreview iOS 27 / iPhone 17 模拟器。所有点击、拖动和 App Switcher 操作走 CUA；文件核对为只读。保留原笔记、测试笔记与备份，不卸载或清空数据。

## 原包实际流程

原 DEV-028 包 SHA `eeb0dbc5346a9c2d7c92aa33e1c8d5bdfac7f7289fe8f8a6546786e688da307b` 完成：新建/重命名、真实拖动落笔、复制页、空页插入、撤销/重做 1→0→1、手指关闭拒绝书写、缩放按钮后书写、保存退出重开三页、系统文件导出/导入、App Switcher 强制关闭并从桌面重启。三页最终为 9/9/2 笔；期间有人机共同输入，不作为受控性能轨迹。

系统导出到本机文件夹 NoteCore-UI-Backup，文件 SHA 与源文件完全相同。导入为新文档，document/page/layer/object 身份全部 fresh，页序/尺寸/纸张/笔刷/变换/规范点归一后相等；原件保留。记录见 [原包 UI 报告](../reports/ios-ui-DEV028.json)。本地保留原包 `dist/NoteCore-0.1.0-preview-ios-simulator-arm64-DEV028.app.zip`，不提交用户笔迹内容。

## 问题与修复

实际操作发现：原横向工具栏把上一页、页码、下一页与其他动作一起滚动，使用末端的纸张菜单后看不到分页导航。SwiftUI 改为固定导航与独立滚动动作区，翻页箭头提供 44×44 目标和中文辅助标签，页码显式暴露 accessibilityValue。仍使用同一分页 owner，不变更文件格式或渲染后端。

修复后重新构建共享 framework 和普通 App。最新模拟器 ZIP SHA `d8e4a90147c0939c2c7b4115a0ffa6429e845f85dbfa72b30621a3960c26b408`，1,596,891 字节。9 项真实 iOS app-host XCTest 通过；随后恢复无测试插件的普通包，7 项安装/全部 payload 匹配/启动检查通过。见 [构建](../reports/ios-preview-build.json)、[宿主](../reports/ios-host-tests.json)、[运行](../reports/ios-runtime-smoke.json)。

新版实际回归确认：升级后原三份笔记保留，切页显示 1/3→2/3→3/3 与 9/9/2 笔，首尾按钮禁用正确；动作区自动滚到纸张菜单末端时导航仍可见，打开并取消菜单不编辑内容；再次强制关闭、桌面重启、打开三页均保持。只读核对源文件与导入文件 SHA 均与升级前一致。见 [新版 UI 回归](../reports/ios-ui-regression.json)。新版没有重复整个系统选择器流程，原包的完整流程证据不得改写成新版全流程已重跑。

## 后续门禁

此前受锁屏阻挡的模拟器书写/备份/正常保存后强停重开子集已有实际证据。物理 Apple Pencil 压力/延迟、防误触、多指捏合、转屏/平板布局、低内存/断电和 SQLite 生产恢复仍未验收。强停已保存文档不证明未落盘内容可恢复。模拟器 ZIP 不是可安装 iPhone 的 IPA。

DEV-034 继续按既有计划复用 Swift 有界执行器与唯一资源池，完成平台图像持有/未知释放/owner 丢失交接和固定视口 trace；不因 UI 补验放开三笔刷 App 后端或提前冻结 C1。
