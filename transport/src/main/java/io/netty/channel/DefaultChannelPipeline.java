/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

import io.netty.channel.Channel.Unsafe;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.PausableEventExecutor;
import io.netty.util.internal.OneTimeTask;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * The default {@link ChannelPipeline} implementation.  It is usually created
 * by a {@link Channel} implementation when the {@link Channel} is created.
 */
final class DefaultChannelPipeline implements ChannelPipeline {

    static final InternalLogger logger = InternalLoggerFactory.getInstance(DefaultChannelPipeline.class);

    @SuppressWarnings("unchecked")
    private static final WeakHashMap<Class<?>, String>[] nameCaches =
            new WeakHashMap[Runtime.getRuntime().availableProcessors()];

    static {
        for (int i = 0; i < nameCaches.length; i ++) {
            nameCaches[i] = new WeakHashMap<Class<?>, String>();
        }
    }

    final AbstractChannel channel;

    final AbstractChannelHandlerContext head;
    final AbstractChannelHandlerContext tail;

    // 维护一个名字和context的关系，减少在链表中查找的复杂度。
    private final Map<String, AbstractChannelHandlerContext> name2ctx =
            new HashMap<String, AbstractChannelHandlerContext>(4);

    /**
     * @see #findInvoker(EventExecutorGroup)
     */
    private Map<EventExecutorGroup, ChannelHandlerInvoker> childInvokers;

    DefaultChannelPipeline(AbstractChannel channel) {
        if (channel == null) {
            throw new NullPointerException("channel");
        }
        this.channel = channel;

        tail = new TailContext(this);
        head = new HeadContext(this);

        head.next = tail;
        tail.prev = head;
    }

    @Override
    public Channel channel() {
        return channel;
    }

    @Override
    public ChannelPipeline addFirst(String name, ChannelHandler handler) {
        return addFirst((ChannelHandlerInvoker) null, name, handler);
    }

    @Override
    public ChannelPipeline addFirst(EventExecutorGroup group, String name, ChannelHandler handler) {
        synchronized (this) {
            name = filterName(name, handler);
            addFirst0(name, new DefaultChannelHandlerContext(this, findInvoker(group), name, handler));
        }
        return this;
    }

    @Override
    public ChannelPipeline addFirst(ChannelHandlerInvoker invoker, String name, ChannelHandler handler) {
        synchronized (this) {
            name = filterName(name, handler);
            addFirst0(name, new DefaultChannelHandlerContext(this, invoker, name, handler));
        }
        return this;
    }

    private void addFirst0(String name, AbstractChannelHandlerContext newCtx) {
        checkMultiplicity(newCtx);

        AbstractChannelHandlerContext nextCtx = head.next;
        newCtx.prev = head;
        newCtx.next = nextCtx;
        head.next = newCtx;
        nextCtx.prev = newCtx;

        name2ctx.put(name, newCtx);

        callHandlerAdded(newCtx);
    }

    @Override
    public ChannelPipeline addLast(String name, ChannelHandler handler) {
        return addLast((ChannelHandlerInvoker) null, name, handler);
    }

    @Override
    public ChannelPipeline addLast(EventExecutorGroup group, String name, ChannelHandler handler) {
        synchronized (this) {
            name = filterName(name, handler);
            addLast0(name, new DefaultChannelHandlerContext(this, findInvoker(group), name, handler));
        }
        return this;
    }

    @Override
    public ChannelPipeline addLast(ChannelHandlerInvoker invoker, String name, ChannelHandler handler) {
        synchronized (this) {
            name = filterName(name, handler);
            addLast0(name, new DefaultChannelHandlerContext(this, invoker, name, handler));
        }
        return this;
    }

    private void addLast0(final String name, AbstractChannelHandlerContext newCtx) {
        checkMultiplicity(newCtx);

        AbstractChannelHandlerContext prev = tail.prev;
        newCtx.prev = prev;
        newCtx.next = tail;
        prev.next = newCtx;
        tail.prev = newCtx;

        name2ctx.put(name, newCtx);

        callHandlerAdded(newCtx);
    }

