package com.nzt.edt.extdumper;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public class Activator extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "com.nzt.edt.extdumper";

    private static Activator plugin;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        plugin = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return plugin;
    }

    public static void log(String message) {
        ILog log = plugin != null ? plugin.getLog() : null;
        if (log != null) {
            log.log(new Status(Status.INFO, PLUGIN_ID, message));
        } else {
            System.out.println("[" + PLUGIN_ID + "] " + message);
        }
    }

    public static void logWarning(String message) {
        ILog log = plugin != null ? plugin.getLog() : null;
        if (log != null) {
            log.log(new Status(Status.WARNING, PLUGIN_ID, message));
        } else {
            System.err.println("[" + PLUGIN_ID + "] WARN: " + message);
        }
    }

    public static void logError(String message, Throwable t) {
        ILog log = plugin != null ? plugin.getLog() : null;
        if (log != null) {
            log.log(new Status(Status.ERROR, PLUGIN_ID, message, t));
        } else {
            System.err.println("[" + PLUGIN_ID + "] ERROR: " + message);
            if (t != null) t.printStackTrace();
        }
    }
}
