package com.example.workbench.antd;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * Web ↔ 原生的桥：把登录凭据交给前台服务、把通知点击的落点交回 Web。
 *
 * <p>存在的理由是一个壳架构下的硬约束——**前台服务读不到 WebView 的 localStorage**。
 * 所以 token 必须由 Web 侧显式同步进来（登录后 start、刷新后 updateToken、退出时 stop），
 * 否则服务只能空转。这一点写在这里，免得后来人以为"服务自己会拿到 token"。
 *
 * <p>另外 Android 13+ 的通知权限也要在这里申请：Capacitor 的权限模型是
 * {@code @Permission + @PermissionCallback}，系统弹窗只能在原生侧发起。
 */
@CapacitorPlugin(
        name = "Notify",
        permissions = {
                @Permission(
                        alias = "notifications",
                        strings = {Manifest.permission.POST_NOTIFICATIONS})
        })
public class NotifyPlugin extends Plugin {

    /** 登录成功后调用：存凭据 + 拉起常驻服务。 */
    @PluginMethod
    public void start(PluginCall call) {
        String token = call.getString("token", "");
        String baseUrl = call.getString("baseUrl", "");
        if (token.isEmpty() || baseUrl.isEmpty()) {
            call.reject("缺少 token 或服务地址");
            return;
        }
        NotifyLedger.saveToken(getContext(), token, baseUrl);
        NotifyLedger.markPermissionAsked(getContext());
        startService();
        JSObject ret = new JSObject();
        ret.put("started", true);
        call.resolve(ret);
    }

    /** accessToken 刷新后同步（api 层 single-flight 刷新成功时调）。 */
    @PluginMethod
    public void updateToken(PluginCall call) {
        String token = call.getString("token", "");
        if (token.isEmpty()) {
            call.reject("token 为空");
            return;
        }
        NotifyLedger.updateToken(getContext(), token);
        call.resolve();
    }

    /** 退出登录：停服务 + 清凭据 + 清台账（下一个人不该看到上一位的已知待办）。 */
    @PluginMethod
    public void stop(PluginCall call) {
        stopService();
        NotifyLedger.clearToken(getContext());
        NotifyLedger.clearSeen(getContext());
        call.resolve();
    }

    /** 用户在「我的」里关掉提醒开关时用。 */
    @PluginMethod
    public void setEnabled(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("enabled", Boolean.TRUE));
        NotifyLedger.setEnabled(getContext(), on);
        if (on) startService(); else stopService();
        call.resolve();
    }

    @PluginMethod
    public void isEnabled(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("enabled", NotifyLedger.isEnabled(getContext()));
        ret.put("canNotify", NotifyCenter.canNotify(getContext()));
        ret.put("watching", NotifyLedger.token(getContext()) != null
                && !NotifyLedger.token(getContext()).isEmpty());
        call.resolve(ret);
    }

    /**
     * Android 13+ 申请通知权限。
     *
     * <p>已授权 → 直接 resolve；已拒绝过且用户勾了"不再问" → resolve(false)，
     * 让页面能给"去设置里开"的引导，而不是反复弹窗骚扰。
     */
    @PluginMethod
    public void ensurePermission(PluginCall call) {
        if (!needsPermission()) {
            call.resolve(grantedResult());
            return;
        }
        if (NotifyLedger.isPermissionAsked(getContext())
                && getPermissionState("notifications") == PermissionState.DENIED) {
            // 已经问过一次了，别再弹；让页面显示"去系统设置开启"的入口
            call.resolve(grantedResult());
            return;
        }
        requestPermissionForAlias("notifications", call, "permissionCallback");
    }

    @PermissionCallback
    private void permissionCallback(PluginCall call) {
        call.resolve(grantedResult());
    }

    private JSObject grantedResult() {
        JSObject ret = new JSObject();
        ret.put("granted", NotifyCenter.canNotify(getContext()));
        return ret;
    }

    private boolean needsPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    /**
     * 取通知点击的落点（待审批单子 id），**一次性消费**。
     *
     * <p>返回 {@code {id: 123}} 或 {@code {id: -1}}（只进审批页不定位）或空对象（没点过）。
     */
    @PluginMethod
    public void consumeDeepLink(PluginCall call) {
        String raw = NotifyCenter.consumeDeepLink();
        JSObject ret = new JSObject();
        if (raw != null) {
            try {
                ret.put("id", Long.parseLong(raw));
            } catch (NumberFormatException ignored) {
                // 理论上不会发生：写入时就是 Long.toString
            }
        }
        call.resolve(ret);
    }

    private void startService() {
        Intent intent = new Intent(getContext(), ApprovalsWatchService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getContext().startForegroundService(intent);
            } else {
                getContext().startService(intent);
            }
        } catch (Exception e) {
            // Android 12+ 后台启动前台服务会被拒；此时只影响"后台提醒"，不该让 App 崩
            android.util.Log.w("NotifyPlugin", "启动前台服务失败", e);
        }
    }

    private void stopService() {
        try {
            getContext().stopService(new Intent(getContext(), ApprovalsWatchService.class));
        } catch (Exception ignored) {
            // 没在跑就算了
        }
    }
}
