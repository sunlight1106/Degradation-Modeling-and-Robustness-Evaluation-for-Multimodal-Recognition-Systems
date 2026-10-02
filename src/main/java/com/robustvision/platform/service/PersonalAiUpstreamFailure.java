package com.robustvision.platform.service;
import com.robustvision.platform.common.BusinessException;
import org.springframework.http.HttpStatus;
/** Sanitized metadata only. A failed or incomplete generation may still be billed upstream. */
public class PersonalAiUpstreamFailure extends BusinessException {
    private final Long inputTokens;
    private final Long outputTokens;
    public PersonalAiUpstreamFailure(Long inputTokens,Long outputTokens) {
        super(HttpStatus.BAD_GATEWAY,"PERSONAL_AI_UPSTREAM_FAILED","供应商未返回完整可用结果；已产生的调用可能仍由供应商计费，请核对其账单");
        this.inputTokens=inputTokens; this.outputTokens=outputTokens;
    }
    public static Long input(Throwable error){return error instanceof PersonalAiUpstreamFailure f?f.inputTokens:null;}
    public static Long output(Throwable error){return error instanceof PersonalAiUpstreamFailure f?f.outputTokens:null;}
}
