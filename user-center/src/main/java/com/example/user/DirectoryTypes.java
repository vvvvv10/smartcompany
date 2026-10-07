package com.example.user;

import java.util.List;

/**
 * 企业通讯录（钉钉 / 企业微信）共用的树形数据形状。
 * 两个 provider 的组织树在管理台用同一个组件渲染，DTO 必须同构。
 */
public final class DirectoryTypes {

    private DirectoryTypes() {
    }

    /** 部门节点：递归下钻，members 为直属成员 */
    public record DeptNode(long id, String name, List<DeptNode> children, List<Member> members) {
    }

    /** 成员：title 企微侧可能没有，容忍空串 */
    public record Member(String userid, String name, String title) {
    }
}
