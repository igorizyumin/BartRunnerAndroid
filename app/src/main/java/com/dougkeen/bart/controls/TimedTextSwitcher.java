package com.dougkeen.bart.controls;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.TextSwitcher;

import com.dougkeen.bart.R;
import com.dougkeen.bart.model.TextProvider;

public class TimedTextSwitcher extends TextSwitcher implements Ticker.TickSubscriber {

    public TimedTextSwitcher(Context context, AttributeSet attrs) {
        super(context, attrs);
        setInstanceVarsFromAttrs(attrs);
    }

    public TimedTextSwitcher(Context context) {
        super(context);
    }

    private void setInstanceVarsFromAttrs(AttributeSet attrs) {
        TypedArray typedArray = getContext().getTheme()
                .obtainStyledAttributes(attrs, R.styleable.TimedTextSwitcher, 0, 0);
        int tickInterval = typedArray.getInteger(R.styleable.TimedTextSwitcher_tickInterval, 0);
        if (tickInterval > 0) {
            setTickInterval(tickInterval);
        }
    }

    private int mTickInterval;
    private TextProvider mTextProvider;

    @Override
    public int getTickInterval() {
        return mTickInterval;
    }

    public void setTickInterval(int tickInterval) {
        this.mTickInterval = tickInterval;
    }

    public void setTextProvider(TextProvider textProvider) {
        mTextProvider = textProvider;
        if (textProvider != null) {
            Ticker.getInstance().addSubscriber(this, getContext());
        } else {
            Ticker.getInstance().removeSubscriber(this);
        }
    }

    private CharSequence mLastText;

    @Override
    public void setCurrentText(CharSequence text) {
        mLastText = text;
        super.setCurrentText(text);
    }

    @Override
    public void onTick(long tickNumber) {
        if (mTextProvider == null) {
            return;
        }
        String text = mTextProvider.getText(tickNumber);
        if (text != null && !text.trim().isEmpty()
                && (mLastText == null || !text.equalsIgnoreCase(mLastText.toString()))) {
            mLastText = text;
            setText(text);
        }
    }

}
