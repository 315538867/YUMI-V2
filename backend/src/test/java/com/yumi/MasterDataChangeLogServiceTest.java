package com.yumi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yumi.catalog.changelog.MasterDataChangeLogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MasterDataChangeLogServiceTest {

    @Autowired
    MasterDataChangeLogService service;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void appendsBeforeAfterSnapshotsWithReasonAndAdmin() throws Exception {
        service.record("PRODUCT", 999L, "P00099",
                java.util.Map.of("name", "旧名"), java.util.Map.of("name", "新名"),
                "改名", "log-test-admin", "log-req-1");

        var row = jdbcTemplate.queryForMap(
                "SELECT before_json, after_json, reason, admin_username, request_id FROM master_data_change_logs "
                        + "WHERE entity_type = 'PRODUCT' AND entity_id = 999 ORDER BY id DESC LIMIT 1");
        JsonNode before = objectMapper.readTree((String) row.get("before_json"));
        JsonNode after = objectMapper.readTree((String) row.get("after_json"));
        assertThat(before.get("name").asText()).isEqualTo("旧名");
        assertThat(after.get("name").asText()).isEqualTo("新名");
        assertThat(row.get("reason")).isEqualTo("改名");
        assertThat(row.get("admin_username")).isEqualTo("log-test-admin");
        assertThat(row.get("request_id")).isEqualTo("log-req-1");
    }

    @Test
    void historyIsAppendOnlyMultipleEditsProduceMultipleRows() {
        service.record("CUSTOMER", 888L, "C00088", null, java.util.Map.of("note", "v1"), null, "a", "r1");
        service.record("CUSTOMER", 888L, "C00088", java.util.Map.of("note", "v1"),
                java.util.Map.of("note", "v2"), "改备注", "a", "r2");

        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_data_change_logs WHERE entity_type='CUSTOMER' AND entity_id = 888",
                Integer.class);
        assertThat(count).isEqualTo(2);
    }
}
