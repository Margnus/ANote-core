# ADR-0016：同authority分页会话与双端宿主接入

2026-10-15；DEV-024D实施前候选。交付A–C已提供根、delta历史和codec，当前App仍单页v1；本文件不作为已实现证据。

## 组件职责

shared-core增加薄分页预览会话：只持一个MemoryDocumentAuthority、当前PageId、工具/输入会话和待重试完成笔。DocumentRoot/SceneSnapshot为唯一内容状态；禁止每页一个PreviewNotebook或保存时拼场景。页目录、对象、历史、root revision均由现有authority发布。会话只做命令组合、输入捕获与只读renderer投影，不把原生View、系统事件或文件IO放进文档域。

身份由原生UUID文档ID与每次打开的新session UUID前缀生成，带有界单调计数，allocator耗尽显式拒绝。不继续用可复用的页号或ink计数重建退役身份。原生所有权检查在UI owner创建会话时绑定；worker只解码不可变root，不能创建另一个后台可变文档owner。Activity/SwiftUI重建从存储owner取得同一活跃会话，只有保存完成且没有未交接责任时才可释放。

保存账本需先从旧单页snapshot具体类型抽离为不可变保存输入协议，包含document identity、content revision、retained/encode work charge与编码能力。旧snapshot与分页snapshot适配同一有界latest/in-flight/failed策略，不复制两份状态机；core不反向import文档域。一次root capture既有全部页面又不可变，worker不读取selected page/UI集合。旧写回只确认其attempt，不能清掉后续任一页面的修改。same-version只属于同一canonical owner的同一内容，不能借此覆盖不同分支。

## 页面交互与输入

1. 当前PageId、缩放/平移和页选择为session状态，切页不提高内容revision。移动当前页保持PageId；复制在源页后插入并选择副本；删除当前页在成功后选择原位置的下一页，否则前一页。最后页清空继续保留ID。history遍历后优先保留仍存在的当前PageId，已删除才重新选择合法邻页，不凭旧页号跳转。
2. 页面操作前取消未完成输入；已经完成但未提交的笔保留原target/command并阻止使其基线失效的页操作，先重试或交接。容量拒绝不能丢笔，也不能仅显示保存成功。页选择与变换锁在同一UI owner协调，不能让UP落到新选择页。
3. renderer只投影选中页规范对象，保持全局绘制顺序，不建立另一份可变笔迹数组。页面尺寸参与viewport正逆映射及适页，切换不同尺寸要复位/校验视口。切页/旋转/detach推进展示generation；迟到frame不能覆盖新页。颜色/粗细/手指策略继续属于session，不能因后台保存改写工具。
4. undo/redo传当前root base和顶部原edit operationId，不按按钮启用时缓存的旧index操作；失败保持UI选择和栈一致。单页与多页的编辑上限、失败重试/导出提示使用产品语言；生产journal退休必须有已确认/未知结果屏障，不在UI里悄悄clear。

## 存储与迁入

列表只读最多4KiB两行header，通过codec区分v1/v2；损坏/不支持与空目录区分。首次打开v1保留原文件字节，用户后续编辑触发保存才用v2原子替换；新建文档直接v2。导出对应一个捕获根，期间编辑不改变导出；导入校验完整body后分配fresh文档/页/layer/对象映射，不覆盖源文件。分配前的文件32MiB/严格UTF-8与codec16M字符/样本准入同时保留，IO work/result预算持续到owner ACK。

失败保存从最新owner snapshot重开而非过期磁盘；未知/失败结果保留责任。导入/打开的旧session回调不能覆盖后来重新打开的同ID会话。预览文件不含history/journal/tombstone，重新建立session必须明确撤销范围不跨关闭持久化；正式跨进程未知operation恢复另由shared-data事务实现。不得把重开重置内存journal当作生产回执退休策略。

## 可测交付顺序

- D1：共享分页会话及只读capture，验证PageId选择、copy/delete/undo/redo、捕获输入/待重试保留、不同页尺寸与session身份。先common及实际owner线程，平台IO仍独立。
- D2：保存输入协议与原账本共用状态机，保持旧单页测试，增加旧页在途/新页修改、失败重开、旧attempt/旧session回调、预算拒绝不关闭等交错。
- D3：Android目录/v1-v2 IO及分页原生控件接入；在专用模拟器完成新建多页、逐页不同笔迹、重排/复制/删除/恢复、保存强停重开、旧v1编辑迁入、v2导入导出和原件保留。更新冒烟脚本以规范内容核对副本，不能沿用“只改D行”的v1身份假设。
- D4：iOS使用同一共享会话/codec/账本接口，SwiftUI/UIKit增加分页与不同尺寸视口；先framework/宿主编译，再在具备runtime/设备后实测对应场景。无runtime不能填写运行通过，Android结果不能代替iOS。

每个节点提交真实证据；没有跨页真实保存/重开就不将DEV-024D/PAGE-02/03标完成。高点数原生渲染、稳定性/低内存、正式receipt持久化与C1冻结继续保持独立门禁。

## 已实现的D1共享会话参考

PagedPreviewNotebook持有唯一MemoryDocumentAuthority、稳定selected PageId、输入与pending完成命令。渲染访问使用按root revision/PageId失效的只读projection，不在每次pointX/Y调用重新遍历场景。投影提供完整规范StrokeObject，原生接入时必须用其矩阵处理笔宽/覆盖关系，不能只变换中心线后沿用原宽度假称完整仿射渲染。

Down前预留命令/对象身份；完成笔即使因journal/history/live容量拒绝也保留原command及样本，cancel只取消未完成输入，页面编辑在pending期间拒绝。切换不同PageId取消未完成笔，迟到UP无活动会话不能落到新页；再次选择同页不取消正在写的笔。copy选择源页后副本；delete选择合法邻页，undo/redo优先保持仍存在的selected ID。页选择不推进根revision，页面尺寸/纸张取规范PageDescriptor。

PagedPreviewSnapshot捕获完整不可变root，旧capture编码不受后续页编辑影响；它只包含已提交内容，不转移pending责任。live预算与codec retained charge统一包含文档ID，避免在恰好边界时出现文档提交成功但snapshot超额。逻辑charge按对象头/点数计算，公开canonical snapshot已在restore/prepare验证几何，因此准入capture不反复扫描不可变点bounds。尚未接入原生保存账本或分页UI，不能把snapshot的内存往返说成文件重开。

10项common会话用例覆盖选择/单revision、copy身份/样本、删页邻页/最后页、输入切页/迟到UP、工具捕获、失败完成命令保留、全根capture/reopen、规范变换/尺寸、坏输入/标题及owner边界；另有真实JVM worker禁止会话读写，合计200项（Core140、shared-core60）。Android ART会话与双端编译分别记产物报告；D2账本泛化、D3 Android实际分页IO/UI及D4 iOS接入/运行仍未完成。

D2模块方向：保存账本和相应测试迁入shared-core，旧Core单页snapshot由shared-core的只读adapter接入，分页snapshot实现同一封闭保存输入协议。Core不为新App引入文档依赖；不复制两个账本。same-version重复capture可走规范引用快路径，失败重开后重建的等价内容也须允许显式重试；不同内容同版本要拒绝，不能回报成功却仍保存旧内容。版本替换、未启动退回、在途/失败保留和旧attempt拒绝沿用同一状态机，用实际双端编译和旧保存故障反例保护过渡。
