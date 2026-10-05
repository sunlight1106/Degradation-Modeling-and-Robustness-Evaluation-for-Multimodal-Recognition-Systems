package com.robustvision.platform.domain;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;
@Entity
@Table(name="personal_recognition_result")
public class PersonalRecognitionResultEntity {
    @Id @Column(length=36) private String id = UUID.randomUUID().toString();
    @Column(name="owner_id",nullable=false) private Long ownerId;
    @Column(name="file_id",nullable=false,length=36) @JdbcTypeCode(SqlTypes.CHAR) private String fileId;
    @Column(name="file_name",nullable=false,length=255) private String fileName;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=24) private AiProvider provider;
    @Column(nullable=false,length=160) private String model;
    @Enumerated(EnumType.STRING) @Column(name="task_type",nullable=false,length=30) private TaskType taskType;
    @Lob @Column(name="result_text",nullable=false,columnDefinition="longtext") private String resultText;
    @Column(name="input_tokens") private Long inputTokens;
    @Column(name="output_tokens") private Long outputTokens;
    @Column(name="created_at",nullable=false) private Instant createdAt = Instant.now();
    protected PersonalRecognitionResultEntity() {}
    public PersonalRecognitionResultEntity(Long ownerId, String fileId, String fileName, AiProvider provider, String model,
            TaskType taskType, String resultText, Long inputTokens, Long outputTokens) {
        this.ownerId=ownerId; this.fileId=fileId; this.fileName=fileName; this.provider=provider; this.model=model;
        this.taskType=taskType; this.resultText=resultText; this.inputTokens=inputTokens; this.outputTokens=outputTokens;
    }
    public String getId(){return id;} public Long getOwnerId(){return ownerId;} public String getFileId(){return fileId;}
    public String getFileName(){return fileName;} public AiProvider getProvider(){return provider;} public String getModel(){return model;}
    public TaskType getTaskType(){return taskType;} public String getResultText(){return resultText;}
    public Long getInputTokens(){return inputTokens;} public Long getOutputTokens(){return outputTokens;} public Instant getCreatedAt(){return createdAt;}
}
