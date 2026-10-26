# M-3：密集页面空间索引实验

## 2026-10-26 原索引canonical点命中参考出口

同一IncrementalSpatialIndexPoc/STR/overlay增加createCanonical、advanceCanonical与queryPoint。entry仅增加可选ObjectId/objectRevision/LayerId元数据；构建通过原BrushEnvelopePoc验证全部规范对象，未支持版本（包括查询区域外对象）明确拒绝，泛型bbox更新撤销规范绑定资格，压实只保留原资格。更新消费原SceneReducer实际before/after/patch，不构造另一scene/authority；changeCount先拒绝超批次，样本工作/metadata/overlay失败不发布部分状态，压实后原patch可重试。

点查询按scene/revision/session验证，原粗筛再用原版本1浮点oracle几何过滤并限定层；entry/node/candidate/sourceSamples/hits各自受限，拒绝/取消无命中前缀，结果仅ID/stamp/计数且无z序。owner仍负责稳定handle、原worker准入和最终当前stamp核对。完整未改对象引用验证是O(N)，由maxObjectTests前检；绑定元数据与临时oracle数组须计入未来原owner总预算，记录/样本额度不证明物理heap或生产增量性能，当前不冻结grid/STR或稳定SDK。

[当前报告](../reports/canonical-point-query.json)：原类13项（新增4项）、完整427 JVM及API35原ART139通过，包括真实规范移动/删除/恢复patch、压实重试、旧结果/新session、假阳性/三笔/压力/跨层、未知版本/handle/各级预算/取消与伪造after对象拒绝，原1500轮随机bbox/路径/mask/图像/执行器回归保持。只安装独立库测试APK3a0bd2e9…，原普通App82eafb0c…与本机原1份笔记先备份且前后hash/集合保持。无本轮普通App索引接入/重建/UI、Apple Native或真机，Apple入口123（common117）仅准备未运行。审计4匹配/28失效/5历史漂移仍failed；C1/M-1/完整预算/public/owner/IO断电与DEV-041门禁保持，不将几何点过滤写成套索/擦除产品验收。

2026-10-15，DEV-026。这是选择架构的实验，不接入预览渲染、不冻结公共API。实现位于canvas-core的internal prototype；初始实验仅使用页坐标AABB与整数ID；2026-10-26增加上述规范身份绑定元数据，仍不持有文档authority、笔迹对象/采样或页面选择状态。

## DEV-040A增量（2026-10-17）

原DEV-026测量结论保留其来源范围。当前在原STR上补齐有界replacement/tombstone overlay、完整批次CAS/预算拒绝、完整或拒绝的查询及压实取消/迟到发布契约；实现为internal IncrementalSpatialIndexPoc，不复制规范笔迹/另设Scene。JVM、ART与Kotlin/Native实际运行新9项共同测试，包括原SceneReducer移动/删除/撤销patch、1,500轮随机编辑暴力bbox对照、全页退化、旧位置遮蔽和超限原实例保持。另将原6项索引测试纳入双端库回归。

采用的是增量遮蔽/失败契约候选；并未选择生产grid/STR、完成App索引适配或测得heap/延迟。对象/overlay/批次上限和tree node、entry test、result预算显式区分；压实排序不可中途取消，原owner需准入旧/新实例及在途读者，结果按当前content stamp再核对。查询ID仍无z序且仅bbox粗筛。报告及源码/二进制绑定见[增量索引证据](../reports/incremental-spatial-index.json)，决策与剩余门禁仍维护于原C1和ADR-0020。

## 可重跑入口

在配置JDK17、GRADLE_USER_HOME与PYTHON_BIN后运行：

```bash
./gradlew --no-daemon --console=plain :canvas-core:runSpatialIndexPoc
```

任务先按现有manifest生成并核验S2二进制文件，从真实文件读取10,000笔×100点，用每笔所有点的包围盒外扩1场景单位模拟MONOLINE宽2的identity覆盖范围。其余三组是直接生成的10,000个AABB：规则100×100分布、同一巨大包围盒、规则分布加100个超大对象。巨大包围盒模拟倾斜长线的宽松边界风险，**没有构造或测量真实倾斜线几何**。没有偷用预览格式的5,000对象/200,000点上限截断S2。

