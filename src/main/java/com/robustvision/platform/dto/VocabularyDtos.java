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
                       boolean owned, long totalWords, long learned, long learning, long due, long mistakes,
                       long skipped, long shared, int duplicatesRemoved) {
        public Book(String id,String title,String description,String attribution,String level,boolean owned,long totalWords,long learned,long learning,long due,long mistakes) {
            this(id,title,description,attribution,level,owned,totalWords,learned,learning,due,mistakes,0,0,0);
        }
    }
    public record Day(LocalDate date, long answers, long correct, long learned, long reviews) {}
    public record Dashboard(Settings settings, List<Book> books, Day today, int streak,
                            List<Day> history, long dueTotal, long starredTotal) {}
    public record NextRequest(@NotBlank @Size(max=36) String bookId,
                              @NotBlank @Pattern(regexp="LEARN|REVIEW|MISTAKES") String mode,
                              @Pattern(regexp="CHOICE|RECALL|LISTENING|MIXED|CONFUSABLE|MEANINGS") String style) {
        public NextRequest(String bookId,String mode){this(bookId,mode,"CHOICE");}
    }
    public record Option(String id, String meaning) {}
    /** No meaning, example, correct option, database word id, or progress mutation in this DTO. */
    public record Question(String id, String term, String ipa, String pos, String mode,
                           int learningCorrect, int masteryTarget, List<Option> options, Instant expiresAt,
                           String practiceKind, String prompt, boolean introduced, int hintLevel) {}
    public record Next(Question question, String reason, long remaining) {}
    public record AnswerRequest(@Size(max=36) String optionId, @Size(max=160) String text) {
        public AnswerRequest(String optionId){this(optionId,null);}
    }
    public record Answer(String questionId, String wordId, boolean correct, String correctOptionId,
                         String meaning, String example, String exampleTranslation, int learningCorrect,
                         int masteryTarget, boolean newlyLearned, LocalDate dueDate, int reviewStage,
                         boolean starred, String message, String evidence, String expectedText,
                         int independentCorrect, int promptedCorrect, int immediateCorrect,
                         int spellingCorrect, int collocationCorrect, Lesson lesson) {}
    public record Word(String id, String term, String ipa, String pos, String meaning, String example,
                       String exampleTranslation, int learningCorrect, int wrongCount, boolean mistake,
                       boolean starred, LocalDate dueDate, int reviewStage, boolean skipped,
                       int independentCorrect, int promptedCorrect, int immediateCorrect,
                       int spellingCorrect, int collocationCorrect, Lesson lesson) {}
    public record WordPage(List<Word> items, long total, int page, int pageSize) {}
    public record StarRequest(boolean starred) {}
    public record SkipRequest(boolean skipped) {}
    public record HintRequest(@Min(1) @Max(3) int level) {}
    public record Hint(int level, String text) {}
    public record Collocation(@NotBlank @Size(max=160) String pattern, @NotBlank @Size(max=200) String meaning,
                              @Size(max=300) String note) {}
    public record Lesson(String term, String ipa, String pos, String meaning, String memoryCue,
                         String usageNote, List<Collocation> collocations, String example, String exampleTranslation,
                         String pronunciationSource) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record ImportWord(@NotBlank @Size(max=80) String term, @NotBlank @Size(max=120) String ipa,
                             @NotBlank @Pattern(regexp="n\\.|v\\.|adj\\.|adv\\.|prep\\.|pron\\.|conj\\.|phr\\.") String pos,
                             @NotBlank @Size(max=160) String meaning,
                             @NotBlank @Size(max=400) String example,
                             @NotBlank @Size(max=400) String exampleTranslation,
                             @NotNull @Size(min=3,max=8) List<@NotBlank @Size(max=160) String> distractors,
                             @Size(max=300) String memoryCue, @Size(max=500) String usageNote,
                             @Size(max=6) List<@NotNull @Valid Collocation> collocations) {
        public ImportWord(String term,String ipa,String pos,String meaning,String example,String exampleTranslation,List<String> distractors) {
            this(term,ipa,pos,meaning,example,exampleTranslation,distractors,null,null,null);
        }
    }
    public record ImportRequest(@NotBlank @Size(max=100) String title,
                                @NotBlank @Size(max=600) String description,
                                @NotBlank @Size(max=300) String attribution,
                                @AssertTrue boolean rightsConfirmed,
                                @NotNull @Size(min=4,max=500) List<@NotNull @Valid ImportWord> words,
                                @Min(1) @Max(1) Integer schemaVersion) {}
}
