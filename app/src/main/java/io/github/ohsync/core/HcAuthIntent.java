package io.github.ohsync.core;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.Collection;

/**
 * 构造「请求 Health Connect 授权」的 Intent。
 *
 * 为什么用 Java 写：
 *  1. 官方契约（PermissionController.createRequestPermissionResultContract）发的是隐式
 *     Intent，只 setPackage。ColorOS 的设置页抢注了同一个 action，被它接走后立刻返回、
 *     什么都不做（实测 Android 16 + ColorOS）。所以必须显式指定 HC 的授权 Activity 组件。
 *  2. extra 要求 ArrayList&lt;String&gt; 用 putParcelableArrayListExtra 写入
 *     （HC 那边用 getParcelableArrayListExtra 读），而 Kotlin 的类型系统不接受
 *     ArrayList&lt;String&gt;（String 在 Kotlin 视角不是 Parcelable）。Java 侧无此限制，
 *     运行时 Bundle 用 writeValue 序列化 String 完全正常。
 *
 * action 与 extra 名取自 androidx.health.connect:connect-client:1.1.0 的
 * HealthPermissionsRequestAppContract，反编译确认。
 */
public final class HcAuthIntent {

    public static final String CONTROLLER_PACKAGE = "com.android.healthconnect.controller";
    private static final String ACTION_REQUEST_PERMISSIONS = "androidx.health.ACTION_REQUEST_PERMISSIONS";
    private static final String EXTRA_PERMISSIONS = "requested_permissions_string";
    private static final String PERMISSIONS_ACTIVITY =
            CONTROLLER_PACKAGE + ".permissions.request.PermissionsActivity";

    private HcAuthIntent() {
    }

    /** 显式指向 HC 授权页的 Intent；调用方负责 startActivity。 */
    public static Intent forPermissions(Collection<String> permissions) {
        Intent intent = new Intent(ACTION_REQUEST_PERMISSIONS);
        intent.putParcelableArrayListExtra(EXTRA_PERMISSIONS, new ArrayList<>(permissions));
        intent.setComponent(new ComponentName(CONTROLLER_PACKAGE, PERMISSIONS_ACTIVITY));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
}
