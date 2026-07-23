package com.hirain.aiagent.runtime;
import com.hirain.aiagent.rag.policy.KnowledgeIntentDecision;
import com.hirain.aiagent.rag.policy.KnowledgeRequestState;
import com.hirain.aiagent.trace.TraceSession;

/**
 * TEXT worker 当前执行请求的线程上下文。
 * <p>
 * LangChain4j 的同步 HTTP adapter 没有 RequestSession 参数，因此通过严格限定在线程内的
 * 上下文传递 requestId 与 deadline。Scope 关闭时恢复旧值，防止线程复用造成串请求。
 */
public final class RequestExecutionContext {

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private RequestExecutionContext() {
    }

    public static Scope bind(String requestId, RequestDeadline deadline) {
        return bind(requestId, deadline, null, null);
    }

    /** 绑定本请求专用、不可变的执行选项；不透传整个 AgentRequest.extraContext。 */
    public static Scope bind(String requestId, RequestDeadline deadline,
                             String originalUserQuestion, String visionDemoImageId) {
        if (requestId == null || requestId.trim().isEmpty()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        if (deadline == null) {
            throw new IllegalArgumentException("deadline must not be null");
        }
        State previous = CURRENT.get();
        CURRENT.set(new State(requestId, deadline, originalUserQuestion, visionDemoImageId, null, null));
        return new Scope(previous);
    }
    public static Scope bind(String requestId, RequestDeadline deadline, String question, String imageId,
                             KnowledgeIntentDecision knowledge, KnowledgeRequestState state) {
        return bind(requestId, deadline, question, imageId, knowledge, state, null);
    }
    /** TraceSession 仅随当前 worker Scope 存活，禁止缓存到 Tool 或 RAG 单例。 */
    public static Scope bind(String requestId, RequestDeadline deadline, String question, String imageId,
                             KnowledgeIntentDecision knowledge, KnowledgeRequestState state, TraceSession traceSession) {
        if (requestId == null || requestId.trim().isEmpty() || deadline == null) throw new IllegalArgumentException("requestId and deadline required");
        State previous=CURRENT.get(); CURRENT.set(new State(requestId,deadline,question,imageId,knowledge,state,traceSession)); return new Scope(previous);
    }

    public static State current() {
        return CURRENT.get();
    }

    public static final class State {
        private final String requestId;
        private final RequestDeadline deadline;
        private final String originalUserQuestion;
        private final String visionDemoImageId;
        private final KnowledgeIntentDecision knowledgeIntentDecision;
        private final KnowledgeRequestState knowledgeRequestState;
        private final TraceSession traceSession;

        private State(String requestId, RequestDeadline deadline,
                      String originalUserQuestion, String visionDemoImageId, KnowledgeIntentDecision knowledgeIntentDecision, KnowledgeRequestState knowledgeRequestState) {
            this(requestId, deadline, originalUserQuestion, visionDemoImageId, knowledgeIntentDecision, knowledgeRequestState, null);
        }
        private State(String requestId, RequestDeadline deadline,
                      String originalUserQuestion, String visionDemoImageId, KnowledgeIntentDecision knowledgeIntentDecision, KnowledgeRequestState knowledgeRequestState, TraceSession traceSession) {
            this.requestId = requestId;
            this.deadline = deadline;
            this.originalUserQuestion = originalUserQuestion;
            this.visionDemoImageId = visionDemoImageId;
            this.knowledgeIntentDecision=knowledgeIntentDecision; this.knowledgeRequestState=knowledgeRequestState;
            this.traceSession=traceSession;
        }

        public String requestId() { return requestId; }
        public RequestDeadline deadline() { return deadline; }
        public String originalUserQuestion() { return originalUserQuestion; }
        public String visionDemoImageId() { return visionDemoImageId; }
        public KnowledgeIntentDecision knowledgeIntentDecision() { return knowledgeIntentDecision; }
        public KnowledgeRequestState knowledgeRequestState() { return knowledgeRequestState; }
        public TraceSession traceSession() { return traceSession; }
    }

    public static final class Scope implements AutoCloseable {
        private final State previous;
        private boolean closed;

        private Scope(State previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
