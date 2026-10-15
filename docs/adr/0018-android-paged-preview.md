# ADR-0018：Android整本分页预览接入

2026-10-15；DEV-024D3。预览实现，公共API及生产C1门禁未冻结。

## 单一owner与IO边界

NoteStore在主线程接管OpenedDocument不可变root并创建唯一PagedPreviewNotebook。worker仅解码root；Activity先检查界面generation，再adopt，过期打开结果不创建可变authority。Activity重建复用同一活跃会话。失败保存重开直接读取账本不可变root，不重复文本编码、解析和复制全部点。

离开编辑器先将完整capture准入原保存账本，再retire活跃会话。仍有pending完成笔时拒绝retire；已关闭会话拒绝迟到书写、页操作和历史编辑，但允许读取已交接的不可变capture。retire不代表磁盘完成；准入后的失败责任仍由原账本持有，列表显示未保存并支持重开重试。

原生预览显式选择journal4096条/32MiB、identity4MiB，history仍128条/8MiB、live8MiB。它只是有界预览额度，不在UI中清空幂等journal，也不是生产历史退休方案。达到容量时仍保留失败完成命令；正式恢复与分块存储另行实现。

## 文件兼容与副本

目录通过最多4KiB header识别v1/v2，完整读取保持32MiB预检、严格UTF-8及codec准入。新建和编辑输出v2；旧v1迁入必须使用旧画布真实的1000×1400尺寸，避免900/1300等原本可见坐标被595×842裁切。新笔记默认595×842，继承尺寸加页。

仅持有一个activeSavedRoot引用，已确认磁盘内容与当前capture根完全相同且账本没有待写时，保存为无IO确认。因此打开、后台、返回或点击保存而没有内容编辑，不会把旧v1改写为v2。首次内容编辑才原子迁入；失败不假称成功。它不是外部编辑文件的监控机制。

导入先完整decode及宿主能力检查，再分配fresh文档/页/layer/对象身份，以copyAsNew共享已验证不可变samples，避免重复解析。写入新文件成功才向界面交付不可变root。完整v2根导出与selected page无关；codec本身仍可保存完整已知笔刷信息。

当前Android预览画笔仅MONOLINE算法1；宿主读取/导入不支持的笔刷或超出Float绘制能力的矩阵时显式失败并保留原件，不将压力笔/荧光笔悄悄画成普通笔。通用组件后续renderer的算法能力协商仍需独立设计。

## 页面与呈现

工具栏提供前后页、加页、复制、删除、前后移动和四种纸张。删除需确认，最后一页清空；撤销记录在关闭后不持久化。所有内容编辑通过共享authority，选择页不产生内容revision或保存。全局撤销/重做后保持合法PageId并刷新标题、页码和画布。

路径按root revision和PageId缓存，避免选择不同页但根revision相同误用旧页路径。viewport按PageDescriptor尺寸适页，切换PageId复位。原始中心线和笔宽经Canvas完整仿射矩阵一起绘制，包含非均匀缩放的圆点；不只变换中心线。原生历史采样和触笔/手指仲裁沿用原输入入口。此缓存仍是全页Path预览，不能当作百万点分块渲染或真机延迟验收。

## 验证入口与剩余

共享测试覆盖不同尺寸正逆映射、旧版右下角笔迹、解码后副本样本共享、关闭后迟到输入及pending阻止关闭。Android独立Instrumentation验证原子写入失败、修复重试、唯一活跃owner、旧v1未编辑逐字节保留、编辑后多页v2与原采样保留。

完整界面回归使用tools/android_preview_smoke.py；多页原生流程使用tools/android_paged_smoke.py，结果必须与最终APK SHA一致。iOS只重新编译共享API并独立启动，Swift分页宿主及书写/保存重开交互接续D4。生产receipt/history持久化、低内存恢复、真实触笔及性能门禁均未完成。
