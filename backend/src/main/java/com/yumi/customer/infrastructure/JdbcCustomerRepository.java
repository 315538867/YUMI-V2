package com.yumi.customer.infrastructure;

import com.yumi.customer.api.CustomerStatus;
import com.yumi.customer.application.port.CustomerRecord;
import com.yumi.customer.application.port.CustomerRepository;
import com.yumi.platform.api.jdbc.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 客户持久化（MySQL）。 */
@Repository
public class JdbcCustomerRepository implements CustomerRepository {

    private final JdbcClient jdbc;

    public JdbcCustomerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CustomerRecord> findByBusinessId(String customerBusinessId) {
        return jdbc.sql(SELECT + " WHERE customer_business_id = :businessId")
                .param("businessId", customerBusinessId)
                .query(this::map)
                .optional();
    }

    @Override
    public Optional<CustomerRecord> findByNo(String customerNo) {
        return jdbc.sql(SELECT + " WHERE customer_no = :customerNo")
                .param("customerNo", customerNo)
                .query(this::map)
                .optional();
    }

    @Override
    public List<CustomerRecord> search(String keyword, CustomerStatus status, int offset, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        List<String> params = new ArrayList<>();
        sql.append(filterClause(keyword, status));
        sql.append(" ORDER BY customer_no, id LIMIT :limit OFFSET :offset");

        var spec = jdbc.sql(sql.toString());
        spec = bindFilters(spec, keyword, status);
        return spec.param("limit", limit).param("offset", offset).query(this::map).list();
    }

    @Override
    public long count(String keyword, CustomerStatus status) {
        String sql = "SELECT COUNT(*) FROM customer_customers WHERE 1 = 1" + filterClause(keyword, status);
        var spec = bindFilters(jdbc.sql(sql), keyword, status);
        return spec.query(Long.class).single();
    }

    @Override
    public void insert(CustomerRecord record) {
        jdbc.sql("""
                        INSERT INTO customer_customers (
                            customer_business_id, customer_no, name, contact_name, phone, default_address,
                            status, content_version, row_version, created_at, created_by, updated_at, updated_by)
                        VALUES (:businessId, :customerNo, :name, :contactName, :phone, :address,
                                :status, 1, 1, :now, :operatorId, :now, :operatorId)
                        """)
                .param("businessId", record.customerBusinessId())
                .param("customerNo", record.customerNo())
                .param("name", record.name())
                .param("contactName", record.contactName())
                .param("phone", record.phone())
                .param("address", record.defaultAddress())
                .param("status", record.status().name())
                .param("now", JdbcTimes.toUtc(record.createdAt()))
                .param("operatorId", record.createdBy())
                .update();
    }

    @Override
    public boolean updateDetails(
            String customerBusinessId, String name, String contactName, String phone,
            String defaultAddress, long expectedRowVersion, Instant now, String operatorId) {
        return jdbc.sql("""
                        UPDATE customer_customers
                        SET name = :name, contact_name = :contactName, phone = :phone,
                            default_address = :address,
                            content_version = content_version + 1, row_version = row_version + 1,
                            updated_at = :now, updated_by = :operatorId
                        WHERE customer_business_id = :businessId AND row_version = :expectedRowVersion
                        """)
                .param("name", name)
                .param("contactName", contactName)
                .param("phone", phone)
                .param("address", defaultAddress)
                .param("now", JdbcTimes.toUtc(now))
                .param("operatorId", operatorId)
                .param("businessId", customerBusinessId)
                .param("expectedRowVersion", expectedRowVersion)
                .update() == 1;
    }

    @Override
    public boolean updateStatus(
            String customerBusinessId, CustomerStatus status, long expectedRowVersion, Instant now,
            String operatorId) {
        return jdbc.sql("""
                        UPDATE customer_customers
                        SET status = :status, content_version = content_version + 1,
                            row_version = row_version + 1, updated_at = :now, updated_by = :operatorId
                        WHERE customer_business_id = :businessId AND row_version = :expectedRowVersion
                        """)
                .param("status", status.name())
                .param("now", JdbcTimes.toUtc(now))
                .param("operatorId", operatorId)
                .param("businessId", customerBusinessId)
                .param("expectedRowVersion", expectedRowVersion)
                .update() == 1;
    }

