package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.KnowledgeEntryEntity;
import com.robustvision.platform.domain.KnowledgeTopicEntity;
import com.robustvision.platform.domain.UserEntity;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.repository.KnowledgeEntryRepository;
import com.robustvision.platform.repository.KnowledgeTopicRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 知识库服务：跨学科主题与知识卡的增删改查。
 *
 * 可见性规则：预置内容 owner 为空，所有登录用户可读；用户自建内容仅本人可读写。
 * 预置卡片允许编辑（编辑后仍标记 builtin，用于区分来源），删除即永久移除。
 */
@Service
public class KnowledgeService {

    private final KnowledgeTopicRepository topicRepository;
    private final KnowledgeEntryRepository entryRepository;
    private final CurrentUserService currentUserService;

    public KnowledgeService(KnowledgeTopicRepository topicRepository,
                            KnowledgeEntryRepository entryRepository,
                            CurrentUserService currentUserService) {
        this.topicRepository = topicRepository;
        this.entryRepository = entryRepository;
        this.currentUserService = currentUserService;
    }

    // ------------------------------------------------------------------
    // 主题
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ApiDtos.KnowledgeTopicView> listTopics() {
        UserEntity user = currentUserService.requireCurrent();
        List<KnowledgeTopicEntity> topics = visibleTopics(user);
        topics.sort(Comparator.comparing(KnowledgeTopicEntity::getDomain)
                .thenComparing(KnowledgeTopicEntity::getSortOrder)
                .thenComparing(KnowledgeTopicEntity::getId));
        return topics.stream().map(this::toTopicView).toList();
    }

    @Transactional(readOnly = true)
    public List<String> listDomains() {
        currentUserService.requireCurrent();
        return topicRepository.findDistinctDomainByOwnerIsNull();
    }

    @Transactional
    public ApiDtos.KnowledgeTopicView createTopic(ApiDtos.CreateKnowledgeTopicRequest request) {
        UserEntity user = currentUserService.requireCurrent();
        String domain = normalize(request.domain());
        String name = request.name().trim();
        if (topicRepository.existsByDomainAndNameAndOwnerId(domain, name, user.getId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "TOPIC_DUPLICATE", "同一领域下已存在同名主题");
        }
        int order = request.sortOrder() == null ? nextSortOrder(user.getId()) : request.sortOrder();
        KnowledgeTopicEntity topic = topicRepository.save(new KnowledgeTopicEntity(
                domain, name, trimToNull(request.description()), false, user, order));
        return toTopicView(topic);
    }

    @Transactional
    public ApiDtos.KnowledgeTopicView updateTopic(Long id, ApiDtos.UpdateKnowledgeTopicRequest request) {
        UserEntity user = currentUserService.requireCurrent();
        KnowledgeTopicEntity topic = requireOwnTopic(id, user);
        if (request.domain() != null && !request.domain().isBlank()) topic.setDomain(normalize(request.domain()));
        if (request.name() != null && !request.name().isBlank()) {
            String name = request.name().trim();
            if (topic.getOwner() != null
                    && topicRepository.existsByDomainAndNameAndOwnerId(topic.getDomain(), name, user.getId())
                    && !name.equals(topic.getName())) {
                throw new BusinessException(HttpStatus.CONFLICT, "TOPIC_DUPLICATE", "同一领域下已存在同名主题");
            }
            topic.setName(name);
        }
        if (request.description() != null) topic.setDescription(trimToNull(request.description()));
        if (request.sortOrder() != null) topic.setSortOrder(request.sortOrder());
        topic.setUpdatedAt(Instant.now());
        return toTopicView(topicRepository.save(topic));
    }

