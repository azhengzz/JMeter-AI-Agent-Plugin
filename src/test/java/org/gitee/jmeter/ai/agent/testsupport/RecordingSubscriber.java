package org.gitee.jmeter.ai.agent.testsupport;

import org.gitee.jmeter.ai.agent.model.AgentResponse;
import org.gitee.jmeter.ai.agent.model.ProgressUpdate;
import org.gitee.jmeter.ai.agent.presenter.CancelCause;
import org.gitee.jmeter.ai.agent.presenter.TurnEvent;
import org.gitee.jmeter.ai.agent.presenter.TurnOrigin;
import org.gitee.jmeter.ai.agent.presenter.TurnSubscriber;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 事件流记录器：Kind 序列（含会话级事件）/来源过滤/回合身份查询 + 载荷分桶。
 * 线程安全（CopyOnWriteArrayList/volatile）——回调线程不保证，测试端断言前自行同步
 * （future.get / awaitUntil 已是同步点）。
 */
public final class RecordingSubscriber implements TurnSubscriber {

    public final List<TurnEvent> events = new CopyOnWriteArrayList<>();
    public final List<String> startedMessages = new CopyOnWriteArrayList<>();
    public final List<AgentResponse> completedResponses = new CopyOnWriteArrayList<>();
    public final List<ProgressUpdate> progressUpdates = new CopyOnWriteArrayList<>();
    public final List<String> injectedMessages = new CopyOnWriteArrayList<>();
    public volatile CancelCause lastCancelledCause;

    @Override public void onTurnEvent(TurnEvent event) {
        events.add(event);
        switch (event.kind()) {
            case TURN_STARTED -> startedMessages.add(event.turn().echoText());
            case PROGRESS -> progressUpdates.add(event.progress());
            case TURN_COMPLETED -> completedResponses.add(event.response());
            case TURN_CANCELLED -> lastCancelledCause = event.cause();
            case INJECTED -> injectedMessages.add(event.message());
            default -> { }
        }
    }

    public int count(TurnEvent.Kind kind) {
        return (int) events.stream().filter(e -> e.kind() == kind).count();
    }

    public TurnEvent lastOf(TurnEvent.Kind kind) {
        return events.stream().filter(e -> e.kind() == kind).reduce((a, b) -> b).orElseThrow();
    }

    public TurnEvent lastStarted() {
        return lastOf(TurnEvent.Kind.TURN_STARTED);
    }

    public TurnEvent lastCompleted() {
        return lastOf(TurnEvent.Kind.TURN_COMPLETED);
    }

    public TurnEvent lastCancelled() {
        return lastOf(TurnEvent.Kind.TURN_CANCELLED);
    }

    public long startedTurnId() {
        return lastStarted().turn().id();
    }

    public long lastCompletedTurnId() {
        return lastCompleted().turn().id();
    }

    public List<Long> completedTurnIds() {
        return events.stream()
                .filter(e -> e.kind() == TurnEvent.Kind.TURN_COMPLETED)
                .map(e -> e.turn().id())
                .toList();
    }

    public TurnEvent eventOf(TurnEvent.Kind kind, long turnId) {
        return events.stream()
                .filter(e -> e.kind() == kind && e.turn() != null && e.turn().id() == turnId)
                .findFirst().orElseThrow();
    }

    public List<TurnEvent.Kind> kindsFor(long turnId) {
        return events.stream()
                .filter(e -> e.turn() != null && e.turn().id() == turnId)
                .map(TurnEvent::kind)
                .toList();
    }

    public int indexOf(TurnEvent.Kind kind, long turnId) {
        for (int i = 0; i < events.size(); i++) {
            TurnEvent e = events.get(i);
            if (e.kind() == kind && e.turn() != null && e.turn().id() == turnId) {
                return i;
            }
        }
        throw new IllegalStateException("no " + kind + " for turn " + turnId);
    }

    public int terminalCountFor(long turnId) {
        return (int) events.stream()
                .filter(e -> e.turn() != null && e.turn().id() == turnId)
                .filter(e -> e.kind() == TurnEvent.Kind.TURN_COMPLETED
                        || e.kind() == TurnEvent.Kind.TURN_CANCELLED)
                .count();
    }

    public List<String> rawKinds() {
        return events.stream().map(e -> e.kind().name()).toList();
    }

    /** 指定来源回合系的 Kind 序列（无回合身份的会话级事件一并计入）。 */
    public List<String> turnKindsFor(TurnOrigin origin) {
        return events.stream()
                .filter(e -> e.turn() == null || e.turn().origin() == origin)
                .map(e -> mapKind(e.kind()))
                .toList();
    }

    private static String mapKind(TurnEvent.Kind kind) {
        return switch (kind) {
            case TURN_STARTED -> "started";
            case PROGRESS -> "progress";
            case TURN_COMPLETED -> "completed";
            case TURN_CANCELLED -> "cancelled";
            case REJECTED_BUSY -> "rejectedBusy";
            case INJECTED -> "injected";
            case COMMAND_RESULT -> "commandResult";
        };
    }
}
