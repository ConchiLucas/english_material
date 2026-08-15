package com.aitaskcenter.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aitaskcenter.model.ImageRun;
import com.aitaskcenter.model.ImageRunStep;
import com.aitaskcenter.repository.ImageRunRepository;
import com.aitaskcenter.repository.ImageRunStepRepository;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

class ImageRunRecoveryInitializerTest {
    @Test
    @SuppressWarnings("unchecked")
    void failsActiveRunAndRunningStepsTogetherWithoutChangingTerminalSteps() throws Exception {
        Fixture fixture = fixture();
        ImageRun queued = run("run-a", "QUEUED");
        ImageRunStep running = step("run-a", 1, "RUNNING");
        ImageRunStep completed = step("run-a", 2, "COMPLETED");
        ImageRunStep failed = step("run-a", 3, "FAILED");
        OffsetDateTime completedAt = OffsetDateTime.parse("2026-08-14T10:00:00+08:00");
        OffsetDateTime failedAt = OffsetDateTime.parse("2026-08-14T11:00:00+08:00");
        completed.setFinishedAt(completedAt);
        failed.setFinishedAt(failedAt);
        when(fixture.runs().findAllByStatusIn(anyCollection())).thenReturn(List.of(queued));
        when(fixture.steps().findAllByRunIdOrderBySequenceAsc(eq("run-a"), any()))
                .thenReturn(List.of(running, completed, failed));

        fixture.initializer().run(new DefaultApplicationArguments());

        ArgumentCaptor<Collection<String>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(fixture.runs()).findAllByStatusIn(statuses.capture());
        assertEquals(List.of("QUEUED", "PLANNING", "GENERATING_REFERENCES", "GENERATING_SHOTS", "COMPOSITING"),
                List.copyOf(statuses.getValue()));
        assertInterrupted(queued);
        assertInterrupted(running);
        assertSame(queued.getFinishedAt(), running.getFinishedAt());
        assertEquals("COMPLETED", completed.getStatus());
        assertEquals(completedAt, completed.getFinishedAt());
        assertEquals("FAILED", failed.getStatus());
        assertEquals(failedAt, failed.getFinishedAt());
        verify(fixture.steps()).saveAndFlush(running);
        verify(fixture.steps(), never()).saveAndFlush(completed);
        verify(fixture.steps(), never()).saveAndFlush(failed);
        verify(fixture.runs()).saveAndFlush(queued);
        verify(fixture.transactions()).commit(any());
        verify(fixture.transactions(), never()).rollback(any());
        verifyRequiresNewTransaction(fixture.transactions(), 1);
    }

    @Test
    void boundsRecoveryStepLookupToTwelveRowsPerRun() throws Exception {
        Fixture fixture = fixture();
        ImageRun run = run("run-b", "GENERATING_SHOTS");
        when(fixture.runs().findAllByStatusIn(anyCollection())).thenReturn(List.of(run));
        when(fixture.steps().findAllByRunIdOrderBySequenceAsc(eq("run-b"), any())).thenReturn(List.of());

        fixture.initializer().run(new DefaultApplicationArguments());

        ArgumentCaptor<org.springframework.data.domain.Pageable> pageable =
                ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(fixture.steps()).findAllByRunIdOrderBySequenceAsc(eq("run-b"), pageable.capture());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(12, pageable.getValue().getPageSize());
    }

    @Test
    void stillFailsRunWhenItHasNoPersistedSteps() throws Exception {
        Fixture fixture = fixture();
        ImageRun run = run("run-empty", "PLANNING");
        when(fixture.runs().findAllByStatusIn(anyCollection())).thenReturn(List.of(run));
        when(fixture.steps().findAllByRunIdOrderBySequenceAsc(eq("run-empty"), any())).thenReturn(List.of());

        fixture.initializer().run(new DefaultApplicationArguments());

        assertInterrupted(run);
        verify(fixture.steps(), never()).saveAndFlush(any(ImageRunStep.class));
        verify(fixture.runs()).saveAndFlush(run);
        verify(fixture.transactions()).commit(any());
    }

    @Test
    void doesNotStartTransactionsOrWriteWhenThereAreNoActiveRuns() throws Exception {
        Fixture fixture = fixture();
        when(fixture.runs().findAllByStatusIn(anyCollection())).thenReturn(List.of());

        fixture.initializer().run(new DefaultApplicationArguments());

        verify(fixture.steps(), never()).findAllByRunIdOrderBySequenceAsc(any(), any());
        verify(fixture.steps(), never()).saveAndFlush(any(ImageRunStep.class));
        verify(fixture.runs(), never()).saveAndFlush(any());
        verify(fixture.transactions(), never()).getTransaction(any());
    }

