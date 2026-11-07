package dev.cogito.llm;

import java.util.List;

/** One conversation with one model. It only grows, so its start can be served from a provider's prompt cache. */
public interface Conversation {
    /** A message from the game side. */
    void user(List<Chat.Part> parts);

    /** Asks the model, adds its reply to the conversation, and returns it. */
    Chat.Reply send();

    /** Answers every call of the last reply, then adds {@code after} (news, a picture) in the same turn. */
    void results(List<Chat.Result> results, List<Chat.Part> after);
}
