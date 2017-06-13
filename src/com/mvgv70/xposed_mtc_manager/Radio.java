package com.mvgv70.xposed_mtc_manager;

import com.mvgv70.utils.Utils;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Radio implements IXposedHookLoadPackage
{
  private static final String TAG = "xposed-mtc-manager";
  private static Activity radioActivity;
  public final static String INTENT_MTC_RADIO_MCU = "com.mvgv70.radio.mcu";
  
  public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
  
    XC_MethodHook onCreate = new XC_MethodHook()
    {
      protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable
      {
        Log.d(TAG,"Radio.onCreate");
        radioActivity = (Activity)param.thisObject;
      }
    };
    
    XC_MethodHook setParameters = new XC_MethodHook()
    {
      protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable
      {
        String mcuCommand = (String)param.args[0];
        if (mcuCommand.startsWith("ctl_radio_frequency="))
        {
          Intent intent = new Intent(INTENT_MTC_RADIO_MCU);
          intent.putExtra("command", mcuCommand);
          radioActivity.sendBroadcast(intent);
        }
      }
    };
    if (!lpparam.packageName.equals("com.microntek.radio")) return;
    Log.d(TAG, "package com.microntek.radio");
    Utils.setTag(TAG);
    Utils.readXposedMap();
    Utils.findAndHookMethod("com.microntek.radio.RadioActivity", lpparam.classLoader, "onCreate", Bundle.class, onCreate);
    Utils.findAndHookMethod("android.media.AudioManager", lpparam.classLoader, "setParameters", String.class, setParameters);
    Log.d(TAG, "com.microntek.radio hook OK");
  }
}