    @Test
    void rollsBackConflictedStepRecoveryAndContinuesWithTheNextRun() throws Exception {
        Fixture fixture = fixture();
        ImageRun first = run("run-first", "QUEUED");
        ImageRun second = run("run-second", "PLANNING");
        ImageRunStep firstStep = step("run-first", 1, "RUNNING");
        ImageRunStep secondStep = step("run-second", 1, "RUNNING");
        when(fixture.runs().findAllByStatusIn(anyCollection())).thenReturn(List.of(first, second));
        when(fixture.steps().findAllByRunIdOrderBySequenceAsc(eq("run-first"), any()))
                .thenReturn(List.of(firstStep));
        when(fixture.steps().findAllByRunIdOrderBySequenceAsc(eq("run-second"), any()))
                .thenReturn(List.of(secondStep));
        when(fixture.steps().saveAndFlush(firstStep))
                .thenThrow(new ObjectOptimisticLockingFailureException(ImageRunStep.class, 1L));

        assertDoesNotThrow(() -> fixture.initializer().run(new DefaultApplicationArguments()));

        verify(fixture.runs(), never()).saveAndFlush(first);
        verify(fixture.steps()).saveAndFlush(secondStep);
        verify(fixture.runs()).saveAndFlush(second);
        assertInterrupted(second);
        assertInterrupted(secondStep);
        verify(fixture.transactions()).rollback(any());
        verify(fixture.transactions()).commit(any());
        verifyRequiresNewTransaction(fixture.transactions(), 2);
    }

    @Test
    void rollsBackConflictedRunRecoveryAndContinuesWithTheNextRun() throws Exception {
        Fixture fixture = fixture();
        ImageRun first = run("run-first", "QUEUED");
        ImageRun second = run("run-second", "PLANNING");
        when(fixture.runs().findAllByStatusIn(anyCollection())).thenReturn(List.of(first, second));
        when(fixture.steps().findAllByRunIdOrderBySequenceAsc(any(), any())).thenReturn(List.of());
        when(fixture.runs().saveAndFlush(first))
                .thenThrow(new ObjectOptimisticLockingFailureException(ImageRun.class, 1L));

        assertDoesNotThrow(() -> fixture.initializer().run(new DefaultApplicationArguments()));

        verify(fixture.runs()).saveAndFlush(first);
        verify(fixture.runs()).saveAndFlush(second);
        assertInterrupted(second);
        verify(fixture.transactions()).rollback(any());
        verify(fixture.transactions()).commit(any());
    }

    private static Fixture fixture() {
        ImageRunRepository runs = mock(ImageRunRepository.class);
        ImageRunStepRepository steps = mock(ImageRunStepRepository.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        return new Fixture(new ImageRunRecoveryInitializer(runs, steps, transactions), runs, steps, transactions);
    }

    private static void verifyRequiresNewTransaction(PlatformTransactionManager transactions, int count) {
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions, times(count)).getTransaction(definition.capture());
        assertTrue(definition.getAllValues().stream().allMatch(value ->
                value.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    private static ImageRun run(String runId, String status) {
        ImageRun run = new ImageRun();
        run.setRunId(runId);
        run.setStatus(status);
        return run;
    }

    private static ImageRunStep step(String runId, int sequence, String status) {
        ImageRunStep step = new ImageRunStep();
        step.setRunId(runId);
        step.setSequence(sequence);
        step.setStatus(status);
        return step;
    }

    private static void assertInterrupted(ImageRun run) {
        assertEquals("FAILED", run.getStatus());
        assertEquals("服务重启，图片批次已中断", run.getErrorMessage());
        assertDatabaseTimestamp(run.getFinishedAt());
    }

    private static void assertInterrupted(ImageRunStep step) {
        assertEquals("FAILED", step.getStatus());
        assertEquals("服务重启，图片批次已中断", step.getErrorMessage());
        assertDatabaseTimestamp(step.getFinishedAt());
    }

    private static void assertDatabaseTimestamp(OffsetDateTime timestamp) {
        assertTrue(timestamp != null && timestamp.getNano() % 1_000 == 0);
    }

    private record Fixture(
            ImageRunRecoveryInitializer initializer,
            ImageRunRepository runs,
            ImageRunStepRepository steps,
            PlatformTransactionManager transactions) {
    }
}
