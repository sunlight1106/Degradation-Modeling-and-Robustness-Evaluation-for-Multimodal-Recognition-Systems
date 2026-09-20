package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.ModelDefinitionEntity;
import com.robustvision.platform.domain.ModelStatus;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.repository.ModelDefinitionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ModelService {
    private final ModelDefinitionRepository modelRepository;

    public ModelService(ModelDefinitionRepository modelRepository) {
        this.modelRepository = modelRepository;
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.ModelView> listActive() {
        return modelRepository.findByStatusOrderByNameAsc(ModelStatus.ACTIVE).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public ModelDefinitionEntity requireActive(Long id) {
        ModelDefinitionEntity model = modelRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MODEL_NOT_FOUND", "模型不存在"));
        if (model.getStatus() != ModelStatus.ACTIVE) {
            throw new BusinessException(HttpStatus.CONFLICT, "MODEL_INACTIVE", "模型当前不可调用");
        }
        return model;
    }

    public ApiDtos.ModelView toView(ModelDefinitionEntity model) {
        String docs = switch (model.getProvider()) {
            case DEEPSEEK -> "https://api-docs.deepseek.com/";
            case KIMI -> "https://platform.moonshot.cn/docs/api/chat";
            case QWEN -> "https://help.aliyun.com/zh/model-studio/qwen-api-reference/";
            default -> "";
        };
        String console = switch (model.getProvider()) {
            case DEEPSEEK -> "https://platform.deepseek.com/usage";
            case KIMI -> "https://platform.moonshot.cn/console/api-keys";
            case QWEN -> "https://bailian.console.aliyun.com/";
            default -> "";
        };
        return new ApiDtos.ModelView(model.getId(), model.getCode(), model.getName(), model.getVersion(),
                model.getProvider(), model.getTaskType(), model.getStatus(), model.getDescription(), docs, console, model.getCreatedAt());
    }
}
