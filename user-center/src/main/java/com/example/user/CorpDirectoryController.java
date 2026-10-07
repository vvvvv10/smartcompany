package com.example.user;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业通讯录聚合（钉钉 + 企业微信）。每个 provider 独立配置、独立降级：
 * 配了凭据的给真数据，没配的 configured=false，端上只提示配置项名。
 *
 * <p><b>读取对所有登录成员开放</b>（与钉钉/企业微办默认「全员可见通讯录」一致）：
 * 管理台菜单按 user:list 显隐、workbench Tab 固定展示，入口各自把关，
 * 后端不再收权限点——普通成员看不到自己的组织架构才是事故。
 * 路径保留 /admin 前缀只是因为网关按前缀转发（Nacos gateway-routes.yaml），
 * 改名要动线上路由，不值当。</p>
 */
@RestController
@RequestMapping("/api/admin/corp")
@RequiredArgsConstructor
public class CorpDirectoryController {

    private final DingDingService dingding;
    private final WeComService wecom;

    public record ProviderTree(String code, String name, boolean configured,
                               List<DirectoryTypes.DeptNode> depts) {
    }

    public record CorpTree(List<ProviderTree> providers) {
    }

    @GetMapping("/tree")
    public CorpTree tree() {
        return new CorpTree(List.of(
                new ProviderTree("dingding", "钉钉", dingding.isConfigured(),
                        dingding.isConfigured() ? dingding.tree() : List.of()),
                new ProviderTree("wecom", "企业微信", wecom.isConfigured(),
                        wecom.isConfigured() ? wecom.tree() : List.of())));
    }
}
