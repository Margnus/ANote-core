# Android 原宿主密集准入与成本基线

采用本次准入结论与原调用链基线；**暂不把完整 S2 接入当前预览，不提高额度**。实际产品源码仍为 `8012663`，App APK 未变；本次新增原宿主仪器入口。不是新的生产功能、完整 S2、M-1 或 C1 验收。

## 样本与准入决定

固定原始 `S2-ink-dense-page.bin`：10,000 笔 × 100 点、28,000,016 文件字节，SHA-256 `22eea352e0ded47f34cabc0c8b543de0126a658377a6ea286cf60130a17c6725`。100 万点逐位读取到原 `StrokeSamples`/Scene，不抽稀、不丢点。为了独立核对容量，笔刷明确设为预览已支持的 MONOLINE-v1；这不同于旧 renderer 夹具的三种笔刷轮换，也不包含 100 个混合资源。

| 层 | 现行限制 / 实际结果 | 本次决定 |
|---|---|---|
| 文档 authority | 默认 5,000 对象、8 MiB live；满样本仅 primitive payload 就是 28,000,000 字节 | 不能据 renderer 离屏实验扩大产品预算 |
| 原预览 codec / notebook | 5,000 对象、200,000 总点、20,000 单笔点、8 MiB retained；完整 ink 返回 INVALID_CONTENT、未产生 notebook | 完整 S2 拒绝；这不是损坏输入的精确诊断枚举，不将其误报为特定单一门槛 |
| 独立总点门槛 | 2,001 笔/200,100 点低于对象上限，仍拒绝创建 notebook | 总点限制是独立阻挡；原 S9 100,000 点长笔也超过单笔上限（S9 本轮仅源码核对） |
| 原整页 native renderer | 总对象 ≤4,096、总点 ≤200,000、选中页 ≤256 笔、仅 MONOLINE-v1 | 原宿主 1,900 笔实际置 admissionRejected，worker 计算 0、发布 0、帧 charge 0；具体 page-input-budget 条件来自源代码复核 |

完整样本在设备上确实构造了 10,000 个独立 StrokeSamples / 40,000 个 primitive arrays。构造阶段约 711 ms，Java used 采样 43,742,920 字节；这些是调试进程单次采样，包含构造临时物和框架，不是 retained size 或峰值。打开拒绝没有创建第二个可编辑 authority；原 root 的数量与修订保持。

## 原 App 消费链

另行命名的 **S2-ink 前缀 1,900 笔/190,000 点** 进入实际 NoteStore.adopt → PagedPreviewNotebook → InkSurfaceHost/InkCanvasView。此范围满足当前 preview 的总点和文档额度，但超过原生整页 256 笔限制，因此持续使用原 Path 回退。

在 API35 小米 23046RP50C、1024×768 View、调试 APK 上通过：12 次合成 API DOWN/UP→提交→投影→undo→redo→undo；正文逐对象/逐样本相等，原 View 软件位图在历史往返后逐像素一致。最后再 redo，保留一笔新接受的 2 点内容；实际文件保存 ACK 后退休原 owner，再由新 NoteStore 从文件重开，全部页面、标题、身份、修订、样式、变换及样本相等。重开结果 1,901 笔/190,002 点。不是普通进程重启，也不是物理触笔输入。

所有动作发生在独立 cache 目录。三个已有用户/此前测试笔记在仪器运行前后逐文件 SHA 不变；其文件内容和名称不入公开报告。测试 fixture、临时 note 仍留本机 cache 供复核，没有删除用户文件。

## 成本与责任

以下为最后一次运行；原始序列、首值、P50/P95/max 都在 evidence.zip。小样本不外推分位数稳定性，首次含 JIT/冷启动影响。

| 原调用 | 样本数 | P50 ms | 最大 ms |
|---|---:|---:|---:|
| authority status | 30 | 0.115 | 2.219 |
| canUndo + canRedo | 30 | 0.231 | 3.693 |
| 热页投影 | 30 | 0.004 | 0.022 |
| DOWN 准入 | 12 | 1.107 | 2.561 |
| UP 提交 | 12 | 1.981 | 6.072 |
| 提交后页投影 | 12 | 1.007 | 2.694 |
| undo / redo | 各 12 | 0.901 / 3.201 | 2.076 / 6.828 |
| 原 View 软件 Canvas 暖绘制 | 10 | 44.264 | 47.031 |

冷 Path 构造加绘制单次 86.386 ms，历史变更后 73.314 ms；adopt 57.288 ms，冷页投影 2.961 ms，capture 3.604 ms。这不是硬件 Canvas GPU 帧时长，不能换算 fps 或触笔延迟。暖绘制当前比状态扫描更显著，**不为本轮新增 authority 增量缓存**；后续先接原呈现路径并分别核对硬件/软件成本。