    @Override
    public ChannelPipeline addBefore(String baseName, String name, ChannelHandler handler) {
        return addBefore((ChannelHandlerInvoker) null, baseName, name, handler);
    }

    @Override
    public ChannelPipeline addBefore(EventExecutorGroup group, String baseName, String name, ChannelHandler handler) {
        synchronized (this) {
            AbstractChannelHandlerContext ctx = getContextOrDie(baseName);
            name = filterName(name, handler);
            addBefore0(name, ctx, new DefaultChannelHandlerContext(this, findInvoker(group), name, handler));
        }
        return this;
    }

    @Override
    public ChannelPipeline addBefore(
            ChannelHandlerInvoker invoker, String baseName, String name, ChannelHandler handler) {
        synchronized (this) {
            AbstractChannelHandlerContext ctx = getContextOrDie(baseName);
            // 重名检查
            name = filterName(name, handler);
            addBefore0(name, ctx, new DefaultChannelHandlerContext(this, invoker, name, handler));
        }
        return this;
    }

    private void addBefore0(
            final String name, AbstractChannelHandlerContext ctx, AbstractChannelHandlerContext newCtx) {
        checkMultiplicity(newCtx);

        newCtx.prev = ctx.prev;
        newCtx.next = ctx;
        ctx.prev.next = newCtx;
        ctx.prev = newCtx;

        name2ctx.put(name, newCtx);

        callHandlerAdded(newCtx);
    }

    @Override
    public ChannelPipeline addAfter(String baseName, String name, ChannelHandler handler) {
        return addAfter((ChannelHandlerInvoker) null, baseName, name, handler);
    }

    @Override
    public ChannelPipeline addAfter(EventExecutorGroup group, String baseName, String name, ChannelHandler handler) {
        synchronized (this) {
            AbstractChannelHandlerContext ctx = getContextOrDie(baseName);
            name = filterName(name, handler);
            addAfter0(name, ctx, new DefaultChannelHandlerContext(this, findInvoker(group), name, handler));
        }
        return this;
    }

    @Override
    public ChannelPipeline addAfter(
            ChannelHandlerInvoker invoker, String baseName, String name, ChannelHandler handler) {

        synchronized (this) {
            AbstractChannelHandlerContext ctx = getContextOrDie(baseName);
            name = filterName(name, handler);
            addAfter0(name, ctx, new DefaultChannelHandlerContext(this, invoker, name, handler));
        }
        return this;
    }

    private void addAfter0(String name, AbstractChannelHandlerContext ctx, AbstractChannelHandlerContext newCtx) {
        checkMultiplicity(newCtx);

        newCtx.prev = ctx;
        newCtx.next = ctx.next;
        ctx.next.prev = newCtx;
        ctx.next = newCtx;

        name2ctx.put(name, newCtx);

        callHandlerAdded(newCtx);
    }

    @Override
    public ChannelPipeline addFirst(ChannelHandler... handlers) {
        return addFirst((ChannelHandlerInvoker) null, handlers);
    }

    @Override
    public ChannelPipeline addFirst(EventExecutorGroup group, ChannelHandler... handlers) {
        if (handlers == null) {
            throw new NullPointerException("handlers");
        }
        if (handlers.length == 0 || handlers[0] == null) {
            return this;
        }

        int size;
        for (size = 1; size < handlers.length; size ++) {
            if (handlers[size] == null) {
                break;
            }
        }

        for (int i = size - 1; i >= 0; i --) {
            ChannelHandler h = handlers[i];
            addFirst(group, generateName(h), h);
        }

        return this;
    }

    @Override
    public ChannelPipeline addFirst(ChannelHandlerInvoker invoker, ChannelHandler... handlers) {
        if (handlers == null) {
            throw new NullPointerException("handlers");
        }
        if (handlers.length == 0 || handlers[0] == null) {
            return this;
        }

        int size;
        for (size = 1; size < handlers.length; size ++) {
            if (handlers[size] == null) {
                break;
            }
        }

        for (int i = size - 1; i >= 0; i --) {
            ChannelHandler h = handlers[i];
            addFirst(invoker, null, h);
        }

        return this;
    }

    @Override
    public ChannelPipeline addLast(ChannelHandler... handlers) {
        return addLast((ChannelHandlerInvoker) null, handlers);
    }

