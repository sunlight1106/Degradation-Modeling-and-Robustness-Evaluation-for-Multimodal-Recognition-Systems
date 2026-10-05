package com.robustvision.platform.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class VocabularyDtos {
    private VocabularyDtos() {}
    public record Settings(String zoneId, int dailyGoal, String selectedBookId, int masteryTarget) {}
    public record SettingsRequest(@NotBlank @Size(max=80) String zoneId,
                                  @Min(1) @Max(100) int dailyGoal, @Size(max=36) String selectedBookId) {}
    public record Book(String id, String title, String description, String attribution, String level,
                       boolean owned, long totalWords, long learned, long learning, long due, long mistakes) {}
    public record Day(LocalDate date, long answers, long correct, long learned, long reviews) {}
    public record Dashboard(Settings settings, List<Book> books, Day today, int streak,
                            List<Day> history, long dueTotal, long starredTotal) {}
    public record NextRequest(@NotBlank @Size(max=36) String bookId,
                              @NotBlank @Pattern(regexp="LEARN|REVIEW|MISTAKES") String mode) {}
    public record Option(String id, String meaning) {}
    /** No meaning, example, correct option, database word id, or progress mutation in this DTO. */
    public record Question(String id, String term, String ipa, String pos, String mode,
                           int learningCorrect, int masteryTarget, List<Option> options, Instant expiresAt) {}
    public record Next(Question question, String reason, long remaining) {}
    public record AnswerRequest(@NotBlank @Size(max=36) String optionId) {}
    public record Answer(String questionId, String wordId, boolean correct, String correctOptionId,
                         String meaning, String example, String exampleTranslation, int learningCorrect,
                         int masteryTarget, boolean newlyLearned, LocalDate dueDate, int reviewStage,
                         boolean starred, String message) {}
    public record Word(String id, String term, String ipa, String pos, String meaning, String example,
                       String exampleTranslation, int learningCorrect, int wrongCount, boolean mistake,
                       boolean starred, LocalDate dueDate, int reviewStage) {}
    public record WordPage(List<Word> items, long total, int page, int pageSize) {}
    public record StarRequest(boolean starred) {}
    public record ImportWord(@NotBlank @Size(max=80) String term, @NotBlank @Size(max=120) String ipa,
                             @NotBlank @Pattern(regexp="n\\.|v\\.|adj\\.|adv\\.|prep\\.|pron\\.|conj\\.|phr\\.") String pos,
                             @NotBlank @Size(max=160) String meaning,
                             @NotBlank @Size(max=400) String example,
                             @NotBlank @Size(max=400) String exampleTranslation,
                             @NotNull @Size(min=3,max=8) List<@NotBlank @Size(max=160) String> distractors) {}
    public record ImportRequest(@NotBlank @Size(max=100) String title,
                                @NotBlank @Size(max=600) String description,
                                @NotBlank @Size(max=300) String attribution,
                                @AssertTrue boolean rightsConfirmed,
                                @NotNull @Size(min=4,max=500) List<@NotNull @Valid ImportWord> words,
                                @Min(1) @Max(1) Integer schemaVersion) {}
}
