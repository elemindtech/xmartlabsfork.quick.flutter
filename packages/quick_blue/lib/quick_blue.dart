import 'dart:async';
import 'dart:typed_data';

import 'src/quick_blue_platform_interface.dart';

export 'src/models.dart';
export 'src/quick_blue_linux.dart';
export 'src/quick_blue_platform_interface.dart';

class QuickBlue {
  // static QuickBluePlatform _platform = QuickBluePlatform.instance;

  // static set platform(QuickBluePlatform platform) {
  //   _platform = platform;
  // }

  // static void setInstance(QuickBluePlatform instance) => _platform = instance;

  static void setLogger(QuickLogger logger) => QuickBluePlatform.instance.setLogger(logger);

  static Future<bool> isBluetoothAvailable() =>
      QuickBluePlatform.instance.isBluetoothAvailable();

  static Stream<AvailabilityState> get availabilityChangeStream =>
      QuickBluePlatform.instance.availabilityChangeStream.map(AvailabilityState.parse);

  static Future<void> startScan([List<String>? advertisedServices]) =>
      QuickBluePlatform.instance.startScan(advertisedServices);

  static void stopScan() => QuickBluePlatform.instance.stopScan();

  static Stream<BlueScanResult> get scanResultStream {
    return QuickBluePlatform.instance.scanResultStream
        .map((item) => BlueScanResult.fromMap(item));
  }

  static Future<void> connect(String deviceId) => QuickBluePlatform.instance.connect(deviceId);

  static Future<void> disconnect(String deviceId) async {
    print('[disconnect] called');
    try {
      print('[disconnect] attempting platform disconnect');
      await QuickBluePlatform.instance.disconnect(deviceId);
      print('[disconnect] success');
    } catch (e) {
      rethrow;
    }
  }

  static void setConnectionHandler(OnConnectionChanged? onConnectionChanged) {
    QuickBluePlatform.instance.onConnectionChanged = onConnectionChanged;
  }

  static void discoverServices(String deviceId) =>
      QuickBluePlatform.instance.discoverServices(deviceId);

  static void setServiceHandler(OnServiceDiscovered? onServiceDiscovered) {
    QuickBluePlatform.instance.onServiceDiscovered = onServiceDiscovered;
  }

  static Future<void> setNotifiable(String deviceId, String service,
      String characteristic, BleInputProperty bleInputProperty) {
    return QuickBluePlatform.instance.setNotifiable(
        deviceId, service, characteristic, bleInputProperty);
  }

  static void setValueHandler(OnValueChanged? onValueChanged) {
    QuickBluePlatform.instance.onValueChanged = onValueChanged;
  }

  static Future<void> readValue(
      String deviceId, String service, String characteristic) {
    return QuickBluePlatform.instance.readValue(deviceId, service, characteristic);
  }

  static Future<void> writeValue(
      String deviceId,
      String service,
      String characteristic,
      Uint8List value,
      BleOutputProperty bleOutputProperty) {
    return QuickBluePlatform.instance.writeValue(
        deviceId, service, characteristic, value, bleOutputProperty);
  }

  static Future<int> requestMtu(String deviceId, int expectedMtu) =>
      QuickBluePlatform.instance.requestMtu(deviceId, expectedMtu);
}
