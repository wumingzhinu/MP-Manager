package io.github.abdurazaaqmohammed.ui.dialogs;

import android.app.Activity;
import android.graphics.Typeface;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.Locale;

import io.github.abdurazaaqmohammed.MPManager.R;
import io.github.abdurazaaqmohammed.utils.BinaryCompareUtil;
import io.github.abdurazaaqmohammed.utils.CopyUtil;
import io.github.abdurazaaqmohammed.utils.FileSize;
import io.github.codehasan.colorpicker.extensions.Extensions;

/**
 * Byte-level diff of any two files. Unlike the text comparison this does not care whether the
 * content is decodable, so it also answers "are these two binaries the same?".
 */
public class CompareFileDialog {

    private final Activity context;
    private final File left;
    private final File right;

    public CompareFileDialog(Activity context, File left, File right) {
        this.context = context;
        this.left = left;
        this.right = right;
    }

    public void show() {
        BinaryCompareUtil.Result result;
        String report;
        try {
            result = BinaryCompareUtil.compare(left, right);
            report = describe(result);
        } catch (Exception e) {
            Extensions.showMessage(context, context.getString(R.string.compare_files_failed)
                    + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            return;
        }
        final String text = report;

        TextView view = new TextView(context);
        view.setText(text);
        view.setTextIsSelectable(true);
        view.setTextSize(12);
        view.setTypeface(Typeface.MONOSPACE);
        int pad = Math.round(16 * context.getResources().getDisplayMetrics().density);
        view.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(view);

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.compare_files)
                .setView(scroll)
                .setNeutralButton(R.string.copy,
                        (d, w) -> CopyUtil.copyToClipboard(context, text))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String describe(BinaryCompareUtil.Result result) {
        StringBuilder sb = new StringBuilder();
        sb.append(left.getName()).append('\n');
        sb.append("  ").append(FileSize.getHumanReadableFileSize(result.sizeLeft))
                .append("  (").append(result.sizeLeft).append(" bytes)\n");
        sb.append(right.getName()).append('\n');
        sb.append("  ").append(FileSize.getHumanReadableFileSize(result.sizeRight))
                .append("  (").append(result.sizeRight).append(" bytes)\n\n");

        if (result.identical()) {
            sb.append(context.getString(R.string.no_differences_found)).append('\n');
            return sb.toString();
        }
        sb.append(context.getString(R.string.compare_size_delta))
                .append(' ').append(result.sizeRight - result.sizeLeft).append('\n');
        sb.append(context.getString(R.string.compare_bytes_differing))
                .append(' ').append(result.differingBytes).append('\n');
        if (result.firstDifference >= 0) {
            sb.append(String.format(Locale.ROOT, "%s 0x%X%n",
                    context.getString(R.string.compare_first_difference), result.firstDifference));
        }
        if (!result.runs.isEmpty()) {
            sb.append('\n');
        }
        for (BinaryCompareUtil.Run run : result.runs) {
            sb.append(String.format(Locale.ROOT, "@0x%08X  +%d%n", run.offset, run.length));
            sb.append("  - ").append(run.left).append('\n');
            sb.append("  + ").append(run.right).append('\n');
        }
        if (result.truncated) {
            sb.append('\n').append(context.getString(R.string.compare_truncated)).append('\n');
        }
        return sb.toString();
    }
}