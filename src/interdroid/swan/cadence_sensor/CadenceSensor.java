package interdroid.swan.cadence_sensor;

import interdroid.swan.sensors.AbstractConfigurationActivity;
import interdroid.swan.sensors.AbstractVdbSensor;
import interdroid.vdb.content.avro.AvroContentProviderProxy;

import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAdapter.LeScanCallback;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

// link to android library: vdb-avro

/**
 * A sensor for Wahoo Blue SC cycling speed and cadence sensor (or compatible
 * devices)
 * 
 * @author roelof &lt;rkemp@cs.vu.nl&gt;
 * 
 */
public class CadenceSensor extends AbstractVdbSensor {

	private static final String TAG = "CadenceSensor";

	/**
	 * The configuration activity for this sensor.
	 */
	public static class ConfigurationActivity extends
			AbstractConfigurationActivity {

		@Override
		public final int getPreferencesXML() {
			return R.xml.cadence_preferences;
		}

	}

	/**
	 * The crank_revolutions field.
	 */
	public static final String CRANK_REVOLUTIONS_FIELD = "crank_revolutions";

	/**
	 * The wheel_revolutions field.
	 */
	public static final String WHEEL_REVOLUTIONS_FIELD = "wheel_revolutions";

	/**
	 * The status field.
	 */
	public static final String STATUS_TEXT_FIELD = "status_text";

	/**
	 * The schema for this sensor.
	 */
	public static final String SCHEME = getSchema();

	/**
	 * The provider for this sensor.
	 */
	public static class Provider extends AvroContentProviderProxy {

		/**
		 * Construct the provider for this sensor.
		 */
		public Provider() {
			super(SCHEME);
		}

	}

	/**
	 * @return the schema for this sensor.
	 */
	private static String getSchema() {
		String scheme = "{'type': 'record', 'name': 'cadence', "
				+ "'namespace': 'interdroid.swan.cadence_sensor.cadence',"
				+ "\n'fields': [" + SCHEMA_TIMESTAMP_FIELDS + "\n{'name': '"
				+ CRANK_REVOLUTIONS_FIELD + "', 'type': 'int'},"
				+ "\n{'name': '" + WHEEL_REVOLUTIONS_FIELD
				+ "', 'type': 'int'}," + "\n{'name': '" + STATUS_TEXT_FIELD
				+ "', 'type': 'string'}" + "\n]" + "}";
		return scheme.replace('\'', '"');
	}

	@Override
	public final String[] getValuePaths() {
		return new String[] { CRANK_REVOLUTIONS_FIELD, WHEEL_REVOLUTIONS_FIELD,
				STATUS_TEXT_FIELD };
	}

	@Override
	public void initDefaultConfiguration(final Bundle defaults) {
	}

	@Override
	public final String getScheme() {
		return SCHEME;
	}

	@Override
	public void onConnected() {
		/* Perform sensor specific sensor setup. */
		storeStatus("Connected to SWAN sensor");
		getAdapter();
		registerReceiver(receiver, new IntentFilter(
				BluetoothAdapter.ACTION_STATE_CHANGED));
	}

	private BroadcastReceiver receiver = new BroadcastReceiver() {

		@Override
		public void onReceive(Context context, Intent intent) {
			int newState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
					BluetoothAdapter.STATE_OFF);
			int oldState = intent.getIntExtra(
					BluetoothAdapter.EXTRA_PREVIOUS_STATE,
					BluetoothAdapter.STATE_OFF);
			storeStatus("Bluetooth state changed: " + asString(oldState)
					+ " -> " + asString(newState));
			if (newState == BluetoothAdapter.STATE_ON) {
				int nrCrankExpressions = expressionIdsPerValuePath
						.get(CRANK_REVOLUTIONS_FIELD) == null ? 0
						: expressionIdsPerValuePath
								.get(CRANK_REVOLUTIONS_FIELD).size();
				int nrWheelExpressions = expressionIdsPerValuePath
						.get(WHEEL_REVOLUTIONS_FIELD) == null ? 0
						: expressionIdsPerValuePath
								.get(WHEEL_REVOLUTIONS_FIELD).size();
				if (nrCrankExpressions + nrWheelExpressions > 0) {
					startScanning();
				}
			}
		}

