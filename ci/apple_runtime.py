"""Select a simulator destination without substituting a requested runtime."""


def select_runtime(devices, runtimes, required=None):
    available = {r['identifier']: r for r in runtimes if r.get('isAvailable')
                 and '.iOS-' in r['identifier']}
    if required is not None:
        if required not in available:
            raise ValueError('Required iOS runtime unavailable: ' + required)
        # Downloaded runtimes need not include any pre-created devices. simctl
        # create remains responsible for rejecting an incompatible device type.
        return required, {'name': 'iPhone 15',
                          'deviceTypeIdentifier': 'com.apple.CoreSimulator.SimDeviceType.iPhone-15'}
    choices = [(rt, d) for rt, group in devices.items() if rt in available
               for d in group if d.get('isAvailable') and d.get('deviceTypeIdentifier')]
    if not choices:
        raise ValueError('No installed available iOS runtime/device combination')
    return next((p for p in choices if 'iPad' in p[1]['name']), choices[0])


def verify_test_runtime(summary, device, runtime):
    actual = [d['device'] for d in summary['devicesAndConfigurations']]
    if not actual or any(d['deviceId'] != device or d['osVersion'] != runtime['version']
                         or d['osBuildNumber'] != runtime['buildversion'] for d in actual):
        raise ValueError('XCTest destination does not match selected runtime/device')
