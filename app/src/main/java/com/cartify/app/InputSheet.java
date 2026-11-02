package com.cartify.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

/**
 * A one-field prompt in the app's own glass sheet, used for naming and renaming.
 *
 * Android's AlertDialog would ignore the glass look, and the soft keyboard has to be
 * raised only after the sheet has finished sliding up, otherwise the window resizes
 * mid-animation and the sheet jumps.
 */
public final class InputSheet {

    public interface OnSubmit {
        void onSubmit(String value);
    }

    private InputSheet() {
    }

    public static void show(Activity activity, String title, String hint,
                            String initial, String confirmLabel, OnSubmit onSubmit) {
        if (activity == null || activity.isFinishing()) return;

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_input, null, false);
        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(true);

        TextView head = content.findViewById(R.id.inputTitle);
        TextView sub = content.findViewById(R.id.inputHint);
        EditText field = content.findViewById(R.id.inputField);
        TextView cancel = content.findViewById(R.id.inputCancel);
        TextView confirm = content.findViewById(R.id.inputConfirm);

        head.setText(title == null ? "" : title);
        if (hint == null || hint.trim().isEmpty()) {
            sub.setVisibility(View.GONE);
        } else {
            sub.setText(hint);
        }
        field.setHint("Name");
        if (initial != null && !initial.isEmpty()) {
            field.setText(initial);
            field.setSelection(field.getText().length());
        }
        confirm.setText(confirmLabel == null ? "Save" : confirmLabel);

        final Runnable submit = () -> {
            String value = field.getText().toString().trim();
            if (value.isEmpty()) {
                field.setError("Enter a name");
                return;
            }
            hideKeyboard(activity, field);
            dialog.dismiss();
            if (onSubmit != null) onSubmit.onSubmit(value);
        };

        // Keep the confirm button in sync with the field so it never looks tappable empty.
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                boolean ok = s.toString().trim().length() > 0;
                confirm.setAlpha(ok ? 1f : 0.45f);
                confirm.setEnabled(ok);
            }
        });
        confirm.setAlpha(field.getText().toString().trim().isEmpty() ? 0.45f : 1f);

        confirm.setOnClickListener(v -> submit.run());
        cancel.setOnClickListener(v -> {
            hideKeyboard(activity, field);
            dialog.dismiss();
        });
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit.run();
                return true;
            }
            return false;
        });

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

        float lift = activity.getResources().getDisplayMetrics().density * 90f;
        content.setTranslationY(lift);
        content.setAlpha(0f);
        content.animate().translationY(0f).alpha(1f)
                .setDuration(260)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        // Focus and keyboard come after the slide-in, so the sheet does not jump.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (dialog.isShowing()) {
                field.requestFocus();
                InputMethodManager imm = (InputMethodManager)
                        activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 280);
    }

    private static void hideKeyboard(Activity activity, View view) {
        InputMethodManager imm = (InputMethodManager)
                activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }
}
