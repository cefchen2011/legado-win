// [desktop-port] 桌面端替代实现，非上游源码。
//
// 上游 OnLineImportActivity / OpenUrlConfirmActivity 是 Android Activity，
// 用于"从网络导入书源"与"打开链接前确认"。
// 桌面端这两件事由应用外壳的窗口承接，因此这里只提供类型占位，
// 使 JsExtensions 里的 `appCtx.startActivity<OnLineImportActivity> { ... }` 保持原样。
package io.legado.app.ui.association

/** 打开链接前确认（桌面端由应用外壳弹窗） */
class OpenUrlConfirmActivity

/** 在线导入书源/订阅源（桌面端由应用外壳弹窗） */
class OnLineImportActivity