    @Override
    public ChannelPipeline addLast(EventExecutorGroup group, ChannelHandler... handlers) {
        if (handlers == null) {
            throw new NullPointerException("handlers");
        }

        for (ChannelHandler h: handlers) {
            if (h == null) {
                break;
            }
            addLast(group, generateName(h), h);
        }

        return this;
    }

    @Override
    public ChannelPipeline addLast(ChannelHandlerInvoker invoker, ChannelHandler... handlers) {
        if (handlers == null) {
            throw new NullPointerException("handlers");
        }

        for (ChannelHandler h: handlers) {
            if (h == null) {
                break;
            }
            addLast(invoker, null, h);
        }

        return this;
    }

    // No need for synchronization because it is always executed in a synchronized(this) block.
    private ChannelHandlerInvoker findInvoker(EventExecutorGroup group) {
        if (group == null) {
            return null;
        }

        // Lazily initialize the data structure that maps an EventExecutorGroup to a ChannelHandlerInvoker.
        Map<EventExecutorGroup, ChannelHandlerInvoker> childInvokers = this.childInvokers;
        if (childInvokers == null) {
            childInvokers = this.childInvokers = new IdentityHashMap<EventExecutorGroup, ChannelHandlerInvoker>(4);
        }

        // Pick one of the child executors and remember its invoker
        // so that the same invoker is used to fire events for the same channel.
        ChannelHandlerInvoker  invoker = childInvokers.get(group);
        if (invoker == null) {
            EventExecutor executor = group.next();
            if (executor instanceof EventLoop) {
                invoker = ((EventLoop) executor).asInvoker();
            } else {
                invoker = new DefaultChannelHandlerInvoker(executor);
            }
            childInvokers.put(group, invoker);
        }

        return invoker;
    }

    String generateName(ChannelHandler handler) {
        WeakHashMap<Class<?>, String> cache = nameCaches[(int) (Thread.currentThread().getId() % nameCaches.length)];
        Class<?> handlerType = handler.getClass();
        String name;
        synchronized (cache) {
            name = cache.get(handlerType);
            if (name == null) {
                name = generateName0(handlerType);
                cache.put(handlerType, name);
            }
        }

        synchronized (this) {
            // It's not very likely for a user to put more than one handler of the same type, but make sure to avoid
            // any name conflicts.  Note that we don't cache the names generated here.
            if (name2ctx.containsKey(name)) {
                String baseName = name.substring(0, name.length() - 1); // Strip the trailing '0'.
                for (int i = 1;; i ++) {
                    String newName = baseName + i;
                    if (!name2ctx.containsKey(newName)) {
                        name = newName;
                        break;
                    }
                }
            }
        }

        return name;
    }

    private static String generateName0(Class<?> handlerType) {
        return StringUtil.simpleClassName(handlerType) + "#0";
    }

    @Override
    public ChannelPipeline remove(ChannelHandler handler) {
        remove(getContextOrDie(handler));
        return this;
    }

    @Override
    public ChannelHandler remove(String name) {
        return remove(getContextOrDie(name)).handler();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends ChannelHandler> T remove(Class<T> handlerType) {
        return (T) remove(getContextOrDie(handlerType)).handler();
    }

    private AbstractChannelHandlerContext remove(final AbstractChannelHandlerContext ctx) {
        assert ctx != head && ctx != tail;

        AbstractChannelHandlerContext context;
        Future<?> future;

        synchronized (this) {
            if (!ctx.channel().isRegistered() || ctx.executor().inEventLoop()) {
                remove0(ctx);
                return ctx;
            } else {
                future = ctx.executor().submit(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (DefaultChannelPipeline.this) {
                            remove0(ctx);
                        }
                    }
                });
                context = ctx;
            }
        }

        // Run the following 'waiting' code outside of the above synchronized block
        // in order to avoid deadlock

        waitForFuture(future);

