package org.gitee.jmeter.ai.agent.session;

import org.gitee.jmeter.ai.agent.model.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Session 并发安全回归测试：会话重置线程（EDT：快照/clear/
 * saveSession）与垂死回合的载体线程（addMessage/saveSession）并发操作同一
 * Session——无同步的 ArrayList 上迭代+结构修改会抛 ConcurrentModificationException，
 * 两个 TRUNCATE_EXISTING 文件写句柄并发会写出撕裂内容。
 */
class SessionConcurrencyTest {

    private static Message msg(String text) {
        return Message.builder().role(Message.Role.USER).content(text).build();
    }

    @Test
    void concurrentMutationAndSnapshot_neverThrows() throws Exception {
        Session session = new Session("hammer");
        session.addMessage(msg("seed"));

        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread adder = new Thread(() -> {
            for (int i = 0; i < 20_000 && error.get() == null; i++) {
                session.addMessage(msg("m" + i));
            }
        }, "session-adder");
        adder.start();
        try {
            for (int i = 0; i < 20_000; i++) {
                session.getUnconsolidatedMessages();
                session.getMessages();
                session.getHistory(10);
                if (i % 100 == 0) {
                    session.clear();
                }
            }
        } catch (Throwable t) {
            error.set(t);
        }
        adder.join(10_000);
        assertNull(error.get(), "读写并发不得抛异常（CME/IOOBE）");
    }

    @Test
    void concurrentSaveAndMutation_neverThrowsOrTears(@TempDir Path tempDir) throws Exception {
        SessionManager manager = new SessionManager(tempDir, "hammer-save");
        Session session = manager.getOrCreate("hammer-save");
        session.addMessage(msg("seed"));

        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread saver = new Thread(() -> {
            for (int i = 0; i < 500 && error.get() == null; i++) {
                manager.saveSession(session);
            }
        }, "session-saver");
        saver.start();
        try {
            for (int i = 0; i < 5_000; i++) {
                session.addMessage(msg("m" + i));
                if (i % 10 == 0) {
                    session.clear(); // 控制文件规模：每次落盘行数有界，测试耗时线性
                }
            }
        } catch (Throwable t) {
            error.set(t);
        }
        saver.join(30_000);
        assertNull(error.get(), "保存与追加并发不得抛异常（CME/撕裂）");
    }
}
