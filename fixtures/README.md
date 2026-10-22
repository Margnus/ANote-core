# D2输入轨迹候选契约

input/synthetic-traces.json为生成的合成样本，版本0.1-draft。origin明确synthetic，设备/实际延迟为null，不能称作真机采集或性能基线。real_samples表示模拟轨迹中的非预测采样，不表示数据来自真实硬件。

坐标为scene-point，时间为单调微秒，pressure为归一化0–1；每个case固定down时的scene/layer/brush和坐标快照。事件包含pointer/tool/phase、真实历史样本容器和独立predicted_samples；层切换/延迟提交是单独控制事件。真实数组只能包含事件时间以前的样本，预测数组必须晚于事件时间。

当前5类轨迹：普通笔、预测只预览、抬笔后切层、未提交前取消、手指导航非笔迹。取消case无已确认checkpoint，因此期望未完成笔不提交；生产长笔checkpoint的保留行为由后续宿主契约验证，不据此丢弃已确认数据。

工具生成器检查格式数值/时间/序列与独立reference投影，并记录文件hash。JVM测试已读取JSON并核验manifest SHA，通过公共InputReplaySession、StrokeSubmissionQueue、SceneReducer和MemorySceneCoordinator逐点对照真实坐标/压力/时间、捕获layer及提交数。JSON依赖仅在jvmTest；原生适配器尚未实现，expected不是实际设备结果。公共测试另有历史批次、10,000点长笔与指针冲突；下一步将它们扩展为JSON轨迹，补规模样本、背压和lease调度反例。

```bash
python3 tools/generate_input_fixtures.py
```

默认每笔96点。规模实验可用--points调整，输出位置用--output指定；生成大样本与测量必须分开，生成耗时不能被用于宣称触笔性能。

## 0.2-draft采集记录候选（DEV-040N）

`input/capture-0.2-synthetic.json`是独立候选，保留原0.1-draft与JVM消费者。`tools/input_capture_contract.py`读取UTF-8 JSON并有界校验：文件16MiB、事件200000、快照4096、回调真实+预测1024点为工具准入，不是生产heap/输入arena阈值。拒绝重复字段、NaN、未知schema与坏后缀，不返回部分成功摘要。

时间保留非负Int64原始整数，clock含域、单位和session origin；原始点先按原单位排序，再用整数差按ns/us/ms向下换算微秒并检查Int64溢出，允许量化后同微秒，不合并真实点。Apple Double秒与Android具体SDK时钟转换尚需原adapter提供可信的转换和采集证据，不能仅改domain字符串冒充已校准。墙钟不参与排序，当前记录不接收延迟结论。

每个点引用独立viewport版本，快照记录view-point→scene-point仿射；校验映射有限且可逆，不用最新矩阵替换历史点。压力有传感器时声明原始范围，无传感器时原始值必须null且显式声明fallback；tilt是相对法线角[0,π/2]，azimuth是平面方向[0,2π]，不支持的轴为null，不能用0伪装能力。provider/build/session、origin与设备元数据分开，synthetic不能附假设备或性能指标；device元数据可校验格式但不能证明来源真实性。

事件sequence连续，generation在detach/rotate/scene-switch后增加并取消未完成输入；完成笔目标在DOWN捕获，预测只在MOVE独立数组且时间晚于event。stylus-only的手指样本只计导航，不把它们投影为笔迹；本校验不模拟完整多指手势。统计中的committed_samples只表示合成记录的UP投影，不是规范authority提交或磁盘回执。停采有未完成输入必须truncated=true，已完成笔统计仍保留；真实截断原因/输出失败仍需采集器报告。

执行`python3 tools/verify_input_capture.py`可检查合法见证与破坏性反例；原catalog/ci也调用同一校验。该候选没有接原生采集器、消费者回放或普通App，不改变笔记格式/C1/生产保存门禁。
