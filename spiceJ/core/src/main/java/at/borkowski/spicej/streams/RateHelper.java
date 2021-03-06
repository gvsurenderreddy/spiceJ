package at.borkowski.spicej.streams;

import java.util.concurrent.atomic.AtomicInteger;

import at.borkowski.spicej.WouldBlockException;
import at.borkowski.spicej.impl.SleepWakeup;
import at.borkowski.spicej.ticks.TickListener;
import at.borkowski.spicej.ticks.TickSource;

/**
 * A helper class for managing a constant rate of something. It uses a concept
 * of "things" to denote the item to have a constant rate, this is in our
 * context always a placeholder for "byte".
 */
class RateHelper {
   private final TickSource tickSource;
   private int thingsPerTick;
   private int prescale;

   private final Listener listener;

   private AtomicInteger spent = new AtomicInteger();
   private SleepWakeup sleep = new SleepWakeup();
   private int timewiseAvailable;
   private boolean nonBlockng = false;

   private IdleNotify test__IdleNotify;

   /**
    * Creates a helper and registers it with the given {@link TickSource}.
    * 
    * @param tickSource
    *           The tick source to use.
    * @param thingsRate
    *           How many "things" (eg. bytes) per tick (after prescaling) should
    *           be allowed.
    * @param prescale
    *           How to prescale ticks
    */
   RateHelper(TickSource tickSource, int thingsRate, int prescaler) {
      this.tickSource = tickSource;
      this.thingsPerTick = thingsRate;
      this.prescale = prescaler;

      timewiseAvailable = thingsRate;

      tickSource.addListener(listener = new Listener());
   }

   /**
    * Cleans up used resources, unregisters the tick source.
    */
   public void close() {
      tickSource.removeListener(listener);
   }

   /**
    * Sets the rate of things (eg. bytes) per tick (after prescaling).
    * 
    * @param thingsPerTick
    */
   public void setThingsPerTick(int thingsPerTick) {
      this.thingsPerTick = thingsPerTick;
      timewiseAvailable = (int) (thingsPerTick - spent.get());
   }

   /**
    * Returns the current rate of things (eg. bytes) per tick.
    * 
    * @return
    */
   public int getThingsPerTick() {
      return thingsPerTick;
   }

   /**
    * Takes one thing (eg. byte). This method blocks until one thing can be used
    * according to the current rate and prescaling settings.
    */
   public void takeOne() {
      while (true) {
         int stored = spent.get();
         if (stored >= thingsPerTick)
            sleep();
         else if (spent.compareAndSet(stored, stored + 1))
            break;
      }
   }

   /**
    * Takes several things (eg. bytes). This method blocks until at least one
    * thing can be used according to the current rate and prescaling settings.
    * 
    * This method returns at least 1.
    * 
    * @param n
    *           up to how many things to take
    * @return the number of things that have been taken, at least 1
    */
   public int take(int n) {
      int lenToTake;
      while (true) {
         int stored = spent.get();
         lenToTake = Math.min(n, thingsPerTick - stored);
         if (stored >= thingsPerTick)
            sleep();
         else if (spent.compareAndSet(stored, stored + lenToTake))
            break;
      }
      timewiseAvailable -= lenToTake;
      return lenToTake;
   }

   /**
    * Returns several things (eg. bytes) as unused. This method is useful in
    * cases where {@link #take(int)} has been called, but not all of the number
    * of things returned have been used.
    * 
    * @param n
    *           How many things to give back
    */
   public void giveBack(int n) {
      spent.addAndGet(-n);
      timewiseAvailable += n;
      wakeup();
   }

   private void wakeup() {
      sleep.wakeup();
   }

   private void sleep() {
      if (test__IdleNotify != null && test__IdleNotify.idle())
         return;
      if (nonBlockng)
         throw new WouldBlockException();

      sleep.sleep();
   }

   /**
    * Testability method: sets a target to call when waiting (blocking) occurs.
    * This is necessary for unit tests.
    */
   void test__SetIdleNotify(IdleNotify target) {
      this.test__IdleNotify = target;
   }

   private class Listener implements TickListener {
      @Override
      public void tick(long tick) {
         if (prescale <= 1 || tick % prescale == 0) {
            int value;
            while (true) {
               int stored = spent.get();
               if (stored > thingsPerTick && spent.compareAndSet(stored, value = (stored - thingsPerTick)))
                  break;
               else if (spent.compareAndSet(stored, (value = 0)))
                  break;
            }
            timewiseAvailable = (int) (thingsPerTick - value);
            wakeup();
         }
      }
   }

   /**
    * Returns an estimate on how many things are available in the current tick.
    * This method might underestimate, meaning that estimate might be lower than
    * the actual number of things available, but never higher.
    * 
    * @return
    */
   public int getTimewiseAvailable() {
      return Math.max(0, timewiseAvailable);
   }

   /**
    * Testability only. An interface for a handler of the event that the
    * {@link RateHelper} would sleep and wait for the next tick. This is
    * necessary for unit tests.
    */
   interface IdleNotify {
      /**
       * Called whenever the {@link RateHelper} would sleep and wait for the
       * next tick.
       * 
       * @return true if the calling {@link RateHelper} is supposed to abort the
       *         actual sleeping and re-check the tick immediately, false
       *         otherwise.
       */
      boolean idle();
   }

   /**
    * Sets the {@link RateHelper}'s non-blocking mode, specifying that this
    * object's {@link #take(int)} and {@link #takeOne()} methods throw
    * {@link WouldBlockException} whenever the stream would block and wait for a
    * tick to happen, in order for new data to become available.
    * 
    * @param nonBlocking
    *           <code>true</code> if non-blocking mode should be turned on,
    *           <code>false</code> otherwise
    */
   public void setNonBlocking(boolean nonBlocking) {
      this.nonBlockng = nonBlocking;
   }

   /**
    * Returns the {@link TickSource} used by this {@link RateHelper} instance.
    * 
    * @return the used tick source
    */
   public TickSource getTickSource() {
      return tickSource;
   }

   /**
    * Returns the current prescale
    * 
    * @return the current prescale
    */
   public int getPrescale() {
      return prescale;
   }

   /**
    * Sets a new prescale
    * 
    * @param prescale
    *           the new prescale
    */
   public void setPrescale(int prescale) {
      this.prescale = prescale;
   }

}
