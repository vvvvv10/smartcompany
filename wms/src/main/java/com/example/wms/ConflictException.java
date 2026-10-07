package com.example.wms;

/**
 * 资源被其他数据引用、不能删/不能改时抛出，映射 409 data_in_use。
 *
 * <p>典型场景：已交接承运人的备货任务不能删（货已经出仓，删掉记录等于把货变没）。</p>
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
