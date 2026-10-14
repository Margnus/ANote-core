# ADR-0004：资源lease、预算与释放确认

状态：D3/C1候选，2026-10-14；单owner参考模型与test-only假后端，不是原生GPU或线程安全实现。

## 生命周期与所有权

ResourceLeasePool在创建资源前发出reservation并占用预算。publish成功才接管资源；读者以独立lease借用，borrow更新LRU顺序。相同key的新值产生独立handle，旧值退出resident但保留所有旧lease。驱逐、替换与close只改变可查找性；不能用WeightedLruCache的displaced结果直接调用native free。

每个池内的同一原始对象不能被两次注册。publish失败没有接管新的资源；失败值由调用方负责，但AlreadyOwned说明该值已有池内所有者，不能把它当“新对象”销毁。该检查基于池内对象身份，不能检测跨池共享同一native handle；生产adapter必须提供唯一所有权/handle registry。

reservation只能在明确未分配时cancel；close不会猜测worker已被取消。已预留资源若在close后才完成，publish仍接管并直接retire，然后正常释放。调用方必须处理StaleReservation：若资源实际上已分配，不能只忽略返回值，需按adapter的未接管资源处置协议清理。

宿主必须保持pool owner存活直到isDrained，不能依赖token标签代替关闭协调。close是终态：拒绝新reserve/borrow，已有lease仍可读/归还，reservation及释放完成仍可处理。所有条目、reservation和lease归还后isDrained才成立；不会在detach时强行清空GPU正在读取的资源。lease归还或release ticket完成后清除内部value引用，再次访问明确失败；所有权标签使用独立identity，已返回token不经标签保留整个pool。但无法阻止调用方先复制裸值再越界使用；平台桥接应提供受控借用句柄。

## 计费模型

`chargedBytes = reservedBytes + residentBytes + retiredBytes`。retired包含仍借用、待释放、释放在途和释放结果未知的条目。borrowedBytes统计被读者引用的**唯一资源**，是resident/retired子集，不能再加一次当总内存。多个读者共享一个资源只计资源字节一次，lease元数据另有maxLeases上限；reservation+条目另有maxResources上限。

准入先检查resident单资源上限、条目数和total剩余预算，使用减法比较避免Long溢出。为控制峰值，替换不能先假设旧资源即将释放再分配新资源。retire降低residentBytes，却不降低chargedBytes；只有后端确认RELEASED才归还总预算。池已满时宿主可先显式驱逐、等待安全释放，再重新reserve；不能drop真实输入或尚未确认命令来换资源。

字节数是adapter声明的资源charge，不是本机测量的heap/GPU footprint。对象头、scheduler、调用方复制、Scene/journal与提交队列仍需纳入统一预算。当前只管理候选缓存资源，不涵盖大于residentBudget的scratch缓冲或多后端共享分配；生产arena/分配器及整机内存门禁未完成。

## 释放、失败与调度

只有retired且所有lease归还的资源可生成ReleaseTicket；生成ticket时依然计费。同一条目最多一个释放attempt，重复/旧/跨池完成不改变预算。nextRelease要求目标lane与配置匹配，只返回动作，不直接调用析构或回调。平台adapter应在指定释放线程执行，然后把结果路由回owner；ResourceLane是逻辑标签，不验证真实线程ID。

| 后端结果 | 模型行为 | 调用方义务 |
|---|---|---|
| RELEASED | 移除条目、归还总预算 | 只在实际释放完成后确认，包括GPU相关同步 |
| NOT_RELEASED | 保留资源/预算；可显式发起新的ticket | 必须确信没有发生释放；例如任务队列拒绝且从未执行 |
| UNKNOWN | 隔离条目/保留预算，不自动再析构 | 查询后端实际状态，resolveUnknown给出确定结果 |

析构后回执丢失与提交丢ACK不同：提交authority可以凭命令台账幂等重放，native析构未必可重试。异常也可能发生在释放之后，不能统一转成NOT_RELEASED。未知结果必须核对，否则可能双重free；确认仍存活后才允许新的ticket。原生后端若不能查询，需提供可证明幂等的释放机制或明确隔离/退出策略，在C1记录成本与限制。

## 可控测试证据与未决项

公共lease测试覆盖预留/取消、两类预算/元数据上限、LRU、同key旧generation、多个读者、foreign/重复token、close后迟到分配、释放失败/未知核对和Long预算边界。test-only ControlledResourceScheduler用单真实线程任意选择OWNER/WORKER/GPU逻辑任务顺序；FakeGpuResource在错误lane、重复释放或已释放后读取时失败。

调度反例覆盖detach→GPU fence→owner归还→GPU析构→owner确认、反向close顺序、执行器队列满拒绝任务、错误lane以及析构后丢ACK。延迟GPU读取同时接入FramePublicationGate：旧frame不能发布，但资源必须活到最后读者退出。GPU析构完成而owner确认尚未执行时仍保留budget。

这些证据是离散顺序模型，不代表真实CPU内存模型、线程竞态、GPU fence或平台surface释放。下一步把提交与资源统一进document/session关闭协调器，补真实owner执行器/跨线程handoff、生产预算与恢复契约；D4验证Android/iOS工具链与native fence/线程约束后才冻结后端接口。持久化仍未实现，C1/D3整体门禁保持未通过。
