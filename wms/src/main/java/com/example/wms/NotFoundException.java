package com.example.wms;

/** 资源不存在时抛出，映射 404 not_found。 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