保存持有真实 `PREFIX_WRITTEN_BEFORE_SYNC` 边界取样后放行，ACK 约 2.569 s（含主动持有，不能作为正常保存性能）。文件 5,329,960 字节，SHA `c6a99a95b6ddda3560cc64b45aa81196cf1fb5153e10a2a541f6891b248b8aeb`；新 owner 读入与采用约 744 ms，单次值。

| 同时责任 | 实际依据与范围 |
|---|---|
| 正文 / 投影 / 保存 | 保存 root 与 authority 是同一实例；初始 stroke 引用与 canonical 原对象相同。源码复核投影是引用列表、预付在 live 中；不复制原始样本。终态 live 5,858,720 字节，codec retained 5,843,384 字节，不应再相加当 heap |
| 历史 / 日志 / 身份 | 49 个 accepted operations；journal charge 201,376、history 1,836、identity 178,478 字节。稀疏 history 与 journal 持有新笔样本的责任仍在；正文中的 undo 不代表样本被静默丢弃 |
| 活动输入 | 第一笔 DOWN 时 input reservation 764,288 字节，结束后 0；这是容量预留，不是实际数组峰值 |
| 保存工作 | encodingWork reservation 216,893,880 字节；受原 256 MiB 执行器限制。原 worker 流式文本写入，预留上界不等于实际分配 |
| Path / native / 退休 | Path 缓存与软件测试 Bitmap 存活；原生页入口拒绝，没有 native frame 可发布或退休。退出时 owner 排空，帧 charge 0；不能据此声称已经测到密集 native buffer 重叠 |
| 实际进程采样 | 持有写入时 Java used 16,873,296、native allocated 25,216,096 字节，PSS 231,105 KiB；是该采样点，非峰值。不可将 Java/native/PSS 相加。包括测试位图、oracle/root、框架、GC 未回收物及驱动统计口径；不等于产品总内存 |
| 新 owner 与 oracle | 退休后 history/journal/identity charge 归零；测试仍保留不可变旧 snapshot 作重开 oracle。重开内存采样明确包含这个额外 root，不能作为普通用户重开的稳态内存 |

## 验证与局限

最终仪器 `dense_admission_status=passed`，PID 29729。首轮 baseline2 同样通过但最终新笔仍处 undo；随后补上 redo/保存内容的断言，最终运行才覆盖新接受笔迹的重开。baseline1 在测试运行前发生 adb exec-in/tee 传输超时；改用 adb push + run-as cp 并校验完整 SHA，不采用残缺传输。

`:android-app:assembleDebugAndroidTest` 成功；公共 JVM 任务以 unchanged-input UP-TO-DATE/XML 复用（canvas 269、shared 556），没有新增公共语义改动。架构边界和可移植输入检查通过。未重新执行 Android 单元/Apple/最低 API，因为 App 与 common 源码/产品 APK 没变；此前报告保持原范围。

没有 M-1 trace 新窗口、固定刷新档、Pencil/主动笔、真实混合资源、4 GB 设备、完整内存峰值或完整 S2 发布证据。本次不改产品额度、不冻结 API/格式、不接生产 SQLite。

## 接入决定及下一具体工作

采用本报告的多层准入清单与原链基线，结束本次测量包；不再重复相同负载以增加测试次数。下一可运行增量：把已采用的分批完整帧责任接到原 worker/pool/平台适配器，先在现有 codec 可承载的明确命名负载上覆盖呈现、动态交接、失败回退与保存重开。只有新的分批接缝和预算证据允许，才替换旧的整页 256 笔入口；不能直接提高该常量。

完整 S2 仍需要独立的文档/格式/保存准入设计：100 万规范点、历史/日志/投影、原生在途/退休和文本编码预算一起核算，兼容现有预览数据，并在目标设备证明总责任。不能把此次前缀集成称为完整 S2 完成；整体 DEV-036/C1 保持在制。

下一包估计：乐观 4、常规 7、风险 10 个执行单元（共享分批责任、Android 原适配器、取消/释放反例、真实消费链与报告；风险包括 Apple 对齐与总预算调整），置信度中低、设备等待未知，不承诺日历日期。完整 S2/存储日期继续等待上述契约与 C1。

## 复现

运行 `tools/generate_scale_fixtures.py` 校验固定二进制；构建 `-PenableAndroidTargets=true :android-app:assembleDebugAndroidTest`。保持该产品 APK 与验证回执 SHA 一致，将上述夹具放到 App 的 `cache/dense-admission-S2.bin`，安装测试 APK，然后：

```text
adb shell am instrument -w -r -e notecore_case dense_admission dev.notecore.preview.test/dev.notecore.app.ViewportInstrumentation
adb exec-out run-as dev.notecore.preview cat cache/dense-admission.jsonl
```

若系统阻止仪器后台启动 Activity，用正常 `am start -W -n dev.notecore.preview/dev.notecore.app.MainActivity` 唤起其等待中的原宿主。报告归档含本机 runner、最终源码摘要、最终/早期日志和检查输出；完整 fixture 可按固定 SHA 重建。用户笔记和系统 Activity 清单未归档。
