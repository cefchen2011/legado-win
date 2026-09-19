// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 ToastUtils 依赖 android.widget.Toast 与 UI 线程；桌面端没有 Toast，
// 这里把提示统一收敛到日志，并保留上游的扩展函数名与调用签名，
// 使引擎源码里的 `appCtx.toastOnUi(...)` 等调用保持不变。
package io.legado.app.utils

import android.content.Context

fun Context.toastOnUi(message: String?) {
    if (message != null) LogUtils.i("Toast", message)
}

fun Context.longToastOnUi(message: String?) {
    if (message != null) LogUtils.i("Toast", message)
}

fun Context.toastOnUi(resId: Int) {
    LogUtils.i("Toast", getString(resId))
}

fun Context.longToastOnUi(resId: Int) {
    LogUtils.i("Toast", getString(resId))
}

/** 上游还有直接以任意对象为接收者的重载，保持一致。 */
fun Any.toastOnUi(context: Context, message: String?) {
    context.toastOnUi(message)
}
