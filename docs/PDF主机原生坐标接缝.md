# PDF-03 主机原生背景与共享坐标接缝

## 交付卡（2026-10-29）

起点Core806a056。目标：回答Robolectric SDK35原生PdfRenderer/Canvas能否消费既有非零CropBox四旋转PDF，并与本轮实际共享Affine2D映射的16个独立锚点对齐。复用原生成器/recipe，不新建任务模型、authority或普通App入口。只读测试PDF源，输出在build；用测试反射调用internal规则的实际JVM实现，不冻结或扩大公开SDK。

验收：四页原生背景尺寸及recipe颜色中心通过；实际共享矩阵映射的源坐标与独立recipe一致，在同一Bitmap上经原生Canvas叠加测试标记，中心像素保持对齐；源PDF前后SHA一致，原生句柄/Bitmap均关闭。保留PNG并查看。失败则记录具体不匹配/环境，不外推ART/iOS或修改黄金值。只测UserUnit=1；不称真实批注对象、导出或任意源PDF完整支持。

平台与门禁：该主机native库不是设备系统PdfRenderer，不能继承历史ART/API36或Apple结果。若通过，仅采用主机同源背景/坐标接缝；还需实际Android/Apple adapter、UserUnit非1/继承属性、真实PDF、生产资源事务及App验收。PDF-03仍未开发/待验收，INK-10/OBJ-03/M-1/C1/DEV-041不变。

## 实际决策：当前主机路径不采用

实际1项探针失败、0跳过，在PdfRenderer构造时抛NoSuchMethodError: java.io.FileDescriptor.getOwnerId$()，栈位于IoUtils.acquireRawFd→ParcelFileDescriptor.detachFd→PdfProcessor.create，未打开页面/生成背景或叠加PNG。源PDF hash保持；不对像素作结论。本轮本地shadows-framework-4.17.jar未列出PdfRenderer/PdfProcessor shadow；只据当前实际调用失败判此环境不可用，不外推所有Robolectric版本。没有更换SDK/依赖或模拟该方法。

探针原样移到tools/probes/pdf-native，仅显式-PenablePdfGeometryNativeProbe=true加入test source set；未忽略异常/测试，默认套件不消费环境探针。复现前运行tools/generate_pdf_fixtures.py（Python需reportlab/pypdf），然后./gradlew.bat -PenableAndroidTargets=true -PenablePdfGeometryNativeProbe=true :android-app:testDebugUnitTest --tests dev.notecore.app.PdfGeometryNativeTest。当前命令预期失败；环境修复后才重新判定，不从默认构建成功推出像素通过。

原生成器在Windows把manifest键写成反斜杠，与原verify_pdf_capabilities.py的正斜杠键契约冲突；改为as_posix，四个生成PDF前后SHA保持。此独立修复采用。实际ART/Mac可用后应扩原同源探针验证新共享矩阵；不为当前主机缺口再添加模拟背景或平行渲染模型。PDF-03、M-1/C1及生产门禁不变。

最终显式启用入口复验同一构造失败，未跳过测试；原样探针源码及失败XML绑定见[报告](../reports/pdf-native-host-attempt.json)。外部核对源hash与生成前记录一致（测试末尾自身hash断言未执行，不混同）。默认Android主机回归12项零失败/零跳过，Android构建/lint通过0错误8警告；共同546项JVM XML零失败复用（UP-TO-DATE），可移植性/架构通过。新APK仅build，未安装/替换dist。无PNG输出，未作视觉通过声明。
