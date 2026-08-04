package com.aicabinet.trade.sms;

public interface SmsSender {
    void send(String phoneNumber, String code);
}
