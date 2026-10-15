# M-2D / M-3B：按需分段与同笔覆盖合并

2026-10-15，DEV-030。接续精确直线段简化，新增ChunkedBrushPoc：按可见范围选择笔迹分段、逐段生成几何、合并子像素覆盖，最后一次应用笔刷透明度。它是带明确容量和工作量上限的CPU参考实现，不是最终RenderPlanner、缓存或原生GPU后端；未接入App三笔刷。

## 数据和身份

catalog持有一份不可变StrokeObject引用，没有另一份规范Scene或写入器。默认每128条原始线段分一段，相邻段共享一个端点；每条原始边恰好由一段覆盖，单点也能生成。分段不修改真实样本或文件。每段保存保守painted bounds，用整笔最大半径扩张原始点范围，再做完整仿射变换；压力笔可产生多余候选，但不以较小宽度漏掉覆盖。STR索引只查询这些可重建边界，不能作为持久化正文。

结果绑定GeometryCacheKey、捕获的sceneRevision、FrameToken、ownerLayer、tile世界坐标/分辨率/子采样数、几何容差、分段大小与独立ruleId。内容revision不匹配则拒绝生成；不同页面的layer不能复用，发布须同时核对FramePublicationGate与expectedLayer。会话/视口更新不会改内容版本，迟到结果不能重新发布。

当前内容绑定保守：任一sceneRevision变化均需要新catalog，尚未实现“验证对象未变后复用索引”的结构共享。不能把它当作每笔新增时都会高效更新全页的生产实现。

## 分段不重复加深

每个tile属于**一笔**，用1×1、2×2或4×4固定世界采样网格保存每像素Int位集合。分段轮廓逐个计算覆盖，以位OR合并，重复端点、回折、自交不会叠加同一子采样。所有候选完成后，才按覆盖率乘一次原始sourceAlpha；不同笔画仍按规范z序独立source-over。不能逐段画半透明路径来代替这个union。

相邻tile沿同一世界子像素网格取样，测试逐位核对接缝。一个tile的所有子采样都已覆盖时，可以安全停止剩余分段工作，因为同笔union已不可能改变；这不是丢弃规范采样或已接受命令。

此覆盖规则是明确的参考AA，不等于Android Canvas/CoreGraphics的真实AA算法，也没有验证GPU双线性采样边缘/gutter或颜色空间。掩码只含覆盖和alpha，不把它当完整页面合成图。

## 资源和失败

- catalog默认最多200,000原始点、4,096段、128KiB bounds数组；索引节点/对象数受段数约束，但实际索引heap没有测量。
- tile最多128×128像素；默认每次1MiB派生工作载荷、128个候选段、20,000,000轮廓/边计算步。mask、候选ID、投影双副本、计划与当前路径按逻辑载荷计费；数组分配前准入，逐段用完，不持有全笔几何。
- 输入已由文档拥有的采样、catalog/索引、对象头、GC未回收载荷及后续原生Path/mask/纹理不在job逻辑值内。它不是实际heap峰值，更不是全局resident+in-flight+retired预算。
- 超过候选、工作或字节预算明确Rejected；取消可在扫描、生成、光栅中发生，只返回Cancelled，不发布半张mask。源笔迹完整保留，调用方可保留上一帧并重调度，不能静默隐藏笔迹。

下一步若缓存mask，必须接入已有单owner字节缓存/资源责任模型，计入被读者持有的退休结果；不能仅限制map大小。当前尚未添加全局cache或异步调度器，避免把局部上限宣传成App总内存上限。

## 已执行正确性验证

8项新增common测试覆盖每条原始边、共享端点和单点，索引对暴力bbox，变压/反射/斜切的原始oracle，荧光笔多次回折后仍只应用一次alpha，视口/页面/迟到结果，预算/光栅中取消，完全覆盖早停，以及相邻tile的逐位接缝。

