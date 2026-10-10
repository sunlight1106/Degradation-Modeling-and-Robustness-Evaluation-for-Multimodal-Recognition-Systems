package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.PersonalAiUsageEntity;
import com.robustvision.platform.domain.PersonalRecognitionResultEntity;
import com.robustvision.platform.repository.PersonalAiUsageRepository;
import com.robustvision.platform.repository.PersonalRecognitionResultRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Short, post-network transactions only. Never retry a paid provider request to repair local accounting. */
@Service
public class PersonalAiPersistenceService {
    static final String USAGE_WARNING = "供应商已返回结果，但平台未能确认保存本次用量记录；使用统计可能不完整。"
            + "请先复制保留结果并核对供应商账单，不要重复发送，避免再次计费。";
    static final String RECOGNITION_WARNING = "供应商已返回结果，但平台未能确认保存识别结果及用量记录。"
            + "请先复制保留下方结果，刷新历史记录并核对供应商账单；不要重复发送，避免再次计费。";
    private final PersonalAiUsageRepository usage;
    private final PersonalRecognitionResultRepository results;
    private final TransactionTemplate transaction;
    private final com.robustvision.platform.repository.UserRepository users;
    public void requireActiveOwner(Long owner){transaction.executeWithoutResult(status->checkOwner(owner));}
    private void checkOwner(Long owner){var user=users.findLockedById(owner).orElseThrow();if(!user.hasActiveAccess(java.time.Instant.now()))throw new BusinessException(HttpStatus.FORBIDDEN,"ACCOUNT_UNAVAILABLE","账号已停用或使用期限已结束");}

    public PersonalAiPersistenceService(PersonalAiUsageRepository usage, PersonalRecognitionResultRepository results,
                                        PlatformTransactionManager transactionManager,com.robustvision.platform.repository.UserRepository users) {
        this.users=users;
        this.usage = usage;
        this.results = results;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void recordUsage(PersonalAiUsageEntity row) {
        transaction.executeWithoutResult(status -> {checkOwner(row.getOwnerId());usage.save(row);});
    }

    public PersonalRecognitionResultEntity recordRecognition(PersonalRecognitionResultEntity result, PersonalAiUsageEntity row) {
        return transaction.execute(status -> {
            checkOwner(result.getOwnerId());
            var saved = results.save(result);
            usage.save(row);
            return saved;
        });
    }

    /** Failure logging must not mask the original outcome or retry the same unavailable database. */
    public BusinessException recordFailure(PersonalAiUsageEntity row, BusinessException failure) {
        try {
            recordUsage(row);
            return failure;
        } catch (RuntimeException persistenceFailure) {
            // Never include exception details, a credential, the prompt, or an upstream error body.
            return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PERSONAL_AI_USAGE_UNCONFIRMED",
                    "未取得完整可用结果，供应商请求可能已产生费用；平台未能确认保存用量记录。已报告输入 / 输出 Tokens："
                            + tokens(row.getInputTokens()) + " / " + tokens(row.getOutputTokens())
                            + "。请核对供应商账单，不要重复发送，避免再次计费。");
        }
    }

    private static String tokens(Long value) { return value == null ? "未知" : value.toString(); }
}
