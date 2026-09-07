package com.dougkeen.bart.data;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.util.Log;

import com.dougkeen.bart.BartRunnerApplication;
import com.dougkeen.bart.model.StationPair;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
public class FavoritesPersistence {
    private static final String TAG = "FavoritesPersistence";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private BartRunnerApplication app;

    public FavoritesPersistence(Context context) {
        app = (BartRunnerApplication) context.getApplicationContext();
    }

    public void persist(List<StationPair> favorites) {
        try (FileOutputStream outputStream = app.openFileOutput(
                "favorites", Context.MODE_PRIVATE)) {
            objectMapper.writeValue(outputStream, favorites);
        } catch (Exception e) {
            Log.e(TAG, "Could not write favorites file", e);
        }
    }

    public List<StationPair> restore() {
        File favoritesFile = app.getFileStreamPath("favorites");
        if (!favoritesFile.exists()) {
            return new ArrayList<StationPair>();
        }
        try (FileInputStream inputStream = app.openFileInput("favorites")) {
            return objectMapper.readValue(inputStream,
                    new TypeReference<ArrayList<StationPair>>() {
                    });
        } catch (Exception e) {
            Log.e(TAG, "Could not read favorites file", e);
        }

        return new ArrayList<StationPair>();
    }
}
