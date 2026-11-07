package dev.cogito.bot;

import com.google.gson.JsonObject;
import dev.cogito.usage.UsageRegistry;

/** The usage declarations as functions, so a plan step can run a mod's action with no model. */
final class UsageFns {
    private UsageFns() {}

    static void register() {
        Api.skill("invokeUsage(id,action,[args])", "run an action a mod declared for its item or block, by the declaration id and action name "
                + "from usage_declarations in the state or the usage tool; args is an object of the action's params", args -> {
            JsonObject inner = args.has("args") && args.get("args").isJsonObject() ? args.getAsJsonObject("args") : new JsonObject();
            return UsageRegistry.invoke(J.str(args, "id"), J.str(args, "action"), inner);
        });
    }
}
