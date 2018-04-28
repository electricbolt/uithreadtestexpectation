package nz.co.electricbolt.uithreadtestexpectation;

public class UiThreadTestExpectationException extends Exception {

    public UiThreadTestExpectationException(String message) {
        super(message);
    }

    public UiThreadTestExpectationException(Exception exception) {
        super(exception);
    }

}
