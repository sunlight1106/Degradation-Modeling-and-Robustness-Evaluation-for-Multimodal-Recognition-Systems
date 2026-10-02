package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.InferenceStatus;
import com.robustvision.platform.domain.InferenceTaskEntity;
import com.robustvision.platform.repository.NoteExperimentSourceRepository;
import com.robustvision.platform.repository.PersonalRecognitionResultRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

/** Own-only notebook sources, independently authorized even for platform administrators. */
@Service
public class NoteExperimentSourceService {
    private final NoteExperimentSourceRepository tasks;
    private final CurrentUserService currentUser;
    private final PersonalRecognitionResultRepository recognition;
    public NoteExperimentSourceService(NoteExperimentSourceRepository tasks, CurrentUserService currentUser, PersonalRecognitionResultRepository recognition) {
        this.recognition = recognition;
        this.tasks = tasks;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<SourceView> list() {
        Long owner = currentUser.requireCurrent().getId();
        var combined = new java.util.ArrayList<SourceView>();
        combined.addAll(tasks.findNoteSources(owner, PageRequest.of(0, 100)).stream().map(t -> new SourceView(t.getId(),
                t.getInputFile().getOriginalName(), t.getTaskType().name(), t.getModel().getName(),
                t.getStatus().name(), t.getCreatedAt(), t.getTraceId())).toList());
        recognition.findTop100ByOwnerIdOrderByCreatedAtDesc(owner).forEach(r -> combined.add(new SourceView(
                "r:" + r.getId(), r.getFileName(), r.getTaskType().name(), r.getModel(), "COMPLETED", r.getCreatedAt(), r.getId())));
        return combined.stream().sorted(java.util.Comparator.comparing(SourceView::createdAt).reversed()).limit(100).toList();
    }

    @Transactional(readOnly = true)
    public Preview preview(List<String> taskIds) {
        return new Preview(buildContext(taskIds), taskIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(taskIds)));
    }

    @Transactional(readOnly = true)
    public String buildContext(List<String> taskIds) {
        Long owner = currentUser.requireCurrent().getId();
        if (taskIds == null || taskIds.isEmpty()) return "";
        if (taskIds.size() > 20 || taskIds.stream().anyMatch(id -> id == null || !id.matches("(?:r:)?[A-Za-z0-9-]{1,36}"))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SOURCE_SELECTION_INVALID", "每次最多选择 20 个有效实验");
        }
        StringBuilder text = new StringBuilder();
        for (String id : new LinkedHashSet<>(taskIds)) {
            if (id.startsWith("r:")) {
                var result = recognition.findByIdAndOwnerId(id.substring(2), owner).orElseThrow(() ->
                        new BusinessException(HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND", "识别结果不存在或无权访问"));
                text.append("## 个人图片识别：").append(escape(result.getFileName())).append("\n\n")
                        .append("- 来源 ID：").append(result.getId()).append("\n")
                        .append("- 模型：").append(escape(result.getModel())).append("\n")
                        .append("- 任务类型：").append(result.getTaskType()).append("\n\n")
                        .append(code(result.getResultText())).append("\n> AI 识别结果应与原图人工核对。\n\n");
                if (text.length() > 24000) throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "SOURCE_CONTEXT_TOO_LARGE", "所选结果超过 24000 字符，请减少选择；不会静默截断数据");
                continue;
            }
            InferenceTaskEntity task = tasks.findOwnNoteSource(id, owner).orElseThrow(() ->
                    new BusinessException(HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND", "实验不存在或无权访问"));
            if (task.getStatus() != InferenceStatus.COMPLETED) {
                throw new BusinessException(HttpStatus.CONFLICT, "SOURCE_NOT_READY", "只能引用已完成的实验结果");
            }
            text.append("## 实验：").append(escape(task.getInputFile().getOriginalName())).append("\n\n")
                    .append("- 任务 ID：").append(task.getId()).append("\n")
                    .append("- traceId：").append(escape(task.getTraceId())).append("\n")
                    .append("- 模型：").append(escape(task.getModel().getName())).append("\n")
                    .append("- 任务类型：").append(task.getTaskType()).append("\n")
                    .append("- 完成时间：").append(task.getCompletedAt()).append("\n")
                    .append("- 基线置信度：").append(task.getBaselineConfidence()).append("\n")
                    .append("- 优化置信度：").append(task.getOptimizedConfidence()).append("\n\n")
                    .append("### 基线结果\n\n").append(code(task.getBaselineResult()))
                    .append("\n### 优化结果\n\n").append(code(task.getOptimizedResult()))
                    .append("\n> 模型置信度为模型自评，未经人工标注校准；DEMO 结果不可用于真实结论。\n\n");
            if (text.length() > 24000) throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "SOURCE_CONTEXT_TOO_LARGE", "所选结果超过 24000 字符，请减少选择；不会静默截断数据");
        }
        return text.toString();
    }

    private static String code(String value) {
        if (value == null || value.isBlank()) return "（无结果）\n";
        if (value.length() > 24000) throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE,
                "SOURCE_CONTEXT_TOO_LARGE", "所选结果超过 24000 字符，请减少选择；不会静默截断数据");
        // A fence longer than every run in the source prevents untrusted JSON escaping it.
        int run = 0, longest = 0;
        for (char ch : value.toCharArray()) { run = ch == '`' ? run + 1 : 0; longest = Math.max(longest, run); }
        String fence = "`".repeat(Math.max(3, longest + 1));
        return fence + "json\n" + value + "\n" + fence + "\n";
    }
    private static String escape(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]", " ").replaceAll("([\\\\`*_{}\\[\\]()<>#+.!|~-])", "\\\\$1");
    }
    public record SourceView(String taskId, String title, String taskType, String modelName,
                             String status, Instant createdAt, String traceId) {}
    public record Preview(String markdown, List<String> sourceIds) {}
}
