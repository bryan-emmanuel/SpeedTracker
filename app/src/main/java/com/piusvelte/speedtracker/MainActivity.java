/**
 Copyright 2015 Bryan Emmanuel <piusvelte@gmail.com>

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package com.piusvelte.speedtracker;

import android.content.Intent;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;

public class MainActivity extends ActionBarActivity implements View.OnClickListener {

    private Button mToggleService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mToggleService = (Button) findViewById(R.id.toggle_service);
        mToggleService.setOnClickListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (TrackerService.isRunning) {
            mToggleService.setText(R.string.stop_service);
        } else {
            mToggleService.setText(R.string.start_service);
        }
    }

    @Override
    public void onClick(View v) {
        Intent serviceIntent = new Intent(this, TrackerService.class);

        if (TrackerService.isRunning) {
            mToggleService.setText(R.string.start_service);
            stopService(serviceIntent);
        } else {
            mToggleService.setText(R.string.stop_service);
            startService(serviceIntent);
        }
    }
}
