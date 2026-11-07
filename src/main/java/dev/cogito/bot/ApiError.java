package dev.cogito.bot;

/** A call that could not do what was asked. The message and hint go back to the model as they are. */
public final class ApiError extends RuntimeException {
    public final String kind;
    public final String hint;

    public ApiError(String kind, String message) {
        this(kind, message, null);
    }

    public ApiError(String kind, String message, String hint) {
        super(message);
        this.kind = kind;
        this.hint = hint;
    }
}
