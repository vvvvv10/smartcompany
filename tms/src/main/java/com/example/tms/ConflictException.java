package com.example.tms;

/**
 * 资源被其他数据引用、不能删/不能改时抛出，映射 409 data_in_use。
 *
 * <p>与 {@link NotFoundException}（404）的区别要说清楚：404 是「你要动的东西不在这里」，
 * 409 是「东西在，但有别的单据正挂在它身上」——比如删一个还有运单引用的承运商。
 * 前端据此可以给出「先处理下游单据」的提示，而不是笼统的「操作失败」。</p>
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
