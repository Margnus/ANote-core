# 可重复笔迹规模样本

这些是蓝图S1/S2/S9的**笔迹子集**，不代表完整文档性能基线。共1,200,000个synthetic点，所有profile的设备结果为空，生成/测试耗时不能称为触笔延迟。

| profile | 笔数 | 点数 | 分布 | 验证用途 |
|---|---:|---:|---|---|
| S1-ink-current-page | 1,000 | 100,000 | 100列网格，32单位间隔 | 当前页输入保点；未生成其余49页、文本与图片 |
| S2-ink-dense-page | 10,000 | 1,000,000 | 10列重叠，2单位间隔 | 密集笔迹流；未生成100混排对象或套索变换 |
| S9-ink-long-stroke | 1 | 100,000 | 长锯齿折线 | 上限附近历史批次消费；队列/lease轨迹待D3 |

`manifest.json`记录固定recipe版本、对象/点数、分布、点包围盒、字节数和SHA。配方用整数与二进制可精确表示的分数，不依赖平台sin/random；更改配方先提升generator版本并评审manifest。坐标为scene-point；压力归一0–1。时间是**每笔相对微秒**，每笔从0开始；每笔单独回放session，不能当作完整文档统一事件时钟。

大型二进制在忽略目录`.generated-fixtures/scale`按需生成，不上传Git。JVM测试自动调用生成器，对照已跟踪manifest后流式消费每个点，核验文件hash、截断/尾部、包围盒、点数及公共回放后的逐点内容。会话逐笔释放，未构建完整Scene、索引、tile或保存文件，不能据此宣称S2场景内存/渲染通过。实际生成文件也进入验证输入指纹，避免旧报告掩盖本地文件变化。

```bash
python3 tools/generate_scale_fixtures.py  # 生成并匹配已有manifest
./gradlew --no-daemon verifyPrototype    # 生成器 + JVM流式回放 + 其余契约
```

二进制仅为开发fixture，不能当作NoteCore正式存储格式：16字节little-endian头（8字节NCSCALE1 + uint32笔数 + uint32每笔点数），随后按笔/点顺序保存28字节记录（float64 x、float64 y、float32 pressure、int64 time_us）。不含资源、图层、笔刷、ID或持久化版本协议。
