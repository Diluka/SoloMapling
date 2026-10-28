package org.gms.soloscripting;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import soloMapling.game.script.nativeenv.NativeScriptSession;

/** Only explicitly runtime-owned native schedulers are tracked; original channel events remain untouched. */
@Aspect
public final class NativeScriptSchedulerAspect {
    @Around("call(void org.gms.server.ThreadManager.newTask(java.lang.Runnable)) && withincode(void org.gms.scripting.event.scheduler.EventScriptScheduler.registerEntry(..)) && this(scheduler) && args(task)")
    public Object register(ProceedingJoinPoint point, Object scheduler, Runnable task) throws Throwable {
        return submit(point, scheduler, task, false);
    }
    @Around("call(void org.gms.server.ThreadManager.newTask(java.lang.Runnable)) && withincode(void org.gms.scripting.event.scheduler.EventScriptScheduler.cancelEntry(..)) && this(scheduler) && args(task)")
    public Object cancel(ProceedingJoinPoint point, Object scheduler, Runnable task) throws Throwable {
        return submit(point, scheduler, task, false);
    }
    @Around("call(void org.gms.server.ThreadManager.newTask(java.lang.Runnable)) && withincode(void org.gms.scripting.event.scheduler.EventScriptScheduler.dispose()) && this(scheduler) && args(task)")
    public Object dispose(ProceedingJoinPoint point, Object scheduler, Runnable task) throws Throwable {
        return submit(point, scheduler, task, true);
    }
    private Object submit(ProceedingJoinPoint point, Object scheduler, Runnable task, boolean dispose) throws Throwable {
        NativeScriptSession session = NativeScriptSession.forScheduler(scheduler);
        if (session == null) return point.proceed();
        Runnable tracked = session.schedulerTask(scheduler, task, dispose);
        try { return point.proceed(new Object[]{tracked}); }
        catch (Throwable rejected) {
            session.rejectSchedulerTask(tracked);
            throw rejected;
        }
    }
    @Around("execution(void org.gms.scripting.event.scheduler.EventScriptScheduler.runBaseSchedule()) && this(scheduler)")
    public Object tick(ProceedingJoinPoint point, Object scheduler) throws Throwable {
        NativeScriptSession session = NativeScriptSession.forScheduler(scheduler);
        if (session == null) return point.proceed();
        final Throwable[] failure = new Throwable[1];
        session.schedulerTick(() -> {
            try { point.proceed(); } catch (Throwable failed) { failure[0] = failed; }
        });
        if (failure[0] != null) throw failure[0];
        return null;
    }
    @Before("execution(org.gms.scripting.event.EventInstanceManager.new(..)) && args(manager,..)")
    public void rejectUnmanagedInstance(Object manager) {
        if (NativeScriptSession.forEventManager(manager) != null) {
            throw new UnsupportedOperationException("native_event_instances_not_supported");
        }
    }
}
