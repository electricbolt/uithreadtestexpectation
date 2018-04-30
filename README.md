# UiThreadTest expectation library

Asynchronous test expectations for Android instrumented `UiThreadTest` tests. Modelled on Xcode's `XCTestExpectation`.

Usage
---

If you have code that runs on a background thread, and then calls back onto the main thread using any one of the following: 

> Java

```java
// 1. runOnUiThread
activity.runOnUiThread(() -> { ... }));

// 2. View.post
view.post(() -> { ... });

// 3. getMainLooper
new Handler(Looper.getMainLooper()).post(() -> { ... });
```

then you can test these using `UiThreadTestExpectation`. The usual approach of using `CountDownLatch` in these situations doesn't work as `CountDownLatch.await()` blocks the main thread, and no messages on the main `Looper` are processed causing the the test to hang forever. `UiThreadTestExpectation` works around this by continuing to process messages on the main `Looper`, whilst simultanously checking if all expectations have been fulfilled and/or timed out.

## Class UiThreadTestExpectation

#### UiThreadTestExpectation.TestExpectation expectationWithDescription(String)

```java
static UiThreadTestExpectation.TestExpectation expectationWithDescription(String description) throws UiThreadTestExpectationException;
```

Creates an expectation associated with the test case being executed.

**params** *description* - This string will be displayed in the console log to help diagnose failures.

**returns** *UiThreadTestExpectation.TestExpectation* object created.

**throws** *UiThreadTestExpectationException* if an expectation has already been created with the same description, or if the description is blank or null.

---

#### waitForExpectationsWithTimeout(double)

```java
static void waitForExpectationsWithTimeout(double seconds) throws UiThreadTestExpectationException;
```

`waitForExpectationsWithTimeout()` runs the main `Looper` to handle events until all expectations are fulfilled or the timeout is reached. When `waitForExpectationsWithTimeout()` returns, any expectations that were created and associated with the test case are automatically disassociated, and will not affect any further invocations of `waitForExpectationsWithTimeout()`. Only one `waitForExpectationsWithTimeout()` can be active at any given time, but it is permissible to chain together multiple { expectations -> wait } calls in either a single test case or multiple test cases.

**params** *seconds* - The amount of time in seconds within which all expectations created with `expectationWithDescription()` must be fulfilled.

**throws** *UiThreadTestExpectationException* in the following situations: #1. re-entrant call to `waitForExpectationsWithTimeout()`; #2. attempting to call `waitForExpectationsWithTimeout()` on a thread that is not the main thread; #3. no expectations were added with `expectationWithDescription()`; #4. one or more expectations were not fulfilled within the time out duration; #5. error running the main `Looper`.

## Class UiThreadTestExpectation.TestExpectation

#### fulfill()

```java
public void fulfill() throws IllegalStateException;
```

Call `fulfill()` to mark an expectation as having been met. You may not call `fulfill()` on a expectation if it has already been fulfilled.

**throws** *IllegalStateException* if the expectation has already been fulfilled.

Example
---

### UiThreadTest test case

> Java

```java
@RunWith(AndroidJUnit4.class)
public class RSAKeyGeneratorTests {

    @Test
    @UiThreadTest
    public void testKeyStore() throws Exception {
        // Create asynchronous expectation and associate with the current test case.
        UiThreadTestExpectation.TestExpectation ex = UiThreadTestExpectation.expectationWithDescription("GenerateKeyPair");

        // Call the system under test.
        RSAKeyGenerator.generate(activityRule.getActivity(), privateKey -> {
            assertNotNull(privateKey);
            ex.fulfill();                  // Fulfill the expectation
        });
   
        // Continue to run the main Looper and handle events until the expectation 'ex' is fulfilled or times out.
        UiThreadTestExpectation.waitForExpectationsWithTimeout(15.0);
    }
    
    @Rule
    public ActivityTestRule<BlankActivity> activityRule =
        new ActivityTestRule(BlankActivity.class);
}
```

### Asynchronous code under test

> Java

```java
// Class RSAKeyGenerator
    
public void generate(Activity activity, RSAKeyGeneratorCompletionBlock block) {
    // Show progress dialog on UI.
    final ProgressDialog dialog = new ProgressDialog(activity);
    dialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
    dialog.setMessage("Initializing... please wait");
    dialog.setIndeterminate(true);
    dialog.setCanceledOnTouchOutside(false);
    dialog.show();

    // Generate RSA key pair on background thread so as not to block UI thread.
    Thread t = new Thread(() -> {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
        kpg.initialize(new KeyGenParameterSpec.Builder(KEYALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT).build());
        
        // Generate Keypair... can take 10 seconds.
        KeyPair kp = kpg.generateKeyPair();
        
        // Keypair generated, hide progress dialog and call completion block on UI thread.
        activity.runOnUiThread(() -> {
            dialog.dismiss();
            block.finished(kp.getPrivate()));
        }));    
    });
    t.start();
}
```