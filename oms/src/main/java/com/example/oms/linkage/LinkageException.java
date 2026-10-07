package com.example.oms.linkage;

/**
 * 三系统联动失败（发货时建 TMS 运单 / WMS 出库记录，或直连下游超时/报错）。
 *
 * <p>刻意继承 RuntimeException：联动调用发生在 OMS 写订单的事务内，
 * 抛出即回滚 OMS 的状态变更——绝不允许「OMS 显示已发货、TMS/WMS 却没建档」
 * 的半截数据。用户重试时靠「先查后建」幂等补齐（见 {@link LinkageService}）。</p>
 *
 * <p>文案面向最终用户，由 GlobalExceptionHandler 原样带出。</p>
 */
public class LinkageException extends RuntimeException {

    public LinkageException(String message) {
        super(message);
    }

    public LinkageException(String message, Throwable cause) {
        super(message, cause);
    }
}
