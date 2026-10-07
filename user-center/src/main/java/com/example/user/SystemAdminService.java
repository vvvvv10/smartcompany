package com.example.user;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 系统（模块）管理员配置（41 号迁移的 system_admins）。
 *
 * <p>「系统」= permissions.module（console/crm/oms/profile/rbac/tms/user/wms 八个），
 * 与权限目录同口径——权限申请第 2 级的审批人就是所申请权限<b>所属模块</b>的系统
 * 管理员；模块没配管理员时该级只能等 ADMIN 兜底。配置入口在权限中心「系统管理员」
 * 页签（role:manage），第一次由 41 号种子给每个模块配 1 号演示账号。</p>
 */
@Service
@RequiredArgsConstructor
public class SystemAdminService {

    private final NamedParameterJdbcTemplate jdbc;
    private final UserDirectory users;

    /** 一个系统及其管理员（列表按模块渲染；管理员为空也返回——前端提示「未配置」）。 */
    public record ModuleAdmins(String module, List<AdminUser> admins) {
    }

    public record AdminUser(long userId, String account, String nickname) {
    }

    /** 全部系统 × 管理员，按模块名排序。模块清单从权限目录取，天然与权限点同口径。 */
    public List<ModuleAdmins> list() {
        Map<String, List<AdminUser>> byModule = new LinkedHashMap<>();
        for (String module : modules()) {
            byModule.put(module, new ArrayList<>());
        }
        jdbc.query("""
                select sa.module, sa.user_id, u.account, u.nickname
                  from system_admins sa
                  join users u on u.id = sa.user_id
                 order by sa.module, u.id
                """, new MapSqlParameterSource(), (rs, i) -> {
            byModule.computeIfAbsent(rs.getString("module"), k -> new ArrayList<>())
                    .add(new AdminUser(rs.getLong("user_id"),
                            rs.getString("account"), rs.getString("nickname")));
            return null;
        });
        List<ModuleAdmins> result = new ArrayList<>();
        byModule.forEach((module, admins) -> result.add(new ModuleAdmins(module, List.copyOf(admins))));
        return result;
    }

    /** 加管理员：模块必须在权限目录里、用户必须存在；主键幂等，重复加不报错。 */
    @Transactional
    public List<ModuleAdmins> add(String rawModule, long userId) {
        String module = requireKnownModule(rawModule);
        users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在或已禁用: " + userId));
        jdbc.update("insert ignore into system_admins (module, user_id) values (:module, :userId)",
                new MapSqlParameterSource(Map.of("module", module, "userId", userId)));
        return list();
    }

    /** 移除管理员：模块没配人之后该模块的第 2 级只剩 ADMIN 能批（列表空态会提示）。 */
    @Transactional
    public List<ModuleAdmins> remove(String rawModule, long userId) {
        String module = requireKnownModule(rawModule);
        jdbc.update("delete from system_admins where module = :module and user_id = :userId",
                new MapSqlParameterSource(Map.of("module", module, "userId", userId)));
        return list();
    }

    private List<String> modules() {
        return jdbc.queryForList(
                "select distinct module from permissions where module <> '' order by module",
                new MapSqlParameterSource(), String.class);
    }

    private String requireKnownModule(String rawModule) {
        String module = rawModule == null ? "" : rawModule.trim();
        if (module.isEmpty()) {
            throw new IllegalArgumentException("系统模块不能为空");
        }
        Integer n = jdbc.queryForObject(
                "select count(1) from permissions where module = :module",
                new MapSqlParameterSource("module", module), Integer.class);
        if (n == null || n == 0) {
            throw new IllegalArgumentException("未知系统模块: " + module);
        }
        return module;
    }
}
