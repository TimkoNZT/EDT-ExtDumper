package com.nzt.edt.extdumper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdapterManager;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalReport;
import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectDumpSupport;

public class GenerateDumpHandler extends AbstractHandler {

    private static final String ERR_NOT_EXTERNAL =
        "Выбранный объект не является внешней обработкой или отчётом.\n"
        + "Генерация .epf/.erf доступна только для ExternalDataProcessor и ExternalReport.";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        EObject object = extractObject(event);
        if (object == null) {
            showError(HandlerUtil.getActiveShell(event), ERR_NOT_EXTERNAL);
            return null;
        }

        IProject project = resolveProject(object);
        if (project == null) {
            showError(HandlerUtil.getActiveShell(event),
                "Не удалось определить проект для выбранного объекта.");
            return null;
        }

        IExternalObjectDumpSupport dump = lookupService(IExternalObjectDumpSupport.class);
        if (dump == null) {
            showError(HandlerUtil.getActiveShell(event),
                "Сервис генерации выгрузок .epf/.erf недоступен.");
            return null;
        }

        IStatus validation = checkDumpPreconditions(project, dump);
        if (!validation.isOK()) {
            showError(HandlerUtil.getActiveShell(event), validation.getMessage());
            return null;
        }

        // Resolve managed EObject from project's external objects
        EObject managed = resolveManagedObject(project, object);
        if (managed != null) {
            object = managed;
        }

        Shell shell = HandlerUtil.getActiveShell(event);
        String name = getObjectName(object);
        scheduleDump(shell, project, object, dump, name);

