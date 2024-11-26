package com.example.mydtmb;

import androidx.appcompat.app.AppCompatActivity;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.Arrays;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int DEVICE_PID = 4100;
    private static final int DEVICE_VID = 1204;
    private static final int EP2_IN = 130;
    private static final int EP2_OUT = 2;
    private boolean mInitializing = false;
    private static int m_TunerType = -1;
    private UsbDevice mDevice = null;
    private UsbInterface mInterface = null;
    private UsbEndpoint mEP2 = null;
    private boolean mIsValidDevice = false;
    private UsbManager mUsbManager = null;
    private static final String ACTION_USB_PERMISSION = "com.example.mydtmb.USB_PERMISSION";
    private enum DeviceType {LETV, AIWA, CVB};
    private DeviceType devType;
    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() { // from class: com.cidana.usbtuner.Bridge.1
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if (MainActivity.ACTION_USB_PERMISSION.equals(intent.getAction())) {
                synchronized (this) {
                    if (intent.getBooleanExtra("permission", false)) {
                            MainActivity.this.mIsValidDevice = true;
                        }
                        MainActivity.this.bCheckPermission = true;
                    }
            }
        }
    };
    private boolean bCheckPermission = false;
    private UsbDeviceConnection mConnection = null;
    private static final String[] C_STRS = {"C=1", "C=3780"};
    private static final String[] PN_STRS = {"PN945","PN595","PN420","NULL-3"};
    private static final String[] N_STRS = {"0.4","0.6","0.8","NULL-3"};
    private static final String[] N2_STRS = {"720","240"};
    private static final String[] AM_STRS = {"4QAM-NR","4QAM","16QAM","32QAM","64QAM","NULL-6","NULL-7"};
    private static final String[] P_STRS = {"Phase: Variable","Phase: Fixed"};
    private TextView textProduct, textFirmwareVersion, textSignal, textStrength, textQuality, textSNR, textFreq;
    private Spinner spinnerFreq;
    private Button btnTest, btnStop;
    private ProgressBar progressBarStrength, progressBarQuality;
    private CheckBox checkBoxKeepScreenOn;
    private RefreshThread refreshThread;

    private class MyBroadcastReceiver extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            final UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device.getVendorId()==DEVICE_VID && device.getProductId()==DEVICE_PID) {
                String action = intent.getAction();
                if (action.equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
                    // USB设备已插入
                    Init(context, 0, 0);
                    getUsbInfo();
                } else if (action.equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
                    // USB设备已拔出
                    refreshThread.pause();
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    textProduct.setText("null");
                    textFirmwareVersion.setText("null");
                    spinnerFreq.setEnabled(true);
                    btnTest.setEnabled(true);
                    btnStop.setEnabled(false);
                    textSignal.setText("");
                    textSNR.setText("");
                    textStrength.setText("0%");
                    textQuality.setText("0%");
                    progressBarStrength.setProgress(0);
                    progressBarQuality.setProgress(0);
                    unInit();
                } else if (action.equals(Intent.ACTION_MEDIA_UNMOUNTED)) {
                    // USB设备已卸载
                }
            }
        }
    }

    private class RefreshThread extends Thread {
        private boolean stop = true;

        public void begin() {
            stop = false;
        }

        public void pause() {
            stop = true;
        }

        @Override
        public void run() {
//            runOnUiThread(new Runnable() {
//                @Override
//                public void run() {
                    while (true) {
                        if (!stop) {
                            getSignalInfo();
                            getQuality();
                        }

                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
//                }
//            });
        }
    }

        @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textProduct = (TextView)findViewById(R.id.textProduct);
        textFirmwareVersion = (TextView)findViewById(R.id.textFirmwareVersion);
        textFreq = (TextView)findViewById(R.id.textFreq);
        textSignal = (TextView)findViewById(R.id.textSignal);
        textSNR = (TextView)findViewById(R.id.textSNR);
        progressBarStrength = (ProgressBar)findViewById(R.id.progressBarStrength);
        textStrength = (TextView)findViewById(R.id.textStrength);
        progressBarQuality = (ProgressBar)findViewById(R.id.progressBarQuality);
        textQuality = (TextView)findViewById(R.id.textQuality);
        spinnerFreq = (Spinner)findViewById(R.id.spinnerFreq);
        btnTest = (Button)findViewById(R.id.btnTest);
        btnTest.setOnClickListener(new Button.OnClickListener(){
            public void onClick(View v) {
                String freqText = String.valueOf(spinnerFreq.getSelectedItem());
                int start = freqText.indexOf("频点:") + 3;
                int end = freqText.indexOf("MHz");
                int freq = (int) (Float.valueOf(freqText.substring(start, end)) * 1000000);
                if (setFrequency(freq)) {
                    spinnerFreq.setEnabled(false);
                    btnTest.setEnabled(false);
                    btnStop.setEnabled(true);
                    refreshThread.begin();
                    textFreq.setText(freqText);
                    getSignalInfo();
                } else {
                    textSignal.setText("no signal");
                }
            }
        });
        btnStop = (Button)findViewById(R.id.btnStop);
        btnStop.setOnClickListener(new Button.OnClickListener(){
            public void onClick(View v) {
                refreshThread.pause();
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                spinnerFreq.setEnabled(true);
                btnTest.setEnabled(true);
                btnStop.setEnabled(false);
                textSignal.setText("");
                textSNR.setText("");
                textStrength.setText("0%");
                textQuality.setText("0%");
                progressBarStrength.setProgress(0);
                progressBarQuality.setProgress(0);
            }
        });
        checkBoxKeepScreenOn = (CheckBox)findViewById(R.id.checkBoxKeepScreenOn);
        checkBoxKeepScreenOn.setOnClickListener(new CheckBox.OnClickListener() {
            public void onClick(View v) {
                if (((CheckBox)v).isChecked()) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });

        mUsbManager = (UsbManager)getSystemService(Context.USB_SERVICE);
        Init(getApplicationContext(), 0, 0);
        getUsbInfo();

        refreshThread = new RefreshThread();
        refreshThread.start();

        textSNR.setText("");
        textSignal.setText("");
        spinnerFreq.setEnabled(true);
        btnTest.setEnabled(true);
        btnStop.setEnabled(false);
        textStrength.setText("");
        textQuality.setText("");

//        PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
        //注册USB设备权限管理广播
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        this.registerReceiver(new MyBroadcastReceiver(), filter);
    }

    private int CheckDeviceID(int i, int i2) {
        int i3 = -1;
        if (i == DEVICE_VID) {
            switch (i2) {
                case DEVICE_PID /* 4100 */:
                    i3 = 0;
                    break;
            }
            Log.i(TAG, "vid: " + i + " pid:" + i2);
        }
        return i3;
    }

    private int CheckDevice() {
        if (mUsbManager != null || !this.mInitializing) {
            if (this.mInitializing) {
                Log.i(TAG, "Tuner is initializing... type: " + m_TunerType);
                return 0;
            } else if (mUsbManager == null) {
                return -1;
            } else {
                for (UsbDevice usbDevice : mUsbManager.getDeviceList().values()) {
                    if (usbDevice != null) {
                        m_TunerType = CheckDeviceID(usbDevice.getVendorId(), usbDevice.getProductId());
                        if (m_TunerType != -1) {
                            this.mDevice = usbDevice;
                            if (mUsbManager.hasPermission(usbDevice)) {
                                this.mIsValidDevice = true;
                            } else {
                                this.mIsValidDevice = false;
                            }
                            return 0;
                        }
                    }
                }
                return -1;
            }
        }
        return 32767;
    }

    private boolean getUsbInfo() {
        if (mConnection != null) {
            byte[] buffer = new byte[4];
            mConnection.controlTransfer(0xC0, 0xED, 0xFE, 0x00, buffer, 4, 0);
            String product = "";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                product = mDevice.getManufacturerName() + "  " + mDevice.getProductName() + " ";
            }
            if (buffer[0]==3) {
                devType = DeviceType.LETV;
                product = product + "(Letv)";
                progressBarStrength.setVisibility(View.VISIBLE);
            } else if (buffer[0]==5) {
                devType = DeviceType.AIWA;
                product = product + "(Aiwa)";
                progressBarStrength.setVisibility(View.VISIBLE);
            } else if (buffer[0]==6) {
                devType = DeviceType.CVB;
                product = product + "(CVB)";
                byte[] key = {0x63, 0x69, 0x64, 0x61, 0x6E, 0x61, 0x4B, 0x45, 0x59};
                mConnection.controlTransfer(0x40, 0xBE, 0xFE, 0x00, key, 9, 0);
                byte[] data = new byte[22];
                mConnection.controlTransfer(0xC0, 0xBF, 0xFE, 0x00, data, 22, 0);
                progressBarStrength.setVisibility(View.GONE);
            }
            textProduct.setText(product);
            textFirmwareVersion.setText(String.valueOf(buffer[0]) + "." + String.valueOf(buffer[1]) + "." + String.valueOf(buffer[2]) + String.valueOf(buffer[3]));
            return true;
        }
        return false;
    }

    private void clearMsg(){
        textSignal.setText("");
        textSNR.setText("");
        progressBarStrength.setProgress(0);
        textStrength.setText("0%");
        progressBarQuality.setProgress(0);
        textQuality.setText("0%");
    }

    private boolean setFrequency(int freq) {
        if (mConnection != null) {
            byte[] buffer = new byte[4];
            buffer[0] = (byte) ((freq>>24) & 0x0FF);
            buffer[1] = (byte) ((freq>>16) & 0x0FF);
            buffer[2] = (byte) ((freq>>8) & 0x0FF);
            buffer[3] = (byte) (freq & 0x0FF);

            mConnection.controlTransfer(0x40, 0xFC, 0xFE, 0x00, buffer, 4, 0);
            mConnection.controlTransfer(0xC0, 0xEA, 0xFE, 0x00, buffer, 1, 0);
//            if (buffer[0] == 1) {
                return true;
//            }
//            return false;
        }
        return false;
    }

    private boolean getSignalInfo() {
        if (mConnection != null) {
            byte[] buffer = new byte[6];
            mConnection.controlTransfer(0xC0, 0xEC, 0xFE, 0x00, buffer, 1, 0);
            if (buffer[0] == 1) {
                mConnection.controlTransfer(0xC0, 0xE7, 0xFE, 0x00, buffer, 6, 0);
                try {
                    textSignal.setText(String.format("%s %s %s %s %s %s", C_STRS[buffer[0]], PN_STRS[buffer[1]], N_STRS[buffer[2]], N2_STRS[buffer[3]], AM_STRS[buffer[4]], P_STRS[buffer[5]]));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                textSignal.setText("no signal");
            }
            return true;
        }
        return false;
    }

    private boolean getQuality() {
        if (mConnection != null) {
            try {
                byte[] buffer = new byte[4];
//            mConnection.controlTransfer(0xC0, 0xE9, 0xFE, 0x00, buffer, 3, 0);
                mConnection.controlTransfer(0xC0, 0xEC, 0xFE, 0x00, buffer, 1, 0);
                mConnection.controlTransfer(0xC0, 0xE8, 0xFE, 0x00, buffer, 2, 0);
                textSNR.setText(String.format(" %d.%d dB", buffer[0], buffer[1]));

                mConnection.controlTransfer(0xC0, 0xEB, 0xFE, 0x00, buffer, 4, 0);
                int strength = buffer[3] + ((buffer[2] + ((buffer[1] + (buffer[0] << 8)) << 8)) << 8);
                if (devType==DeviceType.CVB) {
                    textStrength.setText(String.format("-%d dBm", strength));
                } else {
                    progressBarStrength.setProgress(strength);
                    textStrength.setText(String.format(" %d", strength) + " %");
                }

                int[] qualityList = {0,0,0,0,0};
                for (int i=0; i<5; i++) {
                    mConnection.controlTransfer(0xC0, 0xE9, 0xFE, 0x00, buffer, 3, 0);
                    qualityList[i] = buffer[0] * 10000 + buffer[1] * 100 + buffer[2];
                }
                Arrays.sort(qualityList);
                int quality = qualityList[2];
//                mConnection.controlTransfer(0xC0, 0xE9, 0xFE, 0x00, buffer, 3, 3);
//                int quality = buffer[0] * 10000 + buffer[1] * 100 + buffer[2];
                progressBarQuality.setProgress(quality);
                textQuality.setText(String.format(" %d", quality) + " %");
            } catch (Exception e) {
                e.printStackTrace();
            }
            return true;
        }
//        textQuality.setText("null");
        return false;
    }

    public void unInit() {
        this.mDevice = null;
        this.mConnection = null;
        this.mInitializing = false;
    }

    public void showMsg() {
        new AlertDialog.Builder(MainActivity.this).setTitle("信息提示")//设置对话框标题

                .setMessage("是否需要更换xxx？")
                .setPositiveButton("是", new DialogInterface.OnClickListener() {//添加确定按钮

                    @Override
                    public void onClick(DialogInterface dialog, int which) {//确定按钮的响应事件，点击事件没写，自己添加

                    }
                }).setNegativeButton("否", new DialogInterface.OnClickListener() {//添加返回按钮

                    @Override
                    public void onClick(DialogInterface dialog, int which) {//响应事件，点击事件没写，自己添加

                    }

                }).show();
    }

    public boolean Init(Context context, int i, int i2) {
        Log.i(TAG, "Start Init Tuner");
        if (CheckDevice() != 0) {
            return false;
        }
        if (this.mDevice == null) {
            Log.i(TAG, "Can not find device");
            return false;
        }
        if (!this.mIsValidDevice) {
            PendingIntent broadcast;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                broadcast = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
            } else {
                broadcast = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_ONE_SHOT);
            }
            context.registerReceiver(this.mUsbReceiver, new IntentFilter(ACTION_USB_PERMISSION));
            mUsbManager.requestPermission(this.mDevice, broadcast);
            this.bCheckPermission = false;
            while (!this.bCheckPermission) {
                Log.i(TAG, "wait for permission");
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        if (!this.mIsValidDevice) {
            Log.i(TAG, "no permission");
            return false;
        }
        this.mInitializing = true;
        this.mConnection = mUsbManager.openDevice(this.mDevice);
        if (this.mConnection == null) {
            Log.i(TAG, "Init Tuner failed!");
            this.mInitializing = false;
            return false;
        }
        int interfaceCount = this.mDevice.getInterfaceCount();
        Log.i(TAG, "countIntf: " + interfaceCount);
        for (int i3 = 0; i3 < interfaceCount; i3++) {
            this.mInterface = this.mDevice.getInterface(i3);
            Log.i(TAG, "mInterface: " + this.mInterface);
            if (this.mInterface != null) {
                for (int i4 = 0; i4 < this.mInterface.getEndpointCount(); i4++) {
                    int address = this.mInterface.getEndpoint(i4).getAddress();
                    Log.i(TAG, "EP Address: " + address);
                    if (address == EP2_IN) {
                        this.mEP2 = this.mInterface.getEndpoint(i4);
                        this.mConnection.claimInterface(this.mInterface, true);
                        Log.i(TAG, "EP2 BufferSize " + this.mEP2.getMaxPacketSize());
                    }
                }
            }
        }
        if (this.mInterface == null || this.mEP2 == null) {
            Log.i(TAG, "Can not get EndPoint!");
            this.mInitializing = false;
            return false;
        }
/*        NInitLibrary(this, this.m_DataBuffer);
        for (int i5 = 0; i5 < this.m_request.length; i5++) {
            this.m_request[i5].initialize(this.mConnection, this.mEP2);
            this.m_request[i5].setClientData(this.m_DataBuffer[i5]);
        }
        ClearPIDFilter();
        this.m_Attached = true;*/
        this.mInitializing = false;
//        this.m_DumpThread = new TSDumpThread();
//        this.m_DumpThread.start();
        Log.i(TAG, "Init Tuner OK!");
        return true;
    }
}