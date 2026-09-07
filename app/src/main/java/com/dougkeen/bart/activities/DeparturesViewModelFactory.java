package com.dougkeen.bart.activities;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.dougkeen.bart.model.TimeSource;

/** Creates the departures ViewModel with the application's clock seam. */
public final class DeparturesViewModelFactory implements ViewModelProvider.Factory {
    private final TimeSource timeSource;

    public DeparturesViewModelFactory(TimeSource timeSource) {
        this.timeSource = timeSource;
    }

    @NonNull
    @Override
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(DeparturesViewModel.class)) {
            return modelClass.cast(new DeparturesViewModel(timeSource));
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass);
    }
}
