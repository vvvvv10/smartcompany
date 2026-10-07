package com.example.oms.product;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品接口。
 *
 * <p>身份头由网关清洗后注入：外部伪造的 X-User-Id 已在网关第一步被删掉，
 * 所以这里可以无条件信任。所有登录用户均可读写，不做角色限制。</p>
 */
@RestController
@RequestMapping("/api/oms/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService service;

    @GetMapping
    public ProductService.PageResult<Product> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return service.search(keyword, category, status, page, size);
    }

    /** 下拉/选择器用的轻量列表：id / 款号 / 品名 / 颜色 / 尺码 / 价格。 */
    @GetMapping("/brief")
    public List<ProductBrief> brief() {
        return service.brief();
    }

    @GetMapping("/{id}")
    public Product detail(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public Product create(@Valid @RequestBody ProductPayload payload) {
        return service.create(payload);
    }

    @PutMapping("/{id}")
    public Product update(@PathVariable long id, @Valid @RequestBody ProductPayload payload) {
        // payload.id() 可能漏传或传错，以路径上的 id 为准（12 个国际申报属性原样带过去）
        return service.update(payload.withId(id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
