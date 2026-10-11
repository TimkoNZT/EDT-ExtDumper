package com.nzt.edt.extdumper;

import org.eclipse.ui.IStartup;

public final class EarlyStartup implements IStartup {

    @Override
    public void earlyStartup() {
        try {
            MassDumpGuard.install();
        } catch (Throwable t) {
            Activator.logError("ExtDumper: не удалось включить подавление массового дампа", t);
        }
    }
}
