package com.example.android.auto_home;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;

import android.content.pm.ActivityInfo;
import android.os.AsyncTask;
import android.os.RemoteException;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.estimote.sdk.Beacon;
import com.estimote.sdk.BeaconManager;
import com.estimote.sdk.Region;
import com.estimote.sdk.utils.L;

import static com.estimote.sdk.BeaconManager.MonitoringListener;

/** Detects estimote regions and send messages to other phone based*/
public class RemoteActivity extends ActionBarActivity {

    private static final String TAG = RemoteActivity.class.getSimpleName();

    //Booleans for tracking the state of the remote devices
    //TODO: Implement these states
    private boolean lockClosed = true;
    private boolean lightsOff = true;
    private boolean estimoteStatus;

    private TextView lockStatus;
    private TextView lightStatus;

    private static final int REQUEST_ENABLE_BT = 1234;
    private Region DOOR_REGION;
    private static final int NOTIFICATION_ID = 123;
    private static final String UUID = "B9407F30-F5F8-466E-AFF9-25556B57FE6D";
    private static final int MAX = 28770;
    private static final int MIN = 0; //Unused at this point

    private BeaconManager beaconManager;
    private NotificationManager notificationManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Remote Mode");
        setContentView(R.layout.fragment_remote);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        lockStatus = (TextView)findViewById(R.id.lockStatus);
        lightStatus = (TextView)findViewById(R.id.lightsStatus);

        // Configure verbose debug logging.
        L.enableDebugLogging(true);

        //Setup notification manager for sending regions change notifications
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // Configure BeaconManager.
        DOOR_REGION = new Region("DOOR", UUID, MAX, null);
        beaconManager = new BeaconManager(this);
        beaconManager.setForegroundScanPeriod(1000, 0);
        beaconManager.setBackgroundScanPeriod(1000, 0);
        beaconManager.setMonitoringListener(new MonitoringListener() {
            //On entering the Ice region, send a Lock message
            @Override
            public void onEnteredRegion(final Region region, List<Beacon> beacons) {
                TextView regionNote = (TextView) findViewById(R.id.region_text);
                regionNote.setText(R.string.iceRegion);
                postNotification("Entered Door Region");
                SendUnlockTask myTask = new SendUnlockTask();
                myTask.execute();
                SendLightsOnTask twoTask = new SendLightsOnTask();
                twoTask.execute();
            }

            @Override
            public void onExitedRegion(Region region) {
                TextView regionNote = (TextView) findViewById(R.id.region_text);
                regionNote.setText(R.string.noRegion);
                postNotification("Exited Door Region");
                SendLockTask myTask = new SendLockTask();
                myTask.execute();
                SendLightsOffTask twoTask = new SendLightsOffTask();
                twoTask.execute();
            }
        });