        return context;
    }

    void remove0(AbstractChannelHandlerContext ctx) {
        AbstractChannelHandlerContext prev = ctx.prev;
        AbstractChannelHandlerContext next = ctx.next;
        prev.next = next;
        next.prev = prev;
        name2ctx.remove(ctx.name());
        callHandlerRemoved(ctx);
    }

    @Override
    public ChannelHandler removeFirst() {
        if (head.next == tail) {
            throw new NoSuchElementException();
        }
        return remove(head.next).handler();
    }

    @Override
    public ChannelHandler removeLast() {
        if (head.next == tail) {
            throw new NoSuchElementException();
        }
        return remove(tail.prev).handler();
    }

    @Override
    public ChannelPipeline replace(ChannelHandler oldHandler, String newName, ChannelHandler newHandler) {
        replace(getContextOrDie(oldHandler), newName, newHandler);
        return this;
    }

    @Override
    public ChannelHandler replace(String oldName, String newName, ChannelHandler newHandler) {
        return replace(getContextOrDie(oldName), newName, newHandler);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends ChannelHandler> T replace(
            Class<T> oldHandlerType, String newName, ChannelHandler newHandler) {
        return (T) replace(getContextOrDie(oldHandlerType), newName, newHandler);
    }

    private ChannelHandler replace(
            final AbstractChannelHandlerContext ctx, String newName, ChannelHandler newHandler) {

        assert ctx != head && ctx != tail;

        Future<?> future;
        synchronized (this) {
            if (newName == null) {
                newName = ctx.name();
            } else if (!ctx.name().equals(newName)) {
                newName = filterName(newName, newHandler);
            }

            final AbstractChannelHandlerContext newCtx =
                    new DefaultChannelHandlerContext(this, ctx.invoker, newName, newHandler);

            if (!newCtx.channel().isRegistered() || newCtx.executor().inEventLoop()) {
                replace0(ctx, newName, newCtx);
                return ctx.handler();
            } else {
                final String finalNewName = newName;
                future = newCtx.executor().submit(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (DefaultChannelPipeline.this) {
                            replace0(ctx, finalNewName, newCtx);
                        }
                    }
                });
            }
        }

        // Run the following 'waiting' code outside of the above synchronized block
        // in order to avoid deadlock

        waitForFuture(future);

        return ctx.handler();
    }

    private void replace0(AbstractChannelHandlerContext oldCtx, String newName,
                          AbstractChannelHandlerContext newCtx) {
        checkMultiplicity(newCtx);

        AbstractChannelHandlerContext prev = oldCtx.prev;
        AbstractChannelHandlerContext next = oldCtx.next;
        newCtx.prev = prev;
        newCtx.next = next;

        // Finish the replacement of oldCtx with newCtx in the linked list.
        // Note that this doesn't mean events will be sent to the new handler immediately
        // because we are currently at the event handler thread and no more than one handler methods can be invoked
        // at the same time (we ensured that in replace().)
        prev.next = newCtx;
        next.prev = newCtx;

        if (!oldCtx.name().equals(newName)) {
            name2ctx.remove(oldCtx.name());
        }
        name2ctx.put(newName, newCtx);

        // update the reference to the replacement so forward of buffered content will work correctly
        oldCtx.prev = newCtx;
        oldCtx.next = newCtx;

        // Invoke newHandler.handlerAdded() first (i.e. before oldHandler.handlerRemoved() is invoked)
        // because callHandlerRemoved() will trigger inboundBufferUpdated() or flush() on newHandler and those
        // event handlers must be called after handlerAdded().
        callHandlerAdded(newCtx);
        callHandlerRemoved(oldCtx);
    }

    private static void checkMultiplicity(ChannelHandlerContext ctx) {
        ChannelHandler handler = ctx.handler();
        if (handler instanceof ChannelHandlerAdapter) {
            ChannelHandlerAdapter h = (ChannelHandlerAdapter) handler;
            if (!h.isSharable() && h.added) {
                throw new ChannelPipelineException(
                        h.getClass().getName() +
                        " is not a @Sharable handler, so can't be added or removed multiple times.");
            }
            h.added = true;
        }
    }

    private void callHandlerAdded(final AbstractChannelHandlerContext ctx) {
        if ((ctx.skipFlags & AbstractChannelHandlerContext.MASK_HANDLER_ADDED) != 0) {
            return;
        }

        if (ctx.channel().isRegistered() && !ctx.executor().inEventLoop()) {
            ctx.executor().execute(new Runnable() {
                @Override
                public void run() {
                    callHandlerAdded0(ctx);
                }
            });
            return;
        }
        callHandlerAdded0(ctx);
    }

    private void callHandlerAdded0(final AbstractChannelHandlerContext ctx) {
        try {
            ctx.invokedThisChannelRead = false;
            ctx.handler().handlerAdded(ctx);
        } catch (Throwable t) {
            boolean removed = false;
            try {
                remove(ctx);
                removed = true;
            } catch (Throwable t2) {
                if (logger.isWarnEnabled()) {
                    logger.warn("Failed to remove a handler: " + ctx.name(), t2);
                }
            }

            if (removed) {
                fireExceptionCaught(new ChannelPipelineException(
                        ctx.handler().getClass().getName() +
                        ".handlerAdded() has thrown an exception; removed.", t));
            } else {
                fireExceptionCaught(new ChannelPipelineException(
                        ctx.handler().getClass().getName() +
                        ".handlerAdded() has thrown an exception; also failed to remove.", t));
            }
        }
    }

    private void callHandlerRemoved(final AbstractChannelHandlerContext ctx) {
        if ((ctx.skipFlags & AbstractChannelHandlerContext.MASK_HANDLER_REMOVED) != 0) {
            return;
        }

        if (ctx.channel().isRegistered() && !ctx.executor().inEventLoop()) {
            ctx.executor().execute(new Runnable() {
                @Override
                public void run() {
                    callHandlerRemoved0(ctx);
                }
            });
            return;
        }
        callHandlerRemoved0(ctx);
    }

    private void callHandlerRemoved0(final AbstractChannelHandlerContext ctx) {
        // Notify the complete removal.
        try {
            ctx.handler().handlerRemoved(ctx);
            ctx.setRemoved();
        } catch (Throwable t) {
            fireExceptionCaught(new ChannelPipelineException(
                    ctx.handler().getClass().getName() + ".handlerRemoved() has thrown an exception.", t));
        }
    }

    /**
     * Waits for a future to finish.  If the task is interrupted, then the current thread will be interrupted.
     * It is expected that the task performs any appropriate locking.
     * <p>
     * If the internal call throws a {@link Throwable}, but it is not an instance of {@link Error} or
     * {@link RuntimeException}, then it is wrapped inside a {@link ChannelPipelineException} and that is
     * thrown instead.</p>
     *
     * @param future wait for this future
     * @see Future#get()
     * @throws Error if the task threw this.
     * @throws RuntimeException if the task threw this.
     * @throws ChannelPipelineException with a {@link Throwable} as a cause, if the task threw another type of
     *         {@link Throwable}.
     */
    private static void waitForFuture(Future<?> future) {
        try {
            future.get();
        } catch (ExecutionException ex) {
            // In the arbitrary case, we can throw Error, RuntimeException, and Exception
            PlatformDependent.throwException(ex.getCause());
        } catch (InterruptedException ex) {
            // Interrupt the calling thread (note that this method is not called from the event loop)
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public ChannelHandler first() {
        ChannelHandlerContext first = firstContext();
        if (first == null) {
            return null;
        }
        return first.handler();
    }

    @Override
    public ChannelHandlerContext firstContext() {
        AbstractChannelHandlerContext first = head.next;
        if (first == tail) {
            return null;
        }
        return head.next;
    }

    @Override
    public ChannelHandler last() {
        AbstractChannelHandlerContext last = tail.prev;
        if (last == head) {
            return null;
        }
        return last.handler();
    }

    @Override
    public ChannelHandlerContext lastContext() {
        AbstractChannelHandlerContext last = tail.prev;
        if (last == head) {
            return null;
        }
        return last;
    }

    @Override
    public ChannelHandler get(String name) {
        ChannelHandlerContext ctx = context(name);
        if (ctx == null) {
            return null;
        } else {
            return ctx.handler();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends ChannelHandler> T get(Class<T> handlerType) {
        ChannelHandlerContext ctx = context(handlerType);
        if (ctx == null) {
            return null;
        } else {
            return (T) ctx.handler();
        }
    }

    @Override
    public ChannelHandlerContext context(String name) {
        if (name == null) {
            throw new NullPointerException("name");
        }

        synchronized (this) {
            return name2ctx.get(name);
        }
    }

    @Override
    public ChannelHandlerContext context(ChannelHandler handler) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }

        AbstractChannelHandlerContext ctx = head.next;
        for (;;) {

            if (ctx == null) {
                return null;
            }

            if (ctx.handler() == handler) {
                return ctx;
            }

            ctx = ctx.next;
        }
    }

    @Override
    public ChannelHandlerContext context(Class<? extends ChannelHandler> handlerType) {
        if (handlerType == null) {
            throw new NullPointerException("handlerType");
        }

        AbstractChannelHandlerContext ctx = head.next;
        for (;;) {
            if (ctx == null) {
                return null;
            }
            if (handlerType.isAssignableFrom(ctx.handler().getClass())) {
                return ctx;
            }
            ctx = ctx.next;
        }
    }

    @Override
    public List<String> names() {
        List<String> list = new ArrayList<String>();
        AbstractChannelHandlerContext ctx = head.next;
        for (;;) {
            if (ctx == null) {
                return list;
            }
            list.add(ctx.name());
            ctx = ctx.next;
        }
    }

    @Override
    public Map<String, ChannelHandler> toMap() {
        Map<String, ChannelHandler> map = new LinkedHashMap<String, ChannelHandler>();
        AbstractChannelHandlerContext ctx = head.next;
        for (;;) {
            if (ctx == tail) {
                return map;
            }
            map.put(ctx.name(), ctx.handler());
            ctx = ctx.next;
        }
    }

    @Override
    public Iterator<Map.Entry<String, ChannelHandler>> iterator() {
        return toMap().entrySet().iterator();
    }

    /**
     * Returns the {@link String} representation of this pipeline.
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder()
            .append(StringUtil.simpleClassName(this))
            .append('{');
        AbstractChannelHandlerContext ctx = head.next;
        for (;;) {
            if (ctx == tail) {
                break;
            }

            buf.append('(')
               .append(ctx.name())
               .append(" = ")
               .append(ctx.handler().getClass().getName())
               .append(')');

            ctx = ctx.next;
            if (ctx == tail) {
                break;
            }

            buf.append(", ");
        }
        buf.append('}');
        return buf.toString();
    }

    @Override
    public ChannelPipeline fireChannelRegistered() {
        head.fireChannelRegistered();
        return this;
    }

    @Override
    public ChannelPipeline fireChannelUnregistered() {
        head.fireChannelUnregistered();

        // Remove all handlers sequentially if channel is closed and unregistered.
        if (!channel.isOpen()) {
            destroy();
        }
        return this;
    }

    /**
     * Removes all handlers from the pipeline one by one from tail (exclusive) to head (exclusive) to trigger
     * handlerRemoved().
     *
     * Note that we traverse up the pipeline ({@link #destroyUp(AbstractChannelHandlerContext)})
     * before traversing down ({@link #destroyDown(Thread, AbstractChannelHandlerContext)}) so that
     * the handlers are removed after all events are handled.
     *
     * See: https://github.com/netty/netty/issues/3156
     */
    private void destroy() {
        destroyUp(head.next);
    }

    private void destroyUp(AbstractChannelHandlerContext ctx) {
        final Thread currentThread = Thread.currentThread();
        final AbstractChannelHandlerContext tail = this.tail;
        for (;;) {
            if (ctx == tail) {
                destroyDown(currentThread, tail.prev);
                break;
            }

            final EventExecutor executor = ctx.executor();
            if (!executor.inEventLoop(currentThread)) {
                final AbstractChannelHandlerContext finalCtx = ctx;
                executor.unwrap().execute(new OneTimeTask() {
                    @Override
                    public void run() {
                        destroyUp(finalCtx);
                    }
                });
                break;
            }

            ctx = ctx.next;
        }
    }

    private void destroyDown(Thread currentThread, AbstractChannelHandlerContext ctx) {
        // We have reached at tail; now traverse backwards.
        final AbstractChannelHandlerContext head = this.head;
        for (;;) {
            if (ctx == head) {
                break;
            }

            final EventExecutor executor = ctx.executor();
            if (executor.inEventLoop(currentThread)) {
                synchronized (this) {
                    remove0(ctx);
                }
            } else {
                final AbstractChannelHandlerContext finalCtx = ctx;
                executor.unwrap().execute(new OneTimeTask() {
                    @Override
                    public void run() {
                        destroyDown(Thread.currentThread(), finalCtx);
                    }
                });
                break;
            }

            ctx = ctx.prev;
        }
    }

    @Override
    public ChannelPipeline fireChannelActive() {
        head.fireChannelActive();

        if (channel.config().isAutoRead()) {
            channel.read();
        }

        return this;
    }

    @Override
    public ChannelPipeline fireChannelInactive() {
        head.fireChannelInactive();
        return this;
    }

    @Override
    public ChannelPipeline fireExceptionCaught(Throwable cause) {
        head.fireExceptionCaught(cause);
        return this;
    }

    @Override
    public ChannelPipeline fireUserEventTriggered(Object event) {
        head.fireUserEventTriggered(event);
        return this;
    }

    @Override
    public ChannelPipeline fireChannelRead(Object msg) {
        head.fireChannelRead(msg);
        return this;
    }

    @Override
    public ChannelPipeline fireChannelReadComplete() {
        head.fireChannelReadComplete();
        if (channel.config().isAutoRead()) {
            read();
        }
        return this;
    }

    @Override
    public ChannelPipeline fireChannelWritabilityChanged() {
        head.fireChannelWritabilityChanged();
        return this;
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress) {
        return tail.bind(localAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress) {
        return tail.connect(remoteAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return tail.connect(remoteAddress, localAddress);
    }

    @Override
    public ChannelFuture disconnect() {
        return tail.disconnect();
    }

    @Override
    public ChannelFuture close() {
        return tail.close();
    }

    @Override
    public ChannelFuture deregister() {
        return tail.deregister();
    }

    @Override
    public ChannelPipeline flush() {
        tail.flush();
        return this;
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) {
        return tail.bind(localAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return tail.connect(remoteAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        return tail.connect(remoteAddress, localAddress, promise);
    }

    @Override
    public ChannelFuture disconnect(ChannelPromise promise) {
        return tail.disconnect(promise);
    }

    @Override
    public ChannelFuture close(ChannelPromise promise) {
        return tail.close(promise);
    }

    @Override
    public ChannelFuture deregister(ChannelPromise promise) {
        return tail.deregister(promise);
    }

    @Override
    public ChannelPipeline read() {
        tail.read();
        return this;
    }

    @Override
    public ChannelFuture write(Object msg) {
        return tail.write(msg);
    }

    @Override
    public ChannelFuture write(Object msg, ChannelPromise promise) {
        return tail.write(msg, promise);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
        return tail.writeAndFlush(msg, promise);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg) {
        return tail.writeAndFlush(msg);
    }

    private String filterName(String name, ChannelHandler handler) {
        if (name == null) {
            return generateName(handler);
        }

        if (!name2ctx.containsKey(name)) {
            return name;
        }

        throw new IllegalArgumentException("Duplicate handler name: " + name);
    }

    private AbstractChannelHandlerContext getContextOrDie(String name) {
        AbstractChannelHandlerContext ctx = (AbstractChannelHandlerContext) context(name);
        if (ctx == null) {
            throw new NoSuchElementException(name);
        } else {
            return ctx;
        }
    }

    private AbstractChannelHandlerContext getContextOrDie(ChannelHandler handler) {
        AbstractChannelHandlerContext ctx = (AbstractChannelHandlerContext) context(handler);
        if (ctx == null) {
            throw new NoSuchElementException(handler.getClass().getName());
        } else {
            return ctx;
        }
    }

    private AbstractChannelHandlerContext getContextOrDie(Class<? extends ChannelHandler> handlerType) {
        AbstractChannelHandlerContext ctx = (AbstractChannelHandlerContext) context(handlerType);
        if (ctx == null) {
            throw new NoSuchElementException(handlerType.getName());
        } else {
            return ctx;
        }
    }

    static final class TailContext extends AbstractChannelHandlerContext implements ChannelHandler {
        private static final int SKIP_FLAGS = skipFlags0(TailContext.class);
        private static final String TAIL_NAME = generateName0(TailContext.class);

        TailContext(DefaultChannelPipeline pipeline) {
            super(pipeline, null, TAIL_NAME, SKIP_FLAGS);
        }

        @Override
        public ChannelHandler handler() {
            return this;
        }

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception { }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception { }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception { }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception { }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception { }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception { }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            logger.warn(
                    "An exceptionCaught() event was fired, and it reached at the tail of the pipeline. " +
                            "It usually means the last handler in the pipeline did not handle the exception.", cause);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            try {
                logger.debug(
                        "Discarded inbound message {} that reached at the tail of the pipeline. " +
                                "Please check your pipeline configuration.", msg);
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception { }

        @Skip
        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception { }

        @Skip
        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) throws Exception { }

        @Skip
        @Override
        public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise)
                throws Exception {
            ctx.bind(localAddress, promise);
        }

        @Skip
        @Override
        public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress,
                            SocketAddress localAddress, ChannelPromise promise) throws Exception {
            ctx.connect(remoteAddress, localAddress, promise);
        }

        @Skip
        @Override
        public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
            ctx.disconnect(promise);
        }

        @Skip
        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
            ctx.close(promise);
        }

        @Skip
        @Override
        public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
            ctx.deregister(promise);
        }

        @Skip
        @Override
        public void read(ChannelHandlerContext ctx) throws Exception {
            ctx.read();
        }

        @Skip
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            ctx.write(msg, promise);
        }

        @Skip
        @Override
        public void flush(ChannelHandlerContext ctx) throws Exception {
            ctx.flush();
        }
    }

    static final class HeadContext extends AbstractChannelHandlerContext implements ChannelHandler {
        private static final int SKIP_FLAGS = skipFlags0(HeadContext.class);
        private static final String HEAD_NAME = generateName0(HeadContext.class);

        private final Unsafe unsafe;

        HeadContext(DefaultChannelPipeline pipeline) {
            super(pipeline, null, HEAD_NAME, SKIP_FLAGS);
            unsafe = pipeline.channel().unsafe();
        }

        @Override
        public ChannelHandler handler() {
            return this;
        }

        @Override
        public void bind(
                ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise)
                throws Exception {
            unsafe.bind(localAddress, promise);
        }

        @Override
        public void connect(
                ChannelHandlerContext ctx,
                SocketAddress remoteAddress, SocketAddress localAddress,
                ChannelPromise promise) throws Exception {
            unsafe.connect(remoteAddress, localAddress, promise);
        }

        @Override
        public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
            unsafe.disconnect(promise);
        }

        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
            unsafe.close(promise);
        }

        @Override
        public void deregister(ChannelHandlerContext ctx, final ChannelPromise promise) throws Exception {
            assert !((PausableEventExecutor) ctx.channel().eventLoop()).isAcceptingNewTasks();

            // submit deregistration task
            ctx.channel().eventLoop().unwrap().execute(new OneTimeTask() {
                @Override
                public void run() {
                    unsafe.deregister(promise);
                }
            });
        }

        @Override
        public void read(ChannelHandlerContext ctx) {
            unsafe.beginRead();
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            unsafe.write(msg, promise);
        }

        @Override
        public void flush(ChannelHandlerContext ctx) throws Exception {
            unsafe.flush();
        }

        @Skip
        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception { }

        @Skip
        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) throws Exception { }

        @Skip
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            ctx.fireExceptionCaught(cause);
        }

        @Skip
        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
            ctx.fireChannelRegistered();
        }

        @Skip
        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
            ctx.fireChannelUnregistered();
        }

        @Skip
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            ctx.fireChannelActive();
        }

        @Skip
        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            ctx.fireChannelInactive();
        }

        @Skip
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            ctx.fireChannelRead(msg);
        }

        @Skip
        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
            ctx.fireChannelReadComplete();
        }

        @Skip
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            ctx.fireUserEventTriggered(evt);
        }

        @Skip
        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
            ctx.fireChannelWritabilityChanged();
        }
    }
}
