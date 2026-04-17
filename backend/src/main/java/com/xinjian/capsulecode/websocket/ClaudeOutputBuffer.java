package com.xinjian.capsulecode.websocket;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * per-convId 输出缓冲区，支持 HTTP 轮询。
 *
 * - append()：tail 线程写入，每条 entry 分配自增 seqId
 * - since(cursor)：返回 seqId > cursor 的所有条目
 * - getMaxSeqId()：返回当前最大 seqId（init 时拿 cursor）
 * - 最多保留 MAX_SIZE 条，超出时丢弃最旧的
 */
public class ClaudeOutputBuffer {

    private static final int MAX_SIZE = 2000;

    public record OutputEntry(
            long seqId,
            String type,
            String subtype,
            String text,
            int outputTokens,
            int contextWindow,
            long timestampMs
    ) {}

    private final AtomicLong seqCounter = new AtomicLong(0);
    private final AtomicLong emptyPollCounter = new AtomicLong(0);
    private final LinkedList<OutputEntry> buffer = new LinkedList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // 长轮询条件变量：append 时 signalAll，httpStream 空结果时 await
    private final ReentrantLock notifyLock = new ReentrantLock();
    private final Condition notEmpty = notifyLock.newCondition();

    /**
     * 追加一条输出条目，返回分配的 seqId。
     * 追加完成后立即唤醒所有等待中的长轮询请求。
     */
    public long append(String type, String subtype, String text, int outputTokens, int contextWindow) {
        long seq = seqCounter.incrementAndGet();
        OutputEntry entry = new OutputEntry(seq, type, subtype,
                text != null ? text : "", outputTokens, contextWindow, System.currentTimeMillis());
        lock.writeLock().lock();
        try {
            buffer.addLast(entry);
            if (buffer.size() > MAX_SIZE) {
                buffer.removeFirst();
            }
        } finally {
            lock.writeLock().unlock();
        }
        // 唤醒所有等待中的 httpStream 调用。必须在释放 writeLock 之后再获取 notifyLock，避免锁顺序反转
        notifyLock.lock();
        try {
            notEmpty.signalAll();
        } finally {
            notifyLock.unlock();
        }
        return seq;
    }

    /**
     * 长轮询：若 since(cursor) 立即有结果则直接返回；否则阻塞最多 timeoutMs 毫秒等待新 entry。
     * 双重检查避免丢失 append 与 await 之间的竞态信号。
     */
    public List<OutputEntry> awaitSince(long cursor, long timeoutMs) {
        List<OutputEntry> entries = since(cursor);
        if (!entries.isEmpty()) return entries;
        notifyLock.lock();
        try {
            // 在持有 notifyLock 的状态下再检查一次。若 append 在第一次 since 之后、我们拿到 notifyLock 之前已完成，
            // 此处可以看到最新 entry 并立即返回；若 append 的 signalAll 发生在此时点之后，await 能接收到信号。
            entries = since(cursor);
            if (!entries.isEmpty()) return entries;
            try {
                notEmpty.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return since(cursor);
        } finally {
            notifyLock.unlock();
        }
    }

    /**
     * 返回 seqId > cursor 的所有条目（按 seqId 升序）。
     * 若 cursor < buffer 最小 seqId（极少情况，buffer 被裁剪），
     * 则从 buffer 头部开始返回，客户端应重新 init。
     */
    public List<OutputEntry> since(long cursor) {
        lock.readLock().lock();
        try {
            List<OutputEntry> result = new ArrayList<>();
            for (OutputEntry e : buffer) {
                if (e.seqId() > cursor) {
                    result.add(e);
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 返回当前最大 seqId（init 时使用，作为初始 cursor）。
     * 调用时 tail 尚未启动，后续新增内容的 seqId 均 > 此值。
     */
    public long getMaxSeqId() {
        return seqCounter.get();
    }

    /**
     * 返回 buffer 中最小的 seqId（用于判断 cursor 是否已过期）。
     */
    public long getMinSeqId() {
        lock.readLock().lock();
        try {
            return buffer.isEmpty() ? 0L : buffer.getFirst().seqId();
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return buffer.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 空 poll 计数器（用于日志心跳）。每次空 poll 调用一次，返回递增后的值。
     */
    public long incrementEmptyPollCount() {
        return emptyPollCounter.incrementAndGet();
    }
}
