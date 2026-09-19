package com.aicabinet.edge.hal.chzh

import com.aicabinet.edge.hal.DoorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 创智辉 M8 串口回包 → 门状态 的解析单测。
 *
 * 为什么值得测：`CabinetController` 靠 `currentDoorState()` 判断「用户关门了没」——
 * 早判 CLOSED 会在用户还在挑商品时结束会话并停录，晚判则让会话一直挂着等超时。
 * 这段解析是**唯一**的门磁真相来源（C19c 特意去掉了 unlock 时的乐观置位）。
 *
 * 判的是行为**现状**，包括几个不漂亮的缝隙 —— 它们要么是有意的兼容，要么是需要
 * 现场协议确认的脆弱点，写成断言才有机会被人看见：
 *  1. 关键词是不区分大小写的**子串**匹配，不是精确匹配；
 *  2. `CLOSED`/`OPEN` 是**词**而非前缀，所以 `OPENING` 会落进 `DOOR=O` 被读成 OPEN；
 *  3. 只认 `CLOSED`（带 D），裸 `CLOSE` 不被识别 ⇒ 返回 null、状态保持不变。
 */
class ChzhLockDriverFeedbackTest {

    // ── 关闭方向 ──────────────────────────────────────────────────────────

    @Test
    fun `DOOR=C 判为已关闭`() {
        assertEquals(DoorState.CLOSED, ChzhLockDriver.doorStateFromFeedback("DOOR=C"))
    }

    @Test
    fun `DOOR=0 判为已关闭`() {
        assertEquals(DoorState.CLOSED, ChzhLockDriver.doorStateFromFeedback("DOOR=0"))
    }

    @Test
    fun `CLOSED 词判为已关闭`() {
        assertEquals(DoorState.CLOSED, ChzhLockDriver.doorStateFromFeedback("DOOR=CLOSED"))
        assertEquals(DoorState.CLOSED, ChzhLockDriver.doorStateFromFeedback("closed"))
    }

    // ── 打开方向 ──────────────────────────────────────────────────────────

    @Test
    fun `DOOR=O 判为已打开`() {
        assertEquals(DoorState.OPEN, ChzhLockDriver.doorStateFromFeedback("DOOR=O"))
    }

    @Test
    fun `DOOR=1 判为已打开`() {
        assertEquals(DoorState.OPEN, ChzhLockDriver.doorStateFromFeedback("DOOR=1"))
    }

    @Test
    fun `OPEN 词判为已打开`() {
        assertEquals(DoorState.OPEN, ChzhLockDriver.doorStateFromFeedback("DOOR=OPEN"))
        assertEquals(DoorState.OPEN, ChzhLockDriver.doorStateFromFeedback("open"))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(DoorState.CLOSED, ChzhLockDriver.doorStateFromFeedback("door=c"))
        assertEquals(DoorState.OPEN, ChzhLockDriver.doorStateFromFeedback("Door=o"))
    }

    // ── 无法识别 ⇒ null，调用方保持原状态 ─────────────────────────────────

    @Test
    fun `无关文本返回 null 而不是猜一个状态`() {
        assertNull(ChzhLockDriver.doorStateFromFeedback(""))
        assertNull(ChzhLockDriver.doorStateFromFeedback("OK"))
        assertNull(ChzhLockDriver.doorStateFromFeedback("DOOR=?"))
    }

    @Test
    fun `裸 CLOSE 不被识别 —— 必须含 CLOSED`() {
        // 若现场固件回的是 "DOOR CLOSE"，状态永远不更新（会一路等到超时）。
        // 钉住这条是为了让协议变更时有人能立刻看到它。
        assertNull(ChzhLockDriver.doorStateFromFeedback("DOOR CLOSE"))
        assertEquals(
            DoorState.CLOSED,
            ChzhLockDriver.doorStateFromFeedback("DOOR CLOSED")
        )
    }

    @Test
    fun `OPENING 会因 DOOR=O 子串被读成 OPEN`() {
        // 已知缝隙：DOOR= 后跟 O 开头的一切（OPEN / OPENING / OPENED）都落进打开分支。
        // unlock() 之后本应停在 OPENING 等门磁确认，此处会直接进 OPEN。
        assertEquals(DoorState.OPEN, ChzhLockDriver.doorStateFromFeedback("DOOR=OPENING"))
    }

    @Test
    fun `关闭分支优先于打开分支`() {
        // when 的分支顺序：同时含两类关键词时判 CLOSED。钉住它，避免有人「顺手调个顺序」。
        assertEquals(
            DoorState.CLOSED,
            ChzhLockDriver.doorStateFromFeedback("DOOR=C OPEN")
        )
    }
}
