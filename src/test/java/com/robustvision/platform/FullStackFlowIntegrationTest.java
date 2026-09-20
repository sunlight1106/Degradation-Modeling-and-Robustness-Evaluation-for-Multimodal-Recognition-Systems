package com.robustvision.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.bootstrap.enabled=true",
        "app.bootstrap.admin-username=admin",
        "app.bootstrap.admin-password=TestPassword123!",
        "app.bootstrap.admin-email=admin@test.local"
})
@AutoConfigureMockMvc
@Import(FullStackFlowIntegrationTest.FastPasswordConfig.class)
class FullStackFlowIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void loginUploadRunCompareDownloadAndAdminFlowWorks() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"TestPassword123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.roleCode").value("ADMIN"))
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsByteArray())
                .path("data").path("token").asText();
        assertThat(token).isNotBlank();

        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        MockMultipartFile image = new MockMultipartFile("file", "sample.png", "image/png", png);
        MockMultipartHttpServletRequestBuilder upload = multipart("/api/v1/files").file(image);
        MvcResult uploadResult = mockMvc.perform(upload.header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentType").value("image/png"))
                .andReturn();
        String fileId = objectMapper.readTree(uploadResult.getResponse().getContentAsByteArray())
                .path("data").path("id").asText();

        MvcResult modelsResult = mockMvc.perform(get("/api/v1/models").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(8))
                .andReturn();
        JsonNode models = objectMapper.readTree(modelsResult.getResponse().getContentAsByteArray()).path("data");
        long modelId = 0;
        for (JsonNode model : models) {
            if ("LICENSE_PLATE".equals(model.path("taskType").asText())) modelId = model.path("id").asLong();
        }
        assertThat(modelId).isPositive();

        String request = objectMapper.writeValueAsString(java.util.Map.of(
                "fileId", fileId,
                "modelId", modelId,
                "taskType", "LICENSE_PLATE",
                "enhancementEnabled", true
        ));
        MvcResult taskResult = mockMvc.perform(post("/api/v1/inference/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.optimizedResult.adapter").value("DEMO"))
                .andReturn();
        JsonNode task = objectMapper.readTree(taskResult.getResponse().getContentAsByteArray()).path("data");
        // 基线路与优化路使用不同输入文件（原图 / 增强图），确定性演示适配器的置信度由各自
        // 文件哈希推导，跨文件不存在"优化必然更高"的必然关系。此处校验双路对比链路完整、
        // 取值合法，而不是断言平台明确声明不成立的准确率提升命题。
        double baselineConfidence = task.path("baselineConfidence").asDouble();
        double optimizedConfidence = task.path("optimizedConfidence").asDouble();
        assertThat(baselineConfidence).isBetween(0.0, 1.0);
        assertThat(optimizedConfidence).isBetween(0.0, 1.0);
        assertThat(task.path("baselineResult").path("adapter").asText()).isEqualTo("DEMO");
        assertThat(task.path("optimizedResult").path("route").path("strategies").size()).isPositive();

        String taskId = task.path("id").asText();

        mockMvc.perform(get("/api/v1/inference/tasks/{id}/report", taskId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].username").value("admin"));

        MvcResult rolesResult = mockMvc.perform(get("/api/v1/roles")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode roles = objectMapper.readTree(rolesResult.getResponse().getContentAsByteArray()).path("data");
        long researcherRoleId = 0;
        for (JsonNode role : roles) {
            if ("RESEARCHER".equals(role.path("code").asText())) researcherRoleId = role.path("id").asLong();
        }
        assertThat(researcherRoleId).isPositive();

        String createUserRequest = objectMapper.writeValueAsString(java.util.Map.of(
                "username", "researcher01",
                "password", "Research123!",
                "displayName", "研究员",
                "email", "researcher@test.local",
                "roleId", researcherRoleId
        ));
        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleCode").value("RESEARCHER"));

        MvcResult researcherLogin = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"researcher01\",\"password\":\"Research123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String researcherToken = objectMapper.readTree(researcherLogin.getResponse().getContentAsByteArray())
                .path("data").path("token").asText();

        mockMvc.perform(get("/api/v1/models").header("Authorization", "Bearer " + researcherToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + researcherToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/files/{id}/content", fileId)
                        .header("Authorization", "Bearer " + researcherToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/inference/tasks/{id}/report", taskId)
                        .header("Authorization", "Bearer " + researcherToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicCatalogRegistrationPasswordPolicyAndBillingWork() throws Exception {
        mockMvc.perform(get("/api/v1/public/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(8));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"weak.user\",\"email\":\"weak@test.local\",\"password\":\"onlyletters\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_TOO_WEAK"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"self.registered\",\"email\":\"self.registered@test.local\",\"password\":\"Register2026!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roleCode").value("RESEARCHER"));

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"self.registered\",\"password\":\"Register2026!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsByteArray()).path("data").path("token").asText();
        mockMvc.perform(get("/api/v1/billing/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wallet.balanceCny").value(20.0))
                .andExpect(jsonPath("$.data.wallet.monthlyQuotaCny").value(100.0));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FastPasswordConfig {
        @Bean
        @Primary
        PasswordEncoder fastPasswordEncoder() {
            return new BCryptPasswordEncoder(4);
        }
    }
}
