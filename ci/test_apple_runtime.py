"""Fail-closed destination selection and XCTest receipt checks, without simulator claims."""
import unittest

from apple_runtime import select_runtime, verify_test_runtime

IOS17 = 'com.apple.CoreSimulator.SimRuntime.iOS-17-5'
IOS18 = 'com.apple.CoreSimulator.SimRuntime.iOS-18-5'


class RuntimeSelectionTest(unittest.TestCase):
    def setUp(self):
        self.runtime = {'identifier': IOS17, 'isAvailable': True,
                        'version': '17.5', 'buildversion': '21F79'}

    def test_downloaded_runtime_without_template_is_selected_exactly(self):
        rt, device = select_runtime({}, [self.runtime], IOS17)
        self.assertEqual(rt, IOS17)
        self.assertTrue(device['deviceTypeIdentifier'].endswith('.iPhone-15'))

    def test_missing_unavailable_and_empty_request_never_fall_back(self):
        other = dict(self.runtime, identifier=IOS18)
        for requested, inventory in [(IOS17, [other]),
                                     (IOS17, [dict(self.runtime, isAvailable=False)]), ('', [other])]:
            with self.subTest(requested=requested, inventory=inventory):
                with self.assertRaises(ValueError):
                    select_runtime({}, inventory, requested)

    def test_default_prefers_available_ipad(self):
        phone = {'name': 'iPhone', 'isAvailable': True, 'deviceTypeIdentifier': 'phone'}
        pad = dict(phone, name='iPad', deviceTypeIdentifier='pad')
        self.assertEqual(select_runtime({IOS17: [phone, pad]}, [self.runtime]), (IOS17, pad))
        with self.assertRaises(ValueError):
            select_runtime({IOS17: [pad]}, [dict(self.runtime, isAvailable=False)])

    def test_actual_xctest_device_version_and_build_are_required(self):
        device = {'deviceId': 'same-uuid', 'osVersion': '17.5', 'osBuildNumber': '21F79'}
        def summary(devices):
            return {'devicesAndConfigurations': [{'device': d} for d in devices]}
        verify_test_runtime(summary([device]), 'same-uuid', self.runtime)
        for actual in [[], [dict(device, deviceId='other')], [dict(device, osVersion='18.5')],
                       [dict(device, osBuildNumber='other')], [device, dict(device, osVersion='18.5')]]:
            with self.subTest(actual=actual):
                with self.assertRaises(ValueError):
                    verify_test_runtime(summary(actual), 'same-uuid', self.runtime)


if __name__ == '__main__':
    unittest.main()
