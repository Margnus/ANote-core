# 文本内容与 IME 提交边界（未冻结设计）

2026-11-09，依据蓝图 OBJ-01/OBJ-02、DATA-03、通用画布组件架构的 TextOverlayPort 与新对象完整契约要求。适用现行计划 C–F 不连接生产的格式设计；不是 TextPayload、IME、文本对象或双端功能已经实现。当前 canonical scene/codec 仍只接受原支持内容，不更改 v1/v2 或预览恢复信封。

## 采用决定

采用“逻辑正文和样式是规范内容；组合输入、选区、glyph、字体回退和布局结果分开”的边界。拒绝把平台 attributed string、glyph index、位图、平台字体路径或仍在 composing 的输入直接作为可跨端的规范文本。

这项决定解决后续 codec/命令对字符身份与输入确认的歧义；不冻结生产 schema、字体政策或 SDK。实现出口必须进入原 DocumentRoot/唯一 authority 的有版本 payload 与原子命令，不能在宿主另存一份持久文本正文来绕过 DATA-03/DEV-041。

## 拟议内容及责任

| 内容 | 规范责任 | 不得混入 |
|---|---|---|
| 稳定 objectId/pageId/layerId、对象 epoch | 沿原对象身份/命令目标与历史；复制分配新 ID | 选区下标、View ID、页序号 |
| text | 合法 Unicode 标量序列，保留原字符次序；不自动 NFC/NFD、trim 或替换换行 | 未提交的 marked/composing 候选、预测文字 |
| defaultStyle 与 runs | 字号、ARGB、加粗及逻辑字体请求；run 使用下述 UTF-16 半开范围 | 平台 Font/Typeface 对象、文件句柄、glyph ID |
| logicalBox | 对象局部坐标的有限正宽高，单位沿页坐标；移动/旋转仅改原对象 transform | 设备像素、实际 glyph bbox、系统窗口尺寸 |
| 字体请求/资源引用 | 指向正式资源目录的稳定身份及请求属性；授权、源 hash/face 由资源契约负责 | 本机绝对路径、未经确认的“指定字体已使用”标志 |
| layout/fallback 结果 | 派生，只绑定精确 payload epoch、布局版本、字体解析结果和约束 | 规范正文、durable 回执或另一个内容 revision |

空文本允许存在，使用 defaultStyle 表示下一次插入样式；空文本 runs 为空。样式相同的相邻 run 可合并，但合并不能改变 text 或下标。非空文本的有效 runs 必须有序、无重叠、无空段，完整覆盖 `[0, text.length)`；缺口不能靠某一平台的默认字体静默补齐。规范 writer 可将默认样式展开为一段，但 reader 必须明确处理版本所定义的表示。

字体/样式的具体序列化编号、数值上限、资源键和布局算法版本，在 DATA-03 的完整对象协议与资源政策中确定，本设计不另造可公开使用的 schema。

## 字符范围与样本

采用 UTF-16 code unit 偏移与半开区间 `[start,end)` 作为跨端命令边界；这是序列化/桥接的单位，不是用户可见字符数。任何位置必须在 `0..text.length` 内，不能位于代理项对中间。非法 UTF-16 原样拒绝，不替换为 U+FFFD 后继续提交。

组合音标、ZWJ emoji、双向文字的交互移动/删除仍由宿主文字系统按实际 grapheme/选区规则产生范围；不能把“合法标量边界”误当成完整用户字符边界。共享层检查给出的范围和目标版本，不凭字体 glyph 数反推字符位置。

固定人工样本（不是用户文档或 IME 实机证据）：`中A😀é`，末尾是 `e` 后接 U+0301。

| 范围 | 预期 |
|---|---|
| UTF-16 长度 | 6，Unicode 标量数为 5；不能混作 glyph 数或 grapheme 数 |
| `[2,4)` | 仅 emoji；复制/替换保留两个代理单元作为一个合法标量 |
| `[2,3)` / 插入位置 3 | 拆开代理对，拒绝且原正文/历史/选区草稿保持 |
| `[4,6)` | `e` 与组合音标；不得自动归一化成另一字符序列 |
| 空选区 `[6,6)` | 合法尾部插入；允许与反向选区分别表达 |

选区状态保留 anchor/focus 的方向；计算替换范围时才取 min/max。命令只带确定的替换范围、插入正文及完整样式结果，不持有会在异步回调中变化的平台 selection 对象。

## 宿主编辑会话与提交

