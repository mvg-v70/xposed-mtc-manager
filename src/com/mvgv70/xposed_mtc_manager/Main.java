package com.mvgv70.xposed_mtc_manager;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import com.mvgv70.utils.IniFile;

import android.app.ActivityManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Environment;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class Main implements IXposedHookLoadPackage {
	
  private final static String TAG = "xposed-mtc-manager";
  private static Properties props = new Properties();
  private final static String EXTERNAL_SD = "/mnt/external_sd";
  private final static String SETTINGS_INI = EXTERNAL_SD + "/mtc-manager/settings.ini";
  private final static String WHITELIST_INI = EXTERNAL_SD + "/mtc-manager/whitelist.ini";
  private final static String START_SERVICES_INI = EXTERNAL_SD + "/mtc-manager/start_services.ini";
  private final static String SS_EXCEPTIONS_INI = EXTERNAL_SD + "/mtc-manager/ss_exceptions.ini";
  private final static String MODE_INI = EXTERNAL_SD+"/mtc-manager/mode.ini";
  //
  private final static int MCU_GO_SLEEP = 240;
  private final static int MCU_WAKE_UP = 241;
  private final static int MCU_ILLUMINATION = 147;
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
  // список исключений для таск-киллера
  private static Set<String> white_list = null;
  // настройки скринсейвера
  private static boolean screenSaverEnable = false;
  private static String screenSaverPackage;
  private static String screenSaverClass;
  private static int screenSaverTimeOut = 0;
  private static Set<String> ss_exceptions = null;
  // bluetooth obd-устройство
  private static String obdDevicesName = null;
  private static List<String> obdDevicesList;
  // modeSwitch
  private static boolean modeSwitch = false;
  private final static String APPS_SECTION = "apps";
  private static IniFile mode_props = new IniFile();
  private static List<String> mode_app_list = new ArrayList<String>();
  private static String mode_app = "";
  private static int mode_index = -1;
	
  @Override
  public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
	    
    // MicrontekServer.onCreate()
    XC_MethodHook onCreate = new XC_MethodHook() {
      
      @Override
      protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        Log.d(TAG,"onCreate");
        microntekServer = (Service)param.thisObject;
        am = ((AudioManager)microntekServer.getSystemService(Context.AUDIO_SERVICE));
        // переключение приложений
        IntentFilter ai = new IntentFilter();
        ai.addAction("com.microntek.canbusdisplay");
        microntekServer.registerReceiver(appsReceiver, ai);
        // изменение внутренней переменной для mtc-volume
        IntentFilter vi = new IntentFilter();
        vi.addAction("com.microntek.VOLUME_CHANGED");
        microntekServer.registerReceiver(volumeReceiver, vi);
        Log.d(TAG,"Volume change receiver created");
        // чтение настроечного файла
      	if (Environment.getStorageState(new File(EXTERNAL_SD)).equals(Environment.MEDIA_MOUNTED))
      	{
      	  // чтение настроек
      	  readSettings();
      	  // параметры скринсейвера
          processScreenSaver();
      	  // быстрый запуск сервисов в отдельном потоке
          startServiceThread();
      	}
      	else
      	  // все сделаем при подключении external_sd
      	  createMediaReceiver();
        // показать версию модуля
        try 
        {
     	  Context context = microntekServer.createPackageContext(getClass().getPackage().getName(), Context.CONTEXT_IGNORE_SECURITY);
     	  String version = context.getString(R.string.app_version_name);
          Log.d(TAG,"version="+version);
        } catch (Exception e) {}
        // показать версию mcu
        Log.d(TAG,am.getParameters("sta_mcu_version="));
        // OK
        Log.d(TAG,"onCreate.end");
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
            // прервали thread, штатный обработчик не вызываем
            param.setResult(null);
          else
          {
        	// вызываем штатный обработчик, если процесс завершения уже выполнен
            Log.d(TAG,"call powerOn");
            // сброс таймера скринсейвера
            XposedHelpers.setIntField(microntekServer, "ScreenSaverTimer",0);
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
    	// String pkg_name;
        String className = (String)param.args[0];
        if ((boolean)param.getResult() == false)
        {
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
    XposedHelpers.findAndHookMethod("android.microntek.service.MicrontekServer", lpparam.classLoader, "cmdProc", byte[].class, int.class, int.class, cmdProc);
    XposedHelpers.findAndHookMethod("android.microntek.service.MicrontekServer", lpparam.classLoader, "onCreate", onCreate);
    XposedHelpers.findAndHookMethod("android.microntek.service.MicrontekServer", lpparam.classLoader, "ModeSwitch", ModeSwitch);
    XposedHelpers.findAndHookMethod("android.microntek.ClearProcess", lpparam.classLoader, "getisdontclose", String.class, getisdontclose);
    XposedHelpers.findAndHookMethod("android.microntek.ClearProcess", lpparam.classLoader, "getisdontclose2", String.class, getisdontclose);
    try
    {
      XposedHelpers.findAndHookMethod("android.microntek.mtcser.model.BC5Model", lpparam.classLoader, "isOBDDevice", String.class, isOBDDevice);
    } catch (Error e) {}
    try
    {
      XposedHelpers.findAndHookMethod("android.microntek.mtcser.model.BC6Model", lpparam.classLoader, "isOBDDevice", String.class, isOBDDevice);
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
      Log.d(TAG,"Main: load from "+WHITELIST_INI);
      props.load(new FileInputStream(WHITELIST_INI));
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
      Log.d(TAG,"load from "+SS_EXCEPTIONS_INI);
      props.load(new FileInputStream(SS_EXCEPTIONS_INI));
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
      Log.d(TAG,"load from "+SETTINGS_INI);
      props.load(new FileInputStream(SETTINGS_INI));
      // screenClock
      value = props.getProperty("screenClock","false");
      if (value.equals("true"))
      {
        // включаем альтернативный скринсейвер
        screenSaverEnable = true;
        XposedHelpers.setBooleanField(microntekServer, "ScreenSaverEnable", true);
        // включаем "альтернативный" скринсейвер
        XposedHelpers.setBooleanField(microntekServer, "ScreenSaverEnableLocal", false);
        // screenTimeout
        value = props.getProperty("screenTimeout");
        try
        {
          screenSaverTimeOut = Integer.valueOf(value);
          // сохраняем значение
          XposedHelpers.setIntField(microntekServer, "ScreenSaverTimeOut", screenSaverTimeOut);
        } catch (Exception e) 
        {
          // берем значение из настроек
          screenSaverTimeOut = XposedHelpers.getIntField(microntekServer, "ScreenSaverTimeOut");
        }
        // screenPackage & screenClass
        screenSaverPackage = props.getProperty("screenPackage","");
        screenSaverClass = props.getProperty("screenClass","");
      }
      // mcu
      xposedMCU = props.getProperty("mcu_power","true").equals("true");
      Log.d(TAG,"mcu_power="+xposedMCU);
      // obd
      obdDevicesName = props.getProperty("obd_device","OBD").toUpperCase(Locale.US);
      if (obdDevicesName.isEmpty()) obdDevicesName = "OBD";
      obdDevicesList = Arrays.asList(obdDevicesName.split("\\s*,\\s*"));
      // modeSwitch
      modeSwitch = props.getProperty("modeSwitch","false").equals("true");
      // OK
      Log.d(TAG,"obd_device="+obdDevicesName);
      Log.d(TAG,"ScreenSaverEnable="+screenSaverEnable);
      Log.d(TAG,"ScreenSaverTimeOut="+screenSaverTimeOut);
      Log.d(TAG,"ScreenSaverPackage="+screenSaverPackage);
      Log.d(TAG,"ScreenSaverClass="+screenSaverClass);
    }
    catch (Exception e)
    {
      Log.e(TAG,e.getMessage());
    }
    // mode.ini
    mode_app_list.clear();
    if (modeSwitch)
    {
      mode_props.clear();
      try
      {
        Log.d(TAG,"mode from "+MODE_INI);
        mode_props.loadFromFile(MODE_INI);
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
  
  // запуск сервисов
  private void startServices()
  {
    // читаем файл start_services.ini
    String line;
    try
    {
      BufferedReader br = new BufferedReader(new FileReader(START_SERVICES_INI));
      try
      {
        while ((line = br.readLine()) != null)
        {
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
          XposedHelpers.callMethod(microntekServer, "powerOff");
          Log.d(TAG,"powerOff called");
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
    if(!didShutdown)
    {
      Log.d(TAG,"interrupt shutdown");
      do_shutdown.interrupt();
      am.setParameters("rpt_power=true");
      do_shutdown = null;
      // включаем радио, если оно было включено
      if (isRadioRunning)
      {
    	// именно в таком порядке
        am.setParameters("av_channel_enter=fm");
        am.setParameters("ctl_radio_mute=false");
      }
      // не вызываем штатный обработчик
      return true;
    }
    else
      // завершение работы уже выполнено
      return false;
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
      int mCurVolume = XposedHelpers.getIntField(microntekServer,"mCurVolume");
      int mNewVolume = intent.getIntExtra("volume", mCurVolume);
      if (mNewVolume > 0)
        XposedHelpers.setIntField(microntekServer,"mCurVolume",mNewVolume);
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
    catch (Exception localException) {}
    return null;
  }
  
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
          XposedHelpers.setBooleanField(microntekServer, "ScreenSaverOn", false);
          XposedHelpers.setIntField(microntekServer, "ScreenSaverTimer",0);
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
      intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    else
    {
      // запускаем общим интенотом
      intent = new Intent("com.microntek.ClockScreen");
      intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    return intent;
  }
 
  // закрытие скринсейвера
  private BroadcastReceiver endClockReceiver = new BroadcastReceiver()
  {
    public void onReceive(Context context, Intent intent)
    {
      XposedHelpers.setBooleanField(microntekServer, "ScreenSaverOn", false);
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
          // запускаем сервисы
          startServiceThread();
          // параметры скринсейвера
          processScreenSaver();
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
    Log.d(TAG,"after setComponent");
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
    Log.d(TAG,"if: send="+send);
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
	if (mode_app_list.size() < 0) return;
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
  
}