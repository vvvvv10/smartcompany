package com.example.user;

import java.util.List;
import java.util.Optional;

/**
 * 用户存储抽象。落到自己的 ORM 上即可（MyBatis / JPA / JDBC 都行），
 * 用户中心对外暴露的契约不应该依赖具体持久层实现。
 */
public interface UserDirectory {

    /**
     * 账号是否已被占用。
     *
     * <p><b>只看在职行</b>（status = 1）：uk_account 约束的是生成列 account_active，
     * 离职行那一格是 NULL，压根不参与唯一性。这里若不跟着过滤，预检就会拿一个
     * 离职账号去挡活人，把生成列好不容易让出来的位置又堵回去。</p>
     */
    boolean existsByAccount(String account);

    /**
     * 花名是否已被占用，注册与改名前都要用它预检（真正的兜底是 uk_nickname 唯一索引）。
     *
     * <p>同样只看在职行：同名的人离职后，花名要能让给下一个同名的人。</p>
     */
    boolean existsByNickname(String nickname);

    /**
     * 建号。realName / idCard 由后台「新建用户」采集，自助注册走下面的三参重载，
     * 落空串——注册页这一版不采集身份信息，两处入口不必强求一致。
     *
     * <p>不带租户的重载落 'alibaba'（示例集团集团）——自助注册没有归属选择这一步，
     * 默认归属就是示例集团集团；带租户的版本只给管理端建号用，归属必须先过
     * {@link TenantService#requireActive} 再进来。</p>
     */
    default long create(String account, String passwordHash, String nickname, String realName, String idCard) {
        return create(account, passwordHash, nickname, realName, idCard, "alibaba");
    }

    long create(String account, String passwordHash, String nickname, String realName, String idCard,
                String tenantId);

    default long create(String account, String passwordHash, String nickname) {
        return create(account, passwordHash, nickname, "", "");
    }

    /**
     * 修改花名。
     *
     * @return true=成功；false=被别人抢先占用（并发场景靠 uk_nickname 索引兜底）
     */
    boolean updateNickname(long id, String nickname);

    Optional<Account> findById(long id);

    Optional<Account> findByAccount(String account);

    record Account(long id, String account, String passwordHash, String nickname,
                   List<String> roles, String tenantId) {
    }
}
