package com.robustvision.platform.domain;

/** 笔记引用目标类型。 */
public enum NoteReferenceType {
    /** 引用已上传的图片/视频文件资产 */
    FILE,
    /** 引用某次推理任务，作为可复现证据 */
    TASK,
    /** 引用知识卡 */
    ENTRY
}
