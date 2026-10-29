# 图片EXIF读取接缝

OBJ-03；起点03ff472。补现有编码样本验证的真实缺口：此前方向来自manifest，不能证明Android读取EXIF。仅调整原主机测试消费者，AndroidX ExifInterface 1.4.2为testImplementation，不引入产品依赖或新导入入口。

验收：原16份JPEG/PNG八方向字节经实际解析后驱动原共享矩阵/原生Canvas，manifest仅作方向与像素对照；原文件SHA保持。另从实际编码的无方向JPEG/PNG检查解析器返回未定义0、显式归一化为1后共享映射为恒等且输入字节保持。沿用默认主机回归、共同验证与架构检查，不重跑已知PDF主机失败或设备探测。

采用条件：真实解析值与原独立oracle一致，无写入源文件。实际ART/Apple读取、损坏元数据策略、ICC/高位深及生产资源事务继续待验；不把无标签默认等同于损坏标签容错。

依赖依据：[Android官方发布说明](https://developer.android.com/jetpack/androidx/releases/exifinterface)，仅选稳定1.4.2；不升级既有依赖。通过后采用此测试接缝，停止重复八方向样本扩展；产品reader仍需原资源pin/关闭/失败责任及双端验收。

实际发现：初次无标签断言失败，AndroidX对本主机原生编码JPEG返回字符串0而非null，getAttributeInt(...,1)也得到0；不能仅依赖默认参数。测试消费者对UNDEFINED(0)显式转1，1–8原样进入共享规则；其它值不能任意clamp。该API不能据0区分源中缺失与显式0，严格损坏检测仍未解决。未改共享规则对非法方向的拒绝。

结果：修正后3项图片方向测试/默认20项主机测试零失败零跳过；555项共同JVM结果复用，Android构建/lint0错误8警告，可移植性/架构检查通过。详见[固定报告](../reports/image-exif-reader.json)。采用实际元数据测试入口及0的显式归一化边界，未编写生产adapter；产品读取器还须资源pin/关闭/错误责任与ART/Apple实际验证。
