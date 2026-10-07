package com.example.user;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 租户字典。租户定义很短（就三个），但它是「用户归属哪、业务数据归谁」的唯一裁决：
 * users.tenant_id 只存 id，显示名一律来这里查，不在前端硬编码
 * ——硬编码意味着加第四个租户时要同时发版 App 和 web。
 *
 * <p>登录链路不需要它：JWT 的 tid claim 直接带 id，网关注入 X-Tenant-Id 也是 id，
 * 中文名只在「给人看」的地方用（个人资料、用户管理列表）。</p>
 */
@Service
@RequiredArgsConstructor
public class TenantService {

    private final NamedParameterJdbcTemplate jdbc;

    /** 租户字典行。status：1 启用 0 停用（停用只挡「新归属」，不动存量用户）。 */
    public record Tenant(String id, String name, int status) {
    }

    /** 按显示名排序无意义，alibaba（示例集团集团）固定排最前——它就是默认归属。 */
    public List<Tenant> list() {
        return jdbc.query("""
                select id, name, status from tenants
                 order by case when id = 'alibaba' then 0 else 1 end, id
                """, new MapSqlParameterSource(),
                (rs, rowNum) -> new Tenant(rs.getString("id"), rs.getString("name"), rs.getInt("status")));
    }

    /**
     * id → 显示名。查不到回退成 id 本身而不是「—」：
     * 脏数据（租户行被删）时界面上至少还看得见归属的是谁，不至于凭空消失。
     */
    public String displayName(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return "未知";
        }
        String name = jdbc.queryForObject(
                "select name from tenants where id = :id",
                new MapSqlParameterSource("id", tenantId),
                String.class);
        return name == null || name.isBlank() ? tenantId : name;
    }

    /**
     * 归属租户前先验：不存在/已停用的租户不许写进 users.tenant_id。
     * 否则建了一个指向空气的用户——登录能成功，但所有业务查询按这个 tid 过滤，
     * 结果必然是空，且没有任何报错提示是哪里断了。
     */
    public void requireActive(String tenantId) {
        Integer status = jdbc.queryForObject(
                "select status from tenants where id = :id",
                new MapSqlParameterSource("id", tenantId),
                Integer.class);
        if (status == null) {
            throw new IllegalArgumentException("租户不存在: " + tenantId);
        }
        if (status != 1) {
            throw new ConflictException("tenant_disabled", "租户已停用: " + tenantId);
        }
    }
}
