package com.example.user;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * UserDirectory 的 JDBC 实现。
 *
 * <p>注册走数据库唯一索引兜底：并发场景下靠 uk_account 保证不出现重复账号，
 * 应用侧的 existsByAccount 预检只是为了返回更友好的错误，不能替代唯一约束。</p>
 *
 * <p>uk_account / uk_nickname 约束的是生成列 account_active / nickname_active，
 * 两者只在 status = 1 时才有值——所以唯一性只对在职行生效，离职行可以被
 * 新人重新占用（见 initdb/27-user-create.sql）。</p>
 */
@Repository
@RequiredArgsConstructor
public class JdbcUserDirectory implements UserDirectory {

    private static final String DEFAULT_ROLE = "USER";
    private static final String DEFAULT_TENANT = "alibaba";

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public boolean existsByAccount(String account) {
        // 只看在职行：唯一性在 account_active 上，离职行是 NULL，不占位
        Integer count = jdbc.queryForObject(
                "select count(1) from users where account = :account and status = 1",
                new MapSqlParameterSource("account", account), Integer.class);
        return count != null && count > 0;
    }

    @Override
    public boolean existsByNickname(String nickname) {
        Integer count = jdbc.queryForObject(
                "select count(1) from users where nickname = :nickname and status = 1",
                new MapSqlParameterSource("nickname", nickname), Integer.class);
        return count != null && count > 0;
    }

    @Override
    public boolean updateNickname(long id, String nickname) {
        try {
            int updated = jdbc.update(
                    "update users set nickname = :nickname where id = :id",
                    new MapSqlParameterSource(Map.of("nickname", nickname, "id", id)));
            return updated > 0;
        } catch (DuplicateKeyException ex) {
            // 并发下别人先抢到了这个花名，uk_nickname 唯一索引在这里兜底
            return false;
        }
    }

    @Override
    public long create(String account, String passwordHash, String nickname,
                       String realName, String idCard, String tenantId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("account", account)
                .addValue("passwordHash", passwordHash)
                .addValue("nickname", nickname)
                .addValue("realName", realName == null ? "" : realName)
                .addValue("idCard", idCard == null ? "" : idCard)
                .addValue("tenantId", tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT : tenantId);
        jdbc.update("insert into users (account, password_hash, nickname, real_name, id_card, tenant_id) "
                + "values (:account, :passwordHash, :nickname, :realName, :idCard, :tenantId)",
                params, keyHolder, new String[]{"id"});

        Number key = keyHolder.getKey();
        long userId = key == null ? 0L : key.longValue();
        jdbc.update("insert ignore into user_roles (user_id, role_code) values (:userId, :role)",
                new MapSqlParameterSource(Map.of("userId", userId, "role", DEFAULT_ROLE)));
        return userId;
    }

    @Override
    public Optional<Account> findById(long id) {
        return findOne("select id, account, password_hash, nickname, tenant_id from users where id = :id and status = 1",
                new MapSqlParameterSource("id", id));
    }

    @Override
    public Optional<Account> findByAccount(String account) {
        return findOne("select id, account, password_hash, nickname, tenant_id from users where account = :account and status = 1",
                new MapSqlParameterSource("account", account));
    }

    private Optional<Account> findOne(String sql, MapSqlParameterSource params) {
        Optional<Account> account = jdbc.query(sql, params, rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(new Account(
                    rs.getLong("id"),
                    rs.getString("account"),
                    rs.getString("password_hash"),
                    rs.getString("nickname"),
                    List.of(),
                    rs.getString("tenant_id")));
        });
        return account.map(value -> new Account(
                value.id(), value.account(), value.passwordHash(), value.nickname(),
                loadRoles(value.id()), value.tenantId()));
    }

    private List<String> loadRoles(long userId) {
        List<String> roles = jdbc.queryForList(
                "select role_code from user_roles where user_id = :userId",
                new MapSqlParameterSource("userId", userId), String.class);
        return roles.isEmpty() ? List.of(DEFAULT_ROLE) : roles;
    }
}
