package com.dougkeen.bart.activities;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

/** Creates the routes screen ViewModel with the application graph. */
public final class RoutesViewModelFactory implements ViewModelProvider.Factory {
    private final Application application;

    public RoutesViewModelFactory(Application application) {
        this.application = application;
    }

    @NonNull
    @Override
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(RoutesViewModel.class)) {
            return modelClass.cast(new RoutesViewModel(application));
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass);
    }
}
