# 当前Android独立环境验证

2026-10-29，沿DEV-036/040现行有限C1消费者验证，起点Core 07d9e92。

用户已明确允许新建独立测试AVD；原NoteCoreGui35/5584不存在的历史核查保持原范围。新建NoteCoreCurrent35/5590，API35 AOSP x86_64，与原保存环境分开，不继承原验收。

本轮目标是以当前源码绑定APK补实际ART下原authority身份拒绝/修正重开、原View输入、Store持久化与owner退休证据。新增身份检查复用原title_contract入口、原MemoryDocumentAuthority与PreviewFileIO；测试临时文件仅在唯一cache目录，既有正式笔记集合及字节须保持。先核对官方下载校验和，再建立独立AVD、安装当前App与仪器包、检查已安装APK SHA-256，运行有限四组仪器检查。普通UI须单独通过系统输入和独立PID验证，仪器通过不自动证明普通UI。

验收要求：每组实际运行返回passed和成功结束码，观测集合完整，日志与源/产物指纹记录；失败保留原始输出，不晋升结论。物理触笔/延迟/heap、当前Apple、真实Provider、完整C1及生产SQLite前置仍待，全部113条总目标保持。

运行入口：`tools/verify_current_android_runtime.py --adb <本机adb绝对路径>`；目标固定为独立NoteCoreCurrent35/emulator-5590，报告`reports/current-api35-runtime.json`，不会覆盖历史运行报告。环境准备与实际结果在完成后补记。

## 实际结果

官方镜像/CLI校验通过；WHPX可用，SwiftShader下新API35 AVD实际启动。当前源码App/仪器包构建通过，96项输入指纹逐项核对，已安装App SHA-256与本地APK一致。四组ART检查通过：title/identity 6、input 10、storage 11、owner 7，共34项；正式文件集合保持为空。见[运行报告](../reports/current-api35-runtime.json)、[独立环境](../reports/current-api35-environment.json)，四份原始日志保存在reports/current-api35-instrumentation。

随后独立普通PID2590创建Current35-20261029，系统输入写线/点、撤销/重做、保存退出重开、Home/前台均通过；真实OPEN_DOCUMENT/CREATE_DOCUMENT分别打开并取消，取消后返回原App。完成写入后force-stop，新PID3244重开两笔，文件SHA-256 29475acf8cf31c5f9af724d0d318d059c756885c8ee5dd809362d63e7fe04a4a与重做保存后完全一致；点的显示/撤销/恢复像素及文件内容通过保留样本复核。见[普通UI报告](../reports/current-api35-ordinary-ui.json)。首次AOSP键盘通讯录权限已拒绝，意外覆盖层及重试记录保留；仅最终观测支持成功结论。测试笔记和AVD数据保留。

采用此独立环境及当前原入口修复的Android运行子范围。未验证完整Provider文件交换、选择器期间旋转/进程死、写入中途杀进程或断电；旧AVD/Apple/物理M-1/完整C1不继承本次通过。已有559共同/30主机结果沿前一修复报告，不重新相加。下一步沿真实系统Provider验证原导出→导入副本及失败保留，仍复用原owner/worker/file，不扩生产存储或新格式。
