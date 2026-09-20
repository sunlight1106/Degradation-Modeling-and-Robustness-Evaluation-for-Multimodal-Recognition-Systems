package com.robustvision.platform.config;

import com.robustvision.platform.domain.ModelDefinitionEntity;
import com.robustvision.platform.domain.ModelProvider;
import com.robustvision.platform.domain.RoleEntity;
import com.robustvision.platform.domain.TaskType;
import com.robustvision.platform.domain.UserEntity;
import com.robustvision.platform.repository.ModelDefinitionRepository;
import com.robustvision.platform.repository.RoleRepository;
import com.robustvision.platform.repository.UserRepository;
import com.robustvision.platform.security.Permissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "app.bootstrap", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DataInitializer implements CommandLineRunner {
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final ModelDefinitionRepository modelRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String adminPassword;
    private final String adminEmail;
    private final boolean resetAdminPassword;
    private final String testUsername;
    private final String testPassword;
    private final String testEmail;
    private final boolean resetTestPassword;

    public DataInitializer(RoleRepository roleRepository,
                           UserRepository userRepository,
                           ModelDefinitionRepository modelRepository,
                           PasswordEncoder passwordEncoder,
                           @Value("${app.bootstrap.admin-username:admin}") String adminUsername,
                           @Value("${app.bootstrap.admin-password}") String adminPassword,
                           @Value("${app.bootstrap.admin-email:admin@personal-platform.local}") String adminEmail,
                           @Value("${app.bootstrap.reset-admin-password:false}") boolean resetAdminPassword,
                           @Value("${app.bootstrap.test-username:test}") String testUsername,
                           @Value("${app.bootstrap.test-password:}") String testPassword,
                           @Value("${app.bootstrap.test-email:test@personal-platform.local}") String testEmail,
                           @Value("${app.bootstrap.reset-test-password:true}") boolean resetTestPassword) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.modelRepository = modelRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        if (adminPassword == null || adminPassword.length() < 12) {
            throw new IllegalArgumentException("BOOTSTRAP_ADMIN_PASSWORD must contain at least 12 characters");
        }
        this.adminPassword = adminPassword;
        this.adminEmail = adminEmail;
        this.resetAdminPassword = resetAdminPassword;
        this.testUsername = testUsername;
        // 留空表示不创建体验账号；一旦设置则要求达到最低强度，避免弱口令账号被共享使用
        if (testPassword != null && !testPassword.isBlank() && testPassword.length() < 8) {
            throw new IllegalArgumentException("BOOTSTRAP_TEST_PASSWORD must contain at least 8 characters");
        }
        this.testPassword = testPassword;
        this.testEmail = testEmail;
        this.resetTestPassword = resetTestPassword;
    }

    @Override
    @Transactional
    public void run(String... args) {
        RoleEntity admin = roleRepository.findByCode("ADMIN").orElseGet(() ->
                roleRepository.save(new RoleEntity("ADMIN", "管理员", "平台全部管理权限", Permissions.allCodes())));
        admin.setPermissions(Permissions.allCodes());
        roleRepository.save(admin);
        RoleEntity researcher = roleRepository.findByCode("RESEARCHER").orElseGet(() ->
                roleRepository.save(new RoleEntity("RESEARCHER", "研究员", "上传、运行实验与查看结果", Permissions.researcher())));
        researcher.setPermissions(Permissions.researcher());
        roleRepository.save(researcher);
        RoleEntity viewer = roleRepository.findByCode("VIEWER").orElseGet(() ->
                roleRepository.save(new RoleEntity("VIEWER", "查看者", "只读查看已授权内容", Permissions.viewer())));
        viewer.setPermissions(Permissions.viewer());
        roleRepository.save(viewer);

        if (!userRepository.existsByUsername(adminUsername)) {
            userRepository.save(new UserEntity(
                    adminUsername,
                    passwordEncoder.encode(adminPassword),
                    "系统管理员",
                    adminEmail,
                    admin
            ));
        } else if (resetAdminPassword) {
            UserEntity existingAdmin = userRepository.findByUsername(adminUsername).orElseThrow();
            existingAdmin.setPasswordHash(passwordEncoder.encode(adminPassword));
            userRepository.save(existingAdmin);
        }

        // 体验账号：供访客试用平台功能。未配置密码时跳过创建，避免产生无人知晓口令的账号。
        if (testPassword != null && !testPassword.isBlank()) {
            if (!userRepository.existsByUsername(testUsername)) {
                userRepository.save(new UserEntity(
                        testUsername,
                        passwordEncoder.encode(testPassword),
                        "体验账号",
                        testEmail,
                        researcher
                ));
            } else if (resetTestPassword) {
                UserEntity existingTest = userRepository.findByUsername(testUsername).orElseThrow();
                existingTest.setPasswordHash(passwordEncoder.encode(testPassword));
                userRepository.save(existingTest);
            }
        }

        if (!modelRepository.existsByCodeAndVersion("deepseek-v4-flash-vision-exp-lpr", "2026-08-21")) {
            modelRepository.save(new ModelDefinitionEntity(
                    "deepseek-v4-flash-vision-exp-lpr", "DeepSeek V4 Flash Vision · 车牌分析", "2026-08-21", ModelProvider.DEEPSEEK, TaskType.LICENSE_PLATE,
                    "真实视觉 API：车牌文本、输入质量、退化因素与不确定性结构化分析"
            ));
        }
        if (!modelRepository.existsByCodeAndVersion("deepseek-v4-flash-vision-exp-receipt", "2026-08-21")) {
            modelRepository.save(new ModelDefinitionEntity(
                    "deepseek-v4-flash-vision-exp-receipt", "DeepSeek V4 Flash Vision · 票据分析", "2026-08-21", ModelProvider.DEEPSEEK, TaskType.RECEIPT,
                    "真实视觉 API：OCR、关键字段抽取、图像质量和风险提示"
            ));
        }
        seed("kimi-k3-lpr", "Kimi K3 · 车牌分析", ModelProvider.KIMI, TaskType.LICENSE_PLATE,
                "Kimi 多模态识别：车牌、退化因素与不确定性分析");
        seed("kimi-k3-receipt", "Kimi K3 · 票据分析", ModelProvider.KIMI, TaskType.RECEIPT,
                "Kimi 多模态 OCR：票据字段、输入质量与证据说明");
        seed("kimi-k3-video", "Kimi K3 · 视频分析", ModelProvider.KIMI, TaskType.VIDEO_ANALYSIS,
                "视频事件、场景与可见文本分析，支持降噪音轨前后对照");
        seed("qwen3-vl-plus-lpr", "Qwen3 VL Plus · 车牌分析", ModelProvider.QWEN, TaskType.LICENSE_PLATE,
                "通义千问视觉识别与质量分析");
        seed("qwen3-vl-plus-receipt", "Qwen3 VL Plus · 票据分析", ModelProvider.QWEN, TaskType.RECEIPT,
                "通义千问多模态 OCR 与字段抽取");
        seed("qwen3.5-omni-plus-video", "Qwen3.5 Omni Plus · 音视频分析", ModelProvider.QWEN, TaskType.VIDEO_ANALYSIS,
                "同时理解画面、语音和音效，用降噪音轨进行前后结果对照");
    }

    private void seed(String code, String name, ModelProvider provider, TaskType taskType, String description) {
        if (!modelRepository.existsByCodeAndVersion(code, "2026-08-22")) {
            modelRepository.save(new ModelDefinitionEntity(code, name, "2026-08-22", provider, taskType, description));
        }
    }
}
