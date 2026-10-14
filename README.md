# NoteCore

独立笔记App与通用画布工程，2026-10-14开始实际开发。当前为C0/C1架构验证阶段：已建立KMP工程与可执行公共契约参考原型，尚未实现Android/iOS原生App、画布渲染或磁盘保存。

先读[开发执行计划](docs/开发执行计划.md)、[进度](docs/进度.md)、[原型ADR](docs/adr/0001-c1-reference-prototype.md)。完整需求和整体架构在相邻[项目蓝图](../note-app-blueprint-20261014/README.md)。

## 当前可运行内容

- 平台无关double坐标和矩阵、不可变真实采样、captured scene/layer/tool。
- 纯场景命令和patch，内存单写者的修订/幂等/容量检查；Receipt明确MEMORY_ONLY。
- 迟到frame的revision/generation检查、scene/object/算法版本的几何缓存键。
- 按字节计费的LRU参考策略及重键/淘汰/超额/溢出反例。
- commonTest、独立JVM演示、源码依赖边界和真实测试报告。
- [合成输入轨迹](fixtures/README.md)：5类D2候选及hash，未接入Kotlin/原生消费，不是设备性能结果。

这些是验证架构的reference实现，API可调整；Map复制、内存journal和临时数值范围不代表生产性能或正式文件协议。Native资源lease、worker调度、真实输入、SQLite和完整几何/索引尚未实现。

## 构建与验证

需要JDK17。Gradle8.14和Kotlin2.2.21精确固定；首次执行会下载公开依赖。设置JAVA_HOME为自己的JDK17目录，Gradle User Home建议放本工程目录，不改旧工程缓存/设置。

```bash
export GRADLE_USER_HOME="$PWD/.gradle-user-home"
./gradlew --no-daemon --console=plain verifyPrototype
./gradlew --no-daemon --console=plain :canvas-core:runPrototype
python3 tools/check_architecture.py
python3 tools/collect_verification.py
```

同一入口可用于本地或CI：`bash ci/verify.sh`（需JAVA_HOME，Python可通过PYTHON_BIN指定）。未配置GitHub远程工作流或自动发布。

测试HTML在canvas-core/build/reports/tests/jvmTest/index.html，机器报告在[reports/verification.json](reports/verification.json)。后者由实际Gradle XML生成；代码变化后应重新运行测试再生成报告。

Apple编译入口单独开启：

```bash
./gradlew -PenableAppleTargets=true :canvas-core:linkDebugFrameworkIosSimulatorArm64
```

此入口需兼容的Xcode、Kotlin/Native下载与平台环境；目前未验证，不是可运行的SwiftUI App。本机Apple SDK版本和官方KMP兼容表需重新确认。Android原生模块在D4交付创建，当前不能产出APK。

## 目录

```text
canvas-core/src/commonMain/   # C1契约原型，无平台/DB/网络/业务依赖
canvas-core/src/commonTest/   # 可移植语义反例
canvas-core/src/jvmMain/      # 命令行演示
docs/                       # 实施计划、进度、ADR
tools/                      # 边界校验、测试结果收集
reports/                    # 真实验证记录
```

本次未改旧App/DrawPanel，未提交、推送或发布。D2已开始合成轨迹，下一步补消费/回放与规模样本，再做D3生命周期/背压/lease、D4原生PoC、D5架构冻结。
