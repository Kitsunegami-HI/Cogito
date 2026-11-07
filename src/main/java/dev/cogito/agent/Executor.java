package dev.cogito.agent;

import net.minecraft.world.phys.Vec3;

import java.util.function.Supplier;

/**
 * The replaceable layer: given one step, work until it is done or give up. LlmExecutor calls functions;
 * VlaExecutor emits device frames. Nothing above this interface knows which one is running, and completion
 * is decided by the Verifier either way.
 */
public interface Executor {
    enum Status { DONE, FAILED, BLOCKED }

    record StepResult(Status status, String summary, String lesson) {}

    /** What an executor needs from the loop. */
    record Context(String goal, Plan.Step step, Vec3 startPos, Supplier<Verifier.Result> verify, String state) {}

    StepResult run(Context context) throws InterruptedException;
}
