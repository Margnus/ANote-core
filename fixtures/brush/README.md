# 候选2笔刷几何黄金样本

`brush-golden.json`保存6组固定输入及完整期望轮廓：大坐标单点、等宽水平笔、压力渐变、荧光折返、原地变压和对角线。共212个期望顶点；`brush-manifest.json`固定LF规范文本SHA与1e-9绝对误差。都是合成规范样本，无设备数据。

期望值按ADR-0021独立计算：圆盘用解析角度sin/cos、连接用hypot求法向；Kotlin实现使用固定方向常量和缩放归一化。期望值已存入仓库，测试只读取和核对，不运行生成器、不自动刷新期望值。更改算法/期望需同时审查版本与几何语义，不能通过重生成黄金值掩盖回归。

`BrushGeometryGoldenTest`先核验manifest，再比较全部顶点/偏移/颜色/原点/bounds。通过后将实际Kotlin输出写到忽略目录`build/brush-golden-actual.json`，供渲染和人工检查；实际输出不是期望源。

共同几何未接Android/Swift原生绘制，尚无跨端图像/抗锯齿/颜色空间或性能验收。候选2不进入当前只支持algorithmVersion=1的预览codec；旧文件和UI继续使用原路径。