    @Override
    public void insertChangeFact(ChangeFactRecord fact) {
        jdbc.sql("""
                        INSERT INTO customer_change_facts (
                            fact_business_id, customer_business_id, version_before, version_after,
                            operation_type, changed_fields_json, reason_text, occurred_at, operator_id)
                        VALUES (:factId, :businessId, :versionBefore, :versionAfter, :operationType,
                                :changedFields, :reason, :occurredAt, :operatorId)
                        """)
                .param("factId", fact.factBusinessId())
                .param("businessId", fact.customerBusinessId())
                .param("versionBefore", fact.versionBefore())
                .param("versionAfter", fact.versionAfter())
                .param("operationType", fact.operationType())
                .param("changedFields", fact.changedFieldsJson())
                .param("reason", fact.reasonText())
                .param("occurredAt", JdbcTimes.toUtc(fact.occurredAt()))
                .param("operatorId", fact.operatorId())
                .update();
    }

    @Override
    public List<ChangeFactRecord> findChanges(String customerBusinessId) {
        return jdbc.sql("""
                        SELECT fact_business_id, customer_business_id, version_before, version_after,
                               operation_type, changed_fields_json, reason_text, occurred_at, operator_id
                        FROM customer_change_facts
                        WHERE customer_business_id = :businessId
                        ORDER BY occurred_at, id
                        """)
                .param("businessId", customerBusinessId)
                .query((resultSet, rowNumber) -> new ChangeFactRecord(
                        resultSet.getString("fact_business_id"),
                        resultSet.getString("customer_business_id"),
                        resultSet.getLong("version_before"),
                        resultSet.getLong("version_after"),
                        resultSet.getString("operation_type"),
                        resultSet.getString("changed_fields_json"),
                        resultSet.getString("reason_text"),
                        JdbcTimes.toInstant(resultSet.getObject("occurred_at", LocalDateTime.class)),
                        resultSet.getString("operator_id")))
                .list();
    }

    private static String filterClause(String keyword, CustomerStatus status) {
        StringBuilder clause = new StringBuilder();
        if (keyword != null && !keyword.isBlank()) {
            clause.append(" AND (name LIKE :keyword OR customer_no LIKE :keyword"
                    + " OR contact_name LIKE :keyword OR phone LIKE :keyword)");
        }
        if (status != null) {
            clause.append(" AND status = :status");
        }
        return clause.toString();
    }

    private static JdbcClient.StatementSpec bindFilters(
            JdbcClient.StatementSpec spec, String keyword, CustomerStatus status) {
        JdbcClient.StatementSpec bound = spec;
        if (keyword != null && !keyword.isBlank()) {
            bound = bound.param("keyword", "%" + keyword.strip() + "%");
        }
        if (status != null) {
            bound = bound.param("status", status.name());
        }
        return bound;
    }

    private static final String SELECT = """
            SELECT id, customer_business_id, customer_no, name, contact_name, phone, default_address,
                   status, content_version, row_version, created_at, created_by, updated_at, updated_by
            FROM customer_customers
            """;

    private CustomerRecord map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new CustomerRecord(
                resultSet.getLong("id"),
                resultSet.getString("customer_business_id"),
                resultSet.getString("customer_no"),
                resultSet.getString("name"),
                resultSet.getString("contact_name"),
                resultSet.getString("phone"),
                resultSet.getString("default_address"),
                CustomerStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("content_version"),
                resultSet.getLong("row_version"),
                JdbcTimes.toInstant(resultSet.getObject("created_at", LocalDateTime.class)),
                resultSet.getString("created_by"),
                JdbcTimes.toInstant(resultSet.getObject("updated_at", LocalDateTime.class)),
                resultSet.getString("updated_by"));
    }
}
