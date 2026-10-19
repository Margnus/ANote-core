# ADR-0022：候选2原生轮廓适配与坐标预检

日期：2026-10-19。状态：DEV-027独立后端PoC；未启用到算法1预览UI/codec，M-2未整体验收。

## 坐标与拒绝

BrushPathPlanner读取不可变BrushGeometry，把对象与视口预先组合的sceneToTarget作用到几何；先在Double中合并origin与平移，再转换目标坐标。FLOAT32用于Android Path及跨端对照，FLOAT64可供CoreGraphics单独实验。plan保留规范几何与变换，不复制全份坐标数组，不修改正文或revision。

原生路径分配前检查顶点预算、可逆变换、有限坐标、量化误差、相邻顶点塌缩与轮廓朝向/面积。FLOAT32每坐标误差上限1/1024目标单位；调用方须将目标定义为绘制表面的像素坐标，或在非单位CTM下另行计算实际像素误差。超限返回类型化拒绝，不自动转另一种精度或加粗/删点。输出顶点最多400,000；native Path真实内存、GPU资源与销毁预算尚未测量，计数上限不等于内存达标。

## 平台适配

- Android NativeBrushRenderer只接收已通过预检的FLOAT32 plan。全部轮廓进入一个私有WINDING Path，私有Paint设FILL、抗锯齿和SRC_OVER；每笔只drawPath一次，不向外泄漏可变Path/Paint。
- Swift NativeBrushRenderer使用同一plan生成私有CGPath，全部轮廓一次fillPath(using: .winding)，显式normal混合及plan色。draw前保存CG状态与当前path，结束后分别恢复；CG状态保存本身不能代替当前path保存。本轮Swift实现和4项宿主测试为待Mac编译/执行的源码，不宣称通过。
- 目标坐标已经包含传入变换，宿主不得再次应用同一对象/视口矩阵。Standalone后端不接当前InkCanvasView/InkUIView的算法1编辑入口，不使候选2进入原预览文件。

原生语义依据：[Android Canvas](https://developer.android.com/reference/android/graphics/Canvas)、[SRC_OVER](https://developer.android.com/reference/android/graphics/PorterDuff.Mode)、[CoreGraphics fillPath](https://developer.apple.com/documentation/coregraphics/cgcontext/fillpath(using:))。这些API说明不代表项目代码已在两端验证。

## Android可执行验证

新增固定Robolectric4.17，仅testImplementation；其AndroidX monitor/espresso依赖需要android.useAndroidX=true，没有给生产App新增AndroidX依赖，也不升级AGP/Kotlin。使用[GraphicsMode.NATIVE](https://robolectric.org/javadoc/4.17/org/robolectric/annotation/GraphicsMode.Mode.html)驱动宿主原生Android图形库：这是Windows JVM下的native Skia离屏像素，**不是Android模拟器、ART、硬件Canvas或触笔证据**。

API35模型固定为android-all-instrumented:15-robolectric-13954326-i7，由独立Gradle configuration解析、Copy到忽略的项目目录，Test声明它为输入并按[官方离线配置](https://robolectric.org/configuring/)使用，不修改共享Maven/SDK。该模型JAR纳入验证stamp。当前网络对大文件完整GET出现停滞，本机通过官方Maven Range下载并核验SHA-256/SHA-1补入项目缓存；不改仓库列表、不换依赖版本。

7项测试覆盖：同笔折返alpha96/两笔alpha156、压力端点宽度、等宽笔忽略压力、反射仿射/大原点、拒绝FLOAT64静默转换、笔刷状态隔离，以及6组固定顶点独立Path对照与PNG输出。Gradle声明fixture内容输入、各Test任务独立图像输出；verifyNativeBrush绑定源码/构建/测试XML/PNG指纹，收集器拒绝过期结果。真实结果见进度及reports/android-brush-native.json，不手写测试计数或图片证据。

本轮重新发现可用的C盘Android36 SDK；此前受沙箱可见性限制，仅检查默认D盘SDK并误将platform不可用泛化为本机无平台。使用当前进程ANDROID_HOME选择已有C盘SDK，不改共享SDK配置。Android包/测试编译、lint与native像素分别记录，不沿用历史APK运行报告。

## 下一门禁

Mac重新构建framework/App并跑12项宿主测试（原8项+候选后端4项），再用相同几何做真实双端图像/颜色空间/透明度比较。原生触摸、系统选择器、强停重开、GPU/实际内存、16边近似质量与增量尾段性能保持独立门禁，未具备这些证据前不打开候选2工具或修改预览格式。
