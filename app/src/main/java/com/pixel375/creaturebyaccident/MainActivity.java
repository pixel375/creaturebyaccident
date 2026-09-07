package com.pixel375.creaturebyaccident;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Window;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(8, 13, 10));
        window.setNavigationBarColor(Color.rgb(8, 13, 10));
        setContentView(new SimulationView(this));
    }
}