1. 进入编辑前，绑定 documentId/pageId/objectId、对象 epoch 与原 owner，取得不可变初始 payload。命中权交给文本覆盖层，画布输入不得同时接受同一手势；已有活动真实点/完成 pending 按原契约解决后才能进入。
2. IME 的 composing/marked text 和当前选区由这个宿主编辑会话暂存。不得逐次把候选写进规范 payload、历史或显示“已保存”；composition 更新也不得偷偷修改原 scene。
3. 宿主确认组合输入完成后，构造绑定目标及基准版本的单次文本替换命令，经原 authority 做身份/版本/内容/预算准入。新正文、runs 与逻辑框的必要变化必须在同一次提交内生效；不能先改字符再补样式。
4. 原 authority 返回内存接受后，宿主才把该版本作为已采用正文。显示确认及原存储真实 IO 的 durable 确认仍分开；操作接受不代表已保存。相同 opId 重试只认原 payload，异 payload 拒绝。
5. 容量/旧版本/目标消失/只读/owner 失效拒绝时，原规范内容和历史不变；宿主保留已接收的文字草稿并显示明确结果，不能清空草稿后宣称取消成功。重试必须重新核对目标，不得把旧范围套到新正文。
6. 用户主动放弃未提交 composition 与提交已确认文本是不同动作。切页、后台、尺寸变化、关闭、进程恢复的“提交/保留草稿/明确取消”必须由后续双端消费者逐项验证；本设计不假设系统回调天然完成交接，不保证未保存草稿跨进程恢复。

普通输入的历史合并策略尚未冻结。一个已经提交的命令必须原子可撤销；不能因输入合并而吞并另一个对象、IME 会话或已经确认的边界。撤销需要精确 payload/epoch 及原历史预算，不把整页复制当文本 undo。

## 布局、回退与溢出

移动和旋转只改对象 transform，不改 text/runs/逻辑框；改变逻辑框是单独的内容编辑。派生布局必须使用绑定的逻辑框，显式报告实际字体来源和溢出，不能为了“放得下”静默缩字号、删字或改正文。

Android 已有实际字体样本证明 hasGlyph=true 不能证明采用了指定字体，见[字体来源报告](../reports/font-fallback-art.json)；沿用该拒绝决定。回退必须有可观察的来源/状态，原字体请求和正文保持。屏幕/导出必须消费同一份规范 payload 和声明的布局策略；不同 OS 不承诺文字逐像素一致，但不能用渲染差异掩盖字符、逻辑框、样式或变换丢失。

## 实现出口与退出条件

### 旧源码映射的限制

只读检查当前海外 jnotesv2gp 提交 `675fabc3b55197a01979fbbc556874d876512860` 的 DragEditTexter 和 DragEditTexterCreater，三份原件（含下述 PageFragment）与固定 Git 字节或精确 CRLF 检出一致，摘要及范围见[源结构核验](../reports/legacy-text-source-contract.json)。这不是原 Mac 国内源码或任何旧笔记数据的验证结果，不继承旧迁移证据。

- 数据类同时保留全局 textColor/textSize/typeface/isBold 等字段及 underline/bold/italic/color/fontSize/typefaceData 六份范围列表。不能把独立列表直接拼成规范的完整、无重叠 runs；必须先验证实际应用次序、默认样式、越界/重叠处理及旧实例，再生成确定的有效样式分段。
- 进一步核对同一提交的 PageFragment 实际创建调用：先设置 textContent、全局字号/颜色，再依次应用 color、fontSize、typefaceData（经 FontFamilyFactory）、underline、bold、italic，最后分别调用 setAlignment(alignment) 与 setRowSpace(rowSpace)。这补齐源级调用顺序及两个字段的独立消费者，不能推导 TextViewUtil 内部重叠/裁剪语义或当前打包二进制等价；没有拿另一个 drawpanel 源仓冒充实际依赖。
- `rowSpace` 的编码键为 `t7`；`alignment` 编码键为 `t8`，但 alternate 是 `lineSpace`。这明确否决“字段别名就是语义”的映射方式；读取哪个别名不等于采用其字面含义。尚未确认旧运行时语义或所有历史版本。
- 文本链接、回收状态、editType、无界 offset、背景、padding/边框等原字段超出初始基本文本范围。不能因为新版首发只支持基本样式就静默丢弃它们后报告无损迁移；应按蓝图旧数据迁移政策显式保留、降级或报告不支持。
- 创建者注释明确 width/height/textSize 使用数据坐标而非 View 像素。实际坐标/offset/旋转消费者仍须独立核对，不能只凭注释乘一次屏幕 scale 或把位置和变换叠加两次。

采用决定：基础 TextPayload 设计与完整旧格式转换分开；当前不实现或宣布有损转换成功。下一次迁移映射必须取得合法旧文本实例和实际范围/布局消费者证据，保持源文件，再验证往返/保真报告。本次未构建、运行或修改旧工程。

本设计不新增内容 authority、后台队列、文件、公开 API 或普通 App 文本入口。后续生产接入仍需 M-1/C1 与正式资源事务前置；不把该文件登记为 OBJ-01/OBJ-02 实现或验收通过。

下一次实现应在同一对象增量内完成：原 payload/codec 版本与未知版本拒绝、geometry/bounds/index/hit-test、原 authority 原子替换/undo、资源引用、屏幕与导出，以及双端 TextOverlayPort。先用上述代理对/组合字符反例和版本冲突验证原子性；再以 Android/iOS 实际中英文 IME、选区、复制粘贴、手势互斥、拒绝保草稿、后台/关闭重开验证消费者，分别记录范围。

只有原对象链路消费了该内容，并通过适用的持久化/资源/平台验证，才进入原需求验收。若正式对象协议选择不同索引单位，必须提供无损双向映射与同一反例证据后调整此设计，不能只改字段名或按 glyph 计数规避问题。
