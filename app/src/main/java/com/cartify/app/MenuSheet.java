package com.cartify.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Bottom action sheet used by every "three dots" menu in Cartify.
 *
 * A plain AlertDialog/PopupMenu ignores the app's glass look, so menus are built from
 * the same deep green panel, mint icons and rounded corners as the rest of the UI, and
 * they rise out of the bottom edge instead of popping in.
 */
public final class MenuSheet {

    /** One row: an icon, a label and what to run once the sheet closes. */
    public static final class Item {
        final int icon;
        final String label;
        final boolean danger;
        final Runnable action;

        public Item(int icon, String label, Runnable action) {
            this(icon, label, false, action);
        }

        public Item(int icon, String label, boolean danger, Runnable action) {
            this.icon = icon;
            this.label = label;
            this.danger = danger;
            this.action = action;
        }
    }

    private MenuSheet() {
    }

    public static void show(Activity activity, String title, String subtitle, Item... items) {
        if (activity == null || activity.isFinishing() || items == null || items.length == 0) return;

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_menu, null, false);
        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(true);

        TextView head = content.findViewById(R.id.menuTitle);
        TextView sub = content.findViewById(R.id.menuSubtitle);
        head.setText(title == null ? "" : title);
        if (subtitle == null || subtitle.trim().isEmpty()) {
            sub.setVisibility(View.GONE);
        } else {
            sub.setText(subtitle);
        }

        LinearLayout rows = content.findViewById(R.id.menuItems);
        for (final Item item : items) {
            View row = LayoutInflater.from(activity).inflate(R.layout.item_menu_row, rows, false);
            ImageView icon = row.findViewById(R.id.menuIcon);
            TextView label = row.findViewById(R.id.menuLabel);
            icon.setImageResource(item.icon);
            icon.setImageTintList(ColorStateList.valueOf(
                    activity.getColor(item.danger ? R.color.danger : R.color.accent)));
            label.setText(item.label);
            label.setTextColor(activity.getColor(
                    item.danger ? R.color.danger : R.color.text_primary));
            row.setOnClickListener(v -> {
                dialog.dismiss();
                if (item.action != null) item.action.run();
            });
            rows.addView(row);
        }

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.BOTTOM;
            lp.dimAmount = 0.62f;
            window.setAttributes(lp);
        }
        dialog.show();

        // Rise out of the bottom edge and settle, so the sheet feels attached to the finger.
        float lift = activity.getResources().getDisplayMetrics().density * 90f;
        content.setTranslationY(lift);
        content.setAlpha(0f);
        content.animate().translationY(0f).alpha(1f)
                .setDuration(260)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }
}
