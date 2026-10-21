package com.yumi.platform.authorization.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 角色与授权持久化端口。 */
public interface RoleRepository {

    Optional<RoleRecord> findByRoleCode(String roleCode);

    Optional<RoleRecord> findByBusinessId(String roleBusinessId);

    List<RoleRecord> findAll();

    void insert(RoleRecord record);

    boolean updateRole(
            String roleBusinessId, String name, Set<String> permissionCodes, long expectedRowVersion,
            Instant now, String operatorId);

    Set<String> findRoleCodesOfAccount(String accountBusinessId);

    Set<String> findPermissionsOfAccount(String accountBusinessId);

    void replaceAccountRoles(String accountBusinessId, Set<String> roleCodes, Instant now, String operatorId);

    List<String> findAccountBusinessIdsOfRole(String roleBusinessId);

    /** 幂等写入权限点定义；已存在则更新名称与资源类型。 */
    void upsertPermission(String permissionCode, String name, String resourceType);
}