[机器报告](../reports/spatial-index-poc.json)记录fixture原始SHA、源码UTF-8/LF指纹、JDK/OS/架构、参数、每查询候选检查数、逻辑引用数和时钟结果。每组202次查询含局部、全范围、远端空查询；移动前100个对象后再逐查询核对暴力扫描。三轮预热、五轮测量，记录min/median/max，不设置时间通过阈值。测量顺序固定、单进程共享JIT/GC，尚非JMH统计实验；暴力时钟基线仅count，索引返回Set，分配成本不同，不能直接把时间比值写成产品加速倍数。

## 两种候选

- 网格：cell=32，每对象/查询最多256格；去重后再测AABB。超大范围、不可表示cell坐标走overflow或全量扫描，不截断坐标、不丢候选。put/remove支持单对象更新，移除空bucket。引用数包含复制入bucket的引用与overflow；它不是heap字节。
- STR-packed R-tree：fanout=16，每层按x切片、y分组。不可变快照，重复ID拒绝；批量100次移动按重建10,000项测量。尚无动态插入、删除、增量树、并发发布或重建背压。

查询采用闭区间相交，点/边界接触算命中。返回ID无顺序，不能直接作为绘制顺序；调用方仍须用规范根的z序。entryTests记录实际对象AABB测试数；node/cell计数是两种不同工作量，不能相加直接比较。AABB命中还需要后续精确几何判定，不能叫作套索/擦除完成。

## 本机结果与含义

Mac ARM64/JDK17；全部4组更新前后均与暴力扫描一致。下表为202次查询累计对象AABB测试数，暴力扫描每组2,020,000次。

| 数据集 | 网格 | STR树 | AABB命中数 |
|---|---:|---:|---:|
| S2百万点笔迹子集 | 87,342 | 84,432 | 48,390 |
| 规则100×100 | 20,793 | 14,352 | 10,596 |
| 同一巨大包围盒 | 2,020,000 | 210,000 | 210,000 |
| 混合100个超大对象 | 40,786 | 36,736 | 30,590 |

DEV-026首轮S2的202查询中位耗时网格约3.73ms、树约1.41ms；移动100对象网格约0.031ms、树全重建约0.877ms（首轮报告保存在eac8fea提交）。后续因构建输入变化重跑的数值见当前机器报告，桌面时钟不设通过阈值。巨大包围盒组中网格所有对象都在overflow，对空查询也扫描全量；树能裁掉远端查询，但重叠区域两者仍必须输出10,000项。不会因为树累计数较低就宣称解决了重叠退化。

## 决策与退出条件

保持索引为规范根之外的派生数据：未来发布绑定DocumentId/PageId、根revision、几何算法版本及变换，过期结果不能写入新页；这些生产集成尚未实现。当前两个实现仅单owner实验，不对外导出或接入交互。

1. 下一交付M-2先实现共同笔刷几何及保守painted bounds。当前试验AABB不代替PRESSURE/HIGHLIGHTER、非均匀变换的实际覆盖范围。
2. 再比较静态STR树加小增量覆盖层（替换/删除须遮蔽旧条目）、分段笔迹索引与网格overflow分层。用独立oracle验证再测成本；不立即引入维护两套规范Scene的缓存。
3. 补真实倾斜长线与精确命中、混排对象、内存分配/峰值与移动端基准；给出全页查询输出本身的上界。由此再选结构与重建策略，才修订RenderPlanner/模块边界。
4. M-1真机物理基线仍待设备；本次没有Android/iOS性能、触笔到光子、帧稳定性、实际heap/GPU或C1验收结论。Mac锁屏依赖的UI验证继续延期。

六项commonTest覆盖负坐标/闭边界/零面积、超大与不可表示cell、替换删除/快照隔离、500轮固定seed随机编辑、10,000项重叠退化以及非法参数/重复ID/空集。共享测试219项通过。Android与Apple目标编译结果见当前进度；已有安装包仍是DEV-025宿主交付，本PoC未集成到App，不沿用旧包的运行报告声称已运行本算法。
