package com.nzt.edt.extdumper;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;

public final class MassDumpGuard extends JobChangeAdapter {

    private static final String MASS_PATH_MARKER = "ExternalObjectDumpParticipant";

    private static final String DUMP_JOB_CLASS = "com._1c.g5.v8.dt.internal.platform.services.core.dump.ExternalObjectDumpSupport$DumpJob";

    private static final long LOG_QUIET_MS = 10_000L;

    private static volatile MassDumpGuard installed;

    private final Object lock = new Object();
    private Job logJob;
    private Set<String> objects = new HashSet<>();

    private MassDumpGuard() {
    }

    public static synchronized void install() {
        if (installed != null)
            return;
        installed = new MassDumpGuard();
        Job.getJobManager().addJobChangeListener(installed);
        Activator.log("ExtDumper: массовый дамп при синхронизации будет подавляться.");
    }

    public static synchronized void uninstall() {
        if (installed == null)
            return;
        Job.getJobManager().removeJobChangeListener(installed);
        installed.cancelPendingLog();
        installed = null;
    }

    @Override
    public void scheduled(IJobChangeEvent event) {
        try {
            Job job = event.getJob();
            if (job == null)
                return;
            String className = job.getClass().getName();
            if (!DUMP_JOB_CLASS.equals(className))
                return;
            if (!isMassPath())
                return;
            job.cancel();
            synchronized (lock) {
                objects.add(job.getName());
            }
            scheduleLog();
        } catch (Throwable t) {
            Activator.logError("ExtDumper: ошибка в scheduled()", t);
        }
    }

    private static boolean isMassPath() {
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            if (e.getClassName().contains(MASS_PATH_MARKER))
                return true;
        }
        return false;
    }

    private void scheduleLog() {
        synchronized (lock) {
            if (logJob != null)
                logJob.cancel();
            logJob = new Job("ExtDumper: отчёт о подавлении") {
                @Override
                protected IStatus run(IProgressMonitor monitor) {
                    int distinct;
                    synchronized (lock) {
                        distinct = objects.size();
                        objects = new HashSet<>();
                        if (logJob == this)
                            logJob = null;
                    }
                    if (distinct > 0)
                        Activator.log("ExtDumper: подавлено массовых задач дампа: " + distinct + ".");
                    return Status.OK_STATUS;
                }
            };
            logJob.setSystem(true);
            logJob.schedule(LOG_QUIET_MS);
        }
    }

    private void cancelPendingLog() {
        synchronized (lock) {
            if (logJob != null) {
                logJob.cancel();
                logJob = null;
            }
        }
    }
}
