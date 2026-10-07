package com.example.tms.intl.route;

import com.example.tms.intl.IntlCurrentUser;
import com.example.tms.intl.IntlPage;
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

/** 航线接口（/api/tms/intl/routes）。登录即可读写，权限点只管前端菜单。 */
@RestController
@RequestMapping("/api/tms/intl/routes")
@RequiredArgsConstructor
public class RouteController {

    private final RouteService service;

    @GetMapping
    public IntlPage<Route> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "mode", required = false) String mode,
            @RequestParam(value = "polCode", required = false) String polCode,
            @RequestParam(value = "podCode", required = false) String podCode,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        return service.search(keyword, mode, polCode, podCode, page, size);
    }

    @GetMapping("/brief")
    public List<Route> brief(@RequestParam(value = "mode", required = false) String mode) {
        return service.brief(mode);
    }

    @GetMapping("/{id}")
    public Route get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public Route create(@Valid @RequestBody RoutePayload payload) {
        IntlCurrentUser.requirePermission("新增/修改航线", "tms:intl:edit");
        return service.create(payload);
    }

    @PutMapping("/{id}")
    public Route update(@PathVariable long id, @Valid @RequestBody RoutePayload payload) {
        IntlCurrentUser.requirePermission("新增/修改航线", "tms:intl:edit");
        return service.update(withId(payload, id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        IntlCurrentUser.requirePermission("新增/修改航线", "tms:intl:edit");
        service.delete(id);
    }

    private static RoutePayload withId(RoutePayload payload, long id) {
        if (payload.id() != null && payload.id().equals(id)) {
            return payload;
        }
        return new RoutePayload(id, payload.routeCode(), payload.carrierId(), payload.mode(),
                payload.polCode(), payload.podCode(), payload.transitDays(), payload.volumetricDivisor(),
                payload.viaPorts(), payload.status(), payload.remark());
    }
}
