# 跨端预览交换样本

android-paged-v2.ncp来自专用Android模拟器运行tools/android_paged_smoke.py后实际保存的三页笔记；全部为自动生成的触摸轨迹，未取真实用户笔记。提取时逐字节SHA与当时reports/android-paged-smoke.json匹配，来源APK及报告提交见同名JSON。

iOS宿主测试直接读取原文件，通过真实PreviewModel导入、保存和导出，检查页面顺序/尺寸/纸张、笔刷/矩阵、全部真实采样、fresh文档/页/layer/对象身份与源字节保留。该样本不代表生产旧JNotes数据迁移、完整格式兼容或系统选择器交互。
