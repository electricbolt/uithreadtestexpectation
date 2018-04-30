package nz.co.electricbolt.uithreadtestexpectation;

import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * This class allows you to specify one or more 'expectations' that must occur asynchronously
 * as a result of actions in a @UiThreadTest test case. Once all expectations have been created
 * using the expectationWithDescription() method, the waitForExpectationsWithTimeout() method is
 * called and will block execution of subsequent test code (whilst still running the main looper)
 * until all expected conditions have been fulfilled or a timeout occurs.
 */
public class UiThreadTestExpectation {

    /**
     * Expectations represent a specific asynchronous testing condition.
     */
    public static class TestExpectation {

        private String description;
        private boolean fulfilled;

        // Set to true by reset() to ignore any expectations that haven't been fulfilled after
        // waitForExpectationsWithTimeout() has returned due to a timeout or error, otherwise they
        // might affect the next usage of waitForExpectationsWithTimeout().
        private boolean ignore;

        private TestExpectation(String description) {
            this.description = description;
        }

        /**
         * The string displayed in the console log to help diagnose failures.
         */

        public String getDescription() {
            return description;
        }

        /**
         * Call fulfill() to mark an expectation as having been met. You may not call fullfill()
         * on a expectation if it has already been fulfilled.
         * @throws IllegalStateException if the expectation has already been fulfilled.
         */

        public void fulfill() throws IllegalStateException {
            if (!ignore) {
                if (fulfilled) {
                    reset();
                    throw new IllegalStateException("Expectation '" + description + "' already fired");
                }
                fulfilled = true;
            }
        }

    }

    private static boolean waitingForExpectations;
    private static Map<String,TestExpectation> expectations;
    static {
        reset();
    }

    private static synchronized void reset() {
        if (expectations != null) {
            for (String key : expectations.keySet()) {
                TestExpectation expectation = expectations.get(key);
                expectation.ignore = true;
            }
        }
        waitingForExpectations = false;
        expectations = new HashMap<>();
    }

    /**
     * Creates an expectation associated with the test case being executed.
     * @param description This string will be displayed in the console log to help diagnose failures.
     * @return TestExpectation object created.
     * @throws UiThreadTestExpectationException if an expectation has already been created with the
     * same description, or if the description is blank or null.
     */

    public static synchronized TestExpectation expectationWithDescription(String description) throws UiThreadTestExpectationException {
        if (description == null || description.length() == 0)
            throw new UiThreadTestExpectationException("Expectation description may not be blank or null");
        if (expectations.containsKey(description))
            throw new UiThreadTestExpectationException("Expectation '" + description + "' already added");
        TestExpectation expectation = new TestExpectation(description);
        expectations.put(description, expectation);
        return expectation;
    }

    /**
     * waitForExpectationsWithTimeout()` runs the main Looper to handle events until all expectations
     * are fulfilled or the timeout is reached. When waitForExpectationsWithTimeout() returns, any
     * expectations that were created and associated with the test case are automatically
     * disassociated, and will not affect any further invocations of waitForExpectationsWithTimeout().
     * Only one waitForExpectationsWithTimeout() can be active at any given time, but it is
     * permissible to chain together multiple { expectations -> wait } calls in either a single test
     * case or multiple test cases.
     *
     * @param seconds The amount of time in seconds within which all expectations created with
     *                expectationWithDescription() must be fulfilled.
     * @throws UiThreadTestExpectationException in the following situations: #1. re-entrant call to
     * waitForExpectationsWithTimeout(); #2. attempting to call waitForExpectationsWithTimeout on
     * a thread that is not the main thread; #3. no expectations were added with
     * expectationWithDescription(); #4. one or more expectations were not fulfilled within the
     * time out duration; #5. error running the main Looper.
     */
    public static synchronized void waitForExpectationsWithTimeout(double seconds) throws UiThreadTestExpectationException {
        // Check if already called waitForExpectationsWithTimeout()
        if (waitingForExpectations) {
            reset();
            throw new UiThreadTestExpectationException("waitForExpectationsWithTimeout() has already been called");
        }
        waitingForExpectations = true;

        // Check if running on UiThread.
        if (!Looper.getMainLooper().isCurrentThread()) {
            reset();
            throw new UiThreadTestExpectationException("waitForExpectationsWithTimeout() must be called on the UiThread");
        }

        // Check that expectations have been added.
        if (expectations.size() == 0) {
            reset();
            throw new UiThreadTestExpectationException("No expectations added");
        }

        try {
            // Private fields on MessageQueue and Message that we need to access in order to
            // manually execute the main thread looper.
            Field mMessagesField = MessageQueue.class.getDeclaredField("mMessages");
            mMessagesField.setAccessible(true);
            Method nextMethod = MessageQueue.class.getDeclaredMethod("next");
            nextMethod.setAccessible(true);
            Field nextField = Message.class.getDeclaredField("next");
            nextField.setAccessible(true);

            // Process any messages in the queue, checking to see if expectations have been
            // fulfilled and/or timed out.
            long timeout = System.currentTimeMillis();
            timeout += (long)(seconds * 1000.0);
            while (true) {
                // Get next message from the main Looper.
                final Message message = (Message) mMessagesField.get(Looper.myQueue());
                if (nextField.get(message) == null) {
                    // No message.
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException ie) {
                        // ignore
                    }
                } else {
                    // Process message.
                    Message msg = (Message) nextMethod.invoke(Looper.myQueue());
                    if (msg != null)
                        msg.getTarget().dispatchMessage(msg);
                }

                // Check if all expectations have been fulfilled.
                int fulfilledCount = 0;
                for (String key : expectations.keySet()) {
                    TestExpectation expectation = expectations.get(key);
                    if (expectation.fulfilled)
                        fulfilledCount++;

                }
                if (fulfilledCount == expectations.size()) {
                    // Success.
                    reset();
                    return;
                }

                // Check if timed out.
                long now = System.currentTimeMillis();
                if (now >= timeout) {
                    StringBuilder sb = new StringBuilder();
                    for (String key : expectations.keySet()) {
                        TestExpectation expectation = expectations.get(key);
                        if (!expectation.fulfilled) {
                            if (sb.length() > 0)
                                sb.append(',');
                            sb.append('\'');
                            sb.append(key);
                            sb.append('\'');
                        }
                    }
                    reset();
                    throw new UiThreadTestExpectationException("Timed out waiting for expectations " + sb.toString());
                }
            }
        } catch (NoSuchFieldException e) {
            throw new UiThreadTestExpectationException(e);
        } catch (NoSuchMethodException e) {
            throw new UiThreadTestExpectationException(e);
        } catch (IllegalAccessException e) {
            throw new UiThreadTestExpectationException(e);
        } catch (InvocationTargetException e) {
            throw new UiThreadTestExpectationException(e);
        }
    }

}