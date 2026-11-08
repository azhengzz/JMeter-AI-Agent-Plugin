package org.gitee.jmeter.ai.agent.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取消代际注册表的并发线性化：register 的「检查取消位 → 加入列表」与
 * closeAll 的「置取消位 → 快照清空」必须互斥——否则 register 已通过取消
 * 检查、尚未入列的交错窗口里 closeAll 会漏关该句柄（Stop 后停滞流永不关闭）。
 */
class StreamCloseRegistryTest {

    /** add 可在锁存上暂停的 backing list（确定性制造 check→add 窗口）。 */
    private static final class GatedList extends AbstractList<Runnable> {
        private final List<Runnable> delegate = new ArrayList<>();
        private final CountDownLatch addEntered = new CountDownLatch(1);
        private volatile CountDownLatch releaseAdd;

        void armAddGate() {
            releaseAdd = new CountDownLatch(1);
        }

        void waitForAddEntered() throws InterruptedException {
            addEntered.await();
        }

        void releaseAdd() {
            releaseAdd.countDown();
        }

        @Override
        public boolean add(Runnable r) {
            if (releaseAdd != null) {
                addEntered.countDown();
                try {
                    releaseAdd.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return delegate.add(r);
        }

        @Override
        public Runnable get(int index) {
            return delegate.get(index);
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public void clear() {
            delegate.clear();
        }
    }

    @Test
    void registerRacingCloseAllCannotLoseHandle() throws Exception {
        // R4 复现：register 过了取消检查、add 尚未落地时 closeAll 执行——
        // 该句柄必须仍被关闭（线性化：register 的检查+入列与 closeAll 的
        // 置位+快照在同一锁内互斥，closeAll 要么看到已入列的句柄、要么
        // register 在注册点立关）。
        GatedList handles = new GatedList();
        StreamCloseRegistry registry = new StreamCloseRegistry(handles);
        AtomicInteger closes = new AtomicInteger();
        Runnable handle = closes::incrementAndGet;

        handles.armAddGate();
        Thread registrar = new Thread(() -> registry.register(handle));
        registrar.start();
        handles.waitForAddEntered();   // register 已通过取消检查、阻塞在 add

        Thread closer = new Thread(registry::closeAll);
        closer.start();
        // 无锁实现（旧代码）在此窗口内完成 closeAll——句柄随后才入列，永不关闭；
        // 线性化实现下 closer 阻塞在锁上，放行 add 后 closeAll 快照必含该句柄
        Thread.sleep(100);
        handles.releaseAdd();
        registrar.join(2000);
        closer.join(2000);

        assertEquals(1, closes.get(), "late-register handle must be closed exactly once");
        assertTrue(registry.isEmpty(), "handle dropped after close");
    }

    @Test
    void closeAllThenRegisterClosesImmediately() {
        StreamCloseRegistry registry = new StreamCloseRegistry();
        AtomicInteger closes = new AtomicInteger();
        registry.closeAll();
        registry.register(closes::incrementAndGet);
        assertEquals(1, closes.get(), "cancelled registry closes at registration point");
    }

    @Test
    void registeredHandleClosedOnceAndRegistrationRemovesWithoutClose() {
        StreamCloseRegistry registry = new StreamCloseRegistry();
        AtomicInteger closes = new AtomicInteger();
        StreamCloseRegistry.Registration registration = registry.register(closes::incrementAndGet);
        assertFalse(registry.isEmpty());
        registration.close();          // 正常完成：只摘句柄，不关流
        assertEquals(0, closes.get());
        assertTrue(registry.isEmpty());

        registry.register(closes::incrementAndGet);
        registry.closeAll();
        assertEquals(1, closes.get());
    }

    /** 最小 List 骨架（仅 add/get/size 被注册表使用）。 */
    private abstract static class AbstractList<E> implements List<E> {
        @Override public abstract boolean add(E e);
        @Override public abstract E get(int index);
        @Override public abstract int size();

        @Override public boolean isEmpty() { return size() == 0; }
        @Override public Iterator<E> iterator() {
            return new Iterator<E>() {
                private int i;
                @Override public boolean hasNext() { return i < size(); }
                @Override public E next() { return get(i++); }
            };
        }
        @Override public Object[] toArray() {
            Object[] a = new Object[size()];
            for (int i = 0; i < size(); i++) { a[i] = get(i); }
            return a;
        }
        @Override public <T> T[] toArray(T[] a) { throw new UnsupportedOperationException(); }
        @Override public boolean remove(Object o) { throw new UnsupportedOperationException(); }
        @Override public boolean contains(Object o) { throw new UnsupportedOperationException(); }
        @Override public boolean containsAll(Collection<?> c) { throw new UnsupportedOperationException(); }
        @Override public boolean addAll(Collection<? extends E> c) { throw new UnsupportedOperationException(); }
        @Override public boolean addAll(int index, Collection<? extends E> c) { throw new UnsupportedOperationException(); }
        @Override public boolean removeAll(Collection<?> c) { throw new UnsupportedOperationException(); }
        @Override public boolean retainAll(Collection<?> c) { throw new UnsupportedOperationException(); }
        @Override public E set(int index, E element) { throw new UnsupportedOperationException(); }
        @Override public void add(int index, E element) { throw new UnsupportedOperationException(); }
        @Override public E remove(int index) { throw new UnsupportedOperationException(); }
        @Override public int indexOf(Object o) { throw new UnsupportedOperationException(); }
        @Override public int lastIndexOf(Object o) { throw new UnsupportedOperationException(); }
        @Override public ListIterator<E> listIterator() { throw new UnsupportedOperationException(); }
        @Override public ListIterator<E> listIterator(int index) { throw new UnsupportedOperationException(); }
        @Override public List<E> subList(int fromIndex, int toIndex) { throw new UnsupportedOperationException(); }
    }
}
