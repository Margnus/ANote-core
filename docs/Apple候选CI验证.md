# Apple候选CI验证

2026-10-30，起点Core14ce97c/蓝图1d4eb7a，两仓fetch/pull --ff-only后无远端新增。当前113条仍为59部分实现/54未开发，整项验收0；本机Windows不能运行Xcode，已有Android模拟器/主机结果不替代Apple或物理设备。

交付卡：利用公开仓库现有GitHub连接与标准托管arm64 macOS runner验证当前候选，减少对原Mac环境的依赖。范围为当前canvas/shared Native测试、当前framework及Swift App-host XCTest；独立临时simulator，不改变原固定UDID脚本、原Mac/App文件或历史报告，不采用物理M-1/普通UI结论。

采用macos-15、固定已安装Xcode16.4路径和JDK17，使用仓库原Gradle/Kotlin版本。外部Actions固定到核对过的commit，权限仅contents:read，不请求secrets，不发布App或release；上传仅新CI报告/文本日志，14天保留。Native预期用实际源码发现，目前canvas211/shared306；XCTest沿原49项完整名单。任何编译/数量/跳过/来源不一致均失败，不以CI绿灯之外的意图记通过。

入口`.github/workflows/apple-candidate.yml`与`ci/verify_apple_candidate.py`，总限时45分钟、单阶段亦有限时。CI记录commit、Xcode/系统/设备、编译输入/框架与测试二进制哈希、Native原始日志和XCTest实际summary/tree；失败也保留阶段和错误。原报告保留其历史绑定，产物仅build/apple-candidate，未写回reports/旧iOS报告。

当前状态：本地静态检查后提交触发真实CI，只有实际完成并检查日志/测试结果后才补结论。若runner或依赖获取不可用，记录具体失败，不声称已获得Apple验证。物理M-1、普通交互/真实触笔/heap和完整C1继续待验；不因增加CI而放行生产SQLite或113条验收。

Runner依据：[GitHub标准托管runner](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)、[macOS15 arm64镜像清单](https://github.com/actions/runner-images/blob/main/images/macos/macos-15-arm64-Readme.md)。实际运行环境以CI报告为准，不把镜像文档当执行证据。
