package com.example.user;

/**
 * 业务冲突。与 {@link ForbiddenException} 一样自己定义——
 * classpath 里没有现成的 409 语义，而这类错误（花名被占用、已有在途申请）
 * 必须给前端一个能区分的 code，不能都塞进 invalid_argument。
 *
 * <p>code 沿用后端既有取值风格：nickname_exists / request_exists。</p>
 */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
