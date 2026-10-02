package com.robustvision.platform.repository;

import com.robustvision.platform.domain.InferenceTaskEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InferenceTaskRepository extends JpaRepository<InferenceTaskEntity, String> {
    boolean existsByIdAndRequestedByIdAndInputFileOwnerId(String id, Long requestedById, Long ownerId);
    @EntityGraph(attributePaths = {"inputFile.owner", "outputFile.owner", "model", "requestedBy"})
    List<InferenceTaskEntity> findAllByOrderByCreatedAtDesc();
    @EntityGraph(attributePaths = {"inputFile.owner", "outputFile.owner", "model", "requestedBy"})
    List<InferenceTaskEntity> findByRequestedByIdOrderByCreatedAtDesc(Long requestedBy);

    // Only to-one associations: SQL LIMIT is retained (no collection-fetch pagination).
    @EntityGraph(attributePaths = {"inputFile.owner", "outputFile.owner", "model", "requestedBy"})
    List<InferenceTaskEntity> findTop5ByOrderByCreatedAtDescIdDesc();

    @EntityGraph(attributePaths = {"inputFile.owner", "outputFile.owner", "model", "requestedBy"})
    List<InferenceTaskEntity> findTop5ByRequestedByIdOrderByCreatedAtDescIdDesc(Long requestedBy);

    String SUMMARY_SELECT = """
            select count(t) as total,
                   coalesce(sum(case when t.status = com.robustvision.platform.domain.InferenceStatus.COMPLETED then 1 else 0 end), 0) as completed,
                   coalesce(sum(case when t.status = com.robustvision.platform.domain.InferenceStatus.FAILED then 1 else 0 end), 0) as failed,
                   avg(t.optimizedConfidence - t.baselineConfidence) as averageLift
            from InferenceTaskEntity t
            """;

    @Query(SUMMARY_SELECT)
    SummaryStatistics summarizeAll();

    @Query(SUMMARY_SELECT + " where t.requestedBy.id = :userId")
    SummaryStatistics summarizeByRequestedById(@Param("userId") Long userId);

    interface SummaryStatistics {
        long getTotal();
        long getCompleted();
        long getFailed();
        Double getAverageLift();
    }
}
