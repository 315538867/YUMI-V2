package com.yumi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FileApiTest {

    private static final String USERNAME = "file-test-admin";
    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String FILE_PREFIX = "file-api-test-";
    private static final Path FILES_DIR = Path.of(System.getenv().getOrDefault("YUMI_FILES_DIR", "/tmp/yumi-files"));

    @Autowired
    MockMvc mockMvc;
    @Autowired
    org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired
    JdbcTemplate jdbcTemplate;

    private jakarta.servlet.http.Cookie sessionCookie;

    @BeforeEach
    void createTestAdminAndLogin() throws Exception {
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
        jdbcTemplate.update("""
                INSERT INTO admin_accounts (
                    username, password_hash, status, version, created_at, updated_at
                ) VALUES (?, ?, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, USERNAME, passwordEncoder.encode(PASSWORD));
        MvcResult login = mockMvc.perform(post("/api/session")
                        .contentType("application/json")
                        .content("""
                                {"username":"file-test-admin","password":"CorrectHorseBatteryStaple!"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        sessionCookie = login.getResponse().getCookie("YUMI_SESSION");
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM file_metadata WHERE original_filename LIKE ?", FILE_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM admin_accounts WHERE username = ?", USERNAME);
    }

    @Test
    void uploadsJpegAndPersistsMetadataAndBytes() throws Exception {
        byte[] bytes = payload(16, (byte) 0x31);
        String sha = sha256(bytes);

        MvcResult result = upload(jpegFile(bytes))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.objectKey").value(sha))
                .andExpect(jsonPath("$.data.contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.data.size").value(bytes.length))
                .andExpect(jsonPath("$.data.sha256").value(sha))
                .andReturn();

        long fileId = JSON.readTree(result.getResponse().getContentAsString())
                .path("data").path("fileId").asLong();
        var rows = jdbcTemplate.queryForList(
                "SELECT object_key, original_filename, content_type, content_length, sha256 FROM file_metadata WHERE id = ?",
                fileId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("object_key")).isEqualTo(sha);
        assertThat(rows.get(0).get("original_filename")).isEqualTo("file-api-test-photo.jpg");
        assertThat(rows.get(0).get("content_type")).isEqualTo("image/jpeg");
        assertThat(((Number) rows.get(0).get("content_length")).longValue()).isEqualTo(bytes.length);
        assertThat(rows.get(0).get("sha256")).isEqualTo(sha);
        assertThat(Files.readAllBytes(FILES_DIR.resolve(sha))).isEqualTo(bytes);
    }

    @Test
    void duplicateBytesUploadReturnsSameFileIdWithoutNewRow() throws Exception {
        byte[] bytes = payload(37, (byte) 0x7a);
        String sha = sha256(bytes);

        long firstId = fileId(upload(jpegFile(bytes)).andReturn());
        long before = countBySha(sha);

        long secondId = fileId(upload(jpegFile(bytes)).andReturn());

        assertThat(secondId).isEqualTo(firstId);
        assertThat(countBySha(sha)).isEqualTo(before).isEqualTo(1);
    }

    @Test
    void acceptsPngAndWebp() throws Exception {
        byte[] png = payload(11, (byte) 0x50);
        byte[] webp = payload(13, (byte) 0x57);

        upload(new MockMultipartFile("file", FILE_PREFIX + "a.png", "image/png", png))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentType").value("image/png"))
                .andExpect(jsonPath("$.data.sha256").value(sha256(png)));

        upload(new MockMultipartFile("file", FILE_PREFIX + "a.webp", "image/webp", webp))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentType").value("image/webp"))
                .andExpect(jsonPath("$.data.sha256").value(sha256(webp)));
    }

    @Test
    void rejectsGifAndNonImageMimeWithFieldErrors() throws Exception {
        upload(new MockMultipartFile("file", FILE_PREFIX + "a.gif", "image/gif", payload(9, (byte) 0x61)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("file"));

        upload(new MockMultipartFile("file", FILE_PREFIX + "a.txt", "text/plain", payload(9, (byte) 0x62)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("file"));
    }

    @Test
    void rejectsOversizeUpload() throws Exception {
        byte[] oversize = new byte[5 * 1024 * 1024 + 1];

        upload(new MockMultipartFile("file", FILE_PREFIX + "big.jpg", "image/jpeg", oversize))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_INVALID"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("file"));
    }

    @Test
    void rejectsUploadWithoutAuthentication() throws Exception {
        mockMvc.perform(multipart("/api/files", jpegFile(payload(8, (byte) 0x44)))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void downloadsBytesWithOriginalContentType() throws Exception {
        byte[] bytes = payload(29, (byte) 0x59);
        long fileId = fileId(upload(jpegFile(bytes)).andReturn());

        mockMvc.perform(get("/api/files/{id}", fileId).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string("Content-Disposition", "inline"))
                .andExpect(content().bytes(bytes))
                .andExpect(result -> assertThat(sha256(result.getResponse().getContentAsByteArray()))
                        .isEqualTo(sha256(bytes)));
    }

    @Test
    void downloadsMissingFileAsNotFound() throws Exception {
        mockMvc.perform(get("/api/files/{id}", 999_999_999L).cookie(sessionCookie))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private org.springframework.test.web.servlet.ResultActions upload(MockMultipartFile file) throws Exception {
        return mockMvc.perform(multipart("/api/files").file(file)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .cookie(sessionCookie));
    }

    private MockMultipartFile jpegFile(byte[] bytes) {
        return new MockMultipartFile("file", FILE_PREFIX + "photo.jpg", "image/jpeg", bytes);
    }

    private long fileId(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString())
                .path("data").path("fileId").asLong();
    }

    private long countBySha(String sha) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM file_metadata WHERE sha256 = ?", Long.class, sha);
        return count == null ? 0 : count;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static byte[] payload(int length, byte seed) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (seed + i);
        }
        return bytes;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
