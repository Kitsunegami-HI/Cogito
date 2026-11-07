package dev.cogito.llm;

/**
 * A model request that failed, in words fit to show the person. {@code setup} marks a failure that retrying cannot
 * mend, such as no key or no model chosen; the person has to change the settings first.
 */
public final class LlmException extends RuntimeException {
    public final boolean setup;

    public LlmException(String message, boolean setup) {
        super(message);
        this.setup = setup;
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
        this.setup = false;
    }
}
