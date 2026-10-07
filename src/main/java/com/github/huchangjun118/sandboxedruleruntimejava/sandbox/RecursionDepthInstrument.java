package com.github.huchangjun118.sandboxedruleruntimejava.sandbox;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.ExecutionEventNode;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.instrumentation.TruffleInstrument.Registration;

/**
 * Truffle instrument enforcing a configurable guest call-depth limit. It tags every
 * guest function root, counting depth per worker thread and throwing a terminating
 * guest exception when the limit is exceeded. Unlike the JVM's natural stack overflow
 * this limit is explicit, configurable and reported distinctly.
 */
@Registration(id = RecursionDepthInstrument.ID, name = "RecursionDepth", services = RecursionDepthInstrument.class)
public final class RecursionDepthInstrument extends TruffleInstrument {

    public static final String ID = "ruleRecursionDepth";

    /** Signals a guest-side, catchable-but-typically-fatal recursion depth breach. */
    public static final class DepthExceededException extends AbstractTruffleException {
        private static final long serialVersionUID = 1L;
        public DepthExceededException(String message) {
            super(message);
        }
    }

    /** Per-thread current call depth; reset by the runtime before each evaluation. */
    private final ThreadLocal<int[]> depth = ThreadLocal.withInitial(() -> new int[1]);

    public int currentDepth() {
        return depth.get()[0];
    }

    public void reset() {
        depth.get()[0] = 0;
    }

    private final class DepthEventNode extends ExecutionEventNode {
        @Override
        protected void onEnter(VirtualFrame frame) {
            int d = ++depth.get()[0];
            if (d > limit) {
                CompilerDirectives.transferToInterpreter();
                throw new DepthExceededException(
                        "recursion depth limit of " + limit + " exceeded");
            }
        }

        @Override
        protected void onReturnValue(VirtualFrame frame, Object result) {
            depth.get()[0]--;
        }

        @Override
        protected void onReturnExceptional(VirtualFrame frame, Throwable exception) {
            depth.get()[0]--;
        }
    }

    /** Depth ceiling enforced by this instrument instance (set per engine by the runtime). */
    private volatile int limit = Integer.MAX_VALUE;

    public void setLimit(int limit) {
        this.limit = limit;
    }

    @Override
    protected void onCreate(Env env) {
        env.registerService(this);
        SourceSectionFilter filter = SourceSectionFilter.newBuilder()
                .tagIs(StandardTags.RootTag.class)
                .build();
        env.getInstrumenter().attachExecutionEventFactory(
                filter, context -> new DepthEventNode());
    }
}
