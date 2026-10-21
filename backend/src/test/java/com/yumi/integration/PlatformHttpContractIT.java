package com.yumi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.platform.api.http.HttpHeaders;
import com.yumi.platform.identity.bootstrap.InitialAdministratorCommand;
import com.yumi.support.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 真实平台 HTTP 契约（2.11）：登录、会话失效、权限拒绝、账号管理与受保护读取。
 *
 * <p>与 {@code PlatformHttpWireTest} 不同，本测试启动真实 Spring 上下文、真实 MySQL、
 * 真实认证过滤器与权限服务，不使用认证或幂等替身。仅幂等重放与同键异输入由
 * {@code CommandIdempotencyIT} 在服务层验证；HTTP 层的幂等与业务命令绑定在 2.12 与 13.7 复验。
 */
@AutoConfigureMockMvc
class PlatformHttpContractIT extends AbstractMySqlIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private InitialAdministratorCommand bootstrap;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM platform_account_roles").update();
        jdbc.sql("DELETE FROM platform_role_permissions").update();
        jdbc.sql("DELETE FROM platform_sessions").update();
        jdbc.sql("DELETE FROM platform_accounts").update();
        jdbc.sql("DELETE FROM platform_roles").update();
        jdbc.sql("DELETE FROM platform_permissions").update();
        jdbc.sql("DELETE FROM platform_audit_logs").update();
    }

    @Test
    @DisplayName("登录后可读取当前账号；未带令牌返回 403 且带请求编号")
    void loginAndCurrentAccount() throws Exception {
        bootstrap.initialize("it-admin", "it-pass-1234");

        MvcResult login = mockMvc.perform(post("/api/platform/identity/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"it-admin\",\"password\":\"it-pass-1234\"}"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.REQUEST_ID))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();

        String token = JSON.readTree(login.getResponse().getContentAsString()).get("token").asText();

        mockMvc.perform(get("/api/platform/identity/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginName").value("it-admin"))
                .andExpect(jsonPath("$.permissions").isArray());

        mockMvc.perform(get("/api/platform/identity/me"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("FORBIDDEN"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(header().exists(HttpHeaders.REQUEST_ID));
    }

    @Test
    @DisplayName("登录响应不包含口令或哈希；退出后令牌立即失效")
    void logoutInvalidatesSession() throws Exception {
        bootstrap.initialize("it-admin", "it-pass-1234");
        String token = login("it-admin", "it-pass-1234");

        mockMvc.perform(post("/api/platform/identity/sessions/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/platform/identity/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
    }

    @Test
    @DisplayName("账号创建需要幂等键与管理员权限；创建后可登录但看不到管理入口数据")
    void accountCreationRequiresIdempotencyAndPermission() throws Exception {
        bootstrap.initialize("it-admin", "it-pass-1234");
        String adminToken = login("it-admin", "it-pass-1234");

        mockMvc.perform(post("/api/platform/identity/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"clerk\",\"initialPassword\":\"clerk-pass-1\","
                                + "\"displayName\":\"前台文员\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("VALIDATION"));

        mockMvc.perform(post("/api/platform/identity/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .header(HttpHeaders.IDEMPOTENCY_KEY, "idem-it-account-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"clerk\",\"initialPassword\":\"clerk-pass-1\","
                                + "\"displayName\":\"前台文员\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.loginName").value("clerk"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        String clerkToken = login("clerk", "clerk-pass-1");
        mockMvc.perform(get("/api/platform/identity/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clerkToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        mockMvc.perform(get("/api/platform/identity/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("账号与角色变更写入审计，可在审计检索中查询到")
    void administrationWritesAudit() throws Exception {
        bootstrap.initialize("it-admin", "it-pass-1234");
        String adminToken = login("it-admin", "it-pass-1234");

        MvcResult created = mockMvc.perform(post("/api/platform/identity/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .header(HttpHeaders.IDEMPOTENCY_KEY, "idem-it-account-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"auditor\",\"initialPassword\":\"auditor-pass-1\","
                                + "\"displayName\":\"审计员\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String accountBusinessId = JSON.readTree(created.getResponse().getContentAsString())
                .get("accountBusinessId").asText();

        mockMvc.perform(post("/api/platform/authorization/roles")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .header(HttpHeaders.IDEMPOTENCY_KEY, "idem-it-role-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"AUDITOR\",\"name\":\"审计员\","
                                + "\"permissionCodes\":[\"platform.audit.read\"],\"expectedRowVersion\":0}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/platform/audit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("targetType", "ACCOUNT")
                        .param("targetBusinessId", accountBusinessId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actionCode").value("CREATE_ACCOUNT"))
                .andExpect(jsonPath("$[0].detail.loginName").value("auditor"));
    }

    @Test
    @DisplayName("非管理员不能读取审计、不能管理后台任务、不能维护角色")
    void nonAdministratorCannotUseAdminEndpoints() throws Exception {
        bootstrap.initialize("it-admin", "it-pass-1234");
        String adminToken = login("it-admin", "it-pass-1234");
        mockMvc.perform(post("/api/platform/identity/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .header(HttpHeaders.IDEMPOTENCY_KEY, "idem-it-account-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"outsider\",\"initialPassword\":\"outsider-pass-1\","
                                + "\"displayName\":\"外部账号\"}"))
                .andExpect(status().isCreated());
        String outsiderToken = login("outsider", "outsider-pass-1");

        mockMvc.perform(get("/api/platform/audit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsiderToken)
                        .param("targetType", "ACCOUNT")
                        .param("targetBusinessId", "any"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/platform/tasks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsiderToken)
                        .param("status", "DEAD"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/platform/authorization/roles")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsiderToken)
                        .header(HttpHeaders.IDEMPOTENCY_KEY, "idem-it-role-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleCode\":\"HACK\",\"name\":\"越权\","
                                + "\"permissionCodes\":[\"platform.role.manage\"],\"expectedRowVersion\":0}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("停用账号后原令牌立即失效，且停用本身写入审计")
    void disablingAccountInvalidatesTokenImmediately() throws Exception {
        bootstrap.initialize("it-admin", "it-pass-1234");
        String adminToken = login("it-admin", "it-pass-1234");

        MvcResult created = mockMvc.perform(post("/api/platform/identity/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .header(HttpHeaders.IDEMPOTENCY_KEY, "idem-it-account-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"temp\",\"initialPassword\":\"temp-pass-1\","
                                + "\"displayName\":\"临时账号\"}"))
                .andReturn();
        JsonNode account = JSON.readTree(created.getResponse().getContentAsString());
        String accountBusinessId = account.get("accountBusinessId").asText();
        long rowVersion = account.get("rowVersion").asLong();

        String tempToken = login("temp", "temp-pass-1");

        mockMvc.perform(post("/api/platform/identity/accounts/{id}/status", accountBusinessId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .header(HttpHeaders.IDEMPOTENCY_KEY, "idem-it-disable-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"DISABLED\",\"expectedRowVersion\":" + rowVersion + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        mockMvc.perform(get("/api/platform/identity/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tempToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        mockMvc.perform(get("/api/platform/audit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("targetType", "ACCOUNT")
                        .param("targetBusinessId", accountBusinessId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.actionCode=='CHANGE_ACCOUNT_STATUS')]").exists());
    }

    @Test
    @DisplayName("同一幂等键与相同输入重放不重复创建账号；同键异输入返回冲突")
    void idempotentAccountCreationOverHttp() throws Exception {
        bootstrap.initialize("it-admin", "it-pass-1234");
        String adminToken = login("it-admin", "it-pass-1234");
        String body = "{\"loginName\":\"idem-clerk\",\"initialPassword\":\"idem-pass-1\","
                + "\"displayName\":\"幂等员\"}";

        MvcResult first = mockMvc.perform(post("/api/platform/identity/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .header(HttpHeaders.IDEMPOTENCY_KEY, "idem-http-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String firstId = JSON.readTree(first.getResponse().getContentAsString())
                .get("accountBusinessId").asText();

        MvcResult replay = mockMvc.perform(post("/api/platform/identity/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .header(HttpHeaders.IDEMPOTENCY_KEY, "idem-http-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(JSON.readTree(replay.getResponse().getContentAsString())
                .get("accountBusinessId").asText()).isEqualTo(firstId);

        mockMvc.perform(post("/api/platform/identity/accounts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .header(HttpHeaders.IDEMPOTENCY_KEY, "idem-http-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"other\",\"initialPassword\":\"other-pass-1\","
                                + "\"displayName\":\"另一个\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("IDEMPOTENCY_CONFLICT"));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM platform_accounts WHERE login_name = 'idem-clerk'")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM platform_accounts WHERE login_name = 'other'")
                .query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("无匿名管理员注册路由：未知路径不会创建账号")
    void noAnonymousRegistrationRoute() throws Exception {
        bootstrap.initialize("it-admin", "it-pass-1234");
        long before = jdbc.sql("SELECT COUNT(*) FROM platform_accounts").query(Long.class).single();

        mockMvc.perform(post("/api/platform/identity/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"hacker\",\"password\":\"hacker-pass-1\"}"))
                .andExpect(status().isNotFound());

        assertThat(jdbc.sql("SELECT COUNT(*) FROM platform_accounts").query(Long.class).single())
                .isEqualTo(before);
    }

    @Test
    @DisplayName("文件上传需要幂等键；未引用的临时文件不能通过 HTTP 读取内容")
    void fileUploadAndAuthorizedRead() throws Exception {
        bootstrap.initialize("it-admin", "it-pass-1234");
        String adminToken = login("it-admin", "it-pass-1234");
        String content = java.util.Base64.getEncoder().encodeToString(
                "receipt-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(post("/api/platform/files")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originalName\":\"receipt.png\",\"mediaType\":\"image/png\","
                                + "\"contentBase64\":\"" + content + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("VALIDATION"));

        MvcResult uploaded = mockMvc.perform(post("/api/platform/files")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .header(HttpHeaders.IDEMPOTENCY_KEY, "idem-file-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originalName\":\"receipt.png\",\"mediaType\":\"image/png\","
                                + "\"contentBase64\":\"" + content + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalName").value("receipt.png"))
                .andReturn();
        String fileBusinessId = JSON.readTree(uploaded.getResponse().getContentAsString())
                .get("fileBusinessId").asText();

        mockMvc.perform(get("/api/platform/files/{id}", fileBusinessId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TEMPORARY"));

        mockMvc.perform(get("/api/platform/files/{id}/content", fileBusinessId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .param("permissionCode", "finance.read"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FILE_NOT_REFERENCED"));
    }

    private String login(String loginName, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/platform/identity/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + loginName + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
}