        return null;
    }

    // ============== Extraction ==============

    private static EObject extractObject(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        if (!(selection instanceof IStructuredSelection ss) || ss.isEmpty())
            return null;
        Object first = ss.getFirstElement();

        if (first instanceof EObject eo)
            return eo;

        if (first instanceof IFile file)
            return resolveEObjectFromFile(file);

        if (first instanceof IFolder folder)
            return resolveEObjectFromFolder(folder);

        return null;
    }

    private static EObject resolveEObjectFromFolder(IFolder folder) {
        IFile mdoFile = folder.getFile(folder.getName() + ".mdo");
        if (!mdoFile.exists())
            return null;
        return resolveEObjectFromFile(mdoFile);
    }

    private static EObject resolveEObjectFromFile(IFile file) {
        if (!"mdo".equals(file.getFileExtension()))
            return null;

        // Try adapter
        IAdapterManager mgr = Platform.getAdapterManager();
        EObject adapted = (EObject) mgr.loadAdapter(file, EObject.class.getName());
        if (adapted instanceof ExternalDataProcessor || adapted instanceof ExternalReport)
            return adapted;

        // Fallback: load from EMF resource
        try {
            URI uri = URI.createPlatformResourceURI(file.getFullPath().toString(), true);
            ResourceSet rs = new ResourceSetImpl();
            Resource resource = rs.getResource(uri, true);
            if (resource == null || resource.getContents().isEmpty())
                return null;
            EObject root = resource.getContents().get(0);
            if (root instanceof ExternalDataProcessor || root instanceof ExternalReport)
                return root;
        } catch (Exception e) {
            // invalid .mdo
        }
        return null;
    }

    private static String getObjectName(EObject object) {
        if (object instanceof ExternalDataProcessor ep) return ep.getName();
        if (object instanceof ExternalReport er) return er.getName();
        return object.toString();
    }

    // ============== Validation ==============

    private static IProject resolveProject(EObject object) {
        IV8ProjectManager mgr = lookupService(IV8ProjectManager.class);
        if (mgr == null) return null;
        IV8Project v8 = mgr.getProject(object);
        if (v8 == null) return null;
        return v8.getProject();
    }

    private static EObject resolveManagedObject(IProject project, EObject rawObject) {
        IV8ProjectManager mgr = lookupService(IV8ProjectManager.class);
        if (mgr == null) return null;
        IV8Project v8 = mgr.getProject(project);
        if (!(v8 instanceof IExternalObjectProject ext)) return null;

        Collection<?> externalObjects = ext.getExternalObjects();
        String rawName = getObjectName(rawObject);
        for (Object obj : externalObjects) {
            if (obj instanceof EObject eo && rawName.equals(getObjectName(eo)))
                return eo;
        }
        return null;
    }

    private static IStatus checkDumpPreconditions(IProject project, IExternalObjectDumpSupport dump) {
        IStatus edt = dump.validateDumpGeneration(project);
        if (!edt.isOK())
            return edt;

        IV8ProjectManager mgr = lookupService(IV8ProjectManager.class);
        if (mgr == null)
            return error("Менеджер проектов недоступен.");

        IV8Project v8 = mgr.getProject(project);
        if (!(v8 instanceof IExternalObjectProject ext))
            return error("Проект не является проектом внешних обработок.");

        if (ext.getParent() == null)
            return error("Для генерации выгрузки необходим родительский проект конфигурации.\n"
                + "Подключите проект внешних обработок к основной конфигурации.");

        return Status.OK_STATUS;
    }

    // ============== Generation ==============

    private static void scheduleDump(Shell shell, IProject project, EObject object,
            IExternalObjectDumpSupport dump, String objectName) {
        Job job = new Job("Генерация .epf/.erf: " + objectName) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                boolean was = dump.isEnabled(project);
                dump.setEnabled(project, true);
                Path backupPath = null;
                try {
                    Path dumpPath = dump.getDump(project, object, false, monitor);
                    if (dumpPath == null) {
                        showErrorAsync(shell, "Не удалось определить путь для сохранения файла.");
                        return Status.CANCEL_STATUS;
                    }

                    if (Files.exists(dumpPath)) {
                        backupPath = dumpPath.resolveSibling(dumpPath.getFileName() + ".bak");
                        Files.move(dumpPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
                    }

                    dump.updateDump(project, object, monitor);
                    dumpPath = dump.getDump(project, object, true, monitor);

                    if (dumpPath != null && Files.exists(dumpPath)) {
                        if (backupPath != null) Files.deleteIfExists(backupPath);
                        showSuccessAsync(shell, dumpPath.toAbsolutePath().toString());
                        return Status.OK_STATUS;
                    }

                    if (backupPath != null && Files.exists(backupPath)) {
                        Path restorePath = dumpPath != null ? dumpPath : backupPath.resolveSibling(backupPath.getFileName().toString().replaceAll("\\.bak$", ""));
                        Files.move(backupPath, restorePath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    showErrorAsync(shell,
                        "Файл не был создан.\n"
                        + (dumpPath != null ? dumpPath.toAbsolutePath() : "путь не определён") + "\n\n"
                        + "Возможные причины:\n"
                        + "- Отсутствует или не настроен родительский проект конфигурации\n"
                        + "- Не найдена информационная база\n"
                        + "- Не найден запущенный 1С:Предприятие (runtime)");
                    return Status.CANCEL_STATUS;

                } catch (CoreException e) {
                    restoreBackup(backupPath);
                    showErrorAsync(shell, "Ошибка генерации: " + e.getMessage());
                    return e.getStatus();
                } catch (java.io.IOException e) {
                    restoreBackup(backupPath);
                    showErrorAsync(shell, "Ошибка при работе с файлом: " + e.getMessage());
                    return Status.CANCEL_STATUS;
                } finally {
                    dump.setEnabled(project, was);
                }
            }

            private void restoreBackup(Path backupPath) {
                if (backupPath == null || !Files.exists(backupPath)) return;
                try {
                    Path original = backupPath.resolveSibling(
                        backupPath.getFileName().toString().replaceAll("\\.bak$", ""));
                    Files.move(backupPath, original, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.io.IOException e) { /* ignore */ }
            }
        };
        job.schedule();
    }

    // ============== UI ==============

    private static void showSuccessAsync(Shell shell, String path) {
        asyncExec(shell, () -> new DumpNotification(shell, path).open());
    }

    private static void showErrorAsync(Shell shell, String message) {
        asyncExec(shell, () -> showError(shell, message));
    }

    private static void showError(Shell shell, String message) {
        if (shell == null || shell.isDisposed()) return;
        MessageDialog.openError(shell, "Ошибка", message);
    }

    private static void asyncExec(Shell shell, Runnable r) {
        Display d = shell.getDisplay();
        if (d != null && !d.isDisposed()) d.asyncExec(r);
    }

    // ============== OSGi ==============

    @SuppressWarnings("unchecked")
    private static <T> T lookupService(Class<T> type) {
        Bundle b = FrameworkUtil.getBundle(type);
        if (b == null) return null;
        BundleContext ctx = b.getBundleContext();
        if (ctx == null) return null;
        ServiceReference<T> ref = ctx.getServiceReference(type);
        if (ref == null) return null;
        T service = ctx.getService(ref);
        ctx.ungetService(ref);
        return service;
    }

    private static IStatus error(String message) {
        return new Status(Status.ERROR, Activator.PLUGIN_ID, message);
    }
}
