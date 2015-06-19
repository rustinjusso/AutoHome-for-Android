package com.example.android.auto_home;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.AsyncTask;
import android.os.Handler;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3Client;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

/** Starts up communication with the BLE device and starts scanning for commands over SMS to send to the board */
public class ConnectedReceiverActivity extends ActionBarActivity {

    private final static String TAG = ConnectedReceiverActivity.class.getSimpleName();

    //Extras for grabbing the device info from the starting intent
    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";

    private static final String MLDP_PRIVATE_SERVICE = "00035b03-58e6-07dd-021a-08123a000300";                  //Private service for Microchip MLDP
    private static final String MLDP_DATA_PRIVATE_CHAR = "00035b03-58e6-07dd-021a-08123a000301";                //Characteristic for MLDP Data, properties - notify, write
    private static final String MLDP_CONTROL_PRIVATE_CHAR = "00035b03-58e6-07dd-021a-08123a0003ff";             //Characteristic for MLDP Control, properties - read, write
    private static final String CHARACTERISTIC_NOTIFICATION_CONFIG = "00002902-0000-1000-8000-00805f9b34fb";    //Special UUID for descriptor needed to enable notifications

    private final String lockUrl ="https://s3.amazonaws.com/autohome/lock.txt";
    private final String lightsUrl ="https://s3.amazonaws.com/autohome/lights.txt";
    private SendTask sendTask;

    private static boolean lockClosed = true;
    private static boolean lightsOff = true;

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothGatt mBluetoothGatt;
    private BluetoothGattCharacteristic mDataMDLP, mControlMLDP;

    private TextView mConnectionState;

    private static int statusCounter = 0;
    private static Handler tHandler;

    private static AmazonS3Client s3Client;
    private RequestQueue queue;

