package com.dougkeen.bart.data;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

/** Creates the route-list ViewModel with the application repository. */
public final class FavoritesViewModelFactory implements ViewModelProvider.Factory {
    private final FavoritesRepository repository;

    public FavoritesViewModelFactory(FavoritesRepository repository) {
        this.repository = repository;
    }

    @NonNull
    @Override
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(FavoritesViewModel.class)) {
            return modelClass.cast(new FavoritesViewModel(repository));
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass);
    }
}
