package com.mvgv70.xposed_mtc_radio;

import java.util.ArrayList;

import android.app.ListActivity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;

public class StationList extends ListActivity 
{
  private ArrayList<String> freq;
  private ArrayList<String> name;
  private final static String TAG = "xposed-mtc-radio";
	  
  @Override
  protected void onCreate(Bundle savedInstanceState) 
  {
    super.onCreate(savedInstanceState);
    Log.d(TAG,"onCreate");
    Intent intent = getIntent();
    freq = intent.getStringArrayListExtra("freq");
    name = intent.getStringArrayListExtra("name");
    String current = intent.getStringExtra("current");
    ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, R.layout.station_item, name);
    setListAdapter(adapter);
    if (current != null) setCurrentFrequency(current);
  }
  
  // установка текущей радиостанции
  private void setCurrentFrequency(String current)
  {
    int position = freq.indexOf(current);
    if (position >= 0) setSelection(position);
  }
  
  @Override
  protected void onListItemClick(ListView l, View v, int position, long id) 
  {
    super.onListItemClick(l, v, position, id);
    if (position >= 0)
    {
      Log.d(TAG,"frequency="+freq.get(position));
      // возвращаем интент
      Intent result = new Intent();
      result.putExtra("frequency", freq.get(position));
      setResult(RESULT_OK, result);
      finish();
    }
  }
  
}
