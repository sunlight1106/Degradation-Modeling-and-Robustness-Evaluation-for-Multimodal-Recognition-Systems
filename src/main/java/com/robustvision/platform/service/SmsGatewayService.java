package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.util.Map;

@Service
public class SmsGatewayService {
    private final String mode;
    private final String webhookUrl;

    public SmsGatewayService(@Value("${app.sms.mode:sandbox}") String mode,
                             @Value("${app.sms.webhook-url:}") String webhookUrl) {
        this.mode = mode; this.webhookUrl = webhookUrl;
    }
    public boolean sandbox() { return !"webhook".equalsIgnoreCase(mode); }
    public void send(String phone, String code, String orderId) {
        if (sandbox()) return;
        if (webhookUrl == null || webhookUrl.isBlank())
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "SMS_GATEWAY_MISSING", "短信服务尚未配置");
        try {
            RestClient.create().post().uri(webhookUrl).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("phone", phone, "code", code, "orderId", orderId,
                            "message", "Personal Platform 验证码：" + code + "，10 分钟内有效。"))
                    .retrieve().toBodilessEntity();
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "SMS_SEND_FAILED", "短信验证码发送失败");
        }
    }
}
