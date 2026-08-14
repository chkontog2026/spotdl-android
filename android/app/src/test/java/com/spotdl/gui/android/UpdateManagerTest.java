package com.spotdl.gui.android;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public final class UpdateManagerTest {
    @Test public void comparesSemanticVersions() {
        assertEquals(1, UpdateManager.compareVersions("1.1.0", "1.0.2"));
        assertEquals(0, UpdateManager.compareVersions("v1.1.0", "1.1"));
        assertEquals(-1, UpdateManager.compareVersions("1.1.9", "1.2.0"));
    }

    @Test public void selectsArm64AndFallsBackToUniversal() throws Exception {
        JSONArray assets = new JSONArray()
                .put(new JSONObject().put("name", "SpotDL_Android_Universal_v1.1.0.apk"))
                .put(new JSONObject().put("name", "SpotDL_Android_ARM64_v1.1.0.apk"));

        assertEquals(
                "SpotDL_Android_ARM64_v1.1.0.apk",
                UpdateManager.selectAsset(assets, List.of("arm64-v8a")).getString("name")
        );
        assertEquals(
                "SpotDL_Android_Universal_v1.1.0.apk",
                UpdateManager.selectAsset(assets, List.of("x86_64")).getString("name")
        );
    }
}
