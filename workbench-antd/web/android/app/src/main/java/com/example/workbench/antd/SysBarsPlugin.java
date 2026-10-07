package com.example.workbench.antd;

import android.content.res.Resources;

import androidx.core.view.WindowInsetsCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 把系统状态栏的**真实高度**交给网页。
 *
 * <p>为什么不能只靠 CSS 的 {@code env(safe-area-inset-top)}：Chrome 在 Android 上
 * 的 safe-area 只来自屏幕挖孔（DisplayCutout），**不含状态栏本身**。挖孔机型上那个值
 * 只有孔的高度（本机口径约 40px），而状态栏连图标带边距有 100px 上下，于是页头标题照样
 * 被时间/电量压住；横屏时状态栏挪到左边，上边距更没法表达。模拟器没有挖孔，那个值直接是 0。
 *
 * <p>所以这里量一次 {@code status_bar_height} 给网页，网页写进 {@code --wb-status-h}，
 * 页头与登录页用它做内边距。返回的是**物理像素**——换算成 CSS px 由网页侧按
 * devicePixelRatio 做（见 web/src/lib/systembars.ts），那边才有 devicePixelRatio，
 * 放这边还得再传一遍 density，少一层就少一处出错的机会。
 *
 * <p>取不到时返回 0（横竖屏切换的极短窗口），网页侧会退回 env()，不会把页头顶没了。
 */
@CapacitorPlugin(name = "SysBars")
public class SysBarsPlugin extends Plugin {

    @PluginMethod
    public void statusBarHeight(PluginCall call) {
        int px = 0;
        try {
            Resources res = getContext().getResources();
            int id = res.getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) px = res.getDimensionPixelSize(id);
        } catch (Exception ignored) {
            // 个别定制 ROM 屏蔽系统资源，继续走下面的 insets 兜底
        }
        if (px == 0 && getBridge() != null && getBridge().getActivity() != null) {
            // 兜底：直接问窗口当前的状态栏 inset（横屏/折叠屏切换时更准）
            try {
                android.view.WindowInsets insets =
                        getBridge().getActivity().getWindow().getDecorView().getRootWindowInsets();
                if (insets != null) {
                    px = WindowInsetsCompat.toWindowInsetsCompat(insets)
                            .getInsets(WindowInsetsCompat.Type.statusBars()).top;
                }
            } catch (Exception ignored) {
                // 拿不到就交回 0
            }
        }
        JSObject ret = new JSObject();
        ret.put("height", px);
        call.resolve(ret);
    }
}