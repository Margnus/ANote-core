# Apple候选CI验证

2026-10-30，起点Core14ce97c/蓝图1d4eb7a，两仓fetch/pull --ff-only后无远端新增。当前113条仍为59部分实现/54未开发，整项验收0；本机Windows不能运行Xcode，已有Android模拟器/主机结果不替代Apple或物理设备。

交付卡：利用公开仓库现有GitHub连接与标准托管arm64 macOS runner验证当前候选，减少对原Mac环境的依赖。范围为当前canvas/shared Native测试、当前framework及Swift App-host XCTest；独立临时simulator，不改变原固定UDID脚本、原Mac/App文件或历史报告，不采用物理M-1/普通UI结论。

采用macos-15、固定已安装Xcode16.4路径和JDK17，使用仓库原Gradle/Kotlin版本。外部Actions固定到核对过的commit，权限仅contents:read，不请求secrets，不发布App或release；上传仅新CI报告/文本日志，14天保留。Native预期用实际源码发现，目前canvas211/shared306；XCTest沿原49项完整名单。任何编译/数量/跳过/来源不一致均失败，不以CI绿灯之外的意图记通过。

入口`.github/workflows/apple-candidate.yml`与`ci/verify_apple_candidate.py`，总限时45分钟、单阶段亦有限时。CI记录commit、Xcode/系统/设备、编译输入/框架与测试二进制哈希、Native原始日志和XCTest实际summary/tree；失败也保留阶段和错误。原报告保留其历史绑定，产物仅build/apple-candidate，未写回reports/旧iOS报告。

实际结果：固定提交c20ad15a60f4fc1173c389f3af6f2d6a776abe62的[运行40993095695](https://github.com/Margnus/ANote-core/actions/runs/40993095695)成功；macOS15.7.9/Xcode16.4 arm64、新建iPad Pro 11-inch M4/iOS18.5 simulator，canvas211/shared306 Native及49项Swift App-host全部通过，XCTest失败/跳过/预期失败均0。下载产物ZIP SHA256 b64b91d089fc54a216ea3e218936e92903805de1c5f3080b5d88218b5dd3b38a与GitHub记录一致；155个编译输入逐一对比固定Git提交，7份日志/结果逐一对比报告哈希一致。[报告与原始日志](../reports/apple-candidate-ci-40993095695/report.json)已留存，避免14天远端产物过期丢失。采用当前Apple编译/原生/App-host子范围，原历史Mac报告不重绑定。物理M-1、普通交互/真实触笔/heap和完整C1继续待验；不因增加CI而放行生产SQLite或113条验收。

Runner依据：[GitHub标准托管runner](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)、[macOS15 arm64镜像清单](https://github.com/actions/runner-images/blob/main/images/macos/macos-15-arm64-Readme.md)。实际运行环境以CI报告为准，不把镜像文档当执行证据。


2026-10-30下一交付卡（计划A）：在同一候选CI新增独立XCUITest target，仅操作原App普通界面。验收两页各实际手指拖动、撤销/重做后的画布笔数、页隔离、缩放按钮、Home/恢复、保存返回列表重开、确认保存后terminate/launch重开，独立读取保留实际ncp文件及截图。无启动注入/模型调用/测试专用数据路径；新增控件identifier仅用于稳定定位。49项App-host仍单独完整执行；新增UI结果单列，失败不放宽断言。独立simulator与预览格式子范围不代表Pencil、在途写杀进程、物理性能或完整C1。实际结果待新运行。


### 当前普通UI实际结果（运行40999919318）

固定4b9039f0d35c13b8369916012362b6a34d319008在新iPad Pro 11-inch M4/iOS18.5 simulator通过原211/306 Native及49项App-host，并由独立XCUITest进程完成两页三笔、撤销/重做、页隔离、缩放按钮、Home/恢复、保存返回列表重开及确认写后terminate/launch重开。UI 1个完整操作链，无失败/跳过；[实际报告](../reports/apple-ui-ci-40999919318/report.json)、实际ordinary-ui-note.ncp和4张截图/manifest已留存。四图已查看：第一页两笔、第二页放大一笔、保存三笔、进程重开后第二页一笔均对应。

156项源码/构建输入与固定Git对象逐一匹配，16项报告附属产物哈希一致；远端ZIP SHA256 3eeb3c2f40eabba6151b7007b054c713f210c09e6ccb77517b5cd651916f6bbc一致。实际文件SHA256 1ee74c4e8f808296572e9537b5dbba494e25f76dfb229e87b17d9957b312afbf；两页正文分属2笔/1笔，均为多点实际拖动采样。新容器测试前PreviewNotes为空，原文件保持断言没有非空样本，不能据此声称Apple旧笔记保持；原Mac/Android已有文件报告不重绑定。只采用当前候选模拟手指/普通App界面及确认保存后恢复子范围，Pencil/多指/旋转、在途IO终止、物理延迟/heap、完整C1/生产存储和113条原验收仍待。

本操作链按上述范围收口，后续不重复增加相同界面happy path。下一项按DEV-039现有真实PDF样本补Apple元数据/原生渲染能力与坐标接缝，决定双端支持/拒绝边界，不接生产App或引入新PDF SDK。


### 原只读公开入口当前候选（运行41003459044）

固定6b1c155/独立macOS15.8.2/Xcode16.4/iOS18.5 simulator实际canvas211/shared306 Native、50项Swift App-host（新增公开模式的直接编辑/输入拒绝、活动前缀、退休拒绝）与原普通UI链1项全通过。156项编译输入与固定Git对象、16份附属产物与报告、ZIP SHA256 329005bc786f5fe401222b4b91291529bcef127c886c18bb8b7346318f0aa6e9逐项一致；4截图已查看，实际两页2+1笔文件留存。见[报告](../reports/apple-readonly-ci-41003459044/report.json)、[采用回执](../reports/sketch-readonly/apple-verification.json)。原Android/JVM报告不重绑定，新增公开模式子范围收口；物理M-1/完整C1/稳定SDK与全部113条验收未关闭。
