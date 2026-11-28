package com.suxin.travel.traveljava.common;

/**
 * SSE 公共常量：对话流与提醒流的连接超时此前各写一份 1800000L（30 分钟），
 * 收口为单一事实源——改超时不再需要记得改两处。
 */
public final class SseConstants {

    private SseConstants() {
    }

    /** SSE 连接最长存活 30 分钟（提醒长连接靠自身重连机制续命，非靠这个超时） */
    public static final long TIMEOUT_MS = 1800000L;
}
