package dev.notecore.app

import dev.notecore.document.DocumentRejection
import dev.notecore.document.PreviewFailureKind
import dev.notecore.document.PreviewSessionFailure

internal fun PreviewSessionFailure?.previewMessage(): String = when (this?.kind) {
    PreviewFailureKind.CLOSED -> "此会话已关闭，请重新打开笔记"
    PreviewFailureKind.PENDING_STROKE -> "上一笔尚未提交，请先重试保存"
    PreviewFailureKind.ACTIVE_STROKE -> "请先完成当前笔画"
    PreviewFailureKind.INVALID_ARGUMENT -> "操作参数无效，请检查后重试"
    PreviewFailureKind.MISSING_PAGE -> "页面不存在"
    PreviewFailureKind.CAPACITY -> "此预览已达到笔迹容量"
    PreviewFailureKind.IDENTITY_EXHAUSTED -> "此会话编号已耗尽，请保留笔记并导出备份"
    PreviewFailureKind.HISTORY_EMPTY -> "没有可撤销或重做的操作"
    PreviewFailureKind.DOCUMENT_REJECTED -> when (this?.documentReason) {
        DocumentRejection.JOURNAL_CAPACITY, DocumentRejection.HISTORY_CAPACITY,
        DocumentRejection.LIVE_BYTE_CAPACITY, DocumentRejection.IDENTITY_CAPACITY,
        DocumentRejection.OBJECT_CAPACITY, DocumentRejection.PAGE_CAPACITY ->
            "当前预览已达到编辑容量，请保留笔记并导出备份"
        else -> "操作未完成，原内容仍保留，请重试"
    }
    PreviewFailureKind.NO_ACTIVE_STROKE -> "没有进行中的笔画"
    PreviewFailureKind.INVALID_INPUT -> "输入数据无效"
    PreviewFailureKind.INPUT_BATCH_CAPACITY -> "输入批次超限"
    PreviewFailureKind.READ_ONLY -> "阅读模式不能修改笔记"
    PreviewFailureKind.UNSUPPORTED_BRUSH -> "压感笔将在后续版本提供"
    PreviewFailureKind.EMPTY_SELECTION -> "请先圈选要操作的笔迹"
    null -> "当前操作无法完成"
}
