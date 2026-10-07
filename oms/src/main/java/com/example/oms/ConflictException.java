package com.example.oms;

/**
 * 资源被其他数据引用、不能删/不能改时抛出，映射 409 data_in_use。
 *
 * <p>典型场景：已组批的出口子单不能改明细（组批后件数已计入母单汇总，
 * 改了就与母单对不上）；有子单的母单不能删。</p>
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
