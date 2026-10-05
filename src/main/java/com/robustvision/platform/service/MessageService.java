package com.robustvision.platform.service;

import com.robustvision.platform.common.BusinessException;
import com.robustvision.platform.domain.*;
import com.robustvision.platform.dto.ApiDtos;
import com.robustvision.platform.repository.*;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@Service
public class MessageService {
    private static final int LIST_BATCH_SIZE = 500;
    private final MessageRepository messageRepository;
    private final MessageRecipientRepository recipientRepository;
    private final MessageAttachmentRepository attachmentRepository;
    private final UserRepository userRepository;
    private final FileService fileService;
    private final CurrentUserService currentUserService;
    private final WorkspaceService workspaceService;

    public MessageService(MessageRepository messageRepository, MessageRecipientRepository recipientRepository,
                          MessageAttachmentRepository attachmentRepository, UserRepository userRepository,
                          FileService fileService, CurrentUserService currentUserService, WorkspaceService workspaceService) {
        this.messageRepository = messageRepository; this.recipientRepository = recipientRepository;
        this.attachmentRepository = attachmentRepository; this.userRepository = userRepository;
        this.fileService = fileService; this.currentUserService = currentUserService;
        this.workspaceService = workspaceService;
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.MessageContactView> directory() {
        UserEntity current = currentUserService.requireCurrent();
        return userRepository.findMessageContacts(current.getId(), currentUserService.isSuperAdmin(current), true, List.of(current.getId()))
                .stream().map(user -> new ApiDtos.MessageContactView(user.getId(), user.getUsername(), user.getDisplayName(),
                        "ADMIN".equals(user.getRoleCode()) ? "ADMIN" : currentUserService.isSuperAdmin(current) ? "USER" : "GROUP_MEMBER")).toList();
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.MessageView> inbox() {
        UserEntity current = currentUserService.requireCurrent();
        return toViews(messageRepository.findInboxRows(current.getId()), current);
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.MessageView> sent() {
        UserEntity current = currentUserService.requireCurrent();
        return toViews(messageRepository.findSentRows(current.getId()), current);
    }

    @Transactional
    public ApiDtos.MessageView send(List<Long> recipientIds, String subject, String body, List<MultipartFile> files) {
        return sendDirect(recipientIds, subject, body, files, null);
    }

    @Transactional
    public ApiDtos.MessageView sendDirect(List<Long> recipientIds, String subject, String body, List<MultipartFile> files, String replyToId) {
        UserEntity sender = currentUserService.requireCurrent();
        LinkedHashSet<Long> ids = new LinkedHashSet<>(recipientIds == null ? List.of() : recipientIds);
        if (ids.isEmpty() || ids.size() > 20 || ids.contains(null)) throw new BusinessException(HttpStatus.BAD_REQUEST, "MESSAGE_RECIPIENTS_INVALID", "请选择 1 到 20 个收件人");
        if (sender.getStatus() != UserStatus.ACTIVE || userRepository.findMessageContacts(sender.getId(),
                currentUserService.isSuperAdmin(sender), false, ids).size() != ids.size())
            throw new BusinessException(HttpStatus.FORBIDDEN, "MESSAGE_RECIPIENT_FORBIDDEN", "只能联系平台管理员或有交流权限的同组成员，请刷新联系人列表");
        if (replyToId != null) {
            MessageEntity parent = requireAccessible(replyToId, sender);
            Set<Long> participants = new HashSet<>();
            participants.add(parent.getSender().getId());
            recipientRepository.findByMessageIdOrderByIdAsc(replyToId).forEach(item -> participants.add(item.getRecipient().getId()));
            if (parent.getWorkspaceId() != null || !participants.containsAll(ids))
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MESSAGE_REPLY_SCOPE", "回复只能发给原会话的参与者");
        }
        MessageEntity message = saveMessage(sender, subject, body, files, null, replyToId);
        ids.forEach(id -> recipientRepository.save(new MessageRecipientEntity(message, userRepository.getReferenceById(id))));
        return toView(message, sender);
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.MessageView> groupMessages(Long workspaceId, int page) {
        workspaceService.requireContentPermission(workspaceId, false);
        if (page < 0 || page > 10000) throw new BusinessException(HttpStatus.BAD_REQUEST, "PAGE_INVALID", "页码无效");
        return toViews(messageRepository.findGroupRows(workspaceId, org.springframework.data.domain.PageRequest.of(page, 50)), currentUserService.requireCurrent());
    }

    @Transactional
    public ApiDtos.MessageView sendGroup(Long workspaceId, String body, List<MultipartFile> files, String replyToId) {
        workspaceService.requireContentPermission(workspaceId, true);
        UserEntity sender = currentUserService.requireCurrent();
        MessageEntity message = saveMessage(sender, "群组交流", body, files, workspaceId, replyToId);
        return toView(message, sender);
    }

    private MessageEntity saveMessage(UserEntity sender, String subject, String body, List<MultipartFile> files, Long workspaceId, String replyToId) {
        if (subject == null || subject.isBlank() || subject.trim().length() > 180) throw new BusinessException(HttpStatus.BAD_REQUEST, "MESSAGE_SUBJECT_INVALID", "主题不能为空且不能超过 180 字");
        if (body == null || body.isBlank() || body.length() > 20000) throw new BusinessException(HttpStatus.BAD_REQUEST, "MESSAGE_BODY_INVALID", "正文不能为空且不能超过 20000 字");
        List<MultipartFile> attachments = files == null ? List.of() : files.stream().filter(file -> file != null && !file.isEmpty()).toList();
        if (attachments.size() > 5) throw new BusinessException(HttpStatus.BAD_REQUEST, "MESSAGE_ATTACHMENTS_LIMIT", "每封站内信最多 5 个附件");
        if (replyToId != null) {
            MessageEntity parent = requireAccessible(replyToId, sender);
            if (!Objects.equals(parent.getWorkspaceId(), workspaceId))
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MESSAGE_REPLY_SCOPE", "不能引用其他会话的消息");
        }
        MessageEntity message = new MessageEntity(sender, subject.trim(), body.trim());
        message.setWorkspaceId(workspaceId); message.setReplyToId(replyToId);
        messageRepository.save(message);
        attachments.forEach(upload -> attachmentRepository.save(new MessageAttachmentEntity(message, fileService.storeMessageAttachment(upload, sender))));
        return message;
    }

    @Transactional
    public ApiDtos.MessageView detail(String id) {
        UserEntity current = currentUserService.requireCurrent(); MessageEntity message = requireAccessible(id, current);
        recipientRepository.findByMessageIdAndRecipientId(id, current.getId()).ifPresent(item -> { item.markRead(); recipientRepository.save(item); });
        return toView(message, current);
    }

    @Transactional(readOnly = true)
    public AttachmentDownload attachment(String messageId, Long attachmentId) {
        UserEntity current = currentUserService.requireCurrent(); requireAccessible(messageId, current);
        MessageAttachmentEntity attachment = attachmentRepository.findByIdAndMessageId(attachmentId, messageId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MESSAGE_ATTACHMENT_NOT_FOUND", "附件不存在"));
        return new AttachmentDownload(fileService.asResource(attachment.getFile()), attachment.getFile().getOriginalName(), attachment.getFile().getContentType());
    }

    private MessageEntity requireAccessible(String id, UserEntity current) {
        MessageEntity message = messageRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND", "站内信不存在"));
        if (message.getWorkspaceId() != null) {
            // Check current membership even for the original sender and previously shared attachment links.
            workspaceService.requireContentPermission(message.getWorkspaceId(), false);
            return message;
        }
        boolean sender = message.getSender().getId().equals(current.getId());
        boolean recipient = recipientRepository.findByMessageIdAndRecipientId(id, current.getId()).isPresent();
        if (!sender && !recipient && !currentUserService.isSuperAdmin(current))
            throw new BusinessException(HttpStatus.FORBIDDEN, "MESSAGE_ACCESS_DENIED", "无权查看这封站内信");
        return message;
    }

    /** Two child queries per bounded batch, with no per-message entity loading. */
    private List<ApiDtos.MessageView> toViews(List<MessageRepository.MessageRow> messages, UserEntity current) {
        if (messages.isEmpty()) return List.of();
        List<ApiDtos.MessageView> result = new ArrayList<>(messages.size());
        for (int start = 0; start < messages.size(); start += LIST_BATCH_SIZE) {
            List<MessageRepository.MessageRow> batch = messages.subList(start, Math.min(start + LIST_BATCH_SIZE, messages.size()));
            List<String> ids = batch.stream().map(MessageRepository.MessageRow::getId).toList();
            Map<String, List<ApiDtos.UserDirectoryView>> recipients = new HashMap<>();
            Set<String> readMessages = new HashSet<>();
            for (MessageRecipientRepository.RecipientRow row : recipientRepository.findRowsByMessageIds(ids)) {
                recipients.computeIfAbsent(row.getMessageId(), ignored -> new ArrayList<>()).add(
                        new ApiDtos.UserDirectoryView(row.getRecipientId(), row.getUsername(), row.getDisplayName(), row.getEmail()));
                if (row.getRecipientId().equals(current.getId()) && row.getReadAt() != null) readMessages.add(row.getMessageId());
            }
            Map<String, List<ApiDtos.MessageAttachmentView>> attachments = new HashMap<>();
            for (MessageAttachmentRepository.AttachmentRow row : attachmentRepository.findRowsByMessageIds(ids)) {
                attachments.computeIfAbsent(row.getMessageId(), ignored -> new ArrayList<>()).add(
                        new ApiDtos.MessageAttachmentView(row.getId(), row.getFileName(), row.getContentType(), row.getSizeBytes(),
                                "/api/v1/messages/" + row.getMessageId() + "/attachments/" + row.getId()));
            }
            for (MessageRepository.MessageRow message : batch) {
                result.add(new ApiDtos.MessageView(message.getId(), message.getSenderId(), message.getSenderName(),
                        message.getSubject(), message.getBody(), recipients.getOrDefault(message.getId(), List.of()),
                        attachments.getOrDefault(message.getId(), List.of()),
                        message.getWorkspaceId() != null || message.getSenderId().equals(current.getId()) || readMessages.contains(message.getId()),
                        message.getCreatedAt(), message.getWorkspaceId(), message.getReplyToId()));
            }
        }
        return List.copyOf(result);
    }

    private ApiDtos.MessageView toView(MessageEntity message, UserEntity current) {
        List<MessageRecipientEntity> recipientRows = recipientRepository.findByMessageIdOrderByIdAsc(message.getId());
        List<ApiDtos.UserDirectoryView> recipients = recipientRows.stream().map(item -> new ApiDtos.UserDirectoryView(
                item.getRecipient().getId(), item.getRecipient().getUsername(), item.getRecipient().getDisplayName(), item.getRecipient().getEmail())).toList();
        List<ApiDtos.MessageAttachmentView> attachments = attachmentRepository.findByMessageIdOrderByIdAsc(message.getId()).stream().map(item ->
                new ApiDtos.MessageAttachmentView(item.getId(), item.getFile().getOriginalName(), item.getFile().getContentType(), item.getFile().getSizeBytes(),
                        "/api/v1/messages/" + message.getId() + "/attachments/" + item.getId())).toList();
        boolean read = message.getSender().getId().equals(current.getId()) || recipientRows.stream()
                .filter(item -> item.getRecipient().getId().equals(current.getId())).anyMatch(item -> item.getReadAt() != null);
        return new ApiDtos.MessageView(message.getId(), message.getSender().getId(), message.getSender().getDisplayName(), message.getSubject(),
                message.getBody(), recipients, attachments, read, message.getCreatedAt(), message.getWorkspaceId(), message.getReplyToId());
    }

    public record AttachmentDownload(Resource resource, String fileName, String contentType) {}
}
