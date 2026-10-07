package com.example.crm;

/**
 * 资源被其他数据引用、不能删/不能改时抛出，映射 409 data_in_use。
 *
 * <p>典型场景：还有出口订单/工单引用的客户不能删（跨库引用查不到，
 * 本服务只保自己库里的工单引用）。</p>
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
