# Android存储owner准入

## 2026-10-29：非法配置拒绝不遗留worker

沿同一C1原owner构造准入，起点8ecce61。验收卡目标是所有现有构造参数拒绝发生在worker创建前；本次具体核查exportPickerByteLimit负数，其为内部可配置预算，非法参数是程序错误，不是用户备份失败或业务typed拒绝。

前一线程准入修复后，原负预算require仍位于BoundedOwnerExecutor初始化之后。新增反例实际失败：构造抛IllegalArgumentException，但新建的NoteCore-preview-storage非daemon线程存活且没有返回Store作为owner。反例仅通过前后Thread对象身份差集识别本次空worker，清理时只终止该无任何提交的测试线程，不触碰已有线程或在途IO；此测试清理不作为生产中断协议。

将原require移入首个init、紧随主线程检查，并移除原晚检查。未创建新验证层/执行器，不改变合法预算、格式、保存或回执。新增1项/默认33项主机零失败/错误/跳过，构建通过，lint0错误8警告；原ART owner入口新增同一非法预算拒绝且线程差集为空/目录未创建，9项实际通过。当前App/仪器APK安装指纹匹配，五份正式文件升级/测试/恢复普通进程前后字节保持。见[报告](../reports/store-owner-configuration.json)。采用原构造拒绝资源泄漏修复并收口；共享源码未改，不重复559共同结果。

这只关闭已复现的初始化失败后遗留线程，不提供完全owner/线程失联或跨线程迁移，Apple/物理M-1/长期GPU未signal/完整C1继续待验。下一步回到有限C1支持/拒绝矩阵，仅针对真实消费者缺陷或能改变决定的证据推进，不因每个参数另开通用实验。

2026-10-29，沿DEV-040/C1第3/4项，起点3b94f74。本轮验收卡：原NoteStore只能在其实际回调Looper的主线程创建和获取；错线程准入在worker创建前拒绝，不替换主owner，正常主线程创建/保存/退休保持。禁止另建owner、线程迁移器或队列来掩盖冲突。

原Store的Handler固定投递main，而BoundedOwnerExecutor把创建线程记为owner。已有注释要求App owner是main，但原私有构造/公开get缺少主线程准入；新增主机反例实际证明后台创建返回Store（1项失败），而非立即拒绝。只复现了非法绑定被采用，没有故意派发该Store的非法main回调造成崩溃；反例清理在原创建线程停止自身worker并移除自身Handler消息。原失败XML/日志保留，首个测试夹具的RuntimeEnvironment泛型调用编译错误已修正后才取得该运行反例，不记为业务缺陷。

修复在原构造体首个init检查主Looper，先于PreviewFileIO、Handler、执行器及ledger初始化；get在读取缓存单例之前也检查主Looper。拒绝使用IllegalStateException，属于程序owner前置条件，不包装为文件格式/业务拒绝，不catch所有错误。未更改共享owner/文档格式或创建新worker路径。

两项新增主机测试验证后台构造拒绝/目录未动、缓存单例错线程获取拒绝、原主owner引用/ACTIVE状态保持且可创建。默认32项Android主机测试零失败/错误/跳过，目标构建通过，lint0错误8既有警告。原ART owner_retirement入口新增同一后台构造/get拒绝及main单例保持，在新API35/NoteCoreCurrent35实际8项检查通过；已安装App/仪器APK指纹匹配构建，五份原笔记升级/检查/恢复普通进程前后集合及字节保持。共享源码未改，559共同结果不重跑或算作新验收。见[报告](../reports/store-owner-admission.json)。

采用原Android owner入口拒绝缺陷修复并收口。该准入不提供完全owner/线程消失后的迁移、回执释放或恢复，也不证明真实长期GPU未signal、Apple/物理M-1或完整C1。历史API35运行报告保留原产物绑定；新包不继承旧报告。下一步按有限C1核对剩余真实owner/线程失联的支持/拒绝出口，必须有具体消费者缺口或可改变决策的证据，不扩平行owner实验。
