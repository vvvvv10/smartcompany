package com.example.tms.intl.callback;

/**
 * 回调鉴权失败。401 与 403 分开是这个类存在的全部理由。
 *
 * <p>评审 M2-4 验收标准 ④ 明确要求「无 token / 错 token 均 401/403」——
 * 也就是要求这两个状态码<b>不同</b>，而它们在 HTTP 语义里本来就是两件事：
 * <ul>
 *   <li><b>401 Unauthorized</b> = 「你没出示凭据」。客户端该做的是<b>去拿凭据</b>
 *       （补上 X-Internal-Token 重发）。</li>
 *   <li><b>403 Forbidden</b> = 「你出示了，但不对」。客户端该做的是<b>停手并叫人</b>——
 *       再重试一万次也是同样结果，通常意味着 token 过期了或配置错了。</li>
 * </ul>
 * 都返回 401 的话，承运商的重试逻辑会在 token 配错时无限重推，
 * 而运维从返回码上看不出「这是配置错误」还是「这是网络抖动」。</p>
 */
public class CallbackAuthException extends RuntimeException {

    private final int status;
    private final String code;

    public CallbackAuthException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** HTTP 状态码：401 或 403。 */
    public int status() {
        return status;
    }

    /** 机器可读错误码：{@code missing_internal_token} / {@code invalid_internal_token}。 */
    public String code() {
        return code;
    }
}