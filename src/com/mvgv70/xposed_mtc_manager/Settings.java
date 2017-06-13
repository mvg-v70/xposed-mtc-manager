package com.mvgv70.xposed_mtc_manager;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.mvgv70.utils.Utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class Settings implements IXposedHookLoadPackage {
	
  private final static String TAG = "xposed-mtc-manager";
  private static Context mContext = null;
  // bluetooth obd-устройство
  private static String obdDevicesName = null;
  private static List<String> obdDevicesList;

  // MtcBluetoothSettings.isOBDDevice(String)
  XC_MethodReplacement isOBDDevice = new XC_MethodReplacement() {

    @Override
    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable 
    {
      String deviceName = (String)param.args[0];
      if (obdDevicesList == null) return false;
      if (deviceName == null) return false;
      for (String name : obdDevicesList)
        if (deviceName.toUpperCase(Locale.US).contains(name)) return true;
      return false;
    }
  };

  // MtcBluetoothSettings.onCreate(Bundle)
  XC_MethodHook onCreate = new XC_MethodHook() {

    @Override
    protected void afterHookedMethod(MethodHookParam param) throws Throwable 
    {
      Log.d(TAG,"MtcBluetoothSettings.onCreate");
      mContext = (Context)Utils.getObjectField(param.thisObject,"mContext");
      createReceivers();
      readSettings();
    }
  };
  
  // MtcBluetoothSettings.onDestroy()
  XC_MethodHook onDestroy = new XC_MethodHook() {

    @Override
    protected void afterHookedMethod(MethodHookParam param) throws Throwable 
    {
      Log.d(TAG,"MtcBluetoothSettings.onDestroy");
      mContext.unregisterReceiver(paramsReceiver);
    }
  };
		
  @Override
  public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable 
  {    
    // begin hooks
    if (!lpparam.packageName.equals("com.android.settings")) return;
    Utils.setTag(TAG);
    Utils.readXposedMap();
    Utils.findAndHookMethod("com.android.settings.MtcBluetoothSettings", lpparam.classLoader, "isOBDDevice", String.class, isOBDDevice);
    Utils.findAndHookMethod("com.android.settings.MtcBluetoothSettings", lpparam.classLoader, "onCreate", Bundle.class, onCreate);
    Utils.findAndHookMethod("com.android.settings.MtcBluetoothSettings", lpparam.classLoader, "onDestroy", onDestroy);
    Log.d(TAG,"com.android.settings OK");
  }
  
  // создание ресивера
  private static void createReceivers()
  {
    IntentFilter pi = new IntentFilter();
    pi.addAction(Microntek.INTENT_MTC_PARAMS_LIST);
    mContext.registerReceiver(paramsReceiver, pi);
    Log.d(TAG,"Settings: params receivers created");
  }
 
  // чтение настроек
  private void readSettings()
  {
    // пошлем запрос на получение параметров
    Intent intent = new Intent(Microntek.INTENT_MTC_PARAMS_QUERY);
    mContext.sendBroadcast(intent);
  }
  
  // внутренний обработчик получения параметров
  private static BroadcastReceiver paramsReceiver = new BroadcastReceiver()
  {
    public void onReceive(Context context, Intent intent)
    {
      String action = intent.getAction(); 
      if (action.equals(Microntek.INTENT_MTC_PARAMS_LIST))
      { 
        // имя obd-девайса
        obdDevicesName = intent.getStringExtra("obd_device");
        obdDevicesList = Arrays.asList(obdDevicesName.split("\\s*,\\s*"));
        Log.d(TAG,"Settings: obd_device="+obdDevicesName);
      }
    }
 };

}