        estimoteStatus = true;
        InitSettingsTask initTask = new InitSettingsTask();
        initTask.execute();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_switch) {
            try {
                beaconManager.stopRanging(DOOR_REGION);
            } catch (RemoteException e) {
                Log.d(TAG, "Error while stopping ranging", e);
            }
            beaconManager.disconnect();
            Intent intent = new Intent(RemoteActivity.this, ReceiverActivity.class);
            startActivity(intent);
            finish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    //Disables the back function so the user can't accidentally go back
    // to the remote mode view.
    @Override
    public void onBackPressed() {}

    //Post notification to let user know the regions changes
    private void postNotification(String msg) {
        Intent notifyIntent = new Intent(RemoteActivity.this, RemoteActivity.class);
        notifyIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivities(
                RemoteActivity.this,
                0,
                new Intent[]{notifyIntent},
                PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(RemoteActivity.this)
                .setSmallIcon(R.mipmap.autohomelogo)
                .setContentTitle("Auto Home")
                .setContentText(msg)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build();
        notification.defaults |= Notification.DEFAULT_SOUND;
        notification.defaults |= Notification.DEFAULT_LIGHTS;
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    //Sends a lock message
    public void sendLockMessage(View view) {
        lockClosed = true;
        SendLockTask myTask = new SendLockTask();
        myTask.execute();
        lockStatus.setText(R.string.lockLocked);
    }

    //Sends a lock message
    public void sendUnlockMessage(View view) {
        lockClosed = false;
        SendUnlockTask myTask = new SendUnlockTask();
        myTask.execute();
        lockStatus.setText(R.string.lockUnlocked);
    }

    //Sends a lights message
    public void sendLightsOnMessage(View view) {
        lightsOff = false;
        SendLightsOnTask myTask = new SendLightsOnTask();
        myTask.execute();
        lightStatus.setText(R.string.lightsOn);
    }

    //Sends a lights message
    public void sendLightsOffMessage(View view) {
        lightsOff = true;
        SendLightsOffTask myTask = new SendLightsOffTask();
        myTask.execute();
        lightStatus.setText(R.string.lightsOff);
    }

    /******* ESTIMOTE FUNCTIONALITY ***************************************************************/

    @Override
    protected void onStart() {
        super.onStart();
        // Check if device supports Bluetooth Low Energy.
        if (!beaconManager.hasBluetooth()) {
            Toast.makeText(this, "Device does not have Bluetooth Low Energy", Toast.LENGTH_LONG).show();
            return;
        }

        // If Bluetooth is not enabled, let user enable it.
        if (!beaconManager.isBluetoothEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        } else {
            beaconManager.connect(new BeaconManager.ServiceReadyCallback() {
                @Override
                public void onServiceReady() {
                    try {
                        beaconManager.startRanging(DOOR_REGION);
                    } catch (RemoteException e) {
                        Toast.makeText(RemoteActivity.this, "Cannot start ranging, something terrible happened",
                                Toast.LENGTH_LONG).show();
                        Log.e(TAG, "Cannot start ranging", e);
                    }
                }
            });
        }

    }

    @Override
    protected void onStop() {
        try {
            beaconManager.stopRanging(DOOR_REGION);
        } catch (RemoteException e) {
            Log.d(TAG, "Error while stopping ranging", e);
        }

        super.onStop();
    }

    @Override
    protected void onDestroy() {
        beaconManager.disconnect();
        notificationManager.cancel(NOTIFICATION_ID);
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == Activity.RESULT_OK) {
                connectToService();
            } else {
                Toast.makeText(this, "Bluetooth not enabled", Toast.LENGTH_LONG).show();
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        notificationManager.cancel(NOTIFICATION_ID);
        if(estimoteStatus) {
            try {
                beaconManager.startMonitoring(DOOR_REGION);
            } catch (RemoteException e) {
                Log.d(TAG, "Error while starting monitoring");
            }
        }
    }

    private void connectToService() {
        beaconManager.connect(new BeaconManager.ServiceReadyCallback() {
            @Override
            public void onServiceReady() {
                try {
                    beaconManager.startRanging(DOOR_REGION);
                } catch (RemoteException e) {
                    Toast.makeText(RemoteActivity.this, "Cannot start ranging, something terrible happened",
                            Toast.LENGTH_LONG).show();
                    Log.e(TAG, "Cannot start ranging", e);
                }
            }
        });
    }

    public void toggleEstimote(View view) throws RemoteException {
        if(estimoteStatus){
            beaconManager.stopRanging(DOOR_REGION);
            Button status = (Button)findViewById(R.id.stop_estimote);
            status.setText(R.string.estimoteOn);
            TextView estimoteRegion = (TextView)findViewById(R.id.region_text);
            estimoteRegion.setText(R.string.nullRegion);
            estimoteStatus = false;
        } else {
            beaconManager.startRanging(DOOR_REGION);
            Button status = (Button)findViewById(R.id.stop_estimote);
            status.setText(R.string.estimoteOff);
            TextView estimoteRegion = (TextView)findViewById(R.id.region_text);
            estimoteRegion.setText(R.string.noRegion);
            estimoteStatus = true;
        }
    }

    class SendLockTask extends AsyncTask<Void, Void, Void> {

        protected Void doInBackground(Void... params) {
            try {
                File file = File.createTempFile("lock.txt", null, getApplication().getCacheDir());
                FileWriter writer = new FileWriter(file);
                writer.append("Lock");
                writer.flush();
                writer.close();
                AmazonS3Client s3Client = new AmazonS3Client( new BasicAWSCredentials( "REMOVED", "REMOVED"));
                s3Client.putObject("autohome","lock.txt", file);
            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        }
    }

    class SendUnlockTask extends AsyncTask<Void, Void, Void> {

        protected Void doInBackground(Void... params) {
            try {
                File file = File.createTempFile("lock.txt", null, getApplication().getCacheDir());
                FileWriter writer = new FileWriter(file);
                writer.append("Unlock");
                writer.flush();
                writer.close();
                AmazonS3Client s3Client = new AmazonS3Client( new BasicAWSCredentials( "REMOVED", "REMOVED"));
                s3Client.putObject("autohome","lock.txt", file);
            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        }
    }

    class SendLightsOnTask extends AsyncTask<Void, Void, Void> {

        protected Void doInBackground(Void... params) {
            try {
                File file = File.createTempFile("lights.txt", null, getApplication().getCacheDir());
                FileWriter writer = new FileWriter(file);
                writer.append("On");
                writer.flush();
                writer.close();
                AmazonS3Client s3Client = new AmazonS3Client( new BasicAWSCredentials( "REMOVED", "REMOVED"));
                s3Client.putObject("autohome","lights.txt", file);
            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        }
    }

    class SendLightsOffTask extends AsyncTask<Void, Void, Void> {

        protected Void doInBackground(Void... params) {
            try {
                File file = File.createTempFile("lights.txt", null, getApplication().getCacheDir());
                FileWriter writer = new FileWriter(file);
                writer.append("Off");
                writer.flush();
                writer.close();
                AmazonS3Client s3Client = new AmazonS3Client(new BasicAWSCredentials("REMOVED", "REMOVED"));
                s3Client.putObject("autohome", "lights.txt", file);
            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        }
    }

    class InitSettingsTask extends AsyncTask<Void, Void, Void> {
        protected Void doInBackground (Void... params){
            AmazonS3Client s3Client = new AmazonS3Client(new BasicAWSCredentials("REMOVED", "REMOVED"));
            S3Object file = s3Client.getObject("autohome", "lights.txt");
            S3ObjectInputStream in = file.getObjectContent();
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in));

            try {
                String line = reader.readLine();
                Log.e("lights.txt", line);
                if (line.equals("On")){
                    lightsOff = false;
                } else if (line.equals("Off")){
                    lightsOff = true;
                }

            } catch (IOException e) {
                e.printStackTrace();
            }

            file = s3Client.getObject("autohome", "lock.txt");
            in = file.getObjectContent();
            reader = new BufferedReader(new InputStreamReader(in));

            try {
                String line = reader.readLine();
                Log.e("lock.txt", line);
                if (line.equals("Lock")){
                    lockClosed = true;
                } else if (line.equals("Unlock")){
                    lockClosed = false;
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            if (lockClosed)
                lockStatus.setText(R.string.lockLocked);
            else
                lockStatus.setText(R.string.lockUnlocked);

            if (lightsOff)
                lightStatus.setText(R.string.lightsOff);
            else
                lightStatus.setText(R.string.lightsOn);
        }
    }
}