		private String asString(int state) {
			switch (state) {
			case BluetoothAdapter.STATE_OFF:
				return "'off'";
			case BluetoothAdapter.STATE_ON:
				return "'on'";
			case BluetoothAdapter.STATE_TURNING_OFF:
				return "'turning off'";
			case BluetoothAdapter.STATE_TURNING_ON:
				return "'turning on'";
			}
			return "'unknown'";
		}
	};

	private void getAdapter() {
		adapter = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE))
				.getAdapter();
		if (adapter == null || !adapter.isEnabled()) {
			storeStatus("Could not get Bluetooth Adapter, is Bluetooth turned on?");
		}
	}

	private void startScanning() {
		if (!scanning && gatt == null) {
			scanning = true;
			// create a thread that after some time stops scanning
			new Thread() {
				public void run() {
					synchronized (CadenceSensor.this) {
						// wait 5 seconds for the device being found
						try {
							CadenceSensor.this.wait(5000);
						} catch (InterruptedException e) {
						}
						if (scanning) {
							adapter.stopLeScan(scanCallback);
							scanning = false;
							storeStatus("Timeout scan. Nothing found");
						}

					}
				}
			}.start();
			// start scanning for a device containing the cadence service
			storeStatus("Starting scan");
			adapter.startLeScan(new UUID[] { CADENCE_SERVICE }, scanCallback);
		}
	}

	@Override
	public final void register(final String id, final String valuePath,
			final Bundle configuration) {
		if (adapter == null) {
			getAdapter();
		}
		if (valuePath.equals(STATUS_TEXT_FIELD) || adapter == null
				|| !adapter.isEnabled()) {
			return;
		}
		startScanning();
	}

	@Override
	public final void unregister(final String id) {
		if (adapter == null || !adapter.isEnabled()) {
			return;
		}
		int nrCrankExpressions = expressionIdsPerValuePath
				.get(CRANK_REVOLUTIONS_FIELD) == null ? 0
				: expressionIdsPerValuePath.get(CRANK_REVOLUTIONS_FIELD).size();
		int nrWheelExpressions = expressionIdsPerValuePath
				.get(WHEEL_REVOLUTIONS_FIELD) == null ? 0
				: expressionIdsPerValuePath.get(WHEEL_REVOLUTIONS_FIELD).size();
		if (nrCrankExpressions + nrWheelExpressions == 0) {
			if (gatt != null) {
				storeStatus("Sensor connection closed");
				gatt.close();
				gatt = null;
			}

		}
	}

	@Override
	public final void onDestroySensor() {
		unregisterReceiver(receiver);

		if (adapter != null) {
			adapter = null;
		}
	}

	/**
	 * Data Storage Helper Method.
	 * 
	 * @param crank_revolutions
	 *            value for crank_revolutions
	 * @param wheel_revolutions
	 *            value for wheel_revolutions
	 * @param status
	 *            value for status
	 */
	private void storeReading(int crank_revolutions, int wheel_revolutions) {
		long now = System.currentTimeMillis();
		ContentValues values = new ContentValues();
		if (prevCrankRevolutions != crank_revolutions) {
			values.put(CRANK_REVOLUTIONS_FIELD, crank_revolutions);
			prevCrankRevolutions = crank_revolutions;
		}
		if (prevWheelRevolutions != wheel_revolutions) {
			values.put(WHEEL_REVOLUTIONS_FIELD, wheel_revolutions);
			prevWheelRevolutions = wheel_revolutions;
		}
		putValues(values, now);
	}

	/**
	 * =-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=- Sensor Specific Implementation
	 * =-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
	 */

	// https://developer.bluetooth.org/gatt/characteristics/Pages/CharacteristicViewer.aspx?u=org.bluetooth.characteristic.csc_measurement.xml
	public final static UUID CADENCE_CHARACTERISTIC = UUID
			.fromString("00002a5b-0000-1000-8000-00805f9b34fb");

	// https://developer.bluetooth.org/gatt/services/Pages/ServiceViewer.aspx?u=org.bluetooth.service.cycling_speed_and_cadence.xml
	public final static UUID CADENCE_SERVICE = UUID
			.fromString("00001816-0000-1000-8000-00805f9b34fb");

	// https://developer.bluetooth.org/gatt/descriptors/Pages/DescriptorViewer.aspx?u=org.bluetooth.descriptor.gatt.client_characteristic_configuration.xml
	public final static UUID CLIENT_CHARACTERISTIC_CONFIG = UUID
			.fromString("00002902-0000-1000-8000-00805f9b34fb");

	private BluetoothAdapter adapter;
	private BluetoothGatt gatt;
	private volatile boolean scanning = false;
	private int prevCrankRevolutions;
	private int prevWheelRevolutions;

	private LeScanCallback scanCallback = new LeScanCallback() {

		@Override
		public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
			adapter.stopLeScan(scanCallback);

			storeStatus("Found cadence sensor: " + device.getName()
					+ ", bond state: " + device.getBondState() + ", address: "
					+ device.getAddress());
			gatt = device.connectGatt(CadenceSensor.this, false, gattCallback);

			// inform the abort thread.
			scanning = false;
			synchronized (CadenceSensor.this) {
				CadenceSensor.this.notifyAll();
			}

		}
	};

	private BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

		public void onConnectionStateChange(BluetoothGatt gatt, int status,
				int newState) {
			if (status != BluetoothGatt.GATT_SUCCESS
					|| newState != BluetoothGatt.STATE_CONNECTED) {
				String stateString = "";
				switch (newState) {
				case BluetoothGatt.STATE_CONNECTING:
					stateString = "connecting";
					break;
				case BluetoothGatt.STATE_DISCONNECTING:
					stateString = "disconnecting";
					break;
				case BluetoothGatt.STATE_CONNECTED:
					stateString = "connected";
					break;
				case BluetoothGatt.STATE_DISCONNECTED:
					stateString = "disconnected";
					break;
				}
				String statusString = "" + status;
				switch (status) {
				case BluetoothGatt.GATT_SUCCESS:
					statusString = "success";
					break;
				case BluetoothGatt.GATT_FAILURE:
					statusString = "failure";
					break;
				}
				storeStatus("Failed to connect (status=" + statusString
						+ ", newState=" + stateString
						+ "). Try restarting BT radio.");
			} else {
				storeStatus("Connected to cadence sensor. Discovering services...");
				boolean started = gatt.discoverServices();
				storeStatus("Discovery " + (started ? "started" : "failed"));
			}

		};

		public void onServicesDiscovered(BluetoothGatt gatt, int status) {
			if (status != BluetoothGatt.GATT_SUCCESS) {
				CadenceSensor.this.gatt = null;
				storeStatus("Discovery completed, but failed: " + status);
				return;
			}
			storeStatus("Discovery completed successfully");
			BluetoothGattService cadenceService = gatt
					.getService(CADENCE_SERVICE);
			BluetoothGattCharacteristic cadenceCharacteristic = cadenceService
					.getCharacteristic(CADENCE_CHARACTERISTIC);
			boolean success = gatt.setCharacteristicNotification(
					cadenceCharacteristic, true);
			storeStatus((success ? "S" : "Failed to s")
					+ "tart listening for cadence updates");
			if (!success) {
				return;
			}
			BluetoothGattDescriptor descriptor = cadenceCharacteristic
					.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
			descriptor
					.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
			success = gatt.writeDescriptor(descriptor);
			storeStatus((success ? "Enabled " : "Failed to enable ")
					+ "data notification");
		};

		public void onCharacteristicChanged(BluetoothGatt gatt,
				BluetoothGattCharacteristic characteristic) {
			// hmm, apparently the flags are not in getProperties, but in the
			// first byte...
			int offset = 0;
			int flags = characteristic.getIntValue(
					BluetoothGattCharacteristic.FORMAT_UINT8, offset);
			offset += 1;
			boolean wheelRevolutionPresent = ((flags & 0x01) != 0);
			boolean crankRevolutionPresent = ((flags & 0x02) != 0);
			int wheelRevolutions = -1;
			int lastWheelRevolutionTime = -1;
			int crankRevolutions = -1;
			int lastCrankRevolutionTime = -1;
			if (wheelRevolutionPresent) {
				wheelRevolutions = characteristic.getIntValue(
						BluetoothGattCharacteristic.FORMAT_UINT32, offset);
				offset += 4;
				lastWheelRevolutionTime = characteristic.getIntValue(
						BluetoothGattCharacteristic.FORMAT_UINT16, offset);
				offset += 2;
			}
			if (crankRevolutionPresent) {
				crankRevolutions = characteristic.getIntValue(
						BluetoothGattCharacteristic.FORMAT_UINT16, offset);
				offset += 2;
				lastCrankRevolutionTime = characteristic.getIntValue(
						BluetoothGattCharacteristic.FORMAT_UINT16, offset);
			}
			storeReading(crankRevolutions, wheelRevolutions);
		};

	};

	private void storeStatus(final String status) {
		new Thread() {
			public void run() {
				long now = System.currentTimeMillis();
				ContentValues values = new ContentValues();
				values.put(STATUS_TEXT_FIELD, status);
				putValues(values, now);
			}
		}.start();

	}

}