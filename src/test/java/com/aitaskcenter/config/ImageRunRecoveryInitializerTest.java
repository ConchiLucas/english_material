package com.aitaskcenter.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aitaskcenter.model.ImageRun;
import com.aitaskcenter.repository.ImageRunRepository;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class ImageRunRecoveryInitializerTest {
    @Test
    @SuppressWarnings("unchecked")
    void marksOnlyActiveRunsFailedWhenTheApplicationRestarts() throws Exception {
        ImageRunRepository runs = mock(ImageRunRepository.class);
        ImageRun queued = run("QUEUED");
        ImageRun generating = run("GENERATING_SHOTS");
        ImageRun completed = run("COMPLETED");
        ImageRun failed = run("FAILED");
        when(runs.findAllByStatusIn(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(queued, generating));
        ImageRunRecoveryInitializer initializer = new ImageRunRecoveryInitializer(runs);

        initializer.run(new DefaultApplicationArguments());

        ArgumentCaptor<Collection<String>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(runs).findAllByStatusIn(statuses.capture());
        assertEquals(List.of("QUEUED", "PLANNING", "GENERATING_REFERENCES", "GENERATING_SHOTS", "COMPOSITING"),
                List.copyOf(statuses.getValue()));
        verify(runs).saveAll(List.of(queued, generating));
        verify(runs).flush();
        assertFailedForRestart(queued);
        assertFailedForRestart(generating);
        assertEquals("COMPLETED", completed.getStatus());
        assertNull(completed.getFinishedAt());
        assertEquals("FAILED", failed.getStatus());
        assertNull(failed.getFinishedAt());
    }

    @Test
    void doesNotWriteWhenThereAreNoActiveRuns() throws Exception {
        ImageRunRepository runs = mock(ImageRunRepository.class);
        when(runs.findAllByStatusIn(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of());

        new ImageRunRecoveryInitializer(runs).run(new DefaultApplicationArguments());

        verify(runs, never()).saveAll(org.mockito.ArgumentMatchers.any());
        verify(runs, never()).flush();
    }

    @Test
    void allowsStartupToContinueWhenRecoveryLosesAnOptimisticLockRace() throws Exception {
        ImageRunRepository runs = mock(ImageRunRepository.class);
        when(runs.findAllByStatusIn(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of(run("PLANNING")));
        org.mockito.Mockito.doThrow(new ObjectOptimisticLockingFailureException(ImageRun.class, 1L))
                .when(runs).flush();

        assertDoesNotThrow(() -> new ImageRunRecoveryInitializer(runs).run(new DefaultApplicationArguments()));
    }

    private static ImageRun run(String status) {
        ImageRun run = new ImageRun();
        run.setStatus(status);
        return run;
    }

    private static void assertFailedForRestart(ImageRun run) {
        assertEquals("FAILED", run.getStatus());
        assertEquals("应用重启，图片批次无法继续", run.getErrorMessage());
        assertTrue(run.getFinishedAt() != null && run.getFinishedAt().getNano() % 1_000 == 0);
    }
}
