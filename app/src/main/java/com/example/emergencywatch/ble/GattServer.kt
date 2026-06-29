package com.example.emergencywatch.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log

/**
 * 워치 = BLE GATT 서버(주변기기).
 *  1) SERVICE_UUID 를 광고(advertise) -> Jetson이 스캔으로 발견
 *  2) Jetson이 연결 후 ALERT_CHAR_UUID 에 4바이트 write
 *  3) onCharacteristicWriteRequest 콜백에서 디코드 -> [onAlert] 로 전달
 *
 * 권한(BLUETOOTH_ADVERTISE / BLUETOOTH_CONNECT)은 호출 측(MainActivity)에서
 * 먼저 확인/요청한 뒤 start() 를 부른다. 그래서 여기선 @SuppressLint 로 표시.
 */
@SuppressLint("MissingPermission")
class GattServer(
    private val context: Context,
    private val onAlert: (AlertData) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val tag = "GattServer"
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = manager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    /** GATT 서버 열고 광고 시작. */
    fun start() {
        if (adapter == null || !adapter.isEnabled) {
            onStatus("블루투스가 꺼져 있습니다")
            return
        }

        // 1) GATT 서버 + 서비스/특성 등록
        val server = manager.openGattServer(context, serverCallback)
        val service = BluetoothGattService(
            AlertProtocol.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        val alertChar = BluetoothGattCharacteristic(
            AlertProtocol.ALERT_CHAR_UUID,
            // Write(응답O) + WriteNoResponse(응답X, 더 빠름) 둘 다 허용
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        service.addCharacteristic(alertChar)
        server.addService(service)
        gattServer = server

        // 2) 광고 시작
        startAdvertising()
        onStatus("광고 중 — Jetson 연결 대기")
    }

    private fun startAdvertising() {
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) {
            onStatus("이 기기는 BLE 광고를 지원하지 않습니다")
            return
        }
        advertiser = adv

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY) // 빠른 발견 = 저지연
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // 이름은 길어서 광고패킷 초과 위험 -> 끔
            .addServiceUuid(ParcelUuid(AlertProtocol.SERVICE_UUID))
            .build()

        adapter.name = AlertProtocol.DEVICE_NAME // 기기 이름 설정(식별용)
        adv.startAdvertising(settings, data, advCallback)
    }

    /** 광고/서버 정리. */
    fun stop() {
        try {
            advertiser?.stopAdvertising(advCallback)
            gattServer?.close()
        } catch (e: Exception) {
            Log.w(tag, "stop error: ${e.message}")
        }
        advertiser = null
        gattServer = null
    }

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.e(tag, "advertise fail: $errorCode")
            onStatus("광고 실패 (코드 $errorCode)")
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> onStatus("연결됨: ${device.address}")
                BluetoothProfile.STATE_DISCONNECTED -> onStatus("연결 끊김 — 광고 재대기")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid == AlertProtocol.ALERT_CHAR_UUID) {
                val alert = AlertProtocol.decode(value)
                if (alert != null) {
                    Log.d(tag, "수신: ${alert.toKorean()}")
                    onAlert(alert) // -> MainActivity에서 진동 + 화면 갱신
                }
            }
            // 응답이 필요하면 성공 응답 전송 (WriteNoResponse면 responseNeeded=false)
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }
}
