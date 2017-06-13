package com.mvgv70.xposed_mtc_manager;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.mvgv70.utils.Utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class Bluetooth implements IXposedHookLoadPackage {
		
  private final static String TAG = "xposed-mtc-manager";
  private static Context mContext = null;
  // bluetooth obd-устройство
  private static String obdDevicesName = null;
  private static List<String> obdDevicesList;
  
  // BTDevice.isOBDDevice(String)
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
  
  // BTDevice.start()
  XC_MethodHook start = new XC_MethodHook() {
  
    @Override
    protected void afterHookedMethod(MethodHookParam param) throws Throwable 
    {
      Log.d(TAG,"BTDevice.start");
      mContext = (Context)Utils.getObjectField(param.thisObject,"mContext");
      createReceivers();
      readSettings();
	  }
  };
  
  // BTDevice.stop()
  XC_MethodHook stop = new XC_MethodHook() {
 
    @Override
    protected void afterHookedMethod(MethodHookParam param) throws Throwable 
    {
      Log.d(TAG,"BTDevice.stop");
      mContext.unregisterReceiver(paramsReceiver);
    }
  };
  
  @Override
  public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable 
  {    
    // begin hooks
    if (!lpparam.packageName.equals("com.microntek.bluetooth")) return;
    Utils.setTag(TAG);
    Utils.readXposedMap();
    Utils.findAndHookMethod("com.microntek.bluetooth.BTDevice", lpparam.classLoader, "isOBDDevice", String.class, isOBDDevice);
    Utils.findAndHookMethod("com.microntek.bluetooth.BTDevice", lpparam.classLoader, "start", start);
    Utils.findAndHookMethod("com.microntek.bluetooth.BTDevice", lpparam.classLoader, "stop", stop);
    Log.d(TAG,"com.microntek.bluetooth OK");
  }	  
  
  // создание ресивера
  private static void createReceivers()
  {
    IntentFilter pi = new IntentFilter();
    pi.addAction(Microntek.INTENT_MTC_PARAMS_LIST);
    mContext.registerReceiver(paramsReceiver, pi);
    Log.d(TAG,"Bluetooth: params receivers created");
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
        Log.d(TAG,"Bluetooth: obd_device="+obdDevicesName);
      }
    }
 };

}
