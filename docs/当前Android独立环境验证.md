# 当前Android独立环境验证

## 2026-10-29：真实选择器期间Activity配置重建

本轮沿有限C1原picker消费者，起点7df23db，验收卡目标为真实系统选择器前台期间原Activity实际销毁重建后，导入/导出的成功结果与取消仍正确消费，取消后可再请求，既有文件保持。Manifest自行处理orientation/screenSize等，故不把旋转称Activity重建；改用系统font_scale 1.0→1.1→1.0→1.1→1.0，结束恢复原设置。未修改App或使用Instrumentation调用recreate。

独立API35 AVD普通PID3244保持，实际完成导入取消→再次导入成功→导出成功→导出取消四个子场景。每次系统events都记录MainActivity的wm_relaunch_resume_activity（fontScale配置位40000000）、onDestroy、onCreate，随后onActivityResult；明确证明同进程Activity重建后结果交付。导入取消后系统picker可重新打开；第二次选择有效源仅增加一份两笔副本。导出成功落到Downloads的文件与该副本字节完全相同；导出取消重建后再次请求亦可打开picker并正常取消。原三份笔记所有字节保持，第四份新副本及导出文件保留，字体设置恢复1.0。见[报告](../reports/current-api35-picker-recreation.json)与原始系统事件/操作/截图；入口`tools/verify_current_android_picker_recreation.py`只核验保留证据。

8项观察复核和可移植性检查通过，采用原Store会话/单次结果消费的当前Android真实配置重建子范围。没有重跑未修改的共同/主机/仪器检查。进程死亡、系统权限撤回、完整资源备份、Apple/物理M-1/完整C1仍待，配置重建不能替代这些验收。下一步针对已保存原文件，核查picker前台期间后台App进程被系统终止后的旧结果拒绝与新会话恢复，不把该验证称IO中途崩溃或完整owner丢失收口。

## 2026-10-29：真实Downloads Provider交换与拒绝后恢复

本轮沿DEV-036/040有限C1原消费者矩阵，起点f5d9c4b；验证卡目标为现有预览正文经真实系统选择器导出→导入副本→损坏输入拒绝→有效输入重试。验收要求是原文件及Provider源字节保持、完整笔迹内容保持、副本身份独立、失败不生成可见半文档，使用同一普通App/原Store-worker完成。不新增业务实现、格式、Provider替身或生产资源备份。

NoteCoreCurrent35/5590普通PID3244、当前绑定APK实际完成Downloads CREATE_DOCUMENT导出及OPEN_DOCUMENT导入。导出与原笔记字节完全一致；第一次导入得到新文档，两笔的几何/笔刷/采样点原样保持，文档/页面/图层/笔迹身份重新分配，初始revision/epoch按原复制契约重置。随后将导出前40字节作为独立损坏测试源送入Downloads并经实际选择器选择，UI明确提示“笔记格式无效或版本不支持，原文件已保留”，原两个文件集合及字节保持。可重开第一份副本，随后第二次主动选择有效备份成功导入另一份副本；文件集合观察为1→2→2→2→3，两个合法导入均为明确的独立系统选择操作。最终三份笔记及有效/损坏Provider源均保留。见[报告](../reports/current-api35-provider.json)、[原始观察](../reports/current-api35-provider/actions.jsonl)，验证入口`tools/verify_current_android_provider.py`只核验保留证据，不发送输入。

采用当前AOSP Downloads Provider的普通预览交换/typed格式拒绝/同owner恢复子范围。未注入Provider权限撤回/写出故障，不证明完整资源备份、跨端往返、选择器期间Activity/进程死或Apple/M-1/C1。初次保留证据校验器把UI XML读取混入Provider源读取计数，已限定Downloads路径后通过；业务失败结果未修改。下一步核查真实picker配置重建后的匹配结果/取消责任，继续原会话与不复用请求号，不扩资源生产事务。

2026-10-29，沿DEV-036/040现行有限C1消费者验证，起点Core 07d9e92。

用户已明确允许新建独立测试AVD；原NoteCoreGui35/5584不存在的历史核查保持原范围。新建NoteCoreCurrent35/5590，API35 AOSP x86_64，与原保存环境分开，不继承原验收。

本轮目标是以当前源码绑定APK补实际ART下原authority身份拒绝/修正重开、原View输入、Store持久化与owner退休证据。新增身份检查复用原title_contract入口、原MemoryDocumentAuthority与PreviewFileIO；测试临时文件仅在唯一cache目录，既有正式笔记集合及字节须保持。先核对官方下载校验和，再建立独立AVD、安装当前App与仪器包、检查已安装APK SHA-256，运行有限四组仪器检查。普通UI须单独通过系统输入和独立PID验证，仪器通过不自动证明普通UI。

验收要求：每组实际运行返回passed和成功结束码，观测集合完整，日志与源/产物指纹记录；失败保留原始输出，不晋升结论。物理触笔/延迟/heap、当前Apple、真实Provider、完整C1及生产SQLite前置仍待，全部113条总目标保持。

运行入口：`tools/verify_current_android_runtime.py --adb <本机adb绝对路径>`；目标固定为独立NoteCoreCurrent35/emulator-5590，报告`reports/current-api35-runtime.json`，不会覆盖历史运行报告。环境准备与实际结果在完成后补记。

## 实际结果

官方镜像/CLI校验通过；WHPX可用，SwiftShader下新API35 AVD实际启动。当前源码App/仪器包构建通过，96项输入指纹逐项核对，已安装App SHA-256与本地APK一致。四组ART检查通过：title/identity 6、input 10、storage 11、owner 7，共34项；正式文件集合保持为空。见[运行报告](../reports/current-api35-runtime.json)、[独立环境](../reports/current-api35-environment.json)，四份原始日志保存在reports/current-api35-instrumentation。

随后独立普通PID2590创建Current35-20261029，系统输入写线/点、撤销/重做、保存退出重开、Home/前台均通过；真实OPEN_DOCUMENT/CREATE_DOCUMENT分别打开并取消，取消后返回原App。完成写入后force-stop，新PID3244重开两笔，文件SHA-256 29475acf8cf31c5f9af724d0d318d059c756885c8ee5dd809362d63e7fe04a4a与重做保存后完全一致；点的显示/撤销/恢复像素及文件内容通过保留样本复核。见[普通UI报告](../reports/current-api35-ordinary-ui.json)。首次AOSP键盘通讯录权限已拒绝，意外覆盖层及重试记录保留；仅最终观测支持成功结论。测试笔记和AVD数据保留。

采用此独立环境及当前原入口修复的Android运行子范围。未验证完整Provider文件交换、选择器期间旋转/进程死、写入中途杀进程或断电；旧AVD/Apple/物理M-1/完整C1不继承本次通过。已有559共同/30主机结果沿前一修复报告，不重新相加。下一步沿真实系统Provider验证原导出→导入副本及失败保留，仍复用原owner/worker/file，不扩生产存储或新格式。
