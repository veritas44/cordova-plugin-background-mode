/*
    Copyright 2013-2017 appPlant GmbH

    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */

package de.appplant.cordova.plugin.background;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import android.view.View;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.List;

public class BackgroundMode extends CordovaPlugin {

    // Event types for callbacks
    private enum Event {
        ACTIVATE, DEACTIVATE, FAILURE
    }

    // Plugin namespace
    private static final String JS_NAMESPACE =
            "cordova.plugins.backgroundMode";

    // Flag indicates if the app is in background or foreground
    private boolean inBackground = false;

    // Flag indicates if the plugin is enabled or disabled
    private boolean isDisabled = true;

    // Flag indicates if the service is bind
    private boolean isBind = false;

    // Default settings for the notification
    private static JSONObject defaultSettings = new JSONObject();

    // Service that keeps the app awake
    private ForegroundService service;

    // Used to (un)bind the service to with the activity
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ForegroundService.ForegroundBinder binder =
                    (ForegroundService.ForegroundBinder) service;

            BackgroundMode.this.service = binder.getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            fireEvent(Event.FAILURE, "service disconnected");
        }
    };

    /**
     * Executes the request.
     *
     * @param action   The action to execute.
     * @param args     The exec() arguments.
     * @param callback The callback context used when
     *                 calling back into JavaScript.
     *
     * @return Returning false results in a "MethodNotFound" error.
     *
     * @throws JSONException
     */
    @Override
    public boolean execute (String action, JSONArray args,
                            CallbackContext callback) throws JSONException {

        if (action.equalsIgnoreCase("configure")) {
            JSONObject settings = args.getJSONObject(0);
            boolean update      = args.getBoolean(1);

            configure(settings, update);
        }

        if (action.equalsIgnoreCase("disableWebViewOptimizations")) {
            disableWebViewOptimizations();
        }

        if (action.equalsIgnoreCase("background")) {
            moveToBackground();
        }

        if (action.equalsIgnoreCase("foreground")) {
            moveToForeground();
        }

        if (action.equalsIgnoreCase("tasklist")) {
            excludeFromTaskList();
        }

        if (action.equalsIgnoreCase("enable")) {
            enableMode();
        }

        if (action.equalsIgnoreCase("disable")) {
            disableMode();
        }

        callback.success();

        return true;
    }

    /**
     * Called when the system is about to start resuming a previous activity.
     *
     * @param multitasking Flag indicating if multitasking is turned on for app.
     */
    @Override
    public void onPause(boolean multitasking) {
        super.onPause(multitasking);
        inBackground = true;
        startService();
    }

    /**
     * Called when the activity will start interacting with the user.
     *
     * @param multitasking Flag indicating if multitasking is turned on for app.
     */
    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        inBackground = false;
        stopService();
    }

    /**
     * Called when the activity will be destroyed.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        stopService();
    }

    /**
     * Move app to background.
     */
    private void moveToBackground() {
        Intent intent = new Intent(Intent.ACTION_MAIN);

        intent.addCategory(Intent.CATEGORY_HOME);
        cordova.getActivity().startActivity(intent);
    }

    /**
     * Move app to foreground.
     */
    private void moveToForeground() {
        Context context = cordova.getActivity();
        String pkgName  = context.getPackageName();

        Intent intent = context
                .getPackageManager()
                .getLaunchIntentForPackage(pkgName);

        intent.addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        context.startActivity(intent);
    }

    /**
     * Enable the background mode.
     */
    private void enableMode() {
        isDisabled = false;

        if (inBackground) {
            startService();
        }
    }

    /**
     * Disable the background mode.
     */
    private void disableMode() {
        stopService();
        isDisabled = true;
    }

    /**
     * Update the default settings and configure the notification.
     *
     * @param settings The settings
     * @param update A truthy value means to update the running service.
     */
    private void configure(JSONObject settings, boolean update) {
        if (update) {
            updateNotification(settings);
        } else {
            setDefaultSettings(settings);
        }
    }

    /**
     * Update the default settings for the notification.
     *
     * @param settings The new default settings
     */
    private void setDefaultSettings(JSONObject settings) {
        defaultSettings = settings;
    }

    /**
     * The settings for the new/updated notification.
     *
     * @return
     *      updateSettings if set or default settings
     */
    protected static JSONObject getSettings() {
        return defaultSettings;
    }

    /**
     * Update the notification.
     *
     * @param settings The config settings
     */
    private void updateNotification(JSONObject settings) {
        if (isBind) {
            service.updateNotification(settings);
        }
    }

    /**
     * Bind the activity to a background service and put them into foreground
     * state.
     */
    private void startService() {
        Activity context = cordova.getActivity();

        if (isDisabled || isBind)
            return;

        Intent intent = new Intent(
                context, ForegroundService.class);

        try {
            context.bindService(intent,
                    connection, Context.BIND_AUTO_CREATE);

            fireEvent(Event.ACTIVATE, null);

            context.startService(intent);
        } catch (Exception e) {
            fireEvent(Event.FAILURE, e.getMessage());
        }

        isBind = true;
    }

    /**
     * Bind the activity to a background service and put them into foreground
     * state.
     */
    private void stopService() {
        Activity context = cordova.getActivity();

        Intent intent = new Intent(
                context, ForegroundService.class);

        if (!isBind)
            return;

        fireEvent(Event.DEACTIVATE, null);

        context.unbindService(connection);
        context.stopService(intent);

        isBind = false;
    }

    /**
     * Enable GPS position tracking while in background.
     */
    private void disableWebViewOptimizations() {
        Thread thread = new Thread(){
            public void run() {
                try {
                    Thread.sleep(1000);
                    cordova.getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            View view = webView.getEngine().getView();

                            try {
                                Class<?> xWalkCls = Class.forName(
                                        "org.crosswalk.engine.XWalkCordovaView");

                                Method onShowMethod =
                                        xWalkCls.getMethod("onShow");

                                onShowMethod.invoke(view);
                            } catch (Exception e){
                                view.dispatchWindowVisibilityChanged(View.VISIBLE);
                            }
                        }
                    });
                } catch (InterruptedException e) {
                    // do nothing
                }
            }
        };

        thread.start();
    }

    /**
     * Exclude the app from the recent tasks list.
     */
    private void excludeFromTaskList() {
        ActivityManager am = (ActivityManager) cordova.getActivity()
                .getSystemService(Context.ACTIVITY_SERVICE);

        if (am == null || Build.VERSION.SDK_INT < 21)
            return;

        try {
            Method getAppTasks = am.getClass().getMethod("getAppTasks");
            List tasks = (List) getAppTasks.invoke(am);

            if (tasks == null || tasks.isEmpty())
                return;

            ActivityManager.AppTask task = (ActivityManager.AppTask) tasks.get(0);
            Method setExcludeFromRecents = task.getClass()
                    .getMethod("setExcludeFromRecents", boolean.class);

            setExcludeFromRecents.invoke(task, true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Fire vent with some parameters inside the web view.
     *
     * @param event The name of the event
     * @param params Optional arguments for the event
     */
    private void fireEvent (Event event, String params) {
        String eventName;

        switch (event) {
            case ACTIVATE:
                eventName = "activate"; break;
            case DEACTIVATE:
                eventName = "deactivate"; break;
            default:
                eventName = "failure";
        }

        String active = event == Event.ACTIVATE ? "true" : "false";

        String flag = String.format("%s._isActive=%s;",
                JS_NAMESPACE, active);

        String depFn = String.format("%s.on%s(%s);",
                JS_NAMESPACE, eventName, params);

        String fn = String.format("%s.fireEvent('%s',%s);",
                JS_NAMESPACE, eventName, params);

        final String js = flag + fn + depFn;

        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.loadUrl("javascript:" + js);
            }
        });
    }

}
