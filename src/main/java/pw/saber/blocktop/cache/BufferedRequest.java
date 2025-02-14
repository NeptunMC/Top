package pw.saber.blocktop.cache;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import pw.saber.blocktop.scheduler.SchedulerAdapter;
import pw.saber.blocktop.scheduler.SchedulerTask;

public abstract class BufferedRequest<T> {

  /** The buffer time */
  private final long bufferTime;

  private final TimeUnit unit;
  private final SchedulerAdapter schedulerAdapter;
  /** Mutex to guard processor */
  private final Object[] mutex = new Object[0];
  /** The active processor task, if present */
  private Processor<T> processor = null;

  /**
   * Creates a new buffer with the given timeout millis
   *
   * @param bufferTime the timeout
   * @param unit the unit of the timeout
   */
  public BufferedRequest(long bufferTime, TimeUnit unit, SchedulerAdapter schedulerAdapter) {
    this.bufferTime = bufferTime;
    this.unit = unit;
    this.schedulerAdapter = schedulerAdapter;
  }

  /**
   * Makes a request to the buffer
   *
   * @return the future
   */
  public CompletableFuture<T> request() {
    synchronized (this.mutex) {
      if (this.processor != null) {
        try {
          return this.processor.extendAndGetFuture();
        } catch (ProcessorAlreadyRanException e) {
          // ignore
        }
      }

      Processor<T> p =
          this.processor =
              new Processor<>(this::perform, this.bufferTime, this.unit, this.schedulerAdapter);
      return p.getFuture();
    }
  }

  /**
   * Requests the value, bypassing the buffer
   *
   * @return the value
   */
  public T requestDirectly() {
    return this.perform();
  }

  /**
   * Performs the buffered task
   *
   * @return the result
   */
  protected abstract T perform();

  private static final class Processor<R> {
    private final Supplier<R> supplier;

    private final long delay;
    private final TimeUnit unit;

    private final SchedulerAdapter schedulerAdapter;

    private final Object[] mutex = new Object[0];
    private final CompletableFuture<R> future = new CompletableFuture<>();
    private boolean usable = true;

    private SchedulerTask scheduledTask;
    private CompletionTask boundTask = null;

    Processor(Supplier<R> supplier, long delay, TimeUnit unit, SchedulerAdapter schedulerAdapter) {
      this.supplier = supplier;
      this.delay = delay;
      this.unit = unit;
      this.schedulerAdapter = schedulerAdapter;

      this.scheduleTask();
    }

    private void rescheduleTask() throws ProcessorAlreadyRanException {
      synchronized (this.mutex) {
        if (!this.usable) {
          throw new ProcessorAlreadyRanException();
        }
        if (this.scheduledTask != null) {
          this.scheduledTask.cancel();
        }
        this.scheduleTask();
      }
    }

    private void scheduleTask() {
      this.boundTask = new CompletionTask();
      try {
        this.scheduledTask =
            this.schedulerAdapter.asyncLater(this.boundTask, this.delay, this.unit);
      } catch (RejectedExecutionException e) {
        // If we can't schedule the completion in the future, just do it now.
        this.boundTask.run();
      }
    }

    CompletableFuture<R> getFuture() {
      return this.future;
    }

    CompletableFuture<R> extendAndGetFuture() throws ProcessorAlreadyRanException {
      this.rescheduleTask();
      return this.future;
    }

    private final class CompletionTask implements Runnable {
      @Override
      public void run() {
        synchronized (Processor.this.mutex) {
          if (!Processor.this.usable) {
            throw new IllegalStateException("Task has already ran");
          }

          // check that we're still the bound task.
          // prevents a race condition between #run and #rescheduleTask
          if (Processor.this.boundTask != this) {
            return;
          }

          Processor.this.usable = false;
        }

        // compute result
        try {
          R result = Processor.this.supplier.get();
          Processor.this.future.complete(result);
        } catch (Exception e) {
          new RuntimeException(
              "Processor "
                  + Processor.this.supplier
                  + " threw an exception whilst computing a result",
              e)
              .printStackTrace();
          Processor.this.future.completeExceptionally(e);
        }
      }
    }
  }

  private static final class ProcessorAlreadyRanException extends Exception {}
}