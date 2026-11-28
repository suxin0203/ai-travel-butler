package com.suxin.travel.traveljava.notify;

/** 一次提醒的渠道发送统计：attempted=可用渠道数；success=发送成功数 */
public record NotifyResult(int attempted, int success) {
}
