package com.mvgv70.xposed_mtc_manager;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileReader;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;

import android.app.ActivityManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
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
  // �������� ���������� ��������
  private static volatile boolean isShutdown = false;
  // ��������� ���������� ���������
  private static volatile boolean didShutdown = false;
  // �������� �� �����
  private static boolean isRadioRunning;
  private static Thread do_shutdown;
  private static Thread start_services;
  // ������ ���������� ��� ����-�������
  private static Set<String> white_list = null;
  // ��������� ������������
  private static boolean screenSaverEnable = false;
  private static String screenSaverPackage;
  private static String screenSaverClass;
  private static int screenSaverTimeOut = 0;
  private static Set<String> ss_exceptions = null;
	
  @Override
  public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
	    
    // MicrontekServer.onCreate()
    XC_MethodHook onCreate = new XC_MethodHook() {
      
      @Override
      protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        Log.d(TAG,"onCreate");
        microntekServer = (Service)param.thisObject;
        am = ((AudioManager)microntekServer.getSystemService(Context.AUDIO_SERVICE));
        // ������������ ����������
        IntentFilter ai = new IntentFilter();
        ai.addAction("com.microntek.canbusdisplay");
        microntekServer.registerReceiver(appsReceiver, ai);
        // ��������� ���������� ���������� ��� mtc-volume
        IntentFilter vi = new IntentFilter();
        vi.addAction("com.microntek.VOLUME_CHANGED");
        microntekServer.registerReceiver(volumeReceiver, vi);
        Log.d(TAG,"Volume change receiver created");
        // ������ ��������
        readSettings();
        // �������� ������ ������
        try 
        {
     	  Context context = microntekServer.createPackageContext(getClass().getPackage().getName(), Context.CONTEXT_IGNORE_SECURITY);
     	  String version = context.getString(R.string.app_version_name);
          Log.d(TAG,"version="+version);
        } catch (Exception e) {}
        // �������� ������ mcu
        Log.d(TAG,am.getParameters("sta_mcu_version="));
        // ��������� ������������
        if (screenSaverEnable)
        {
          // ������ ��������� ������������
          IntentFilter si = new IntentFilter();
          si.addAction("com.microntek.screensaver");
          microntekServer.registerReceiver(screenSaverReceiver, si);
          Log.d(TAG,"screensaver receiver created");
          // ������ ���������� ������������
          IntentFilter ei = new IntentFilter();
          ei.addAction("com.microntek.endclock");
          ei.addAction("com.microntek.musicclockreset");
          microntekServer.registerReceiver(endClockReceiver, ei);
          Log.d(TAG,"end clock receiver created");
        }
        // ������� ������ �������� � ��������� ������
        startServiceThread();
        // OK
        Log.d(TAG,"onCreate.end");
      }
    };
	    
    // MicrontekServer.cmdProc(byte[], int, int)
    XC_MethodHook cmdProc = new XC_MethodHook() {
      
      @Override
      protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
        // ���������
        byte[] paramArray = (byte[])param.args[0];
        int index = (int)param.args[1];
        @SuppressWarnings("unused")
        int extra = (int)param.args[2];
        // ��������� ����� ���
        int val = 0xFF & paramArray[(index+1)];
        if ((val != 151) && (val != 163)) Log.d(TAG,"cmd="+val);
        // ���������
        if (val == MCU_GO_SLEEP)
        {
          Log.d(TAG,"MCU_GO_SLEEP");
          PowerOff();
          // �� �������� ������� ����������
          param.setResult(null);
        }
        else if (val == MCU_WAKE_UP)
        {
          Log.d(TAG,"MCU_WAKE_UP");
          if (PowerOn())
            // �������� thread, ������� ���������� �� ��������
            param.setResult(null);
          else
          {
        	// �������� ������� ����������, ���� ������� ���������� ��� ��������
            Log.d(TAG,"call powerOn");
            // ����� ������� ������������
            XposedHelpers.setIntField(microntekServer, "ScreenSaverTimer",0);
            // ��������� �����������
            microntekServer.sendBroadcast(new Intent("com.microntek.endclock"));
          }
        }
        else if (val == MCU_ILLUMINATION)
        {
          Log.d(TAG,"MCU_ILLUMINATION");
          // ���������� ������ mtc-volume � ���������/���������� ���������
          Intent intent = new Intent("com.mvg_v70.brightness");
          microntekServer.sendBroadcast(intent);
        }
      }
    };
	    
    // ClearProcess.getisdontclose(String)
    XC_MethodHook getisdontclose = new XC_MethodHook() {
      
      @Override
      protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    	String pkg_name;
        String className = (String)param.args[0];
        if ((boolean)param.getResult() == false)
        {
          // ���� microntek ���������� ������� ��������� ��� ������
          Iterator<String> packages = white_list.iterator();
          while (packages.hasNext()) 
      	  {
            pkg_name = packages.next();
            if (className.startsWith(pkg_name))
            {
              Log.d(TAG,className+" not closed");
              param.setResult(true);
              break;
            }
      	  }
        }
      }
    };
	    
    // ClearProcess.getisdontclose2(String)
    XC_MethodHook getisdontclose2 = new XC_MethodHook() {
      
      @Override
      protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        String pkg_name;
	    String className = (String)param.args[0];
	    if ((boolean)param.getResult() == false)
	    {
	      // ���� microntek ���������� ������� ��������� ��� ������
          Iterator<String> packages = white_list.iterator();
	      while (packages.hasNext()) 
	      {
	        pkg_name = packages.next();
	        if (className.startsWith(pkg_name))
	        {
	          Log.d(TAG,className+" not closed");
	          param.setResult(true);
	          break;
	        }
	      }
	    }
	  }
    };
	    
    // begin hooks
    if (!lpparam.packageName.equals("android.microntek.service")) return;
    Log.d(TAG,"android.microntek.service");
    XposedHelpers.findAndHookMethod("android.microntek.service.MicrontekServer", lpparam.classLoader, "cmdProc", byte[].class, int.class, int.class, cmdProc);
    XposedHelpers.findAndHookMethod("android.microntek.service.MicrontekServer", lpparam.classLoader, "onCreate", onCreate);
    XposedHelpers.findAndHookMethod("android.microntek.ClearProcess", lpparam.classLoader, "getisdontclose", String.class, getisdontclose);
    XposedHelpers.findAndHookMethod("android.microntek.ClearProcess", lpparam.classLoader, "getisdontclose2", String.class, getisdontclose2);
    Log.d(TAG,"android.microntek.service hook OK");
  }
  
  // ������ ��������
  private void readSettings()
  {
    // ����� ������
    try
    {
      Log.d(TAG,"load from "+WHITELIST_INI);
      props.load(new FileInputStream(WHITELIST_INI));
      white_list = props.stringPropertyNames();
      Log.d(TAG,"load white_list: count="+white_list.size());
      props.clear();
    }
    catch (Exception e)
    {
      Log.e(TAG,e.getMessage());
    }
    // ���������� ������������
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
    // ����� ���������
    try
    {
      String value;
      Log.d(TAG,"load from "+SETTINGS_INI);
      props.load(new FileInputStream(SETTINGS_INI));
      // screenClock
      value = props.getProperty("screenClock","false");
      if (value.equals("true"))
      {
        // �������� �������������� �����������
        screenSaverEnable = true;
        XposedHelpers.setBooleanField(microntekServer, "ScreenSaverEnable", true);
        // �������� "��������������" �����������
        XposedHelpers.setBooleanField(microntekServer, "ScreenSaverEnableLocal", false);
        // screenTimeout
        value = props.getProperty("screenTimeout");
        try
        {
          screenSaverTimeOut = Integer.valueOf(value);
          // ��������� ��������
          XposedHelpers.setIntField(microntekServer, "ScreenSaverTimeOut", screenSaverTimeOut);
        } catch (Exception e) 
        {
          // ����� �������� �� ��������
          screenSaverTimeOut = XposedHelpers.getIntField(microntekServer, "ScreenSaverTimeOut");
        }
        // screenPackage & screenClass
        screenSaverPackage = props.getProperty("screenPackage","");
        screenSaverClass = props.getProperty("screenClass","");
      }
      Log.d(TAG,"load common settings: count="+props.size());
      Log.d(TAG,"ScreenSaverEnable="+screenSaverEnable);
      Log.d(TAG,"ScreenSaverTimeOut="+screenSaverTimeOut);
      Log.d(TAG,"ScreenSaverPackage="+screenSaverPackage);
      Log.d(TAG,"ScreenSaverClass="+screenSaverClass);
    }
    catch (Exception e)
    {
      Log.e(TAG,e.getMessage());
    }
  }

  private void startServiceThread()
  {
    // ������ �������� � ��������� ������
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
  
  // ������ ��������
  private void startServices()
  {
    // ������ ���� start_services.ini
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
            // ��������� ������ ��� ������/��� �������
            String packageName = line.substring(0,pos);
            String className = line.substring(pos+1,line.length());
            Log.d(TAG,"start service "+packageName+"/"+className);
            ComponentName cn = new ComponentName(packageName,className);
            Intent intent = new Intent();
            intent.setComponent(cn);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            try
            {
              // �������� ���������� ������
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
  	
  // ����������
  private void PowerOff()
  {
    if (isShutdown)
    {
      // ������ �� ���������� ������
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
        // ���� thread �� ��� ������� � PowerOn()
        if (isShutdown)
        {
          // �������� ������������ ����� powerOff()
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
    // ����� �� ������� MCU_WAKE_UP
    am.setParameters("rpt_power=false");
    Log.d(TAG,"starting shutdown delay thread");
    do_shutdown.start();
  }
	  
  // ���������
  private boolean PowerOn()
  {
    isShutdown = false;
    if(!didShutdown)
    {
      Log.d(TAG,"interrupt shutdown");
      do_shutdown.interrupt();
      am.setParameters("rpt_power=true");
      do_shutdown = null;
      // �������� �����, ���� ��� ���� ��������
      if (isRadioRunning)
      {
    	// ������ � ����� �������
        am.setParameters("av_channel_enter=fm");
        am.setParameters("ctl_radio_mute=false");
      }
      // �� �������� ������� ����������
      return true;
    }
    else
      // ���������� ������ ��� ���������
      return false;
  }
	  
  // ���������� ������������ ����������
  private BroadcastReceiver appsReceiver = new BroadcastReceiver()
  {
	  
    public void onReceive(Context context, Intent intent)
    {
      String event = intent.getStringExtra("type");
      // �������� �� �����
      if (event.equals("radio-on"))
        isRadioRunning = true;
      else if (event.equals("radio-off"))
	    isRadioRunning = false;      
    }
  };
	  
  // ������������� ��� mtc-service
  // ��������� ���������� ���������� � ��������� ��������� �� ������� com.microntek.VOLUME_CHANGED
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
  
  // ������� �� MicrontekServer
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
  
  // �����������
  private BroadcastReceiver screenSaverReceiver = new BroadcastReceiver()
  {
    public void onReceive(Context context, Intent intent)
    {
      Log.d(TAG,"start screensaver");
      String topPackage = getTopActivityClassName(microntekServer);
      Log.d(TAG,"topPackage="+topPackage);
      if (ss_exceptions != null)
      {
        // �� ��������� � ������ ����������
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
    	// ��������� screen saver
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
  
  // ������ ������� ������������
  private Intent getScreenSaverIntent()
  {
	Intent intent;
    if (!screenSaverPackage.isEmpty() && !screenSaverClass.isEmpty())
    {
      // ���� ����� �������� ��� creeen saver
      intent = new Intent();
      intent.setComponent(new ComponentName(screenSaverPackage, screenSaverPackage+"."+screenSaverClass));
      intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    else
    {
      // ��������� ����� ���������
      intent = new Intent("com.microntek.ClockScreen");
      intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    return intent;
  }
 
  // �������� ������������
  private BroadcastReceiver endClockReceiver = new BroadcastReceiver()
  {
    public void onReceive(Context context, Intent intent)
    {
      XposedHelpers.setBooleanField(microntekServer, "ScreenSaverOn", false);
    }
 };

  
}