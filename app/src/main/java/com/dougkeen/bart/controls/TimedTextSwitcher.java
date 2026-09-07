package com.dougkeen.bart.controls;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextSwitcher;

public class TimedTextSwitcher extends TextSwitcher {

    public TimedTextSwitcher(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TimedTextSwitcher(Context context) {
        super(context);
    }

    private CharSequence mLastText;

    @Override
    public void setCurrentText(CharSequence text) {
        mLastText = text;
        super.setCurrentText(text);
    }

}
