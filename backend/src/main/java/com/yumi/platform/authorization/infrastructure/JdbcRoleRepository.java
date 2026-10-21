package com.yumi.platform.authorization.infrastructure;

import com.yumi.platform.authorization.application.port.RoleRecord;
import com.yumi.platform.authorization.application.port.RoleRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 角色与授权持久化（MySQL）。 */
@Repository
public class JdbcRoleRepository implements RoleRepository {

    private static final ZoneOffset UTC = ZoneOffset.UTC;

    private final JdbcClient jdbc;

    public JdbcRoleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<RoleRecord> findByRoleCode(String roleCode) {
        return jdbc.sql("""
                        SELECT id, role_business_id, role_code, name, status, row_version, created_at
                        FROM platform_roles WHERE role_code = :roleCode
                        """)
                .param("roleCode", roleCode)
                .query(this::map)
                .optional();
    }

    @Override
    public Optional<RoleRecord> findByBusinessId(String roleBusinessId) {
        return jdbc.sql("""
                        SELECT id, role_business_id, role_code, name, status, row_version, created_at
                        FROM platform_roles WHERE role_business_id = :roleBusinessId
                        """)
                .param("roleBusinessId", roleBusinessId)
                .query(this::map)
                .optional();
    }

    @Override
    public List<RoleRecord> findAll() {
        return jdbc.sql("""
                        SELECT id, role_business_id, role_code, name, status, row_version, created_at
                        FROM platform_roles ORDER BY role_code
                        """)
                .query(this::map)
                .list();
    }

    @Override
    public void insert(RoleRecord record) {
        jdbc.sql("""
                        INSERT INTO platform_roles (
                            role_business_id, role_code, name, status, row_version,
                            created_at, created_by, updated_at, updated_by)
                        VALUES (:roleBusinessId, :roleCode, :name, :status, 1,
                                :now, :operatorId, :now, :operatorId)
                        """)
                .param("roleBusinessId", record.roleBusinessId())
                .param("roleCode", record.roleCode())
                .param("name", record.name())
                .param("status", record.status())
                .param("now", LocalDateTime.ofInstant(record.createdAt(), UTC))
                .param("operatorId", "system")
                .update();
        replacePermissions(record.roleBusinessId(), record.permissionCodes());
    }

    @Override
    public boolean updateRole(
            String roleBusinessId, String name, Set<String> permissionCodes, long expectedRowVersion,
            Instant now, String operatorId) {
        int updated = jdbc.sql("""
                        UPDATE platform_roles
                        SET name = :name, row_version = row_version + 1,
                            updated_at = :now, updated_by = :operatorId
                        WHERE role_business_id = :roleBusinessId AND row_version = :expectedRowVersion
                        """)
                .param("name", name)
                .param("now", LocalDateTime.ofInstant(now, UTC))
                .param("operatorId", operatorId)
                .param("roleBusinessId", roleBusinessId)
                .param("expectedRowVersion", expectedRowVersion)
                .update();
        if (updated != 1) {
            return false;
        }
        replacePermissions(roleBusinessId, permissionCodes);
        return true;
    }

    private void replacePermissions(String roleBusinessId, Set<String> permissionCodes) {
        jdbc.sql("DELETE FROM platform_role_permissions WHERE role_business_id = :roleBusinessId")
                .param("roleBusinessId", roleBusinessId)
                .update();
        for (String permissionCode : permissionCodes) {
            jdbc.sql("""
                            INSERT INTO platform_role_permissions (role_business_id, permission_code)
                            VALUES (:roleBusinessId, :permissionCode)
                            """)
                    .param("roleBusinessId", roleBusinessId)
                    .param("permissionCode", permissionCode)
                    .update();
        }
    }

    @Override
    public Set<String> findRoleCodesOfAccount(String accountBusinessId) {
        return new LinkedHashSet<>(jdbc.sql("""
                        SELECT r.role_code
                        FROM platform_account_roles ar
                        JOIN platform_roles r ON r.role_business_id = ar.role_business_id
                        WHERE ar.account_business_id = :accountBusinessId
                        ORDER BY r.role_code
                        """)
                .param("accountBusinessId", accountBusinessId)
                .query(String.class)
                .list());
    }

    @Override
    public Set<String> findPermissionsOfAccount(String accountBusinessId) {
        return new LinkedHashSet<>(jdbc.sql("""
                        SELECT DISTINCT rp.permission_code
                        FROM platform_account_roles ar
                        JOIN platform_role_permissions rp ON rp.role_business_id = ar.role_business_id
                        WHERE ar.account_business_id = :accountBusinessId
                        ORDER BY rp.permission_code
                        """)
                .param("accountBusinessId", accountBusinessId)
                .query(String.class)
                .list());
    }

    @Override
    public void replaceAccountRoles(
            String accountBusinessId, Set<String> roleCodes, Instant now, String operatorId) {
        jdbc.sql("DELETE FROM platform_account_roles WHERE account_business_id = :accountBusinessId")
                .param("accountBusinessId", accountBusinessId)
                .update();
        for (String roleCode : roleCodes) {
            RoleRecord role = findByRoleCode(roleCode)
                    .orElseThrow(() -> new IllegalArgumentException("角色不存在：" + roleCode));
            jdbc.sql("""
                            INSERT INTO platform_account_roles (account_business_id, role_business_id)
                            VALUES (:accountBusinessId, :roleBusinessId)
                            """)
                    .param("accountBusinessId", accountBusinessId)
                    .param("roleBusinessId", role.roleBusinessId())
                    .update();
        }
    }

    @Override
    public List<String> findAccountBusinessIdsOfRole(String roleBusinessId) {
        return jdbc.sql("""
                        SELECT account_business_id FROM platform_account_roles
                        WHERE role_business_id = :roleBusinessId
                        """)
                .param("roleBusinessId", roleBusinessId)
                .query(String.class)
                .list();
    }

    @Override
    public void upsertPermission(String permissionCode, String name, String resourceType) {
        jdbc.sql("""
                        INSERT INTO platform_permissions (permission_code, name, resource_type)
                        VALUES (:permissionCode, :name, :resourceType)
                        ON DUPLICATE KEY UPDATE name = VALUES(name), resource_type = VALUES(resource_type)
                        """)
                .param("permissionCode", permissionCode)
                .param("name", name)
                .param("resourceType", resourceType)
                .update();
    }

    private RoleRecord map(ResultSet resultSet, int rowNumber) throws SQLException {
        String roleBusinessId = resultSet.getString("role_business_id");
        Set<String> permissions = new LinkedHashSet<>(jdbc.sql("""
                        SELECT permission_code FROM platform_role_permissions
                        WHERE role_business_id = :roleBusinessId ORDER BY permission_code
                        """)
                .param("roleBusinessId", roleBusinessId)
                .query(String.class)
                .list());
        return new RoleRecord(
                resultSet.getLong("id"),
                roleBusinessId,
                resultSet.getString("role_code"),
                resultSet.getString("name"),
                resultSet.getString("status"),
                resultSet.getLong("row_version"),
                permissions,
                com.yumi.platform.api.jdbc.JdbcTimes.toInstant(resultSet.getObject("created_at", LocalDateTime.class)));
    }
}
