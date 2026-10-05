package com.robustvision.platform.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class InferenceQueueWorkerTest {
    private final InferenceQueueService queue = mock(InferenceQueueService.class);
    private final InferenceService inference = mock(InferenceService.class);

    @AfterEach
    void clearInterrupt() { Thread.interrupted(); }

    @Test
    void drainsExistingBacklogInOrderWithoutPrefetching() {
        when(queue.poll()).thenReturn("first", "second", "third", null);
        new InferenceQueueWorker(queue, inference, 32).poll();
        InOrder order = inOrder(queue, inference);
        order.verify(queue).poll(); order.verify(inference).processQueuedTask("first");
        order.verify(queue).poll(); order.verify(inference).processQueuedTask("second");
        order.verify(queue).poll(); order.verify(inference).processQueuedTask("third");
        order.verify(queue).poll(); order.verifyNoMoreInteractions();
    }

    @Test
    void boundsWorkForFairnessAndLeavesUnstartedJobsInQueue() {
        when(queue.poll()).thenReturn("first", "second", "third");
        new InferenceQueueWorker(queue, inference, 2).poll();
        verify(queue, times(2)).poll();
        verify(inference).processQueuedTask("first");
        verify(inference).processQueuedTask("second");
        verifyNoMoreInteractions(inference);
    }

    @Test
    void emptyQueueOnlyNeedsOnePopAndNoProcessing() {
        new InferenceQueueWorker(queue, inference, 32).poll();
        verify(queue).poll(); verifyNoInteractions(inference);
    }

    @Test
    void blankJobsCannotCauseAnUnboundedLoop() {
        when(queue.poll()).thenReturn(" ");
        new InferenceQueueWorker(queue, inference, 3).poll();
        verify(queue, times(3)).poll(); verifyNoInteractions(inference);
    }

    @Test
    void interruptionDoesNotRemoveAnotherJob() {
        Thread.currentThread().interrupt();
        new InferenceQueueWorker(queue, inference, 32).poll();
        verifyNoInteractions(queue, inference);
    }

    @Test
    void unhandledFailureDoesNotPrefetchFollowingJob() {
        when(queue.poll()).thenReturn("first", "second");
        doThrow(new IllegalStateException("test failure")).when(inference).processQueuedTask("first");
        assertThatThrownBy(() -> new InferenceQueueWorker(queue, inference, 32).poll()).isInstanceOf(IllegalStateException.class);
        verify(queue).poll(); verify(inference).processQueuedTask("first"); verifyNoMoreInteractions(inference);
    }

    @Test
    void validatesConfiguredBatchSize() {
        assertThatThrownBy(() -> new InferenceQueueWorker(queue, inference, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InferenceQueueWorker(queue, inference, 1025)).isInstanceOf(IllegalArgumentException.class);
    }
}
