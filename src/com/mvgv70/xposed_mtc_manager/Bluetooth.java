package com.mvgv70.xposed_mtc_manager;

import java.io.FileInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class Bluetooth implements IXposedHookLoadPackage {
		
  private final static String TAG = "xposed-mtc-manager";
  private static Properties props = new Properties();
  private final static String EXTERNAL_SD = "/mnt/external_sd";
  private final static String SETTINGS_INI = EXTERNAL_SD + "/mtc-manager/settings.ini";
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
      readSettings();
	 }
  };
			
  @Override
  public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable 
  {    
    // begin hooks
    if (!lpparam.packageName.equals("com.microntek.bluetooth")) return;
    XposedHelpers.findAndHookMethod("com.microntek.bluetooth.BTDevice", lpparam.classLoader, "isOBDDevice", String.class, isOBDDevice);
    XposedHelpers.findAndHookMethod("com.microntek.bluetooth.BTDevice", lpparam.classLoader, "start", start);
    Log.d(TAG,"com.microntek.bluetooth OK");
  }	  
  
  // чтение настроек
  private void readSettings()
  {
    // общие настройки
    try
    {
      Log.d(TAG,"Bluetooth: load from "+SETTINGS_INI);
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