    @Transactional
    public void deleteTopic(Long id) {
        UserEntity user = currentUserService.requireCurrent();
        KnowledgeTopicEntity topic = requireOwnTopic(id, user);
        long entries = entryRepository.countByTopicId(topic.getId());
        if (entries > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "TOPIC_NOT_EMPTY",
                    "该主题下还有 " + entries + " 张知识卡，请先移动或删除后再删除主题");
        }
        topicRepository.delete(topic);
    }

    // ------------------------------------------------------------------
    // 知识卡
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ApiDtos.KnowledgeEntryView> listEntries(Long topicId, String keyword) {
        UserEntity user = currentUserService.requireCurrent();
        List<KnowledgeEntryEntity> entries;
        if (keyword != null && !keyword.isBlank()) {
            entries = entryRepository.search(user.getId(), keyword.trim());
            if (topicId != null) entries = entries.stream().filter(e -> topicId.equals(e.getTopic().getId())).toList();
        } else if (topicId != null) {
            requireReadableTopic(topicId, user);
            entries = entryRepository.findByTopicIdOrderBySortOrderAscCreatedAtAsc(topicId);
        } else {
            entries = new ArrayList<>();
            for (KnowledgeTopicEntity topic : visibleTopics(user)) {
                entries.addAll(entryRepository.findByTopicIdOrderBySortOrderAscCreatedAtAsc(topic.getId()));
            }
        }
        return entries.stream().map(this::toEntryView).toList();
    }

    @Transactional(readOnly = true)
    public ApiDtos.KnowledgeEntryView entry(String id) {
        UserEntity user = currentUserService.requireCurrent();
        return toEntryView(requireReadableEntry(id, user));
    }

    @Transactional
    public ApiDtos.KnowledgeEntryView createEntry(ApiDtos.CreateKnowledgeEntryRequest request) {
        UserEntity user = currentUserService.requireCurrent();
        KnowledgeTopicEntity topic = requireOwnTopic(request.topicId(), user);
        int order = request.sortOrder() == null ? 0 : request.sortOrder();
        KnowledgeEntryEntity entry = entryRepository.save(new KnowledgeEntryEntity(
                topic, request.title().trim(), trimToNull(request.summary()), request.body(),
                trimToNull(request.tags()), false, user, order));
        return toEntryView(entry);
    }

    @Transactional
    public ApiDtos.KnowledgeEntryView updateEntry(String id, ApiDtos.UpdateKnowledgeEntryRequest request) {
        UserEntity user = currentUserService.requireCurrent();
        KnowledgeEntryEntity entry = requireWritableEntry(id, user);
        if (request.topicId() != null) entry.setTopic(requireOwnTopic(request.topicId(), user));
        if (request.title() != null && !request.title().isBlank()) entry.setTitle(request.title().trim());
        if (request.summary() != null) entry.setSummary(trimToNull(request.summary()));
        if (request.body() != null && !request.body().isBlank()) entry.setBody(request.body());
        if (request.tags() != null) entry.setTags(trimToNull(request.tags()));
        if (request.sortOrder() != null) entry.setSortOrder(request.sortOrder());
        entry.setUpdatedAt(Instant.now());
        return toEntryView(entryRepository.save(entry));
    }

    @Transactional
    public void deleteEntry(String id) {
        UserEntity user = currentUserService.requireCurrent();
        KnowledgeEntryEntity entry = requireWritableEntry(id, user);
        long references = entryRepository.countNoteReferences(entry.getId());
        if (references > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "ENTRY_REFERENCED",
                    "该知识卡已被 " + references + " 篇笔记引用，删除后这些引用将失效");
        }
        entryRepository.delete(entry);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 供种子初始化器与笔记服务复用的只读查找。 */
    @Transactional(readOnly = true)
    public KnowledgeEntryEntity findEntryEntity(String id) {
        return entryRepository.findById(id).orElse(null);
    }

    @Transactional(readOnly = true)
    public boolean topicExists(Long id) {
        return topicRepository.existsById(id);
    }

    @Transactional(readOnly = true)
    public boolean entryExists(String id) {
        return entryRepository.existsById(id);
    }

    private List<KnowledgeTopicEntity> visibleTopics(UserEntity user) {
        List<KnowledgeTopicEntity> topics = new ArrayList<>(topicRepository.findByOwnerIsNullOrderBySortOrderAscIdAsc());
        topics.addAll(topicRepository.findByOwnerIdOrderBySortOrderAscIdAsc(user.getId()));
        return topics;
    }

    private KnowledgeTopicEntity requireReadableTopic(Long id, UserEntity user) {
        return topicRepository.findByIdAndOwnerIsNull(id)
                .or(() -> topicRepository.findByIdAndOwnerId(id, user.getId()))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "TOPIC_NOT_FOUND", "知识主题不存在"));
    }

    private KnowledgeTopicEntity requireOwnTopic(Long id, UserEntity user) {
        return topicRepository.findByIdAndOwnerId(id, user.getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.FORBIDDEN, "TOPIC_READONLY",
                        "预置主题不可修改；如需调整请复制为自己的主题后再编辑"));
    }

    private KnowledgeEntryEntity requireReadableEntry(String id, UserEntity user) {
        return entryRepository.findByIdAndOwnerIsNull(id)
                .or(() -> entryRepository.findByIdAndOwnerId(id, user.getId()))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "ENTRY_NOT_FOUND", "知识卡不存在"));
    }

    /**
     * 可写校验：预置知识卡（owner 为空）允许任何持 knowledge:write 的用户编辑——
     * 个人知识库场景下内置内容也应可增补修正；用户自建卡则仅限本人或管理员。
     */
    private KnowledgeEntryEntity requireWritableEntry(String id, UserEntity user) {
        KnowledgeEntryEntity entry = entryRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "ENTRY_NOT_FOUND", "知识卡不存在"));
        if (entry.getOwner() != null
                && !entry.getOwner().getId().equals(user.getId())
                && !currentUserService.isSuperAdmin(user)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ENTRY_FORBIDDEN", "无权编辑他人创建的知识卡");
        }
        return entry;
    }

    private int nextSortOrder(Long ownerId) {
        return topicRepository.findByOwnerIdOrderBySortOrderAscIdAsc(ownerId).stream()
                .mapToInt(KnowledgeTopicEntity::getSortOrder).max().orElse(0) + 10;
    }

    private ApiDtos.KnowledgeTopicView toTopicView(KnowledgeTopicEntity topic) {
        return new ApiDtos.KnowledgeTopicView(
                topic.getId(), topic.getDomain(), topic.getName(), topic.getDescription(),
                topic.isBuiltin(), topic.getSortOrder(), entryRepository.countByTopicId(topic.getId()),
                topic.getCreatedAt(), topic.getUpdatedAt());
    }

    private ApiDtos.KnowledgeEntryView toEntryView(KnowledgeEntryEntity entry) {
        return new ApiDtos.KnowledgeEntryView(
                entry.getId(), entry.getTopic().getId(), entry.getTopic().getName(), entry.getTopic().getDomain(),
                entry.getTitle(), entry.getSummary(), entry.getBody(), splitTags(entry.getTags()),
                entry.isBuiltin(), entryRepository.countNoteReferences(entry.getId()), entry.getSortOrder(),
                entry.getCreatedAt(), entry.getUpdatedAt());
    }

    static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) return List.of();
        return Arrays.stream(tags.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
