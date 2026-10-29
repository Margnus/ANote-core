# PDF-03 裁剪与旋转双向坐标规则

## 交付卡（2026-10-29）

起点Core cb29ea5。原系统能力已有fixtures/pdf/recipe.json的非零CropBox四旋转像素锚点，但共享批注/导出无原坐标双向入口。本轮复用原Affine2D，仅新增internal几何规则，无renderer/authority/IO/格式或普通App接入。目标：有效裁剪框的PDF原坐标→左上原点/向下y的规范页面，四种顺时针旋转及UserUnit只应用一次，逆映射保留源坐标。

依据：[ISO 32000-1 8.3.2.3及页字典](https://developer.adobe.com/document-services/docs/assets/35e4369068f86065372c18787171a17e/PDF_ISO_32000-1.pdf)：默认用户空间y向上、CropBox定义可见区域、UserUnit定义单位；Rotate按90度倍数顺时针。实现公式为该约定下的推导，尚不是平台renderer适配已验收。

验收：有限非空有效框、合法90度倍数旋转（负值和整圈归一）、正有限UserUnit；输出尺寸为旋转后裁剪尺寸乘单位，限定原PageDescriptor的1..14400，超限/不可逆或不可表示矩阵明确拒绝，不截断/改写源值。候选原框坐标限±1e9，与当前规范输入表达范围保持一致，非PDF格式合法性全集。测试使用原recipe的16个颜色中心锚点、四角、负原点、UserUnit缩放及正逆往返/异常，不用仅矩阵自逆替代独立锚点。

平台adapter必须先解析继承属性，并明确解析有效CropBox/MediaBox裁剪、默认UserUnit和renderer是否已应用旋转/单位；向该规则传入原始有效框，不得把已旋转页面再旋转一次。此处不解析PDF字典、不处理引擎内部坐标/设备像素/缩放或输出裁剪。背景与批注必须消费同一映射，后续导出用逆矩阵；实际native adapter及图文对象/导出仍需同源实际像素验证。

M-1/C1/DEV-041、INK-10/OBJ-03等原依赖不变，PDF-03保持未开发/待验收，不以几何子范围替代产品叠加。采用后接入真实源/adapter时须绑定source hash/index、框/rotation/UserUnit及规则版本，拒绝复用不同来源的派生映射。

## 实际结论

采用为后续同源背景/批注/导出adapter的双向几何规则，并收口本子范围。新增6项共同测试通过，共546项JVM XML零失败，shared本轮执行、canvas复用；[绑定报告](../reports/pdf-page-geometry-rules.json)。可移植性/架构通过。无平台/renderer/App改动，不重建未变化平台包或重绑旧系统像素报告；PDF-03仍未开发/待验收。后续优先核对真实引擎输入单位与同源实际像素，不再增加平行矩阵模型。
