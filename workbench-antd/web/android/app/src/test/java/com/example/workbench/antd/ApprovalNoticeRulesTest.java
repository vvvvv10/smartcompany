package com.example.workbench.antd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.Test;

/**
 * 待办提醒的纯逻辑单测。
 *
 * <p>这几条规则错一次的代价都很直观：同一张单子反复提醒（台账没记上）、
 * 新单子被漏掉（台账记太宽）、通知文案把权限码暴露给用户、轮询间隔退避不对导致
 * 后端挂掉时空转耗电。所以钉在单测里，而不是靠手指点。
 *
 * <p>与原生版 {@code workbench-android} 的 {@code ApprovalNoticeTest} 同款断言，
 * 两端规则必须一致。
 */
public class ApprovalNoticeRulesTest {

    @Test
    public void 台账键跨类型不撞() {
        // 花名 12 与权限申请 12 是两张单子，键撞了就互相吞掉提醒
        assertEquals("n:12", ApprovalNoticeRules.nickKey(12));
        assertEquals("p:12", ApprovalNoticeRules.permKey(12));
    }

    @Test
    public void 只提醒台账里没有的单子() {
        List<String> current = Arrays.asList("n:1", "n:2", "p:7");
        LinkedHashSet<String> seen = new LinkedHashSet<>(Arrays.asList("n:1", "p:7"));
        assertEquals(Arrays.asList("n:2"), ApprovalNoticeRules.newKeys(current, seen));
    }

    @Test
    public void 首次轮询把当前待办全当新的() {
        // 调用方靠「台账为空」判断这是首次，只补记不补发
        List<String> current = Arrays.asList("n:1", "n:2", "n:3");
        assertEquals(3, ApprovalNoticeRules.newKeys(current, new LinkedHashSet<>()).size());
    }

    @Test
    public void 台账满时丢最旧的() {
        LinkedHashSet<String> current = new LinkedHashSet<>();
        List<String> added = new ArrayList<>();
        for (int i = 0; i < ApprovalNoticeRules.MAX_SEEN + 20; i++) {
            added.add("n:" + i);
        }
        LinkedHashSet<String> merged = ApprovalNoticeRules.pruneKeys(current, added);
        assertEquals(ApprovalNoticeRules.MAX_SEEN, merged.size());
        // 最新的必须留着，最早的那条必须被丢掉
        assertTrue(merged.contains("n:" + (ApprovalNoticeRules.MAX_SEEN + 19)));
        assertTrue(!merged.contains("n:0"));
    }

    @Test
    public void 台账没新增时原样返回不写盘() {
        // 绝大多数轮询「一条新的都没有」，这时该短路（返回同一个引用）
        LinkedHashSet<String> current = new LinkedHashSet<>(Arrays.asList("n:1", "n:2"));
        assertTrue(current == ApprovalNoticeRules.pruneKeys(current, new ArrayList<>()));
    }

    @Test
    public void 台账编解码往返一致() {
        List<String> keys = Arrays.asList("n:1", "p:9", "n:30");
        LinkedHashSet<String> back = ApprovalNoticeRules.decodeSeen(ApprovalNoticeRules.encodeSeen(keys));
        assertEquals(new LinkedHashSet<>(keys), back);
        // 空串不能变成含空键的集合——否则每轮都会被当成「新单子」
        assertTrue(ApprovalNoticeRules.decodeSeen("").isEmpty());
        assertTrue(ApprovalNoticeRules.decodeSeen(null).isEmpty());
    }

    @Test
    public void 通知标题单条不带数量() {
        assertEquals("有新的审批待办", ApprovalNoticeRules.noticeTitle(1));
        assertEquals("有新的审批待办", ApprovalNoticeRules.noticeTitle(0));
        assertEquals("有 3 条新的审批待办", ApprovalNoticeRules.noticeTitle(3));
    }

    @Test
    public void 提醒文案只放人话不放权限码() {
        ApprovalNoticeRules.Notice nick =
                ApprovalNoticeRules.ofNick(1L, "白露", "13800000032", "白露E2E");
        assertEquals("白露 申请把花名改为「白露E2E」，等你审批", nick.body);
        assertEquals("n:1", nick.key);

        ApprovalNoticeRules.Notice perm =
                ApprovalNoticeRules.ofPerm(2L, "", "13800000031", "", "wms:view");
        // 花名为空时退回账号；权限名为空时退回权限码（总不能空着）
        assertEquals("13800000031 申请权限「wms:view」，等你审批", perm.body);
        assertEquals("p:2", perm.key);
    }

    @Test
    public void 首次轮询不补发历史待办() {
        // 刚装好 / 刚登录：台账里有 3 条"没见过的"，但这是首次，全部补记不补发
        assertTrue(!ApprovalNoticeRules.shouldNotify(false, 3));
    }

    @Test
    public void 完成过首次轮询后新单子要提醒() {
        assertTrue(ApprovalNoticeRules.shouldNotify(true, 1));
        assertTrue(ApprovalNoticeRules.shouldNotify(true, 5));
    }

    @Test
    public void 没有新单子不打扰() {
        assertTrue(!ApprovalNoticeRules.shouldNotify(true, 0));
    }

    @Test
    public void 轮询间隔三档自适应() {
        // 正常 1 分钟；两类都没权限退到 5 分钟；两路失败退到 2 分钟
        assertEquals(60_000L, ApprovalsWatchService.intervalForTest("OK"));
        assertEquals(5 * 60_000L, ApprovalsWatchService.intervalForTest("NO_PERMISSION"));
        assertEquals(2 * 60_000L, ApprovalsWatchService.intervalForTest("ERROR"));
        // 服务自己停了也回到正常间隔（下次启动重新开始）
        assertEquals(60_000L, ApprovalsWatchService.intervalForTest("STOPPED"));
    }
}
