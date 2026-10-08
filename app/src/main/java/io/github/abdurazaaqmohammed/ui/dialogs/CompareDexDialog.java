package io.github.abdurazaaqmohammed.ui.dialogs;

import android.app.Activity;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.reandroid.dex.key.TypeKey;
import com.reandroid.dex.model.DexClass;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.github.abdurazaaqmohammed.MPManager.R;
import io.github.abdurazaaqmohammed.utils.CopyUtil;
import io.github.abdurazaaqmohammed.utils.DexCompareUtil;

/**
 * Class-level diff of two dex files, or of the dex files inside two APKs.
 *
 * <p>Both archives are closed as soon as the class list is built. Tapping a modified class
 * therefore re-opens them for the member-level view: holding them open for the lifetime of the
 * dialog would pin two whole APK dex trees in memory for as long as the user leaves it on screen.
 */
public class CompareDexDialog {

    private record Row(String text, String className, DexCompareUtil.Status status) {
        @Override
        public String toString() {
            return text;
        }
    }

    private final Activity context;
    private final File left;
    private final File right;

    public CompareDexDialog(Activity context, File left, File right) {
        this.context = context;
        this.left = left;
        this.right = right;
    }

    public void show() {
        List<Row> rows = new ArrayList<>();
        String failure = null;
        try (DexCompareUtil.Opened a = DexCompareUtil.open(left);
             DexCompareUtil.Opened b = DexCompareUtil.open(right)) {
            List<DexCompareUtil.ClassDiff> diffs = DexCompareUtil.compare(a.directory(), b.directory());
            if (diffs.isEmpty()) {
                rows.add(new Row(context.getString(R.string.no_differences_found), null, null));
            }
            for (DexCompareUtil.ClassDiff diff : diffs) {
                rows.add(new Row("[" + label(diff.status) + "] " + diff.name, diff.name, diff.status));
            }
        } catch (Exception e) {
            failure = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        if (failure != null) {
            new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.compare_dex)
                    .setMessage(failure)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        ListView list = new ListView(context);
        list.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, rows));
        list.setOnItemClickListener((parent, view, position, id) -> {
            Row row = rows.get(position);
            if (row.className == null || row.status != DexCompareUtil.Status.MODIFIED) return;
            showMembers(row.className);
        });
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.compare_dex)
                .setView(list)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showMembers(String className) {
        StringBuilder body = new StringBuilder(className).append('\n');
        try (DexCompareUtil.Opened a = DexCompareUtil.open(left);
             DexCompareUtil.Opened b = DexCompareUtil.open(right)) {
            TypeKey key = TypeKey.of(className);
            DexClass leftClass = key == null ? null : a.directory().getDexClass(key);
            DexClass rightClass = key == null ? null : b.directory().getDexClass(key);
            for (DexCompareUtil.MemberLine line : DexCompareUtil.members(leftClass, rightClass)) {
                body.append('[').append(label(line.status())).append("] ").append(line.text())
                        .append('\n');
            }
        } catch (Exception e) {
            body.append(e.getMessage() == null ? e.toString() : e.getMessage()).append('\n');
        }
        String text = body.toString();

        TextView view = new TextView(context);
        view.setText(text);
        view.setTextIsSelectable(true);
        view.setTextSize(13);
        int pad = Math.round(16 * context.getResources().getDisplayMetrics().density);
        view.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(view);

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.compare_dex)
                .setView(scroll)
                .setNeutralButton(R.string.copy, (d, w) -> CopyUtil.copyToClipboard(context, text))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String label(DexCompareUtil.Status status) {
        if (status == DexCompareUtil.Status.ADDED) return context.getString(R.string.added);
        if (status == DexCompareUtil.Status.REMOVED) return context.getString(R.string.removed);
        return context.getString(R.string.modified);
    }
}