    private String mDeviceAddress, mDeviceName;
    private String incomingMessage;
    private boolean mConnected = false;
    private boolean writeComplete;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connected_receiver);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        //Setup the BLE device and connect to it
        final Intent intent = getIntent();
        mDeviceName = intent.getStringExtra(EXTRAS_DEVICE_NAME);
        this.setTitle(mDeviceName);
        mDeviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);

        ((TextView) findViewById(R.id.deviceAddress)).setText(mDeviceAddress);          //Display device address on the screen
        mConnectionState = (TextView) findViewById(R.id.connectionState);               //Display the connection state

        //Check to make sure the BT is still enabled.
        final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "@string/ble_not_supported", Toast.LENGTH_SHORT).show();
            finish();
        }

        s3Client = new AmazonS3Client( new BasicAWSCredentials( "REMOVED", "REMOVED"));
        queue = Volley.newRequestQueue(this);
        tHandler = new Handler();
        tHandler.post(timerTask);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_connected_receiver, menu);
        if (mConnected) {
            menu.findItem(R.id.menu_connect).setVisible(false);
            menu.findItem(R.id.menu_disconnect).setVisible(true);
        } else {
            menu.findItem(R.id.menu_connect).setVisible(true);
            menu.findItem(R.id.menu_disconnect).setVisible(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_connect:
                if (mBluetoothGatt != null) {
                    mBluetoothGatt.connect();
                }
                return true;
            case R.id.menu_disconnect:
                if (mBluetoothGatt != null) {
                    mBluetoothGatt.disconnect();
                }
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    //Restarts the BLE connection upon resume
    protected void onResume() {
        super.onResume();
        if (mBluetoothAdapter == null || mDeviceAddress == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            finish();
        }

        if (mBluetoothGatt != null) {
            Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
            if (mBluetoothGatt.connect()) {
                Log.w(TAG, "Existing Gatt unable to connect.");
                finish();
            }
        }

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(mDeviceAddress);
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            finish();
        }
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
        Log.d(TAG, "Trying to create a new connection.");

        tHandler.postDelayed(timerTask, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mBluetoothGatt.disconnect();
        tHandler.removeCallbacks(timerTask);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mBluetoothGatt.disconnect();
        tHandler.removeCallbacks(timerTask);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mBluetoothGatt.disconnect();
        mBluetoothGatt.close();
        tHandler.removeCallbacks(timerTask);
    }

    //Wrappers for using the buttons to send messages
    public void sendLockMessage(View view) { lockMessage(); setLockMessage(); }
    public void sendUnlockMessage(View view) { unlockMessage(); setUnlockMessage(); }
    public void sendLightsOnMessage(View view) { lightsOnMessage(); setLightsOnMessage();}
    public void sendLightsOffMessage(View view) { lightsOffMessage(); setLightsOffMessage(); }

    //Sends a lock message to the BLE device
    public void lockMessage(){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                lockClosed = true;
                if (mDataMDLP != null) {
                    Toast.makeText(ConnectedReceiverActivity.this, "Sending Lock request message", Toast.LENGTH_SHORT).show();
                    mDataMDLP.setValue("Z");
                    writeCharacteristic(mDataMDLP);
                } else
                    Toast.makeText(ConnectedReceiverActivity.this, "Lock message failed", Toast.LENGTH_SHORT).show();
            }
        });
    }

    //Sends a lock message to the BLE device
    public void unlockMessage(){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                lockClosed = false;
                if (mDataMDLP != null) {
                    Toast.makeText(ConnectedReceiverActivity.this, "Sending Unlock request message", Toast.LENGTH_SHORT).show();
                    mDataMDLP.setValue("A");
                    writeCharacteristic(mDataMDLP);
                } else
                    Toast.makeText(ConnectedReceiverActivity.this, "Unlock message failed", Toast.LENGTH_SHORT).show();
            }
        });
    }

    //Sends a lights message to the BLE device
    public void lightsOnMessage() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                lightsOff = false;
                if (mDataMDLP != null) {
                    Toast.makeText(ConnectedReceiverActivity.this, "Sending Lights On request message", Toast.LENGTH_SHORT).show();
                    mDataMDLP.setValue("B");
                    writeCharacteristic(mDataMDLP);
                } else
                    Toast.makeText(ConnectedReceiverActivity.this, "Lights On message failed", Toast.LENGTH_SHORT).show();
            }
        });
    }

    //Sends a lights message to the BLE device
    public void lightsOffMessage() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                lightsOff = true;
                if (mDataMDLP != null) {
                    Toast.makeText(ConnectedReceiverActivity.this, "Sending Lights Off request message", Toast.LENGTH_SHORT).show();
                    mDataMDLP.setValue("X");
                    writeCharacteristic(mDataMDLP);
                } else
                    Toast.makeText(ConnectedReceiverActivity.this, "Lights Off message failed", Toast.LENGTH_SHORT).show();
            }
        });
    }

    //Sends a lock message
    public void setLockMessage() {
        lockClosed = true;
        sendTask = new SendTask();
        sendTask.execute("lock.txt", "Lock");
    }

    //Sends a lock message
    public void setUnlockMessage() {
        lockClosed = false;
        sendTask = new SendTask();
        sendTask.execute("lock.txt", "Unlock");
    }

    //Sends a lights message
    public void setLightsOnMessage() {
        lightsOff = false;
        sendTask = new SendTask();
        sendTask.execute("lights.txt", "Off");
    }

    //Sends a lights message
    public void setLightsOffMessage() {
        lightsOff = true;
        sendTask = new SendTask();
        sendTask.execute("lights.txt", "Off");
    }

    //Sends a lock message to the BLE device
    public void statusMessage(){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mDataMDLP != null) {
                    mDataMDLP.setValue("?");
                    writeCharacteristic(mDataMDLP);
                } else
                    Log.e("StatusMessage", "Write characteristic failed.");
            }
        });
    }

    class SendTask extends AsyncTask<String, Void, Void> {
        protected Void doInBackground(String... params) {
            try {
                File file = File.createTempFile(params[0], null, getApplication().getCacheDir());
                FileWriter writer = new FileWriter(file);
                writer.append(params[1]);
                writer.flush();
                writer.close();
                s3Client.putObject("autohome", params[0], file);
            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        }
    }

    private Runnable timerTask = new Runnable() {
        @Override
        public void run() {
            // Request a string response from the provided URL.
            StringRequest lockRequest = new StringRequest(Request.Method.GET, lockUrl,
                    new Response.Listener<String>() {
                        @Override
                        public void onResponse(String response) {
                            if(response.equals("Lock") && !lockClosed)
                               lockMessage();
                            else if(response.equals("Unlock") && lockClosed)
                                unlockMessage();
                        }
                    }, new Response.ErrorListener() {
                @Override
                public void onErrorResponse(VolleyError error) {
                    Log.e("Volley Response", "That didn't work!");
                }
            });
            // Add the request to the RequestQueue.
            queue.add(lockRequest);

            // Request a string response from the provided URL.
            StringRequest lightsRequest = new StringRequest(Request.Method.GET, lightsUrl,
                    new Response.Listener<String>() {
                        @Override
                        public void onResponse(String response) {
                            if(response.equals("On") && lightsOff)
                                lightsOnMessage();
                            else if(response.equals("Off") && !lightsOff)
                                lightsOffMessage();
                        }
                    }, new Response.ErrorListener() {
                @Override
                public void onErrorResponse(VolleyError error) {
                    Log.e("Volley Response", "That didn't work!");
                }
            });
            // Add the request to the RequestQueue.
            queue.add(lightsRequest);

            if(statusCounter == 60){
                statusMessage();
                statusCounter = 0;
            } else { statusCounter++; }

            tHandler.postDelayed(this, 1000);
        }
    };

    private void updateConnectionState(final int resourceId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mConnectionState.setText(resourceId);
            }
        });
    }

    //This is where the responses will be read
    private void processIncomingPacket(String data) {
        TextView text = (TextView) findViewById(R.id.responseText);
        incomingMessage = incomingMessage.concat(data);
        text.setText(incomingMessage);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Bluetooth Low Energy Configuration and use code
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private void findMldpGattService(List<BluetoothGattService> gattServices) {
        if (gattServices == null) {                                                     //Verify that list of GATT services is valid
            Log.d(TAG, "findMldpGattService found no Services");
            return;
        }
        String uuid;                                                                    //String to compare received UUID with desired known UUIDs
        mDataMDLP = null;                                                               //Searching for a characteristic, start with null value

        for (BluetoothGattService gattService : gattServices) {                         //Test each service in the list of services
            uuid = gattService.getUuid().toString();                                    //Get the string version of the service's UUID
            if (uuid.equals(MLDP_PRIVATE_SERVICE)) {                                    //See if it matches the UUID of the MLDP service
                List<BluetoothGattCharacteristic> gattCharacteristics = gattService.getCharacteristics(); //If so then get the service's list of characteristics
                Log.e(TAG, gattCharacteristics.toString());
                for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) { //Test each characteristic in the list of characteristics
                    uuid = gattCharacteristic.getUuid().toString();                     //Get the string version of the characteristic's UUID
                    if (uuid.equals(MLDP_DATA_PRIVATE_CHAR)) {                          //See if it matches the UUID of the MLDP data characteristic
                        mDataMDLP = gattCharacteristic;                                 //If so then save the reference to the characteristic
                        Log.d(TAG, "Found MLDP data characteristics");
                    } else if (uuid.equals(MLDP_CONTROL_PRIVATE_CHAR)) {                  //See if UUID matches the UUID of the MLDP control characteristic
                        mControlMLDP = gattCharacteristic;                              //If so then save the reference to the characteristic
                        Log.d(TAG, "Found MLDP control characteristics");
                    }
                    final int characteristicProperties = gattCharacteristic.getProperties(); //Get the properties of the characteristic
                    if ((characteristicProperties & (BluetoothGattCharacteristic.PROPERTY_NOTIFY)) > 0) { //See if the characteristic has the Notify property
                        mBluetoothGatt.setCharacteristicNotification(gattCharacteristic, true); //If so then enable notification in the BluetoothGatt
                        BluetoothGattDescriptor descriptor = gattCharacteristic.getDescriptor(UUID.fromString(CHARACTERISTIC_NOTIFICATION_CONFIG)); //Get the descripter that enables notification on the server
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); //Set the value of the descriptor to enable notification
                        mBluetoothGatt.writeDescriptor(descriptor);                     //Write the descriptor
                    }
                    if ((characteristicProperties & (BluetoothGattCharacteristic.PROPERTY_INDICATE)) > 0) { //See if the characteristic has the Indicate property
                        mBluetoothGatt.setCharacteristicNotification(gattCharacteristic, true); //If so then enable notification (and indication) in the BluetoothGatt
                        BluetoothGattDescriptor descriptor = gattCharacteristic.getDescriptor(UUID.fromString(CHARACTERISTIC_NOTIFICATION_CONFIG)); //Get the descripter that enables indication on the server
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE); //Set the value of the descriptor to enable indication
                        mBluetoothGatt.writeDescriptor(descriptor);                     //Write the descriptor
                    }
                    if ((characteristicProperties & (BluetoothGattCharacteristic.PROPERTY_WRITE)) > 0) { //See if the characteristic has the Write (acknowledged) property
                        gattCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); //If so then set the write type (write with acknowledge) in the BluetoothGatt
                    }
                    if ((characteristicProperties & (BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) > 0) { //See if the characteristic has the Write (unacknowledged) property
                        gattCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE); //If so then set the write type (write with no acknowledge) in the BluetoothGatt
                    }
                }
                break;                                                                  //Found the MLDP service and are not looking for any other services
            }
        }
        if (mDataMDLP == null) {                                                        //See if the MLDP data characteristic was not found
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(ConnectedReceiverActivity.this, R.string.mldp_not_supported, Toast.LENGTH_SHORT).show(); //If so then show an error message
                    Log.d(TAG, "findMldpGattService found no MLDP service");
                    finish();                                                                   //and end the activity
                }
            });
        }
    }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) { //Change in connection state
            if (newState == BluetoothProfile.STATE_CONNECTED) {                         //See if we are connected
                Log.i(TAG, "Connected to GATT server.");
                mConnected = true;                                                      //Record the new connection state
                updateConnectionState(R.string.connected);                              //Update the display to say "Connected"
                invalidateOptionsMenu();                                                //Force the Options menu to be regenerated to show the disconnect option
                mBluetoothGatt.discoverServices();                                      // Attempt to discover services after successful connection.
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {                 //See if we are not connected
                Log.i(TAG, "Disconnected from GATT server.");
                mConnected = false;                                                     //Record the new connection state
                updateConnectionState(R.string.disconnected);                           //Update the display to say "Disconnected"
                invalidateOptionsMenu();                                                //Force the Options menu to be regenerated to show the connect option
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {              //Service discovery complete
            if (status == BluetoothGatt.GATT_SUCCESS && mBluetoothGatt != null) {       //See if the service discovery was successful
                findMldpGattService(mBluetoothGatt.getServices());                      //Get the list of services and call method to look for MLDP service
            } else {                                                                      //Service discovery was not successful
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }

        //For information only. This application uses Indication to receive updated characteristic data, not Read
        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) { //A request to Read has completed
            if (status == BluetoothGatt.GATT_SUCCESS) {                                     //See if the read was successful
                String dataValue = characteristic.getStringValue(0);                        //Get the value of the characteristic
                processIncomingPacket(dataValue);                                           //Process the data that was received
            }
        }

        //For information only. This application sends small packets infrequently and does not need to know what the previous write completed
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) { //A request to Write has completed
            if (status == BluetoothGatt.GATT_SUCCESS) {                                 //See if the write was successful
                writeComplete = true;                                                   //Record that the write has completed
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) { //Indication or notification was received
            String dataValue = characteristic.getStringValue(0);                        //Get the value of the characteristic
            processIncomingPacket(dataValue);                                           //Process the data that was received
        }
    };

    private void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {                      //Check that we have access to a Bluetooth radio
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(characteristic);                              //Request the BluetoothGatt to Read the characteristic
    }

    private void writeCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {                      //Check that we have access to a Bluetooth radio
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        int test = characteristic.getProperties();                                      //Get the properties of the characteristic
        if ((test & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0 && (test & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) { //Check that the property is writable
            return;
        }

        if (mBluetoothGatt.writeCharacteristic(characteristic)) {                       //Request the BluetoothGatt to do the Write
            Log.d(TAG, "writeCharacteristic successful");                               //The request was accepted, this does not mean the write completed
        } else {
            Log.d(TAG, "writeCharacteristic failed");                                   //Write request was not accepted by the BluetoothGatt
        }
    }
}


