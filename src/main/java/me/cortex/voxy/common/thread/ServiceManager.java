package me.cortex.voxy.common.thread;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.Pair;

import java.util.Arrays;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

public class ServiceManager {
    private static final class ThreadCtx {
        // Worker ThreadLocals must not retain removed services and their worlds after shutdown.
        WeakReference<Service[]> services = new WeakReference<>(null);
        int index;
        long remaining;
    }

    private final IntConsumer jobRelease;
    private final ThreadLocal<ThreadCtx> accelerationContext = ThreadLocal.withInitial(ThreadCtx::new);
    private final AtomicInteger totalJobs = new AtomicInteger();
    private volatile Service[] services = new Service[0];
    private volatile boolean isShutdown = false;

    public ServiceManager(IntConsumer jobRelease) {
        this.jobRelease = jobRelease;
    }


    public Service createServiceNoCleanup(Supplier<Runnable> ctxFactory, long weight) {
        return this.createService(()->new Pair<>(ctxFactory.get(), ()->{}), weight, "");
    }

    public Service createServiceNoCleanup(Supplier<Runnable> ctxFactory, long weight, String name) {
        return this.createService(()->new Pair<>(ctxFactory.get(), ()->{}), weight, name);
    }

    public Service createService(Supplier<Pair<Runnable, Runnable>> ctxFactory, long weight) {
        return this.createService(ctxFactory, weight, "");
    }

    public Service createService(Supplier<Pair<Runnable, Runnable>> ctxFactory, long weight, String name) {
        return this.createService(ctxFactory, weight, name, null);
    }
    public synchronized Service createService(Supplier<Pair<Runnable, Runnable>> ctxFactory, long weight, String name, BooleanSupplier limiter) {
        if (weight <= 0) throw new IllegalArgumentException("Service weight must be positive");
        if (this.isShutdown) throw new IllegalStateException("Service manager is shutdown");
        Service newService = new Service(ctxFactory, this, weight, name, limiter);
        var newServices = Arrays.copyOf(this.services, this.services.length+1);
        newServices[newServices.length-1] = newService;
        this.services = newServices;
        return newService;
    }

    public int tryRunAJob() {//Executes a single job on the current thread
        if (this.services.length == 0 || this.totalJobs.get() == 0) return 1;
        return this.runAJob0();
    }

    private int runAJob0() {//Executes a single job on the current thread
        var ctx = this.accelerationContext.get();
        while (true) {
            var services = this.services;
            if (services.length == 0 || this.totalJobs.get() == 0) return 1;
            if (ctx.services.get() != services) {
                ctx.services = new WeakReference<>(services);
                ctx.index = 0;
                ctx.remaining = services[0].weight;
            }
            boolean limited = false;
            boolean raced = false;
            for (int visited = 0; visited < services.length; visited++) {
                var service = services[ctx.index];
                if (service.isLive() && service.numJobs() != 0) {
                    if (service.limiter != null && !service.limiter.getAsBoolean()) {
                        limited = true;
                    } else {
                        // Consume the turn before invoking user work: it may help another service
                        // recursively on this same thread while waiting on a pooled semaphore.
                        if (--ctx.remaining == 0) advance(ctx, services);
                        if (service.runJob()) {
                            this.remJobs(1);
                            return 0;
                        }
                        raced = true;
                        break;
                    }
                }
                // Empty and limited services surrender their remaining turns immediately.
                advance(ctx, services);
            }
            if (ctx.services.get() != this.services || raced) continue;
            return limited ? 3 : 2;
        }
    }

    private static void advance(ThreadCtx ctx, Service[] services) {
        ctx.index = (ctx.index + 1) % services.length;
        ctx.remaining = services[ctx.index].weight;
    }

    public void shutdown() {
        if (this.isShutdown) {
            throw new IllegalStateException("Service manager already shutdown");
        }
        this.isShutdown = true;
        while (this.services.length != 0) {
            Thread.yield();
            synchronized (this) {
                for (var s : this.services) {
                    if (s.isLive()) {
                        throw new IllegalStateException("Service '" + s.name + "' was not in shutdown when manager shutdown");
                    }
                }
            }
        }
        while (this.totalJobs.get()!=0) {
            Thread.yield();
        }
    }

    synchronized void removeService(Service service) {
        var services = this.services;
        var newServices = new Service[services.length-1];
        int j = 0;
        for (int i = 0; i < services.length; i++) {
            if (services[i] != service) {
                newServices[j++] = services[i];
            }
        }
        if (j != newServices.length) {
            throw new IllegalStateException("Could not find the service in the services array");
        }

        this.services = newServices;
    }

    void execute(Service service) {
        this.totalJobs.incrementAndGet();
    }

    void wake() {
        this.jobRelease.accept(1);
    }

    void remJobs(int remaining) {

        if (this.totalJobs.addAndGet(-remaining)<0) {
            throw new IllegalStateException("total jobs <0");
        }
    }

    void handleException(Service service, Exception exception) {
        Logger.error("Service '"+service.name+"' on thread '"+Thread.currentThread().getName()+"' had an exception", exception);
    }
}
