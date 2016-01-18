package com.mvgv70.xposed_mtc_manager;

import java.io.FileInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import android.os.Bundle;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class Settings implements IXposedHookLoadPackage {
	
  private final static String TAG = "xposed-mtc-manager";
  private static Properties props = new Properties();
  private final static String EXTERNAL_SD = "/mnt/external_sd";
  private final static String SETTINGS_INI = EXTERNAL_SD + "/mtc-manager/settings.ini";
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
      readSettings();
    }
  };
		
  @Override
  public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable 
  {    
    // begin hooks
    if (!lpparam.packageName.equals("com.android.settings")) return;
    XposedHelpers.findAndHookMethod("com.android.settings.MtcBluetoothSettings", lpparam.classLoader, "isOBDDevice", String.class, isOBDDevice);
    XposedHelpers.findAndHookMethod("com.android.settings.MtcBluetoothSettings", lpparam.classLoader, "onCreate", Bundle.class, onCreate);
    Log.d(TAG,"com.android.settings OK");
  }	  

  // чтение настроек
  private void readSettings()
  {
    // общие настройки
    try
    {
      Log.d(TAG,"Settings: load from "+SETTINGS_INI);
      props.load(new FileInputStream(SETTINGS_INI));
      // obd
      obdDevicesName = props.getProperty("obd_device","OBD").toUpperCase(Locale.US);
      if (obdDevicesName.isEmpty()) obdDevicesName = "OBD";
      obdDevicesList = Arrays.asList(obdDevicesName.split("\\s*,\\s*"));
    }
    catch (Exception e)
    {
      Log.e(TAG,e.getMessage());
    }
  }

}
