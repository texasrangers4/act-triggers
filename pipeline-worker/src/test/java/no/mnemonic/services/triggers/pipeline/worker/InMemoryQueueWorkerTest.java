package no.mnemonic.services.triggers.pipeline.worker;

import no.mnemonic.commons.component.ValidationContext;
import no.mnemonic.commons.metrics.MetricsData;
import no.mnemonic.commons.utilities.lambda.LambdaUtils;
import no.mnemonic.services.triggers.api.service.v1.TriggerAdministrationService;
import no.mnemonic.services.triggers.pipeline.api.SubmissionException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static no.mnemonic.services.triggers.pipeline.api.SubmissionException.ErrorCode.NoResourcesAvailable;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class InMemoryQueueWorkerTest {

  @Mock
  private TriggerAdministrationService service;
  @Mock
  private RuleEvaluationEngine engine;

  private AtomicBoolean finishedSignal;
  private InMemoryQueueWorker worker;

  @Before
  public void setUp() throws Exception {
    initMocks(this);
    when(engine.getMetrics()).thenReturn(new MetricsData());

    finishedSignal = new AtomicBoolean(false);
    worker = new InMemoryQueueWorker(service)
        .setRuleEvaluationEngine(engine);
    worker.startComponent();
  }

  @After
  public void cleanUp() {
    if (worker != null) {
      worker.stopComponent();
    }
  }

  @Test
  public void testValidateWithZeroWorkerThreadsFails() {
    ValidationContext context = new ValidationContext();
    worker.setNumberOfWorkerThreads(0)
        .validate(context);
    assertFalse(context.isValid());
  }

  @Test
  public void testValidateWithZeroSubmissionWaitTimeFails() {
    ValidationContext context = new ValidationContext();
    worker.setSubmissionWaitTimeSeconds(0)
        .validate(context);
    assertFalse(context.isValid());
  }

  @Test(expected = IllegalStateException.class)
  public void testSubmitWithoutThreadPoolThrowsException() throws Exception {
    new InMemoryQueueWorker(service).submit(new TestTriggerEvent());
  }

  @Test(expected = SubmissionException.class)
  public void testSubmitWithoutEventThrowsException() throws Exception {
    worker.submit(null);
  }

  @Test(expected = SubmissionException.class)
  public void testSubmitEventWithoutIdThrowsException() throws Exception {
    worker.submit(new TestTriggerEvent().setId(null));
  }

  @Test(expected = SubmissionException.class)
  public void testSubmitEventWithoutTimestampThrowsException() throws Exception {
    worker.submit(new TestTriggerEvent().setTimestamp(0));
  }

  @Test(expected = SubmissionException.class)
  public void testSubmitEventWithoutServiceThrowsException() throws Exception {
    worker.submit(new TestTriggerEvent().setService(""));
  }

  @Test(expected = SubmissionException.class)
  public void testSubmitEventWithoutEventThrowsException() throws Exception {
    worker.submit(new TestTriggerEvent().setEvent(""));
  }

  @Test(expected = SubmissionException.class)
  public void testSubmitEventWithoutOrganizationThrowsException() throws Exception {
    worker.submit(new TestTriggerEvent().setOrganization(null));
  }

  @Test(expected = SubmissionException.class)
  public void testSubmitEventWithoutAccessModeThrowsException() throws Exception {
    worker.submit(new TestTriggerEvent().setAccessMode(null));
  }

  @Test
  public void testSubmitAndEvaluateSuccess() throws Exception {
    doAnswer(i -> {
      finishedSignal.set(true);
      return null;
    }).when(engine).evaluate(any());

    TestTriggerEvent event = new TestTriggerEvent();
    worker.submit(event);

    if (LambdaUtils.waitFor(finishedSignal::get, 10, TimeUnit.SECONDS)) {
      assertEquals(1L, worker.getMetrics().getSubMetrics("inMemoryQueueWorker").getData("totalCompletedTasks"));
      assertEquals(0L, worker.getMetrics().getSubMetrics("inMemoryQueueWorker").getData("totalFailedTasks"));
      verify(engine).evaluate(event);
    } else {
      fail("Rule evaluation task did not finish!");
    }
  }

  @Test
  public void testSubmitAndEvaluateFailure() throws Exception {
    doAnswer(i -> {
      finishedSignal.set(true);
      throw new RuntimeException();
    }).when(engine).evaluate(any());

    TestTriggerEvent event = new TestTriggerEvent();
    worker.submit(event);

    if (LambdaUtils.waitFor(finishedSignal::get, 10, TimeUnit.SECONDS)) {
      assertEquals(1L, worker.getMetrics().getSubMetrics("inMemoryQueueWorker").getData("totalCompletedTasks"));
      assertEquals(1L, worker.getMetrics().getSubMetrics("inMemoryQueueWorker").getData("totalFailedTasks"));
      verify(engine).evaluate(event);
    } else {
      fail("Rule evaluation task did not finish!");
    }
  }

  @Test
  public void testSubmitWithoutAvailableThread() {
    doAnswer(i -> {
      Thread.sleep(3_000);
      return null;
    }).when(engine).evaluate(any());

    TestTriggerEvent event1 = new TestTriggerEvent();
    TestTriggerEvent event2 = new TestTriggerEvent();

    try {
      worker.setNumberOfWorkerThreads(1)
          .setSubmissionWaitTimeSeconds(1);
      worker.startComponent();
      worker.submit(event1);
      worker.submit(event2);

      fail("Worker did not throw SubmissionException!");
    } catch (SubmissionException ex) {
      assertEquals(NoResourcesAvailable, ex.getErrorCode());
      verify(engine).evaluate(event1);
      verify(engine, never()).evaluate(event2);
    }
  }

  @Test
  public void testSubmitMultipleEventsRateLimited() throws Exception {
    AtomicInteger taskCounter = new AtomicInteger();
    doAnswer(i -> {
      Thread.sleep(1_000);
      if (taskCounter.incrementAndGet() == 3) {
        finishedSignal.set(true);
      }
      return null;
    }).when(engine).evaluate(any());

    worker.setNumberOfWorkerThreads(1);
    worker.startComponent();
    worker.submit(new TestTriggerEvent());
    worker.submit(new TestTriggerEvent());
    worker.submit(new TestTriggerEvent());

    if (LambdaUtils.waitFor(finishedSignal::get, 10, TimeUnit.SECONDS)) {
      assertEquals(3L, worker.getMetrics().getSubMetrics("inMemoryQueueWorker").getData("totalCompletedTasks"));
      assertEquals(0L, worker.getMetrics().getSubMetrics("inMemoryQueueWorker").getData("totalFailedTasks"));
      verify(engine, times(3)).evaluate(any());
    } else {
      fail("Rule evaluation task did not finish!");
    }
  }
}
