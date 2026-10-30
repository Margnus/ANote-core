# PDF元数据与渲染port边界

2026-10-29，沿DEV-039/040与C1第5项，起点48720dc。交付卡目标：回答现有Android系统API能否独立提供共享页几何所需的有效元数据，明确缺失输入时的责任；结合实际反例决定采用范围并停止同类探针。不是冻结生产接口或新建PDF解析器。

## 决定及证据

系统PdfRenderer保留为只读渲染候选，不采用为独立完整的PDF源元数据port。本机部署样本API35与编译API36的公开PdfRenderer/Page签名已逐项核对：有index/width/height、render及内容/表单等方法，没有有效CropBox原点/矩形、Rotate、UserUnit或原始页字典读取入口。SDK jar指纹和完整公开签名保留在[报告](../reports/pdf-metadata-port.json)。这是这两份本地SDK的公开接口盘点，不外推所有版本或隐藏API，也没有执行API36运行测试。

实际API35的额外见证：原UserUnit=1样本与仅改为2的样本，第一页原生背景均300×180且全部解码像素完全相同；源PDF哈希不同、页字典单位已独立核验。它们在现有共享物理页规则下需要300×180与600×360。因此不能从这两个样本的渲染尺寸或图像反推单位；这不是任意PDF的视觉等价结论。原UserUnit=2直接尺寸检查保持failed，不以本轮接口盘点替代或覆盖它。

## 采用前置条件

下表是生产port必须满足的责任约束，不是新增代码接口或文件格式。复用既有只读源资源/稳定源页绑定、PdfPageGeometryRules及Affine2D，不另建正文或第二套矩阵模型。

| 输入/责任 | 必须建立的事实 | 缺失或冲突时 |
|---|---|---|
| 源身份与存活 | 绑定实际不可变PDF字节/指纹、源页index及原资源reader所有权；改源后旧派生几何失效 | 不发布正文页或复用旧映射，原源保留；读者pin/资源事务仍依赖正式存储门禁 |
| 有效页框 | 元数据reader解析继承链后的框及MediaBox/CropBox关系，保留非零/负原点和准确数值 | 不用平台width/height补造原点，不从可见内容bbox猜框 |
| 有效旋转 | reader解析原Rotate继承/默认，按共享规则归一；明确renderer已应用哪些旋转 | 不再旋转一次或从长宽交换猜方向，未知则不采用 |
| UserUnit | reader给出实际页字典值，或明确证明缺省后才使用1；共享规则只应用一次 | 系统API未暴露该值不等于源未设置，禁止直接默认1 |
| 渲染空间 | 保留平台报告尺寸的独立含义；明确目标bitmap/device像素与共享物理页之间的变换及裁剪 | 先完成同源背景/叠加像素验证，不能把“能打开/画出”当几何采用成功 |
| 失败/额度 | 元数据输入经共享有限框/旋转/单位/尺寸校验，源读取/解密/不支持/容量失败由实际读取者分类 | 整页拒绝且原源保留，无部分元数据成功，不以宽高相近钳制/重写原值 |
| owner与释放 | reader/page/renderer/bitmap由原owner/worker的准入及最终读者责任管理 | 不能因元数据失败或超时跳过native reader释放/账本，不新增旁路pool |

当前共享规则的默认参数仅适用于调用者已验证的可信单位约定；它不是生产读取者对未知源的缺省证明。测试recipe提供已审计元数据，但不能被当作真实PDF的reader。现有Python/pypdf仅用于测试文件生成/独立审计，不进入Android运行时；本轮没有采用隐藏API、自写语法解析器或新增PDF SDK。

## 结束条件与下一步

接口盘点、受控反例及上述采用边界足以否决“系统width/height直接充当完整元数据”的路径，本有限调查收口；不继续增加缩放值或从像素猜元数据的实验。有效元数据读取策略和跨平台reader/渲染adapter仍是明确缺口，需在原PDF平台能力/资源生命周期评审中解决；C1/M-1前不接生产App或正式存储。下一独立增量只应针对真实只读消费者的具体准入/释放缺口，或取得当前Apple/物理设备等缺失环境证据。PDF-03/113条状态不晋升。


## 2026-10-30：Apple原样本能力交付卡

