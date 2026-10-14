# D2输入轨迹候选契约

input/synthetic-traces.json为生成的合成样本，版本0.1-draft。origin明确synthetic，设备/实际延迟为null，不能称作真机采集或性能基线。real_samples表示模拟轨迹中的非预测采样，不表示数据来自真实硬件。

坐标为scene-point，时间为单调微秒，pressure为归一化0–1；每个case固定down时的scene/layer/brush和坐标快照。事件包含pointer/tool/phase、真实历史样本容器和独立predicted_samples；层切换/延迟提交是单独控制事件。真实数组只能包含事件时间以前的样本，预测数组必须晚于事件时间。

当前5类轨迹：普通笔、预测只预览、抬笔后切层、未提交前取消、手指导航非笔迹。取消case无已确认checkpoint，因此期望未完成笔不提交；生产长笔checkpoint的保留行为由后续宿主契约验证，不据此丢弃已确认数据。

工具生成器检查格式数值/时间/序列与独立reference投影，并记录文件hash。它尚未接入Kotlin/原生输入适配器；expected不是实际设备结果。下一步冻结事件schema、实现适配器消费与回放，再加入历史批次、multi-pointer切换、超长笔、背压和lease调度轨迹。

```bash
python3 tools/generate_input_fixtures.py
```

默认每笔96点。规模实验可用--points调整，输出位置用--output指定；生成大样本与测量必须分开，生成耗时不能被用于宣称触笔性能。
