package com.landrop.transfer.util

import java.util.concurrent.atomic.AtomicInteger

/**
 * 全局唯一的任务号。
 *
 * 之前发送任务从 2 开始、接收任务从 100 开始，各自独立自增 —— 发送到第 99 个任务时
 * 必然与某个接收任务撞号，导致 `Engine.cancel(id)` 取消错任务，以及任务列表的
 * `LazyColumn(key = id)` 重复 key 崩溃（多设备并行会显著加快撞号）。
 */
object JobIds {
    private val seq = AtomicInteger(1000)

    fun next(): Int = seq.getAndIncrement()
}
