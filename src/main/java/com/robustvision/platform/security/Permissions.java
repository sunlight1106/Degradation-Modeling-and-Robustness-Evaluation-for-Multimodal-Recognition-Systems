package com.robustvision.platform.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Permissions {
    private Permissions() {}

    public static final List<Definition> CATALOG = List.of(
            new Definition("dashboard:read", "查看总览", "工作台"),
            new Definition("knowledge:read", "查看知识库主题与知识卡", "知识库"),
            new Definition("knowledge:write", "创建和编辑知识库主题与知识卡", "知识库"),
            new Definition("note:read", "查看自己的笔记", "知识库"),
            new Definition("note:write", "创建、编辑、导出和分享笔记", "知识库"),
            new Definition("file:read", "查看与下载自己的文件", "文件"),
            new Definition("file:read:any", "查看与下载全部文件", "文件"),
            new Definition("file:write", "上传文件", "文件"),
            new Definition("experiment:read", "查看自己的实验", "实验"),
            new Definition("experiment:read:any", "查看全部实验", "实验"),
            new Definition("experiment:run", "运行模型实验", "实验"),
            new Definition("report:download", "下载实验报告", "实验"),
            new Definition("model:read", "查看模型", "模型"),
            new Definition("model:write", "管理模型", "模型"),
            new Definition("billing:read", "查看自己的余额与账单", "计费"),
            new Definition("billing:recharge", "创建本地充值订单", "计费"),
            new Definition("billing:read:any", "查看供应商预算与额度", "计费"),
            new Definition("billing:manage", "调整用户余额、配额与供应商预算", "计费"),
            new Definition("credential:manage", "管理模型供应商密钥", "模型"),
            new Definition("workspace:manage", "创建和管理工作空间", "协作"),
            new Definition("message:read", "收发站内信", "协作"),
            new Definition("user:read", "查看用户", "系统管理"),
            new Definition("user:write", "管理用户", "系统管理"),
            new Definition("role:read", "查看角色权限", "系统管理"),
            new Definition("role:write", "修改角色权限", "系统管理")
    );

    public static Set<String> allCodes() {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        CATALOG.forEach(item -> values.add(item.code()));
        return values;
    }

    public static Set<String> researcher() {
        return new LinkedHashSet<>(List.of(
                "dashboard:read", "file:read", "file:write", "experiment:read",
                "experiment:run", "report:download", "model:read", "billing:read", "billing:recharge",
                "workspace:manage", "message:read",
                "knowledge:read", "knowledge:write", "note:read", "note:write"
        ));
    }

    public static Set<String> viewer() {
        return new LinkedHashSet<>(List.of(
                "dashboard:read", "file:read", "experiment:read", "report:download", "model:read", "billing:read",
                "workspace:manage", "message:read",
                "knowledge:read", "note:read"
        ));
    }

    public record Definition(String code, String label, String group) {}
}
