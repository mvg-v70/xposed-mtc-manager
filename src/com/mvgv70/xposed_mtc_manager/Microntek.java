package com.mvgv70.xposed_mtc_manager;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import com.mvgv70.utils.IniFile;
import com.mvgv70.utils.Utils;

import android.app.ActivityManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class Microntek implements IXposedHookLoadPackage {
	
  private final static String TAG = "xposed-mtc-manager";
  private static Properties props = new Properties();
  private static String EXTERNAL_SD = "/mnt/external_sd";
  private final static String SETTINGS_INI = "mtc-manager/settings.ini";
  private final static String WHITELIST_INI = "mtc-manager/whitelist.ini";
  private final static String START_SERVICES_INI = "mtc-manager/start_services.ini";
  private final static String SS_EXCEPTIONS_INI = "mtc-manager/ss_exceptions.ini";
  private final static String MODE_INI = "mtc-manager/mode.ini";
  public final static String INTENT_MTC_PARAMS_QUERY = "com.mvgv70.manager.params_query";
  public final static String INTENT_MTC_PARAMS_LIST = "com.mvgv70.manager.params_list";
  public final static String INTENT_MTC_RADIO_MCU = "com.mvgv70.radio.mcu";
  //
  private final static int MCU_GO_SLEEP = 240;
  private final static int MCU_WAKE_UP = 241;
  private final static int MCU_ILLUMINATION = 147;
  // private final static int MCU_READY = 135;
  // private final static int MCU_KEY_DOWN = 142;
  // private final static int MCU_KEY_UP = 143;
  // private final static int MCU_BACK_VIEW = 158;
  // private final static int MCU_RDS = 162;
  // private final static int MCU_TOUCH = 163;
  private static int shutdownDelay = 5;
  private static Service microntekServer = null;
  private static AudioManager am;
  // задержка выключения запущена
  private static volatile boolean isShutdown = false;
  // процедура выключения выполнена
  private static volatile boolean didShutdown = false;
  // запущено ли радио
  private static boolean xposedMCU = true;
  private static boolean isRadioRunning;
  private static Thread do_shutdown;
  private static Thread start_services;
  private static String radioFreq = "";
  // список исключений для таск-киллера
  private static Set<String> white_list = null;
  // настройки скринсейвера
  private static boolean screenSaverEnable = false;
  private static String screenSaverPackage;
  private static String screenSaverClass;
  private static int screenSaverTimeOut = 0;
  private static Set<String> ss_exceptions = null;
  // bluetooth obd-устройство
  private static String obdDevicesName = "";
  private static List<String> obdDevicesList = null;
  // modeSwitch
  private static boolean modeSwitch = false;
  private final static String APPS_SECTION = "apps";
  private static IniFile mode_props = new IniFile();
  private static List<String> mode_app_list = new ArrayList<String>();
  private static String mode_app = "";
  private static int mode_index = -1;
  private static boolean gps_isfront = false;
  // clear last app
  private static boolean clear_last_app = false;
  // синхронизация времени по gps
  private static boolean sync_gps_time = false;
  // включение wi-fi
  private static boolean wifi_on = false;
	
  @Override
  public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
	    
    // MicrontekServer.onCreate()
    XC_MethodHook onCreate = new XC_MethodHook() {
      
      @Override
      protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        Log.d(TAG,"onCreate");
        microntekServer = (Service)param.thisObject;
        am = ((AudioManager)microntekServer.getSystemService(Context.AUDIO_SERVICE));
        // показать версию модуля
        try 
        {
          Context context = microntekServer.createPackageContext(getClass().getPackage().getName(), Context.CONTEXT_IGNORE_SECURITY);
          String version = context.getString(R.string.app_version_name);
          Log.d(TAG,"version="+version);
          Log.d(TAG,"android "+Build.VERSION.RELEASE);
        } catch (Exception e) {}
        // показать версию mcu
        Log.d(TAG,am.getParameters("sta_mcu_version="));
        // переключение приложений
        IntentFilter ai = new IntentFilter();
        ai.addAction("com.microntek.canbusdisplay");
        microntekServer.registerReceiver(appsReceiver, ai);
        // изменение внутренней переменной для mtc-volume
        IntentFilter vi = new IntentFilter();
        vi.addAction("com.microntek.VOLUME_CHANGED");
        microntekServer.registerReceiver(volumeReceiver, vi);
        Log.d(TAG,"Volume change receiver created");
        // путь к файлу из build.prop
        EXTERNAL_SD = Utils.getModuleSdCard();
        Log.d(TAG,EXTERNAL_SD+" "+Environment.getStorageState(new File(EXTERNAL_SD)));
        // чтение настроечного файла
      	if (Environment.getStorageState(new File(EXTERNAL_SD)).equals(Environment.MEDIA_MOUNTED))
      	{
      	  // чтение настроек
          readSettings();
          // создаем внутренние ресиверы
          createInternalReceivers();
      	  // параметры скринсейвера
          processScreenSaver();
      	  // быстрый запуск сервисов в отдельном потоке
          startServiceThread();
          // убираем признак последнего запущеннного приложения
          clearLastApp();
          // определяем текущую позицию
          createLocationListener();
        }
        else
          // все сделаем при подключении external_sd
          createMediaReceiver();
      }
    };
	    
    // MicrontekServer.cmdProc(byte[], int, int)
    XC_MethodHook cmdProc = new XC_MethodHook() {
      
      @Override
      protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        // параметры
        if (!xposedMCU) return;
        byte[] paramArray = (byte[])param.args[0];
        int index = (int)param.args[1];
        @SuppressWarnings("unused")
        int extra = (int)param.args[2];
        // непонятно зачем это
        int val = 0xFF & paramArray[(index+1)];
        if ((val != 151) && (val != 163)) Log.d(TAG,"cmd="+val);
        // обработка
        if (val == MCU_GO_SLEEP)
        {
          Log.d(TAG,"MCU_GO_SLEEP");
          PowerOff();
          // не вызываем штатный обработчик
          param.setResult(null);
        }
        else if (val == MCU_WAKE_UP)
        {
          Log.d(TAG,"MCU_WAKE_UP");
          if (PowerOn())
          {
            Log.d(TAG,"do not call PowerOn");
            // прервали thread, штатный обработчик не вызываем
            param.setResult(null);
          }
          else
          {
            // вызываем штатный обработчик, если процесс завершения уже выполнен
            Log.d(TAG,"call powerOn");
            // сброс таймера скринсейвера
            Utils.setIntField(microntekServer, "ScreenSaverTimer", 0);
            // закрываем скринсейвер
            microntekServer.sendBroadcast(new Intent("com.microntek.endclock"));
          }
        }
        else if (val == MCU_ILLUMINATION)
        {
          Log.d(TAG,"MCU_ILLUMINATION");
          // уведомляем сервис mtc-volume и включении/выключении габаритов
          Intent intent = new Intent("com.mvg_v70.brightness");
          microntekServer.sendBroadcast(intent);
        }
      }
    };
    
    // MicrontekServer.ModeSwitch()
    XC_MethodHook ModeSwitch = new XC_MethodHook() {
      
      @Override
      protected void beforeHookedMethod(MethodHookParam param) throws Throwable 
      {
        Log.d(TAG,"ModeSwitch");
        if (modeSwitch)
        {
          modeSwitch(microntekServer);
          // не вызываем штатный обработчик
          param.setResult(null);
        }
      }
    };
	    
    // ClearProcess.getisdontclose(String)
    XC_MethodHook getisdontclose = new XC_MethodHook() {
      
      @Override
      protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        String className = (String)param.args[0];
        if ((boolean)param.getResult() == false)
        {
          if (white_list == null) return;
          // если microntek собирается закрыть программу или сервис
          for (String pkg_name : white_list)
          if (className.startsWith(pkg_name))
          {
            Log.d(TAG,className+" not closed");
            param.setResult(true);
            break;
          }
        }
      }
    };
	    
    // BT*Model.isOBDDevice(String)
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
    
    // begin hooks
    if (!lpparam.packageName.equals("android.microntek.service")) return;
    Log.d(TAG,"android.microntek.service");
    Utils.setTag(TAG);
    Utils.readXposedMap();
    Utils.findAndHookMethod("android.microntek.service.MicrontekServer", lpparam.classLoader, "cmdProc", byte[].class, int.class, int.class, cmdProc);
    Utils.findAndHookMethod("android.microntek.service.MicrontekServer", lpparam.classLoader, "onCreate", onCreate);
    Utils.findAndHookMethod("android.microntek.service.MicrontekServer", lpparam.classLoader, "ModeSwitch", ModeSwitch);
    Utils.findAndHookMethod("android.microntek.ClearProcess", lpparam.classLoader, "getisdontclose", String.class, getisdontclose);
    Utils.findAndHookMethod("android.microntek.ClearProcess", lpparam.classLoader, "getisdontclose2", String.class, getisdontclose);
    try
    {
      Utils.findAndHookMethod("android.microntek.mtcser.model.BC5Model", lpparam.classLoader, "isOBDDevice", String.class, isOBDDevice);
    } catch (Error e) {}
    try
    {
      Utils.findAndHookMethod("android.microntek.mtcser.model.BC6Model", lpparam.classLoader, "isOBDDevice", String.class, isOBDDevice);
    }
    catch (Error e) {}
    Log.d(TAG,"android.microntek.service hook OK");
  }
  
  // чтение настроек
  private void readSettings()
  {
    // белый список
    try
    {
      Log.d(TAG,"Main: load from "+EXTERNAL_SD+WHITELIST_INI);
      props.load(new FileInputStream(EXTERNAL_SD+WHITELIST_INI));
      white_list = props.stringPropertyNames();
      Log.d(TAG,"load white_list: count="+white_list.size());
      props.clear();
    }
    catch (Exception e)
    {
      Log.e(TAG,e.getMessage());
    }
    // исключения скринсейвера
    try
    {
      Log.d(TAG,"load screensaver exception from "+EXTERNAL_SD+SS_EXCEPTIONS_INI);
      props.load(new FileInputStream(EXTERNAL_SD+SS_EXCEPTIONS_INI));
      ss_exceptions = props.stringPropertyNames();
      Log.d(TAG,"load ss exceptions: count="+ss_exceptions.size());
      props.clear();
    }
    catch (Exception e)
    {
      Log.e(TAG,e.getMessage());
    }
    // общие настройки
    try
    {
      String value;
      Log.d(TAG,"load settings from "+EXTERNAL_SD+SETTINGS_INI);
      props.load(new FileInputStream(EXTERNAL_SD+SETTINGS_INI));
      // screenClock
      value = props.getProperty("screenClock","false");
      if (value.equals("true"))
      {
        // включаем альтернативный скринсейвер
        screenSaverEnable = true;
        Utils.setBooleanField(microntekServer, "ScreenSaverEnable", true);
        // включаем "альтернативный" скринсейвер
        Utils.setBooleanField(microntekServer, "ScreenSaverEnableLocal", false);
        // screenTimeout
        value = props.getProperty("screenTimeout");
        try
        {
          screenSaverTimeOut = Integer.valueOf(value);
          // сохраняем значение
          Utils.setIntField(microntekServer, "ScreenSaverTimeOut", screenSaverTimeOut);
        } catch (Exception e) 
        {
          // берем значение из настроек
          screenSaverTimeOut = Utils.getIntField(microntekServer, "ScreenSaverTimeOut");
        }
        Log.d(TAG,"ScreenSaverEnable="+screenSaverEnable);
        Log.d(TAG,"ScreenSaverTimeOut="+screenSaverTimeOut);
        // screenPackage & screenClass
        screenSaverPackage = props.getProperty("screenPackage","");
        screenSaverClass = props.getProperty("screenClass","");
        Log.d(TAG,"ScreenSaverPackage="+screenSaverPackage);
        Log.d(TAG,"ScreenSaverClass="+screenSaverClass);
      }
      // mcu
      xposedMCU = props.getProperty("mcu_power","true").equals("true");
      Log.d(TAG,"mcu_power="+xposedMCU);
      // obd
      obdDevicesName = props.getProperty("obd_device","OBD").toUpperCase(Locale.US);
      Log.d(TAG,"obd_device="+obdDevicesName);
      // modeSwitch
      modeSwitch = props.getProperty("modeSwitch","false").equals("true");
      Log.d(TAG,"modeSwitch="+modeSwitch);
      // clear last app
      clear_last_app = props.getProperty("clear_last_app","false").equals("true");
      Log.d(TAG,"clear_last_app="+clear_last_app);
      // sync_gps_time
      sync_gps_time = props.getProperty("sync_gps_time","false").equals("true");
      Log.d(TAG,"sync_gps_time="+sync_gps_time);
      // wifi_on
      wifi_on = props.getProperty("wifi.on","false").equals("true");
      Log.d(TAG,"wifi.on="+wifi_on);
      // OK
    }
    catch (Exception e)
    {
      Log.e(TAG,e.getMessage());
    }
    // obd
    if (obdDevicesName.isEmpty()) obdDevicesName = "OBD";
    obdDevicesList = Arrays.asList(obdDevicesName.split("\\s*,\\s*"));
    // mode.ini
    mode_app_list.clear();
    if (modeSwitch)
    {
      mode_props.clear();
      try
      {
        Log.d(TAG,"mode from "+EXTERNAL_SD+MODE_INI);
        mode_props.loadFromFile(EXTERNAL_SD+MODE_INI);
        mode_app_list = mode_props.getLines(APPS_SECTION);
        Log.d(TAG,"mode app count "+mode_app_list.size());
      }
      catch (Exception e)
      {
        Log.e(TAG,e.getMessage());
      }
    }
  }
  
  // настройка скринсейвера
  private void processScreenSaver()
  {
    // параметры скринсейвера
    if (screenSaverEnable)
    {
      // интент включения скринсейвера
      IntentFilter si = new IntentFilter();
      si.addAction("com.microntek.screensaver");
      microntekServer.registerReceiver(screenSaverReceiver, si);
      Log.d(TAG,"screensaver receiver created");
      // интент выключения скринсейвера
      IntentFilter ei = new IntentFilter();
      ei.addAction("com.microntek.endclock");
      ei.addAction("com.microntek.musicclockreset");
      microntekServer.registerReceiver(endClockReceiver, ei);
      Log.d(TAG,"end clock receiver created");
    }
  }

  private void startServiceThread()
  {
    // запуск сервисов в отдельном потоке
    start_services = new Thread("start_service")
    {
      public void run()
      {
        startServices();
      }
    };
    //
    Log.d(TAG,"start services thread");
    start_services.run();
  }
  
  // запуск сервисов и включение wi-fi
  private void startServices()
  {
    if (wifi_on)
    {
      // включение wi-fi
      try
      {
        WifiManager Wifi = (WifiManager)microntekServer.getSystemService(Context.WIFI_SERVICE);
        Log.d(TAG,"wifi state="+Wifi.getWifiState());
        Wifi.setWifiEnabled(true);
        Log.d(TAG,"wifi set on");
      }
      catch (Exception e)
      {
        Log.e(TAG,e.getMessage());
      }
    }
    // читаем файл start_services.ini
    String line;
    try
    {
      Log.d(TAG,"load services list from: "+EXTERNAL_SD+START_SERVICES_INI);
      BufferedReader br = new BufferedReader(new FileReader(EXTERNAL_SD+START_SERVICES_INI));
      try
      {
        while ((line = br.readLine()) != null)
        {
          if (line.isEmpty()) continue;
          if (line.startsWith("#")) continue;
          if (line.startsWith(";")) continue;
          int pos = line.indexOf("/");
          if (pos >= 0)
          {
            // разбираем формат имя пакета/имя сервиса
            String packageName = line.substring(0,pos);
            String className = line.substring(pos+1,line.length());
            Log.d(TAG,"start service "+packageName+"/"+className);
            ComponentName cn = new ComponentName(packageName,className);
            Intent intent = new Intent();
            intent.setComponent(cn);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            try
            {
              // пытаемся стартовать сервис
              ComponentName cr = microntekServer.startService(intent);
              if (cr == null) Log.w(TAG,"service "+line+" not found");
            }
            catch (Exception e)
            {  	      
              Log.e(TAG,e.getMessage());
            }
          }
          else
            Log.w(TAG,"incorrect service declaration: "+line);
        }
      }
      finally
      {
        br.close();
      }
    }
    catch (Exception e)
    {
      Log.e(TAG,e.getMessage());
    }
  }
  	
  // выключение
  private void PowerOff()
  {
    if (isShutdown)
    {
      // защита от повторного вызова
      Log.d(TAG,"shutdown process already running");
      return;
    }
    isShutdown = true;
    didShutdown = false;
    //
    do_shutdown = new Thread("delay_shutdown")
    {
      public void run()
      {
        Log.d(TAG,"shutdown thread running, sleep "+shutdownDelay);
        // clear last app
        String lastApps;
        if (clear_last_app)
          lastApps = "null,null,null";
        else
          lastApps = getLastPackages();
        // устанавливаем список запущенных приложений
        Log.d(TAG,"microntek.lastpackname=>"+lastApps);
        Settings.System.putString(microntekServer.getContentResolver(),"microntek.lastpackname",lastApps);
        try 
        {
          sleep(shutdownDelay*1000);
        } 
        catch (InterruptedException e) 
        { 
          Log.d(TAG,"thread interrupted"); 
        }
        Log.d(TAG,"shutdown thread ending");
        // если thread не был прерван в PowerOn()
        if (isShutdown)
        {
          // вызываем оригинальный метод powerOff()
          didShutdown = true;
          Log.d(TAG,"do shutdown: call powerOff");
          Utils.callMethod(microntekServer, "powerOff");
          Log.d(TAG,"powerOff called");
          am.setParameters("rpt_power=false");
        }
        else
        {
          Log.d(TAG,"shutdown process interrupted");
        }
      }
    };
    Log.d(TAG,"isRadioRunning="+isRadioRunning);
    // иначе не получим MCU_WAKE_UP
    am.setParameters("rpt_power=false");
    Log.d(TAG,"starting shutdown delay thread");
    do_shutdown.start();
  }
	  
  // включение
  private boolean PowerOn()
  {
    isShutdown = false;
    if (!didShutdown)
    {
      Log.d(TAG,"interrupt shutdown");
      do_shutdown.interrupt();
      am.setParameters("rpt_power=true");
      do_shutdown = null;
      // включаем радио, если оно было включено
      if (isRadioRunning)
      {
        am.setParameters("av_channel_enter=fm");
        if (!radioFreq.isEmpty())
        {
          // устанавливаем последнюю частоту Радио
          am.setParameters(radioFreq);
          Log.d(TAG,"setParameters "+radioFreq);
        }
        am.setParameters("ctl_radio_mute=false");
      }
      // не вызываем штатный обработчик
      return true;
    }
    else
      // завершение работы уже выполнено
      return false;
  }
  
  // три запущенных приложения через запятую 
  private String getLastPackages()
  {
    String result;
    String travelPkg = null;
    String mtcPackage = null;
    String topPackage = null;
    try
    {
      int i = 0;
      String name;
      Iterator<ActivityManager.RunningTaskInfo> iterator = ((ActivityManager)microntekServer.getSystemService(Context.ACTIVITY_SERVICE)).getRunningTasks(30).iterator();
      while (iterator.hasNext())
      {
        name = ((ActivityManager.RunningTaskInfo)iterator.next()).topActivity.getPackageName();
        if (name.equals("com.microntek.travel")) 
          travelPkg = "com.microntek.travel";
        if (i == 0)
          topPackage = name;
        if ((mtcPackage == null) && (name.startsWith("com.microntek.")))
          mtcPackage = name;
        i++;
      }
    }
    catch (Exception e) {}
    // результат
    result = travelPkg+","+mtcPackage+","+topPackage;
	return result;  
  }
  
  // обработчик переключения приложений
  private BroadcastReceiver appsReceiver = new BroadcastReceiver()
  {
	  
    public void onReceive(Context context, Intent intent)
    {
      String event = intent.getStringExtra("type");
      // запущено ли радио
      if (event.equals("radio-on"))
        isRadioRunning = true;
      else if (event.equals("radio-off"))
        isRadioRunning = false;      
    }
  };
	  
  // дополнительно для mtc-service
  // установим внутреннюю переменную с величиной громкости по интенту com.microntek.VOLUME_CHANGED
  private BroadcastReceiver volumeReceiver = new BroadcastReceiver()
  {
    public void onReceive(Context context, Intent intent)
    {
      int mCurVolume = Utils.getIntField(microntekServer,"mCurVolume");
      int mNewVolume = intent.getIntExtra("volume", mCurVolume);
      if (mNewVolume > 0)
        Utils.setIntField(microntekServer,"mCurVolume",mNewVolume);
    }
  };
  
  // функция из MicrontekServer
  public static String getTopActivityClassName(Context context)
  {
    try
    {
      ActivityManager taskList = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
      String className = ((ActivityManager.RunningTaskInfo)taskList.getRunningTasks(1).get(0)).topActivity.getPackageName();
      return className;
    }
    catch (Exception e) {}
    return "";
  }
  
  // частота Радио
  private BroadcastReceiver radioFreqReceiver = new BroadcastReceiver()
  {
    public void onReceive(Context context, Intent intent)
    {
      radioFreq = intent.getStringExtra("command");
      Log.d(TAG, "freq=" + radioFreq);
    }
  };
  
  // скринсейвер
  private BroadcastReceiver screenSaverReceiver = new BroadcastReceiver()
  {
    public void onReceive(Context context, Intent intent)
    {
      Log.d(TAG,"start screensaver");
      String topPackage = getTopActivityClassName(microntekServer);
      Log.d(TAG,"topPackage="+topPackage);
      if (ss_exceptions != null)
      {
        // не запускать в списке исключений
        if (ss_exceptions.contains(topPackage)) 
        {
          Log.d(TAG,topPackage+" in screensaver exception list");
          Utils.setBooleanField(microntekServer, "ScreenSaverOn", false);
          Utils.setIntField(microntekServer, "ScreenSaverTimer",0);
          return;
        }
      }
      try 
      {
        // запускаем screen saver
        context.startActivity(getScreenSaverIntent());
      } 
      catch (Exception e) 
      {
        Log.w(TAG,"screen saver exception: "+e.getMessage());
      }
      catch (Error e) 
      {
        Log.w(TAG,"screen saver error: "+e.getMessage());
      }
    }
  };
  
  // интент запуска скринсейвера
  private Intent getScreenSaverIntent()
  {
    Intent intent;
    if (!screenSaverPackage.isEmpty() && !screenSaverClass.isEmpty())
    {
      // явно задан активити для creeen saver
      intent = new Intent();
      intent.setComponent(new ComponentName(screenSaverPackage, screenSaverPackage+"."+screenSaverClass));
      intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    else
    {
      // запускаем общим интенотом
      intent = new Intent("com.microntek.ClockScreen");
      intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    return intent;
  }
 
  // закрытие скринсейвера
  private BroadcastReceiver endClockReceiver = new BroadcastReceiver()
  {
    public void onReceive(Context context, Intent intent)
    {
      Utils.setBooleanField(microntekServer, "ScreenSaverOn", false);
    }
  };
  
  // включить обработчик подключения носителей
  private void createMediaReceiver()
  {
    IntentFilter ui = new IntentFilter();
    ui.addAction(Intent.ACTION_MEDIA_MOUNTED);
    ui.addDataScheme("file");
    microntekServer.registerReceiver(mediaReceiver, ui);
    Log.d(TAG,"media mount receiver created");
  }
  
  // обработчик MEDIA_MOUNT
  private BroadcastReceiver mediaReceiver = new BroadcastReceiver()
  {
    public void onReceive(Context context, Intent intent)
    {
      String action = intent.getAction(); 
      String drivePath = intent.getData().getPath();
      Log.d(TAG,"media receiver:"+drivePath+" "+action);
      if (action.equals(Intent.ACTION_MEDIA_MOUNTED))
        // если подключается external_sd
        if (EXTERNAL_SD.equals(drivePath))
        {
          // читаем настройки
          readSettings();
          // создаем внутренние ресиверы
          createInternalReceivers();
          // запускаем сервисы
          startServiceThread();
          // параметры скринсейвера
          processScreenSaver();
          // определяем текущую позицию
          createLocationListener();
        }
    }
  };
  
  // включить внутренние ресиверы
  private void createInternalReceivers()
  {
    // параметры
    IntentFilter oi = new IntentFilter();
    oi.addAction(INTENT_MTC_PARAMS_QUERY);
    microntekServer.registerReceiver(internalReceiver, oi);
    // радио
    IntentFilter ri = new IntentFilter();
    ri.addAction(INTENT_MTC_RADIO_MCU);
    microntekServer.registerReceiver(radioFreqReceiver, ri);
    Log.d(TAG,"internal receivers created");
  }
  
  // внутренний обработчик
  private BroadcastReceiver internalReceiver = new BroadcastReceiver()
  {
    public void onReceive(Context context, Intent intent)
    {
      String action = intent.getAction(); 
      if (action.equals(INTENT_MTC_PARAMS_QUERY))
      {
        // пошлем имя obd-девайса
        Intent listIntent = new Intent(INTENT_MTC_PARAMS_LIST);
        listIntent.putExtra("obd_device", obdDevicesName);
        microntekServer.sendBroadcast(listIntent);
      }
    }
  };

  // запуск/остановка приложения 
  private void handleModeApp(Context context, String app, String action, String defaultSend, String defaultIntent)
  {
    // параметры из mode.ini
    String section = app+":"+action;
    String intent_name = mode_props.getValue(section, "intent", defaultIntent);
    String send = mode_props.getValue(section, "send", defaultSend);
    String extra = mode_props.getValue(section, "extra", "");
    String value = mode_props.getValue(section, "value", "");
    String extra_type = mode_props.getValue(section, "extra_type", "");
    String packageName = mode_props.getValue(section, "package", app);
    String serviceName = mode_props.getValue(section, "service", "");
    String command = mode_props.getValue(section, "command", "");
    //
    Log.d(TAG,section);
    Log.d(TAG,"intent_name="+intent_name);
    Log.d(TAG,"send="+send);
    Log.d(TAG,"extra="+extra);
    Log.d(TAG,"extra_type="+extra_type);
    Log.d(TAG,"value="+value);
    Log.d(TAG,"packageName="+packageName);
    Log.d(TAG,"serviceName="+serviceName);
    Log.d(TAG,"command="+command);
    // component name
    ComponentName cn = new ComponentName(packageName, serviceName);
    // обрабатываем
    Intent intent;
    if (intent_name.equalsIgnoreCase("default"))
    {
      Log.d(TAG,"find default activity");
      // найдем activity по-умолчанию
      intent = context.getPackageManager().getLaunchIntentForPackage(app);
      if (intent != null)
        intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      else
      {
        Log.w(TAG,"default activity not found for "+app);
        return;
      }
    }
    else
    {
      if (intent_name.isEmpty())
        intent = new Intent();
      else
        intent = new Intent(intent_name);
    }
    Log.d(TAG,"intent created");
    // component
    if (!packageName.isEmpty() && !serviceName.isEmpty())
    {
      Log.d(TAG,"setComponent");
      intent.setComponent(cn);
    }
    // добавим extra
    if (!extra.isEmpty())
    {
      Log.d(TAG,extra+"="+value);
      // в зависимости от extra_type
      if (extra_type.equalsIgnoreCase("int"))
        intent.putExtra(extra,Integer.valueOf(value));
      else if (extra_type.equalsIgnoreCase("long"))
        intent.putExtra(extra,Long.valueOf(value));
      else
        intent.putExtra(extra,value);
    }
    // штатные приложения в фоновом режиме при работающей навигации
    if (gps_isfront && packageName.startsWith("com.microntek."))
    {
      Log.d(TAG,"add extra start=1");
      intent.putExtra("start", 1);
    }
    // как запускаем: service/activity/intent/cmd/none
    if (send.equalsIgnoreCase("intent"))
    {
      Log.d(TAG,"send intent "+intent_name);
      context.sendBroadcast(intent);
    }
    else if (send.equalsIgnoreCase("service"))
    {
      Log.d(TAG,"start service "+intent_name);
      try
      {
        context.startService(intent);
      }
      catch (Exception e)
      {
        Log.e(TAG,"start service: "+e.getMessage());
      }
    }
    else if (send.equalsIgnoreCase("activity"))
    {
      Log.d(TAG,"start activity for "+app);
      try
      {
        context.startActivity(intent);
      }
      catch (Exception e)
      {
        Log.e(TAG,"start activity: "+e.getMessage());
      }
    }
    else if (send.equalsIgnoreCase("cmd"))
    {
      // выполним комманду command
      Log.d(TAG,"execute "+command);
      if (!command.isEmpty()) executeCmd(command);
    }
    else if (send.equalsIgnoreCase("none"))
    {
      // ничего не делаем
      Log.d(TAG,"do nothing");
    }
  }
  
  // переключение приложений
  private void modeSwitch(Context context)
  {
	if (mode_app_list.size() <= 0) return;
    try
    {
     // определить активную программу и ее индекс
     try 
      {
        ActivityManager acm = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningTaskInfo> taskList = acm.getRunningTasks(10);
        for (ActivityManager.RunningTaskInfo task : taskList)
        {
          if (mode_app_list.contains(task.baseActivity.getPackageName())) 
          {
            mode_app = task.baseActivity.getPackageName();
            mode_index = mode_app_list.indexOf(mode_app);
            Log.d(TAG,"mode_app="+mode_app+", mode_index="+mode_index);
            break;
          }
        }
      } catch (Exception e) {}
      // следующий индекс
      int index;
      if ((mode_index+1) < mode_app_list.size())
        index = mode_index+1;
      else
        index = 0;
      Log.d(TAG,"next="+index);
      // текущая программа: останавливаем
      if (!mode_app.isEmpty()) handleModeApp(context, mode_app, "stop", "intent", "");
      // запускаемая программа
      String run_app = mode_app_list.get(index);
      Log.d(TAG,"runApp="+run_app);
      // состояние навигационной программы
      gps_isfront = Utils.getBooleanField(microntekServer, "gps_isfront");
      Log.d(TAG,"gps_isfront="+gps_isfront);
      // стартуем следующую
      handleModeApp(context, run_app, "run", "activity", "default");
      // запускаем
      handleModeApp(context, run_app, "start", "intent", "");
      mode_app = run_app;
      mode_index = index;
    }
    catch (Exception e)
    {
      Log.e(TAG,e.getMessage());
    }
  }
  
  // выполнение команды с привилегиями root
  private void executeCmd(String command)
  {
    // su (as root)
    Process process = null;
    DataOutputStream os = null;
    InputStream err = null;
    try 
    {
      process = Runtime.getRuntime().exec("su");
      os = new DataOutputStream(process.getOutputStream());
      err = process.getErrorStream();
      os.writeBytes(command+" \n");
      os.writeBytes("exit \n");
      os.flush();
      os.close();
      process.waitFor();
      // анализ ошибок
      byte[] buffer = new byte[1024];
      int len = err.read(buffer);
      if (len > 0)
      {
        String errmsg = new String(buffer,0,len);
        Log.e(TAG,errmsg);
      } 
    } 
    catch (Exception e) 
    {
      Log.e(TAG,e.getMessage());
    }
  }
  
  private static void clearLastApp()
  {
    String last_package_name = Settings.System.getString(microntekServer.getContentResolver(),"microntek.lastpackname");
    Log.d(TAG,"last_package_name="+last_package_name);
    if (clear_last_app)
      Settings.System.putString(microntekServer.getContentResolver(),"microntek.lastpackname","null,null,null");
  }
  
  private static void createLocationListener()
  {
    if (sync_gps_time)
      try
      {
        LocationManager locationManager = (LocationManager)microntekServer.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager != null)
        {
          // одноразовое определение координат
          locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, locationListener, null);
          Log.d(TAG,"location listener created");
        }
      }
      catch (Exception e) { }
  }
  
  private static LocationListener locationListener = new LocationListener() 
  {
    public void onLocationChanged(Location location)
    {
      try
      {
        Log.d(TAG,"gps location detected");
        SystemClock.setCurrentTimeMillis(location.getTime());
        Log.d(TAG,"system time changed");
      }
      catch (Exception e)
      {
        Log.e(TAG,e.getMessage());
      }
    }
      
    public void onProviderDisabled(String provider) {}
      
    public void onProviderEnabled(String provider) {}
    
    // изменение статуса gps
    public void onStatusChanged(String provider, int status, Bundle extras) 
    {
    }
  };
  
}
