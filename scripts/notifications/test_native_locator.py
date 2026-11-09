"""Regression for the recorded OEM group/header/card ambiguity; no device or payload tests."""
from pathlib import Path
import sys
import importlib.util
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
spec = importlib.util.spec_from_file_location('notification_native_actions', Path(__file__).resolve().parents[1] / 'test-notification-actions-native.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
card, safe_tree = module.card, module.safe_tree


class NativeNotificationLocatorTest(unittest.TestCase):
    def test_expanded_title_header_does_not_hide_sibling_action_area(self):
        tree = ET.fromstring('''<hierarchy><node resource-id="com.android.systemui:id/expandableNotificationRow">
          <node><node text="越界合成通知 900002"/><node resource-id="android:id/expand_button"/></node>
          <node resource-id="android:id/actions"><node text="测试动作"/><node text="测试回复"/></node>
        </node></hierarchy>''')
        selected = card(tree, 900002)
        self.assertTrue(any(n.get('text') == '测试动作' for n in selected.iter('node')))
        self.assertTrue(selected.get('resource-id').endswith('expandableNotificationRow'))

    def test_collapsed_child_selects_enclosing_group_control(self):
        tree = ET.fromstring('''<hierarchy><node resource-id="com.android.systemui:id/expandableNotificationRow">
          <node resource-id="android:id/expand_button"/>
          <node resource-id="com.android.systemui:id/expandableNotificationRow"><node text="越界合成通知 900002"/></node>
        </node></hierarchy>''')
        self.assertTrue(any(n.get('resource-id') == 'android:id/expand_button' for n in card(tree, 900002).iter('node')))

    def test_other_notification_button_cannot_be_selected_and_text_stays_private(self):
        tree = ET.fromstring('''<hierarchy>
          <node resource-id="com.android.systemui:id/expandableNotificationRow"><node text="unrelated synthetic private fixture"/><node text="测试动作"/></node>
          <node resource-id="com.android.systemui:id/expandableNotificationRow"><node text="越界合成通知 900002"/><node resource-id="android:id/expand_button"/><node text="private fixture content"/></node>
        </hierarchy>''')
        selected = card(tree, 900002)
        self.assertFalse(any(n.get('text') == '测试动作' for n in selected.iter('node')))
        self.assertNotIn('private fixture', str(safe_tree(tree, 900002)))
        self.assertIsNone(card(tree, 900007))

    def test_hyperos_group_uses_real_clickable_parent_without_aosp_row_id(self):
        tree=ET.fromstring('''<hierarchy><node clickable="true" bounds="[413,347][1724,606]">
          <node resource-id="com.android.systemui:id/notification_children_container">
            <node clickable="false"><node resource-id="com.android.systemui:id/expanded"><node text="越界合成通知 900003"/></node></node>
          </node></node></hierarchy>''')
        selected=card(tree,900003)
        self.assertEqual('true',selected.get('clickable'))
        self.assertTrue(any(n.get('resource-id','').endswith('notification_children_container') for n in list(selected)))


if __name__ == '__main__':
    unittest.main()
