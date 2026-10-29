# PDF-03 主机原生背景与共享坐标接缝

## 2026-10-29：UserUnit与继承几何的能力边界

沿原卡未决元数据边界，起点7b66a6a。验收卡目标是区分父Pages节点继承CropBox/Rotate与页字典非1 UserUnit，实际系统尺寸/背景/共享物理页坐标不一致则记录限制，不修改oracle使其通过。复用原内容流，生成两个元数据派生样本；未改原PDF或生成新的图形模型。UserUnit的默认用户空间单位语义参照[iText官方说明](https://kb.itextpdf.com/itext/how-to-get-the-userunit-from-a-pdf-file)，页对象/单位属性接口参照[Adobe SDK文档](https://opensource.adobe.com/dc-acrobat-sdk-docs/pdflsdk/apireference/PD_Layer/PDPage.html)；本轮平台结论仅来自实际观察，不从规范或API存在推断支持。

`tools/generate_pdf_metadata_fixtures.py`以原PDF派生UserUnit=2和继承几何各四页。生成后先检查原始leaf字典未含MediaBox/CropBox/Rotate，属性实际在父节点，再解析有效几何；两份内容流均与原四页逐字节相同。原PDF SHA保持；生成器/派生PDF/manifest指纹保留。没有把UserUnit当作继承属性。

实际API35结果：继承样本四页/16锚点和16黑色叠加中心通过，背景/叠加图保留并查看；UserUnit=2直接尺寸接缝在第一页明确失败，PdfRenderer报告300×180，而当前共享物理页规则为600×360。该失败仅到第一页背景，未验证其后页/叠加；保留原始失败码0、日志及300×180背景，未吞异常、改尺寸期望或标绿。独立PNG复核确认该背景仍在未乘2的原锚点显示四颜色。不能仅凭此称所有系统渲染都“忽略UserUnit”，但当前直接尺寸消费方式不支持该场景。

采用决定：当前原继承CropBox/Rotate样本的接缝可采用；拒绝把原始PdfRenderer width/height直接等同非1 UserUnit的共享物理页尺寸。生产port必须先得到有效元数据并建立明确渲染变换，当前没有该生产reader/adapter，不宣称完整非1 UserUnit支持。报告为`completed_with_capability_limit`，两个原生调用各自passed/failed保持，见[能力报告](../reports/pdf-metadata-art.json)。复核命令需有pypdf的Python运行`tools/verify_pdf_metadata_art.py`。

Poppler按CropBox渲染首页面作视觉上下文，缺少部分display font的警告保留，未用其覆盖ART失败或充当像素oracle。五份正式笔记前后集合/hash保持，当前源码/安装产物指纹及仪器构建通过；无共同/普通App业务修改，不重复559/33主机结果。Apple/物理M-1/任意真实PDF/批注导出/资源事务及C1仍待，PDF-03统计不晋升。该有限元数据能力调查收口，下一步沿原系统PDF能力矩阵确定有效元数据读取与渲染port契约，不能自行扩展为PDF语法解析器或未批准的大SDK接入。

## 2026-10-29：原探针的实际ART出口通过

本轮沿原卡“实际ART可用后验证同源背景/共享矩阵”的明确出口，起点d38cc79。前一owner/关闭入口有界复核未发现新可复现缺陷，不再增加相似owner参数测试；新独立AVD可用后，改用实际系统PdfRenderer完成本卡，不重试已知JVM FileDescriptor失败路径。

将原四页探针的同一CropBox/16锚点/共享规则调用移植到原ViewportInstrumentation的`pdf_geometry`分支。既有生成PDF与recipe仅复制为androidTest只读资产，PDF SHA-256仍8c29b75c713114288652cbd9afe54a65fc82865543e8d5fd81d3bf8a737aa9ee；没有生成新PDF或改变独立oracle。测试反射只调用现有internal PdfPageGeometryRules，不增加公开facade或普通App PDF入口。输出仅唯一cache目录，源PDF只读打开，Page/Renderer/FD以use关闭，Bitmap finally回收。

当前API35/NoteCoreCurrent35实际通过：系统PdfRenderer四页尺寸300×180/180×300与非零CropBox recipe一致，0/90/180/270度16个颜色中心和实际共享pdfToPage逐一吻合；同一Bitmap/Canvas叠加黑点16个中心通过。保留4背景/4叠加PNG，已查看原背景及四旋转叠加图；独立保留证据脚本再次从PNG像素核对颜色和黑色中心。源PDF前后hash保持，五份正式笔记集合/hash保持，已安装App/仪器包及当前源码输入指纹核对通过。目标仪器构建、可移植性与依赖方向通过；共同/默认33主机未改不重跑。见[ART报告](../reports/pdf-art-geometry.json)、[原始输出](../reports/pdf-art-geometry/run.log)，复核入口`tools/verify_pdf_art_geometry.py`仅检查保留数据。

采用此实际ART背景/共享坐标接缝，当前JVM主机路径仍不采用，原失败报告不改结论或绑定。仅UserUnit=1固定四页，不证明继承属性/UserUnit非1、任意真实PDF、真实批注对象/导出/取消/资源事务、当前Apple或物理性能；PDF-03及C1/生产存储状态不晋升。下一步核对PDF平台能力中已记录的UserUnit/继承属性与实际源元数据边界，必须形成支持/拒绝决定，不追加模拟背景或平行渲染器。

## 交付卡（2026-10-29）

起点Core806a056。目标：回答Robolectric SDK35原生PdfRenderer/Canvas能否消费既有非零CropBox四旋转PDF，并与本轮实际共享Affine2D映射的16个独立锚点对齐。复用原生成器/recipe，不新建任务模型、authority或普通App入口。只读测试PDF源，输出在build；用测试反射调用internal规则的实际JVM实现，不冻结或扩大公开SDK。

验收：四页原生背景尺寸及recipe颜色中心通过；实际共享矩阵映射的源坐标与独立recipe一致，在同一Bitmap上经原生Canvas叠加测试标记，中心像素保持对齐；源PDF前后SHA一致，原生句柄/Bitmap均关闭。保留PNG并查看。失败则记录具体不匹配/环境，不外推ART/iOS或修改黄金值。只测UserUnit=1；不称真实批注对象、导出或任意源PDF完整支持。

平台与门禁：该主机native库不是设备系统PdfRenderer，不能继承历史ART/API36或Apple结果。若通过，仅采用主机同源背景/坐标接缝；还需实际Android/Apple adapter、UserUnit非1/继承属性、真实PDF、生产资源事务及App验收。PDF-03仍未开发/待验收，INK-10/OBJ-03/M-1/C1/DEV-041不变。

## 实际决策：当前主机路径不采用

实际1项探针失败、0跳过，在PdfRenderer构造时抛NoSuchMethodError: java.io.FileDescriptor.getOwnerId$()，栈位于IoUtils.acquireRawFd→ParcelFileDescriptor.detachFd→PdfProcessor.create，未打开页面/生成背景或叠加PNG。源PDF hash保持；不对像素作结论。本轮本地shadows-framework-4.17.jar未列出PdfRenderer/PdfProcessor shadow；只据当前实际调用失败判此环境不可用，不外推所有Robolectric版本。没有更换SDK/依赖或模拟该方法。

探针原样移到tools/probes/pdf-native，仅显式-PenablePdfGeometryNativeProbe=true加入test source set；未忽略异常/测试，默认套件不消费环境探针。复现前运行tools/generate_pdf_fixtures.py（Python需reportlab/pypdf），然后./gradlew.bat -PenableAndroidTargets=true -PenablePdfGeometryNativeProbe=true :android-app:testDebugUnitTest --tests dev.notecore.app.PdfGeometryNativeTest。当前命令预期失败；环境修复后才重新判定，不从默认构建成功推出像素通过。

原生成器在Windows把manifest键写成反斜杠，与原verify_pdf_capabilities.py的正斜杠键契约冲突；改为as_posix，四个生成PDF前后SHA保持。此独立修复采用。实际ART/Mac可用后应扩原同源探针验证新共享矩阵；不为当前主机缺口再添加模拟背景或平行渲染模型。PDF-03、M-1/C1及生产门禁不变。

最终显式启用入口复验同一构造失败，未跳过测试；原样探针源码及失败XML绑定见[报告](../reports/pdf-native-host-attempt.json)。外部核对源hash与生成前记录一致（测试末尾自身hash断言未执行，不混同）。默认Android主机回归12项零失败/零跳过，Android构建/lint通过0错误8警告；共同546项JVM XML零失败复用（UP-TO-DATE），可移植性/架构通过。新APK仅build，未安装/替换dist。无PNG输出，未作视觉通过声明。
