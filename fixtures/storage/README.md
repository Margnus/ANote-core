# 资源引用保护业务样本

resource-gc-cases.json来自蓝图05“文件与数据库联合提交”和DATA-08，使用synthetic不透明字节。fixture id用于观察，不定义生产资源ID/SQLite schema。cutoff仅为显式测试策略，不批准任何真实保留天数。

运行python tools/verify_resource_gc_rules.py。验证器只在build下的唯一临时目录和主机临时SQLite执行候选查询；query期间源文件集合/hash保持，结束清理自建目录，不接触App、个人文件或真实资源。候选不是删除授权，最终事务/读者/崩溃协议未实现；详见docs/资源GC引用保护规则.md。DATA-08不因样本通过晋升实现/验收。
