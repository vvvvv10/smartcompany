package com.example.crm.intl;

import java.util.List;

/**
 * 国际运单域共用的分页信封。
 *
 * <p>与各服务既有 {@code OrderService.PageResult} 结构完全一致（{list,total,page,size}），
 * 但**不复用那个类**：那是国内订单域的类型，让国际域依赖它会在将来某一刻
 * 出现「国际运单分页要加一个字段 → 改了一个国内订单也在用的类」。</p>
 *
 * <p>wire 格式不变还有个好处：OMS 的 LinkageClient 里那个通用 {@code Page<T>}
 * 反序列化器可以直接吃这个响应，不需要为国际域再写一份 DTO。</p>
 */
public record IntlPage<T>(List<T> list, long total, int page, int size) {

    /** 空页：查询无结果时给前端一个结构完整的空信封，省掉各处的 null 判断。 */
    public static <T> IntlPage<T> of(List<T> list, long total, int page, int size) {
        return new IntlPage<>(list == null ? List.of() : list, total, page, size);
    }
}
