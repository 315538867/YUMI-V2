package com.yumi.catalog.settings;

import com.yumi.catalog.changelog.MasterDataChangeLogService;
import com.yumi.identity.AuditContext;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 静态数据 API（任务 2.22）：类别由系统固定 code、不可增删改名；类别下条目按类别管理。
 * 星级/包装档位/缝边种类条目由用户增删改（被引用禁删），员工工种为系统预置四道工序、仅可改名。
 * 星级/包装档位/缝边种类都只提供「标准分钟」（整数 1–360），人工费由全局时薪派生。
 * 条目名称在类别内唯一；增删改记变更日志；查询不产生业务写入。
 */
@Service
@Transactional
public class StaticDataService {

    public static final String STAR_LEVEL = "STAR_LEVEL";
    public static final String PACKAGING_TIER = "PACKAGING_TIER";
    public static final String SEAM_TYPE = "SEAM_TYPE";
    public static final String WORK_TYPE = "WORK_TYPE";

    private static final List<String> ORDER = List.of(STAR_LEVEL, PACKAGING_TIER, SEAM_TYPE, WORK_TYPE);
    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put(STAR_LEVEL, "制品星级");
        LABELS.put(PACKAGING_TIER, "包装档位");
        LABELS.put(SEAM_TYPE, "缝边种类");
        LABELS.put(WORK_TYPE, "员工工种");
    }

    /** 类别清单：code 系统固定，条目数按类别统计。 */
    public record CategoryView(String code, String name, int itemCount) {
    }

    /** 条目：星级/包装档位/缝边种类给 stdMinutes（整数分钟），员工工种给系统 code。 */
    public record ItemView(long id, String code, String name, String stdMinutes) {
    }

    public record ItemRequest(String name, String stdMinutes, String reason) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final MasterDataChangeLogService changeLog;
    private final AuditContext auditContext;

    public StaticDataService(JdbcTemplate jdbcTemplate, MasterDataChangeLogService changeLog,
                             AuditContext auditContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.changeLog = changeLog;
        this.auditContext = auditContext;
    }

    public List<CategoryView> categories() {
        return ORDER.stream()
                .map(code -> new CategoryView(code, LABELS.get(code), countItems(code)))
                .toList();
    }

    public List<ItemView> items(String code) {
        requireCategory(code);
        return switch (code) {
            case STAR_LEVEL -> jdbcTemplate.query(
                    "SELECT id, name, std_minutes FROM star_levels ORDER BY id",
                    (rs, rowNum) -> new ItemView(rs.getLong(1), null, rs.getString(2),
                            String.valueOf(rs.getInt(3))));
            case PACKAGING_TIER -> jdbcTemplate.query(
                    "SELECT id, tier_name, std_minutes FROM packaging_tiers ORDER BY id",
                    (rs, rowNum) -> new ItemView(rs.getLong(1), null, rs.getString(2),
                            String.valueOf(rs.getInt(3))));
            case SEAM_TYPE -> jdbcTemplate.query(
                    "SELECT id, name, std_minutes FROM seam_types ORDER BY id",
                    (rs, rowNum) -> new ItemView(rs.getLong(1), null, rs.getString(2),
                            String.valueOf(rs.getInt(3))));
            default -> jdbcTemplate.query(
                    "SELECT id, code, name FROM work_types ORDER BY id",
                    (rs, rowNum) -> new ItemView(rs.getLong(1), rs.getString(2), rs.getString(3), null));
        };
    }

    public ItemView create(String code, ItemRequest request) {
        requireCategory(code);
        if (WORK_TYPE.equals(code)) {
            throw invalid("code", "员工工种由系统预置，不提供新增");
        }
        var name = requireName(request.name());
        try {
            switch (code) {
                case STAR_LEVEL -> jdbcTemplate.update(
                        "INSERT INTO star_levels (name, std_minutes, version, created_at, updated_at) "
                                + "VALUES (?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                        name, stdMinutes(request, 1, 360));
                case PACKAGING_TIER -> jdbcTemplate.update(
                        "INSERT INTO packaging_tiers (tier_name, std_minutes, version, created_at, updated_at) "
                                + "VALUES (?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                        name, stdMinutes(request, 1, 360));
                default -> jdbcTemplate.update(
                        "INSERT INTO seam_types (name, std_minutes, version, created_at, updated_at) "
                                + "VALUES (?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                        name, stdMinutes(request, 1, 360));
            }
        } catch (DuplicateKeyException duplicate) {
            throw duplicateName();
        }
        var id = findId(code, name);
        recordChange(code, id, null, snapshot(itemById(code, id)), request.reason());
        return itemById(code, id);
    }

    public ItemView update(String code, long id, ItemRequest request) {
        requireCategory(code);
        var before = itemById(code, id);
        var name = request.name() == null ? before.name() : requireName(request.name());
        try {
            switch (code) {
                case STAR_LEVEL -> jdbcTemplate.update(
                        "UPDATE star_levels SET name = ?, std_minutes = ?, version = version + 1, "
                                + "updated_at = UTC_TIMESTAMP(6) WHERE id = ?",
                        name, request.stdMinutes() == null ? Integer.valueOf(before.stdMinutes())
                                : stdMinutes(request, 1, 360), id);
                case PACKAGING_TIER -> jdbcTemplate.update(
                        "UPDATE packaging_tiers SET tier_name = ?, std_minutes = ?, version = version + 1, "
                                + "updated_at = UTC_TIMESTAMP(6) WHERE id = ?",
                        name, request.stdMinutes() == null ? Integer.valueOf(before.stdMinutes())
                                : stdMinutes(request, 1, 360), id);
                case SEAM_TYPE -> jdbcTemplate.update(
                        "UPDATE seam_types SET name = ?, std_minutes = ?, version = version + 1, "
                                + "updated_at = UTC_TIMESTAMP(6) WHERE id = ?",
                        name, request.stdMinutes() == null ? Integer.valueOf(before.stdMinutes())
                                : stdMinutes(request, 1, 360), id);
                default -> jdbcTemplate.update(
                        "UPDATE work_types SET name = ?, version = version + 1, "
                                + "updated_at = UTC_TIMESTAMP(6) WHERE id = ?",
                        name, id);
            }
        } catch (DuplicateKeyException duplicate) {
            throw duplicateName();
        }
        var after = itemById(code, id);
        recordChange(code, id, snapshot(before), snapshot(after), request.reason());
        return after;
    }

    public void delete(String code, long id) {
        requireCategory(code);
        if (WORK_TYPE.equals(code)) {
            throw invalid("code", "员工工种由系统预置，不提供删除");
        }
        itemById(code, id);
        var reference = referenceCount(code, id);
        if (reference > 0) {
            throw new ApiException(ErrorCode.CONFLICT_REFERENCED, "条目已被引用，不能删除",
                    List.of(new ApiFieldError("id", "已被引用，不能删除")));
        }
        var before = itemById(code, id);
        switch (code) {
            case STAR_LEVEL -> jdbcTemplate.update("DELETE FROM star_levels WHERE id = ?", id);
            case PACKAGING_TIER -> jdbcTemplate.update("DELETE FROM packaging_tiers WHERE id = ?", id);
            default -> jdbcTemplate.update("DELETE FROM seam_types WHERE id = ?", id);
        }
        recordChange(code, id, snapshot(before), Map.of("exists", false), null);
    }

    // ---------- 内部工具 ----------

    private void requireCategory(String code) {
        if (!LABELS.containsKey(code)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "静态数据类别不存在");
        }
    }

    private int countItems(String code) {
        var table = switch (code) {
            case STAR_LEVEL -> "star_levels";
            case PACKAGING_TIER -> "packaging_tiers";
            case SEAM_TYPE -> "seam_types";
            default -> "work_types";
        };
        var count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 引用守卫：星级/档位被商品引用、缝边种类被商品的默认缝边剪袋类型引用（订单引用随阶段三实现）、
     * 工种被员工引用时禁止删除。
     */
    private int referenceCount(String code, long id) {
        var count = switch (code) {
            case STAR_LEVEL -> jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM products WHERE star_level_id = ?", Integer.class, id);
            case PACKAGING_TIER -> jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM products WHERE packaging_tier_id = ?", Integer.class, id);
            case SEAM_TYPE -> jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM products WHERE seam_type_id = ?", Integer.class, id);
            default -> jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM employee_work_types WHERE work_type_id = ?", Integer.class, id);
        };
        return count == null ? 0 : count;
    }

    private long findId(String code, String name) {
        var sql = switch (code) {
            case STAR_LEVEL -> "SELECT id FROM star_levels WHERE name = ?";
            case PACKAGING_TIER -> "SELECT id FROM packaging_tiers WHERE tier_name = ?";
            default -> "SELECT id FROM seam_types WHERE name = ?";
        };
        var id = jdbcTemplate.queryForObject(sql, Long.class, name);
        return id == null ? 0 : id;
    }

    private ItemView itemById(String code, long id) {
        return items(code).stream().filter(item -> item.id() == id).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "静态数据条目不存在"));
    }

    private void recordChange(String code, long id, Map<String, Object> before, Map<String, Object> after,
                              String reason) {
        var audit = auditContext.current();
        changeLog.record(code, id, String.valueOf(id), before, after, reason,
                audit.adminUsername(), audit.requestId());
    }

    private static Map<String, Object> snapshot(ItemView item) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", item.name());
        if (item.code() != null) {
            map.put("code", item.code());
        }
        if (item.stdMinutes() != null) {
            map.put("stdMinutes", item.stdMinutes());
        }
        return map;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw invalid("name", "名称不能为空");
        }
        return name.trim();
    }

    private static int stdMinutes(ItemRequest request, int min, int max) {
        var raw = request.stdMinutes();
        if (raw == null) {
            throw invalid("stdMinutes", "标准时长必填");
        }
        try {
            var value = Integer.parseInt(raw.trim());
            if (value < min || value > max) {
                throw invalid("stdMinutes", "必须在 " + min + "-" + max + " 分钟");
            }
            return value;
        } catch (NumberFormatException bad) {
            throw invalid("stdMinutes", "必须是整数分钟");
        }
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_INVALID, message, List.of(new ApiFieldError(field, message)));
    }

    private static ApiException duplicateName() {
        return new ApiException(ErrorCode.CONFLICT_DUPLICATE, "名称已存在",
                List.of(new ApiFieldError("name", "名称已存在")));
    }
}
