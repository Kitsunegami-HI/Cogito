package dev.cogito.llm;

import java.util.List;

/** One configured provider, with its SDK client. Made by {@link Llm} and dropped when the settings change. */
interface Provider extends AutoCloseable {
    Conversation start(Chat.Request request);

    /** The model ids this key can use, as the provider lists them. */
    List<String> models();

    @Override
    void close();
}
