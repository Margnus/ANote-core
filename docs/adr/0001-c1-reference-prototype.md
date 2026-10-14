# ADR-0001：先建立可执行核心契约参考原型

状态：原型决策，生产架构C1尚未冻结。日期2026-10-14。

采用独立NoteCore工程，canvas-core/commonMain仅依赖Kotlin标准库。当前注册JVM验证入口，Apple Arm64/模拟器Arm64目标按显式属性启用；Android原生模块在输入PoC阶段添加，避免C1前增加AGP/SDK/厂商耦合。

M0原型固定Kotlin 2.2.21、Gradle 8.14、JDK17，兼容依据见[官方表](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)，不是最新生产版本承诺。本机已读到Xcode27.0（27A266a）/SDK27.0；其与KMP的兼容、Native下载与framework编译尚未验证，JVM成功不代表Apple可发布。

场景命令先prepare纯patch，再由宿主内存单写者做baseRevision验证。commandId重复只允许相同完整payload，避免用hash碰撞决定幂等。Receipt明确MEMORY_ONLY；后续共享数据层实现durable commit，不能把此原型当作磁盘保存。

数组在接收时防御复制且不暴露可变原数组；Scene和Patch访问返回集合副本。此方法用于证明所有权，不承诺百万点成本。生产版本应使用版本化分块和结构共享，经过同样conformance后替换reference策略。

缓存只证明resident-byte淘汰和键一致性，不拥有GPU对象、不处理lease/dispose。presentation gate只证明修订/generation匹配，不证明平台线程或surface生命周期已经实现。

C1冻结前允许调整API、数值界限、后端、数据布局和工具链；每次保留反例与语义测试，避免为了优化重新引入弱内容比较、回调读当前层或全局状态。

原型TransformStroke的transform是完整的local-to-scene矩阵替换，不是增量；调用方需明确用then组合拖动delta。原始点不被变换重写，expectedObjectRevision防止基于过期矩阵覆盖另一修改。生产工具的手势采样/命令组合在D3/D4继续验证。