标准Gradle当前246项/28 suites通过。新增独立canvas-core测试APK，只包含测试宿主，不改App SDK公开接口或读取笔记；Android ART实际执行26项共同笔刷/简化/分段/mask测试通过。iOS同样实际运行26项Kotlin/Native测试通过。两端运行均无需Mac解锁，分别见[Android](../reports/android-brush-common-tests.json)、[Apple](../reports/apple-brush-common-tests.json)；没有把这两组相同测试加到JVM数字里宣称平台总分。

DEV-028六组原生Canvas/CoreGraphics离屏图像报告仍只覆盖当时的整笔轮廓消费者；新分段掩码尚未验证其原生纹理/图像消费。普通预览包也保持DEV-028，App仍仅MONOLINE。

## S9局部生成成本

[机器报告](../reports/chunked-brush-costs.json)从hash核验的S9十万点样本建立782段catalog，测试每笔8个64×32、2×2子采样窗口。每个窗口命中并生成4段，累计720个稳定原始oracle探针通过；目录查询另对全部段暴力扫描一致。

| 笔刷 | 整笔简化路径顶点 | 每窗口生成顶点 | 整笔路径逻辑字节 | 每窗口job最大逻辑工作字节 |
|---|---:|---:|---:|---:|
| MONOLINE | 193,746 | 1,100 | 3,149,936 | 20,924 |
| PRESSURE | 287,500 | 1,544 | 4,675,004 | 23,180 |
| HIGHLIGHTER | 193,746 | 1,100 | 3,149,936 | 20,924 |

每笔另驻留25,024字节bounds载荷和54个STR节点，原始100,000点始终保留。表格不是实际heap比较，也没有把整笔可缓存路径与CPU重算tile的时钟当成产品加速倍数。目录初建要扫描原始点，热缓存/更新成本仍待评估。大范围一次命中782段时默认返回candidate-budget，不截断到128段后冒充完整结果。

## S2索引的收益与退化

同一S2百万点数据、宽10保守边界，比较10,000条整笔bounds与按32条线段分成40,000条片段bounds；这是全页**边界索引实验**，尚无全页renderer/authority。202次查询都对暴力bbox一致。

- 200个4×8局部查询：候选输入点总数1,872,100→1,097,229（含重复边界点），减少41.4%。它是候选点读取工作量，不是套索精确命中或真实帧耗时。
- 整页查询：1,000,000→1,030,000点，增加3%；结果条目10,000→40,000。
- 索引节点669→2,668，约4倍。没有测量实际heap，不能忽略这些驻留与更新代价。

因此优先评估“一级整笔粗筛→可见笔迹按需展开二级分段→有界mask/cache”，而不是直接把所有笔迹永久铺成全页分段树。选择阈值、重建/增量策略、实际堆和原生资源仍需测量，C1暂不冻结；当前物理模块仍为shared-core→canvas-core。

## 重跑和下一交付

```bash
bash ci/verify.sh
python3 tools/verify_android_brush_common.py --serial emulator-5580 --adb /path/to/adb
python3 tools/verify_apple_brush_common.py --device <专用模拟器UDID>
python3 tools/verify_offline_jvm.py --measure-chunks --native-library
```

按已有构建说明设置JDK17、Android SDK、工程Gradle/Konan缓存及可选KOTLIN_NATIVE_HOME。最后一项可重跑独立桌面测量/KLIB；它不替代标准Gradle或原生运行证据。

后续先补mask的原生图像/纹理消费、颜色/AA与gutter差分，再建立跨viewport复用但内容/页面严格隔离的有界缓存及读者责任。用真实视口轨迹测命中/重建、全局工作载荷与原生驻留，之后再选最终后端与细化模块，不增设空模块或提前批量生产实现。M-1真机、App三笔刷、原生AA边缘、实际heap/GPU与完整P0仍未验收；Mac锁屏相关交互按用户要求延期。

自动审批在系统提示的重试时间后已恢复；本轮正常fetch确认两个远端没有并发新提交，按原授权在关键节点正常推送，不使用绕过通道。