起点53be7bc，原App普通UI收口后沿DEV-039核对Apple原生平台差异。复用原CoreGraphics/PDFKit探针，仅新增显式--metadata分支；原模式与固定原Mac脚本保持，不修改源PDF。独立托管macOS15/Xcode16.4/iOS simulator编译实际iOS二进制，读取原crop-rotations及既有inherited-geometry/UserUnit2共12页的有效框、旋转、实际页字典UserUnit；未设置与非数值明确区分，不从renderer宽高猜单位。输出原尺寸及按实际单位选择目标矩形的原生图像和drawing transform，记录来源/二进制哈希。没有调用internal共享几何或新增public facade，故本轮只决定平台reader/render候选，不宣称共享runtime adapter已实现。

验收：原源哈希/内容流绑定保持，12页元数据与原recipe/manifest匹配，16图独立按既有四颜色锚点及实际目标尺寸核对并查看。CI仅采集，成功状态明确collected_pending_pixel_verification；不能以绿灯替代像素检查。若单位/继承属性或图像不符，保留实际失败/限制，不改oracle。无新SDK/PDF写入/App资源接入；Android已知UserUnit限制、物理M-1、C1及PDF-03原验收不变。入口.github/workflows/apple-pdf.yml、ci/collect_apple_pdf.py。


运行41001924807（固定1b1a9d6，macOS15.8.2/Xcode16.4/iOS18.5）实际采集完成；独立核验12页元数据全匹配，24次raw/physical图像检查20通过，UserUnit2四张physical图锚点失败。目标尺寸虽为600×360/360×600，内容仍原尺寸居中，首图getDrawingTransform返回[1,0,0,1,110,40]。失败原图/日志及verification.json保留在reports/apple-pdf-ci-41001924807，ZIP SHA256 c977e9381bf877836e8225c624ab743932b6a6cd667cb772ea14e52af9c6a01a；源/产物哈希与原始继承字典/内容流审计通过。结论仅否决本样本“增大目标矩形即得到物理尺度”的消费方式，不外推所有CoreGraphics变换。

据实际失败调整原render helper：按已读UserUnit显式缩放context，再拼接原未放大目标的drawing transform；unit1/原模式行为保持。继续使用原16锚点/每页原oracle，不扩大样本或改变期望；新运行通过前不采纳物理尺度适配。源元数据reader仍只作为平台候选，共享runtime adapter/生产owner/资源接入未实现。


### 实际采用与收口

固定8b2ad2414acb57cecac49b77953b469c5ad2290f的[运行41002383325](https://github.com/Margnus/ANote-core/actions/runs/41002383325)完成后，独立核验[verification.json](../reports/apple-pdf-ci-41002383325/verification.json)通过12页源元数据、24个raw/physical尺寸/四锚点核对项（16个唯一PNG）。源8项/附属产物19项哈希、三份PDF原内容流/继承leaf审计通过；ZIP SHA256 457da6b6747cf81a26757f8980eb7c2bb60982b2ecd51e0160026fe4ba44d6d7与远端一致。16图已查看；UserUnit2首图显式组合矩阵[2,0,0,2,-80,-100]，原色锚点在物理尺度位置，四旋转均通过。实际环境macOS15.8.2/Xcode16.4 arm64、独立iPad M4/iOS18.5 simulator；没有继承旧Mac或普通App结果。

采纳本样本范围的CGPDFPage有效框/rotationAngle与页字典UserUnit读取候选；未设置由实际字典缺值证明，非数值不吞成1。采纳“显式context尺度→原未放大drawing transform”的原生渲染子范围，不采用仅增大目标矩形的路径；首次20/24反例与报告保持。Apple可读元数据不补Android API35/36缺口，也不是共享runtime adapter或生产reader已实现。当前规则仅既有裁剪/四旋转/单位1或2样本，不外推任意PDF、解密/非法单位/复杂box继承、文本字体/批注导出、原owner取消/资源事务或物理性能。本有限能力卡收口，停止同类数值/图片探针；下一步回到C1原最小公开消费者白名单的双端责任/支持与拒绝缺口，DEV-041仍受原门禁。

复核：使用含Pillow/pypdf的Python执行tools/verify_apple_pdf_metadata.py --evidence reports/apple-pdf-ci-41002383325；首次目录41001924807预期completed_with_capability_limit，不能改为passed。两个结果均检查固定Git源而不是套用当前HEAD。
