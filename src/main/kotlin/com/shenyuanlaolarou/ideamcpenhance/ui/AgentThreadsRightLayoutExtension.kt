package com.shenyuanlaolarou.ideamcpenhance.ui

import com.intellij.toolWindow.DefaultToolWindowLayoutBuilder
import com.intellij.toolWindow.DefaultToolWindowLayoutExtension

/**
 * 让内置 JetBrains AI 的「Agent Sessions」工具窗口（toolWindow id = `air.threads`）默认停靠在右侧。
 *
 * ### 为什么要在这里改
 *
 * 这个窗口不是本插件提供的：它由 IDE 内置的 `air-plugin` 声明，其 `plugin.xml` 里写的是
 * `anchor="left"`，并且该插件还注册了一条 `defaultToolWindowLayout` 扩展，在构建默认布局时
 * 把 `air.threads` 塞进左栏（`builder.left.addOrUpdate("air.threads")`）。
 * 用户手动拖到右边只对当前项目生效，新项目仍按默认布局回到左侧。
 *
 * `anchor` 不是扩展能单独设置的字段：`DefaultToolWindowDescriptorBuilder` 只有 `getAnchor()`，
 * 值由 `addOrUpdate` 时所属的 stripe 决定（同 id 复用同一个 builder，后写覆盖先写）。
 * 所以这里以 upsert 方式把同一个 id 改挂到右栏。
 *
 * ### 为什么是 `order="last"`，而不是 `order="after ..."`
 *
 * `LoadingOrder.sortByLoadingOrder` 里 `last` 的语义是「排在所有非 last 扩展之后」。
 * 内置那条正是 `order="last"`，因此任何**非 last** 的扩展（包括 `after <它的 orderId>`）
 * 都必然排在它前面 —— 而 `after <一个 last 扩展>` 会与「last 排在所有非 last 之后」
 * 形成环，平台直接抛 `SortingException: Could not satisfy sorting requirements`。
 * 两个都是 `last` 且互不约束时，平台按**注册顺序**稳定排列，第三方插件晚于内置插件注册，
 * 于是我们这条最后执行、右栏生效。
 *
 * ### 生效范围
 *
 * 只影响「默认布局」层：全局 `window.layouts.xml` 尚无布局时构建的初始布局，
 * 以及新工具窗口「补种」进既有项目布局时用的工厂默认布局（`seedIntoExistingLayout`）。
 * 项目里已经存了 `air.threads` 条目的布局（例如用户手动拖过的项目）不受影响。
 *
 * ### 风险
 *
 * `air.threads` 是 air-plugin 的内部标识，无稳定性保证；id 变更后这里会退化成
 * 一条无人认领的布局记录（不报错，也不生效）。同理，若 JetBrains 给内置那条注册
 * 显式加了 `id` 属性或改了它的 `order`，需要回来复查顺序假设。
 */
class AgentThreadsRightLayoutExtension : DefaultToolWindowLayoutExtension {

    override fun buildV1Layout(builder: DefaultToolWindowLayoutBuilder) {
        moveToRightStripe(builder)
    }

    override fun buildV2Layout(builder: DefaultToolWindowLayoutBuilder) {
        moveToRightStripe(builder)
    }

    /**
     * 空 lambda 是故意的：内置扩展已经把 weight / split / `seedIntoExistingLayout` 设好，
     * 这里只改停靠位置，其余属性沿用（builder 是按 id 复用、逐条累积的）。
     */
    private fun moveToRightStripe(builder: DefaultToolWindowLayoutBuilder) {
        builder.right.addOrUpdate(AGENT_SESSIONS_ID) {}
    }

    private companion object {
        /** 内置 air-plugin 的 Agent Sessions 工具窗口 id。 */
        const val AGENT_SESSIONS_ID = "air.threads"
    }
}
