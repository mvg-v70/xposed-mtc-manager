package com.mvgv70.xposed_mtc_manager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public class Radio implements IXposedHookLoadPackage
{
  private static Activity radioActivity;
  private final static String TAG = "xposed-mtc-manager";
  
  @Override
  public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable 
  {
    // RadioActivity.onCreate()
    XC_MethodHook onCreate = new XC_MethodHook() {
	           
      @Override
      protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        radioActivity = (Activity)param.thisObject;
      }
    };

    // AudioManager.setParameters(String)
    XC_MethodHook setParameters = new XC_MethodHook() {
	           
      @Override
      protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    	String value = (String)param.args[0];
    	if (value.startsWith("ctl_radio_frequency=")) 
        {
    	  Intent intent = new Intent("com.android.radio.mcu");
    	  intent.putExtra("command", value);
    	  radioActivity.sendBroadcast(intent);
        }
      }
    };
    
    // start hooks  
    if (!lpparam.packageName.equals("com.microntek.radio")) return;
    Log.d(TAG,"package com.microntek.radio");
    XposedHelpers.findAndHookMethod("com.microntek.radio.RadioActivity", lpparam.classLoader, "onCreate", Bundle.class, onCreate);
    XposedHelpers.findAndHookMethod("android.media.AudioManager", lpparam.classLoader, "setParameters", String.class, setParameters);
    Log.d(TAG,"com.microntek.radio hook OK");
  }
      
};
