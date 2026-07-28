package com.test.Bluetooh_784_20;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Vibrator; // 必须导入震动器包
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

	private final static int REQUEST_CONNECT_DEVICE = 1;
	private final static String MY_UUID = "00001101-0000-1000-8000-00805F9B34FB";

	private InputStream is;
	private String smsg = "";

	BluetoothDevice _device = null;
	volatile BluetoothSocket _socket = null; // volatile：UI线程与readThread跨线程读写
	boolean bRun = true;
	private int setHeartrateMinValue=60;
	private int setHeartrateMaxValue=120;
	private float setTempMinValue= 15.0F;
	private float setTempMaxValue= 37.3F;
	boolean bThread = false;

	private BluetoothAdapter _bluetooth = BluetoothAdapter.getDefaultAdapter();

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		setTitle("银龄智守 — 独居老人监测手表");

		// 修复：未连接蓝牙时状态栏不得虚报"监控中"
		setStatusDisconnected("手环未连接");

		final int MY_PERMISSION_ACCESS_COARSE_LOCATION = 11;
		final int MY_PERMISSION_ACCESS_FINE_LOCATION = 12;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			if(this.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)!= PackageManager.PERMISSION_GRANTED){
				requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION},MY_PERMISSION_ACCESS_COARSE_LOCATION);
			}
			if(this.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!= PackageManager.PERMISSION_GRANTED){
				requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},MY_PERMISSION_ACCESS_FINE_LOCATION);
			}
		}

		if (_bluetooth == null){
			Toast.makeText(this, "无法打开手机蓝牙！", Toast.LENGTH_LONG).show();
			finish();
			return;
		}

		new Thread(){
			public void run(){
				if(!_bluetooth.isEnabled()){
					_bluetooth.enable();
				}
			}
		}.start();
	}

	// 蓝牙消息解析中心
	@SuppressLint("HandlerLeak")
	Handler handler= new Handler(){
		public void handleMessage(Message msg){
			super.handleMessage(msg);

			// 解析心率
			int intIndex1 = smsg.indexOf("$Heartrate:");
			if(intIndex1!=-1) {
				String heartrate = smsg;
				TextView textView = findViewById(R.id.textViewHeartrate);
				textView.setText(heartrate.substring(intIndex1+11,heartrate.indexOf("#",intIndex1)));
			}

			// 解析温度
			int intIndex3 = smsg.indexOf("$Temperature:");
			if(intIndex3!=-1) {
				String temperature = smsg;
				TextView textView = findViewById(R.id.textViewTemperature);
				textView.setText(temperature.substring(intIndex3+13,temperature.indexOf("#",intIndex3)));
			}

			// 解析步数
			int intIndex4 = smsg.indexOf("$Steps:");
			if(intIndex4!=-1) {
				String Steps = smsg;
				TextView textView = findViewById(R.id.textViewSteps);
				textView.setText(Steps.substring(intIndex4+7,Steps.indexOf("#",intIndex4)));
			}

			// 解析设置参数 (上限下限等)
			if(smsg.indexOf("$setHeartMax:")!=-1) {
				TextView tv = findViewById(R.id.textViewSetHeartrateMax);
				tv.setText(smsg.substring(smsg.indexOf("$setHeartMax:")+13,smsg.indexOf("#",smsg.indexOf("$setHeartMax:"))));
			}
			if(smsg.indexOf("$setTempMin:")!=-1) {
				TextView tv = findViewById(R.id.textViewTempMin);
				tv.setText(smsg.substring(smsg.indexOf("$setTempMin:")+12,smsg.indexOf("#",smsg.indexOf("$setTempMin:"))));
			}
			if(smsg.indexOf("$setTempMax:")!=-1) {
				TextView tv = findViewById(R.id.textViewTempMax);
				tv.setText(smsg.substring(smsg.indexOf("$setTempMax:")+12,smsg.indexOf("#",smsg.indexOf("$setTempMax:"))));
			}
			if(smsg.indexOf("$setHeartMin:")!=-1) {
				TextView tv = findViewById(R.id.textViewSetHeartrateMin);
				tv.setText(smsg.substring(smsg.indexOf("$setHeartMin:")+13,smsg.indexOf("#",smsg.indexOf("$setHeartMin:"))));
			}

			// 【核心功能实现】：跌倒信号联动报警
			if(smsg.toUpperCase().contains("FALL")) {
				TextView textViewStatus = findViewById(R.id.diedao);

				// 防重复判断：如果已经是报警状态，就不再重复发短信
				if (!textViewStatus.getText().toString().contains("已自动发送短信")) {
					// 1. 改变 UI 文字和颜色
					textViewStatus.setText("检测到跌倒！已自动发送短信！");
					textViewStatus.setTextColor(0xFFBE3E43); // 深红色
					textViewStatus.setBackgroundResource(R.drawable.bg_status_alert);

					// 2. 自动调用发短信方法 (使用 MainActivity.this 解决作用域报错)
					MainActivity.this.onEmergencyButtonClicked(null);

					// 3. 手机强烈震动 (使用 MainActivity.this 解决作用域报错)
					Vibrator vibrator = (Vibrator) MainActivity.this.getSystemService(Context.VIBRATOR_SERVICE);
					if (vibrator != null) {
						vibrator.vibrate(new long[]{0, 800, 200, 800}, -1);
					}
				}
			}
			// 注意：此处删除了 else，确保报警文字显示后不会被后续的正常心率数据冲掉。

			smsg="";
		}
	};

	// 辅助方法：软键盘处理
	@Override
	public boolean dispatchTouchEvent(MotionEvent ev) {
		if (ev.getAction() == MotionEvent.ACTION_DOWN) {
			View v = getCurrentFocus();
			if (isShouldHideKeyboard(v, ev)) {
				v.clearFocus();
				hideKeyboard(v.getWindowToken());
			}
		}
		return super.dispatchTouchEvent(ev);
	}

	private boolean isShouldHideKeyboard(View v, MotionEvent event) {
		if (v !=null && (v instanceof EditText)) {
			int[] l = {0, 0};
			v.getLocationOnScreen(l);
			int left = l[0], top = l[1], bottom = top + v.getHeight(), right = left + v.getWidth();
			if (event.getRawX() > left && event.getRawX() < right && event.getRawY() > top && event.getRawY() < bottom) {
				return false;
			} else { return true; }
		}
		return false;
	}

	private void hideKeyboard(IBinder token) {
		if (token !=null) {
			InputMethodManager im =(InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
			im.hideSoftInputFromWindow(token,InputMethodManager.HIDE_NOT_ALWAYS);
		}
	}

	// ===== 状态栏(diedao)显示控制：灰色=未连接 / 绿色=正常监控 / 红色=跌倒报警(handler中触发) =====
	private void setStatusDisconnected(String text) {
		TextView tv = findViewById(R.id.diedao);
		if (tv == null) return; // 防御：Activity 销毁后的极端时序
		tv.setText(text);
		tv.setTextColor(0xFF617487);      // 灰蓝色文字
		tv.setBackgroundResource(R.drawable.bg_status_disconnected);
	}

	private void setStatusMonitoring() {
		TextView tv = findViewById(R.id.diedao);
		if (tv == null) return;
		tv.setText("状态正常，监控中...");
		tv.setTextColor(0xFF066B66);      // 青绿色文字
		tv.setBackgroundResource(R.drawable.bg_status_monitoring);
	}

	// 蓝牙连接回调
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch(requestCode){
			case REQUEST_CONNECT_DEVICE:
				if (resultCode == Activity.RESULT_OK) {
					String address = data.getExtras().getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
					_device = _bluetooth.getRemoteDevice(address);
					try{
					_socket = _device.createRfcommSocketToServiceRecord(UUID.fromString(MY_UUID));
					_bluetooth.cancelDiscovery(); // 连接前取消搜索
					_socket.connect();
					Toast.makeText(this, "连接"+_device.getName()+"成功！", Toast.LENGTH_SHORT).show();
					setStatusMonitoring(); // 连接成功才进入监控状态
					is = _socket.getInputStream();
					if(!bThread){ readThread.start(); bThread=true; }
					else{ bRun = true; }
				}catch(IOException e){
					_socket = null; // 修复：失败时置空，否则之后再点"连接手环"无反应
					Toast.makeText(this, "连接失败！", Toast.LENGTH_SHORT).show();
					setStatusDisconnected("连接失败，手环未连接");
				}
				}
				break;
			default:break;
		}
	}

	// 蓝牙接收线程
	Thread readThread=new Thread(){
		public void run(){
			int num = 0;
			byte[] buffer = new byte[1024];
			byte[] buffer_new = new byte[1024];
			int i = 0, n = 0;
			bRun = true;
			while(true){
				try{
					while(is.available()==0){ while(bRun == false){} }
					while(true){
						if(!bThread) return;
						num = is.read(buffer);
						n=0;
						for(i=0;i<num;i++){
							if((buffer[i] == 0x0d)&&(buffer[i+1]==0x0a)){
								buffer_new[n] = 0x0a; i++;
							}else{ buffer_new[n] = buffer[i]; }
							n++;
						}
						smsg = new String(buffer_new,0,n);
						if(is.available()==0)break;
					}
					handler.sendMessage(handler.obtainMessage());
			}catch(IOException e){
				// 修复：读取异常多为手环超范围/意外断链（手动点"断开"也会走到这里）
				if(_socket != null){
					// 非手动断开 → 意外断链：清理资源并通知界面
					try { _socket.close(); } catch(IOException ex) {}
					_socket = null;
					handler.post(new Runnable(){
						public void run(){
							setStatusDisconnected("手环连接已断开，请重新连接");
							Toast.makeText(MainActivity.this, "手环连接断开，监控暂停", Toast.LENGTH_SHORT).show();
						}
					});
				}
				// 无连接时空转休眠，避免占满 CPU
				try { Thread.sleep(300); } catch(InterruptedException ex) { return; }
			}
			}
		}
	};

	// 按钮点击事件：连接、断开、退出
	@SuppressLint("MissingPermission")
	public void onConnectButtonClicked(View view) {
		if(!_bluetooth.isEnabled()){
			Toast.makeText(this, " 打开蓝牙中...", Toast.LENGTH_SHORT).show();
			_bluetooth.enable(); return;
		}
		if(_socket==null){
			Intent serverIntent = new Intent(this, DeviceListActivity.class);
			startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
		}
	}

	public void onDisconnectionClicked(View view) {
		if(_socket!=null) {
			// 先置空再 close：确保 readThread 抛 IOException 时读到的一定是 null，
			// 不会误触发"意外断链"通知（避免双 Toast 和文案混乱）
			BluetoothSocket s = _socket;
			_socket = null;
			bRun = false;
			try {
				Toast.makeText(this, "蓝牙断开", Toast.LENGTH_SHORT).show();
				s.close();
			} catch (IOException e) {}
		}
		setStatusDisconnected("手环未连接，监控已暂停"); // 断开后状态栏不再虚报"监控中"
	}

	public void onExitButtonClicked(View view) {
		onDisconnectionClicked(null); finish();
	}

	// ===== 短信接收号码：SharedPreferences 持久化，可在页面底部入口修改 =====
	private String getSmsTargetNumber() {
		return getSharedPreferences("config", MODE_PRIVATE)
				.getString("sms_number", ""); // 默认号码
	}

	// 页面底部入口：弹窗更改接收短信的手机号码
	public void onSmsNumberButtonClicked(View view) {
		final EditText input = new EditText(this);
		input.setText(getSmsTargetNumber());
		input.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
		input.setSelection(input.getText().length());

		new android.app.AlertDialog.Builder(this)
			.setTitle("更改接收短信的手机号码")
			.setView(input)
			.setPositiveButton("保存", new android.content.DialogInterface.OnClickListener(){
				public void onClick(android.content.DialogInterface dialog, int which){
					String num = input.getText().toString().trim();
					if (num.length() == 11 && num.matches("1[0-9]{10}")) {
						getSharedPreferences("config", MODE_PRIVATE).edit()
								.putString("sms_number", num).apply();
						Toast.makeText(MainActivity.this, "短信接收号码已更新：" + num, Toast.LENGTH_SHORT).show();
					} else {
						Toast.makeText(MainActivity.this, "请输入正确的11位手机号", Toast.LENGTH_SHORT).show();
					}
				}
			})
			.setNegativeButton("取消", null)
			.show();
	}

	// 跌倒/紧急求助发短信 (手动触发与自动触发复用此逻辑)
	public void onEmergencyButtonClicked(View view) {
		String targetNumber = getSmsTargetNumber(); // 接收号码可在页面底部入口更改
		String smsContent = "【银龄智守】警告：监测到老人发生意外跌倒或发起求助，请立即确认安全！";

		if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
			requestPermissions(new String[]{Manifest.permission.SEND_SMS}, 101);
			Toast.makeText(this, "请授予短信权限后重试", Toast.LENGTH_SHORT).show();
		} else {
			try {
				android.telephony.SmsManager smsManager = android.telephony.SmsManager.getDefault();
				smsManager.sendTextMessage(targetNumber, null, smsContent, null, null);
				Toast.makeText(this, "自动求助短信已发出！", Toast.LENGTH_LONG).show();
			} catch (Exception e) {
				Toast.makeText(this, "短信发送失败", Toast.LENGTH_SHORT).show();
				e.printStackTrace();
			}
		}
	}

	// 设置参数加减逻辑 (心率、温度)
	public void onAddHeartrateMinClicked(View view) { sendParam("setHeartMin:", ++setHeartrateMinValue); }
	public void onDecHeartrateMinClicked(View view) { sendParam("setHeartMin:", --setHeartrateMinValue); }
	public void onAddHeartrateMaxClicked(View view) { sendParam("setHeartMax:", ++setHeartrateMaxValue); }
	public void onDecHeartrateMaxClicked(View view) { sendParam("setHeartMax:", --setHeartrateMaxValue); }

	// 封装发送参数的方法，让代码更整洁
	private void sendParam(String cmd, Object val) {
		if(_socket == null) return;
		try {
			OutputStream os = _socket.getOutputStream();
			String message = cmd + val + "\r\n";
			os.write(message.getBytes());
		} catch (IOException e) {}
	}

	// 守护网站：内嵌全屏浏览（登录状态通过 Cookie + localStorage 自动保存）
	public void onMapButtonClicked(View view) {
		openInWebView("""");
	}

	// 守护网站管理员端
	public void onAdminButtonClicked(View view) {
		openInWebView("""");
	}

	// 通用的 WebView 内嵌浏览方法
	private void openInWebView(String url) {
		android.webkit.WebView webView = findViewById(R.id.webView_map);
		View scrollView = findViewById(R.id.scroll_dashboard);
		View bottomBar = findViewById(R.id.ll_bottom_buttons);

		scrollView.setVisibility(View.GONE);
		bottomBar.setVisibility(View.GONE);
		webView.setVisibility(View.VISIBLE);

		android.webkit.WebSettings settings = webView.getSettings();
		settings.setJavaScriptEnabled(true);
		settings.setDomStorageEnabled(true); // 支持 localStorage 保存登录状态

		// 启用 Cookie 并持久化，保证登录状态在退出 App 后仍保留
		android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();
		cookieManager.setAcceptCookie(true);
		cookieManager.setAcceptThirdPartyCookies(webView, true);

		webView.setWebViewClient(new android.webkit.WebViewClient());
		webView.loadUrl(url);
	}

	@Override
	protected void onPause() {
		super.onPause();
		// 将 Cookie 立即写入磁盘持久化，避免登录状态丢失
		android.webkit.CookieManager.getInstance().flush();
	}

	@Override
	public void onBackPressed() {
		android.webkit.WebView webView = findViewById(R.id.webView_map);
		View scrollView = findViewById(R.id.scroll_dashboard);
		View bottomBar = findViewById(R.id.ll_bottom_buttons);

		if (webView != null && webView.getVisibility() == View.VISIBLE) {
			if (webView.canGoBack()) { webView.goBack(); }
			else {
				webView.setVisibility(View.GONE);
				scrollView.setVisibility(View.VISIBLE);
				bottomBar.setVisibility(View.VISIBLE);
			}
		} else { super.onBackPressed(); }
	}

	// 温度加减示例 (根据你的 Float 需求微调)
	public void onAddTempMinClicked(View view) { setTempMinValue += 0.1F; sendParam("setTempMin:", String.format("%.1f", setTempMinValue)); }
	public void onDecTempMinClicked(View view) { setTempMinValue -= 0.1F; sendParam("setTempMin:", String.format("%.1f", setTempMinValue)); }
	public void onAddTempMaxClicked(View view) { setTempMaxValue += 0.1F; sendParam("setTempMax:", String.format("%.1f", setTempMaxValue)); }
	public void onDecTempMaxClicked(View view) { setTempMaxValue -= 0.1F; sendParam("setTempMax:", String.format("%.1f", setTempMaxValue)); }
}
