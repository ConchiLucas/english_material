package com.aitaskcenter.repository;

import com.aitaskcenter.model.StoryRun;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoryRunRepository extends JpaRepository<StoryRun, Long> {
    Optional<StoryRun> findByRunId(String runId);

    List<StoryRun> findAllByOrderByCreatedAtDesc();

    @Query("""
            select storyRun from StoryRun storyRun
            where storyRun.status in :statuses
              and storyRun.finalStory is not null
              and trim(storyRun.finalStory) <> ''
              and length(storyRun.finalStory) <= :maxStoryLength
            order by storyRun.createdAt desc
            """)
    List<StoryRun> findImageSourceStories(
            @Param("statuses") Collection<String> statuses,
            @Param("maxStoryLength") int maxStoryLength,
            Pageable pageable);
}
