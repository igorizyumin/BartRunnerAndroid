package com.dougkeen.bart.controls

import android.content.Context
import android.util.AttributeSet
import android.widget.Checkable
import androidx.appcompat.widget.LinearLayoutCompat

/** A layout that exposes its checked state to a state-list background. */
class CheckableLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayoutCompat(context, attrs, defStyleAttr), Checkable {
    private var checked = false

    override fun isChecked(): Boolean = checked

    override fun setChecked(checked: Boolean) {
        this.checked = checked
        refreshDrawableState()
    }

    override fun toggle() {
        isChecked = !isChecked
    }

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val drawableState = super.onCreateDrawableState(extraSpace + 1)
        if (checked) {
            mergeDrawableStates(drawableState, CHECKED_STATE)
        }
        return drawableState
    }

    private companion object {
        val CHECKED_STATE = intArrayOf(android.R.attr.state_checked)
    }
}
