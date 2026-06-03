package com.nzt.edt.extdumper;

import org.eclipse.jface.dialogs.PopupDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.plugin.AbstractUIPlugin;

public class DumpNotification extends PopupDialog {

    private static final int FADE_STEPS = 20;
    private static final int FADE_INTERVAL = 25;

    private final String filePath;
    private Image icon;
    private Font boldFont;

    public DumpNotification(Shell parentShell, String filePath) {
        super(parentShell, SWT.ON_TOP | SWT.NO_TRIM, false, false, false, false, false, null, null);
        this.filePath = filePath;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Color bg = parent.getDisplay().getSystemColor(SWT.COLOR_INFO_BACKGROUND);
        Color fg = parent.getDisplay().getSystemColor(SWT.COLOR_INFO_FOREGROUND);
        parent.setBackground(bg);
        parent.setForeground(fg);
        parent.setBackgroundMode(SWT.INHERIT_FORCE);

        Composite canvas = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 10;
        layout.marginHeight = 10;
        layout.verticalSpacing = 5;
        canvas.setLayout(layout);

        Label iconLabel = new Label(canvas, SWT.NONE);
        ImageDescriptor desc = AbstractUIPlugin.imageDescriptorFromPlugin(
            Activator.PLUGIN_ID, "icons/info.png");
        if (desc != null) {
            icon = desc.createImage();
            iconLabel.setImage(icon);
        }
        iconLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, true));
        iconLabel.setBackground(bg);

        Composite textContainer = new Composite(canvas, SWT.NONE);
        textContainer.setBackground(bg);
        GridLayout textLayout = new GridLayout(1, false);
        textLayout.marginWidth = 0;
        textLayout.marginHeight = 0;
        textContainer.setLayout(textLayout);
        textContainer.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Label titleLabel = new Label(textContainer, SWT.NONE);
        titleLabel.setText("Файл сохранён");
        FontData[] fontData = titleLabel.getFont().getFontData();
        for (FontData fd : fontData) {
            fd.setStyle(SWT.BOLD);
        }
        boldFont = new Font(titleLabel.getDisplay(), fontData);
        titleLabel.setFont(boldFont);
        titleLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        titleLabel.setBackground(bg);
        titleLabel.setForeground(fg);

        Label pathLabel = new Label(textContainer, SWT.WRAP);
        pathLabel.setText(filePath);
        pathLabel.setToolTipText(filePath);
        pathLabel.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        pathLabel.setBackground(bg);
        pathLabel.setForeground(fg);

        Composite buttonContainer = new Composite(textContainer, SWT.NONE);
        GridLayout buttonLayout = new GridLayout(1, false);
        buttonLayout.marginWidth = 0;
        buttonLayout.marginHeight = 0;
        buttonContainer.setLayout(buttonLayout);
        buttonContainer.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
        buttonContainer.setBackground(bg);

        Button closeButton = new Button(buttonContainer, SWT.PUSH);
        closeButton.setText("Закрыть");
        closeButton.setBackground(bg);
        closeButton.setForeground(fg);
        closeButton.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        closeButton.addListener(SWT.Selection, e -> close());

        return canvas;
    }

    @Override
    protected void initializeBounds() {
        super.initializeBounds();
        int heightDelta = 20;
        int margin = 20;
        int minWidth = 150;
        int minHeight = 80;
        Shell shell = getShell();
        Point preferred = shell.computeSize(-1, -1);
        Rectangle ideBounds = getParentShell().getMonitor().getClientArea();
        int ideWidth = ideBounds.width;
        int ideHeight = ideBounds.height;
        int targetWidth = Math.max(preferred.x + heightDelta, minWidth);
        if (targetWidth > ideWidth - 2 * margin) {
            targetWidth = ideWidth - 2 * margin;
        }
        Point recomputed = shell.computeSize(targetWidth, -1);
        int targetHeight = Math.max(recomputed.y, minHeight);
        if (targetHeight > ideHeight - 2 * margin) {
            targetHeight = ideHeight - 2 * margin;
        }
        shell.setSize(targetWidth, targetHeight);
        int x = ideBounds.x + ideWidth - targetWidth - margin;
        int y = ideBounds.y + ideHeight - targetHeight - margin;
        int minX = ideBounds.x + margin;
        int maxX = ideBounds.x + ideWidth - targetWidth - margin;
        x = Math.min(Math.max(x, minX), maxX);
        int minY = ideBounds.y + margin;
        int maxY = ideBounds.y + ideHeight - targetHeight - margin;
        y = Math.min(Math.max(y, minY), maxY);
        shell.setLocation(x, y);
    }

    @Override
    public int open() {
        int result = super.open();
        Shell shell = getShell();
        Display display = Display.getCurrent();

        shell.setAlpha(0);

        for (int i = 1; i <= FADE_STEPS; i++) {
            int alpha = (int)(255.0 * i / FADE_STEPS);
            display.timerExec(i * FADE_INTERVAL, () -> {
                if (!shell.isDisposed()) shell.setAlpha(alpha);
            });
        }

        display.timerExec(5000, () -> {
            if (!shell.isDisposed()) close();
        });

        return result;
    }

    @Override
    public boolean close() {
        Shell shell = getShell();
        if (shell != null && !shell.isDisposed()) {
            shell.setAlpha(0);
        }
        if (icon != null && !icon.isDisposed()) {
            icon.dispose();
        }
        if (boldFont != null && !boldFont.isDisposed()) {
            boldFont.dispose();
        }
        return super.close();
    }
}

