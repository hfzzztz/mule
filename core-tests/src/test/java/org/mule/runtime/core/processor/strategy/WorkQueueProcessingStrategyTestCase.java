/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.processor.strategy;

import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.internal.matchers.ThrowableMessageMatcher.hasMessage;
import static org.mule.runtime.core.processor.strategy.AbstractRingBufferProcessingStrategyFactory.DEFAULT_BUFFER_SIZE;
import static org.mule.runtime.core.processor.strategy.AbstractRingBufferProcessingStrategyFactory.DEFAULT_WAIT_STRATEGY;
import static org.mule.runtime.core.processor.strategy.ProcessingStrategyUtils.TRANSACTIONAL_ERROR_MESSAGE;

import org.mule.runtime.core.api.DefaultMuleException;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.processor.strategy.ProcessingStrategy;
import org.mule.runtime.core.exception.MessagingException;
import org.mule.runtime.core.processor.strategy.WorkQueueProcessingStrategyFactory.WorkQueueProcessingStrategy;
import org.mule.runtime.core.transaction.TransactionCoordination;
import org.mule.tck.testmodels.mule.TestTransaction;

import ru.yandex.qatools.allure.annotations.Description;
import ru.yandex.qatools.allure.annotations.Features;
import ru.yandex.qatools.allure.annotations.Stories;

@Features("Processing Strategies")
@Stories("Work Queue Processing Strategy")
public class WorkQueueProcessingStrategyTestCase extends AbstractProcessingStrategyTestCase {

  public WorkQueueProcessingStrategyTestCase(Mode mode) {
    super(mode);
  }

  @Override
  protected ProcessingStrategy createProcessingStrategy(MuleContext muleContext, String schedulersNamePrefix) {
    return new WorkQueueProcessingStrategy(() -> blocking,
                                           2,
                                           scheduler -> {
                                           },
                                           () -> custom, DEFAULT_BUFFER_SIZE,
                                           1,
                                           DEFAULT_WAIT_STRATEGY,
                                           muleContext);
  }

  @Override
  @Description("Regardless of processor type, when the WorkQueueProcessingStrategy is configured, the pipeline is executed "
      + "synchronously in a single IO thead.")
  public void singleCpuLight() throws Exception {
    super.singleCpuLight();
    assertSynchronousIOScheduler(1);
  }

  @Override
  @Description("Regardless of processor type, when the WorkQueueProcessingStrategy is configured, the pipeline is executed "
      + "synchronously in a single IO thead.")
  public void singleCpuLightConcurrent() throws Exception {
    super.singleCpuLightConcurrent();
    assertThat(threads.size(), allOf(greaterThanOrEqualTo(1), lessThanOrEqualTo(2)));
    assertThat(threads.stream().filter(name -> name.startsWith(IO)).count(), allOf(
                                                                                   greaterThanOrEqualTo(1l),
                                                                                   lessThanOrEqualTo(2l)));
    assertThat(threads.stream().filter(name -> name.startsWith(CPU_LIGHT)).count(), equalTo(0l));
    assertThat(threads.stream().filter(name -> name.startsWith(CPU_INTENSIVE)).count(), equalTo(0l));
  }

  @Override
  @Description("Regardless of processor type, when the WorkQueueProcessingStrategy is configured, the pipeline is executed "
      + "synchronously in a single IO thead.")
  public void multipleCpuLight() throws Exception {
    super.multipleCpuLight();
    assertSynchronousIOScheduler(1);
  }

  @Override
  @Description("Regardless of processor type, when the WorkQueueProcessingStrategy is configured, the pipeline is executed "
      + "synchronously in a single IO thead.")
  public void singleBlocking() throws Exception {
    super.singleBlocking();
    assertSynchronousIOScheduler(1);
  }

  @Override
  @Description("Regardless of processor type, when the WorkQueueProcessingStrategy is configured, the pipeline is executed "
      + "synchronously in a single IO thead.")
  public void multipleBlocking() throws Exception {
    super.multipleBlocking();
    assertSynchronousIOScheduler(1);
  }

  @Override
  @Description("Regardless of processor type, when the WorkQueueProcessingStrategy is configured, the pipeline is executed "
      + "synchronously in a single IO thead.")
  public void singleCpuIntensive() throws Exception {
    super.singleCpuIntensive();
    assertSynchronousIOScheduler(1);
  }

  @Override
  @Description("Regardless of processor type, when the WorkQueueProcessingStrategy is configured, the pipeline is executed "
      + "synchronously in a single IO thead.")
  public void multipleCpuIntensive() throws Exception {
    super.multipleCpuIntensive();
    assertSynchronousIOScheduler(1);
  }

  @Override
  @Description("Regardless of processor type, when the WorkQueueProcessingStrategy is configured, the pipeline is executed "
      + "synchronously in a single IO thead.")
  public void mix() throws Exception {
    super.mix();
    assertSynchronousIOScheduler(1);
  }

  @Override
  @Description("Regardless of processor type, when the WorkQueueProcessingStrategy is configured, the pipeline is executed "
      + "synchronously in a single IO thead.")
  public void mix2() throws Exception {
    super.mix2();
    assertSynchronousIOScheduler(1);
  }

  @Override
  @Description("When the WorkQueueProcessingStrategy is configured and a transaction is active processing fails with an error")
  public void tx() throws Exception {
    flow.setMessageProcessors(asList(cpuLightProcessor, cpuIntensiveProcessor, blockingProcessor));
    flow.initialise();
    flow.start();

    TransactionCoordination.getInstance().bindTransaction(new TestTransaction(muleContext));

    expectedException.expect(MessagingException.class);
    expectedException.expectCause(instanceOf(DefaultMuleException.class));
    expectedException.expectCause(hasMessage(equalTo(TRANSACTIONAL_ERROR_MESSAGE)));
    process(flow, testEvent());
  }

  private void assertSynchronousIOScheduler(int concurrency) {
    assertThat(threads.size(), equalTo(concurrency));
    assertThat(threads.stream().filter(name -> name.startsWith(IO)).count(), equalTo((long) concurrency));
    assertThat(threads.stream().filter(name -> name.startsWith(CPU_LIGHT)).count(), equalTo(0l));
    assertThat(threads.stream().filter(name -> name.startsWith(CPU_INTENSIVE)).count(), equalTo(0l));
  }

}
