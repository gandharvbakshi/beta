import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("update_play_data_safety.py")
SPEC = importlib.util.spec_from_file_location("update_play_data_safety", SCRIPT)
data_safety = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = data_safety
SPEC.loader.exec_module(data_safety)


class RemoveRetiredPhotosTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.purpose_ids = (
            "PSL_APP_FUNCTIONALITY",
            "PSL_ANALYTICS",
            "PSL_DEVELOPER_COMMUNICATIONS",
            "PSL_FRAUD_PREVENTION_SECURITY",
            "PSL_ADVERTISING",
            "PSL_PERSONALIZATION",
            "PSL_ACCOUNT_MANAGEMENT",
        )

    def current_template_rows(self):
        rows = [{
            "Question ID (machine readable)": "PSL_DATA_TYPES_PHOTOS_AND_VIDEOS",
            "Response ID (machine readable)": "PSL_PHOTOS",
            "Response value": "true",
            "Human-friendly question label": "Photos and videos/Photos",
        }]
        prefix = "PSL_DATA_USAGE_RESPONSES:PSL_PHOTOS:"
        answers = [
            ("PSL_DATA_USAGE_COLLECTION_AND_SHARING", "PSL_DATA_USAGE_ONLY_COLLECTED", "true"),
            ("PSL_DATA_USAGE_COLLECTION_AND_SHARING", "PSL_DATA_USAGE_ONLY_SHARED", ""),
            ("PSL_DATA_USAGE_EPHEMERAL", "", "false"),
            ("DATA_USAGE_USER_CONTROL", "PSL_DATA_USAGE_USER_CONTROL_OPTIONAL", ""),
            ("DATA_USAGE_USER_CONTROL", "PSL_DATA_USAGE_USER_CONTROL_REQUIRED", "true"),
        ]
        answers.extend(("DATA_USAGE_COLLECTION_PURPOSE", purpose, "true" if purpose == "PSL_APP_FUNCTIONALITY" else "") for purpose in self.purpose_ids)
        answers.extend(("DATA_USAGE_SHARING_PURPOSE", purpose, "") for purpose in self.purpose_ids)
        rows.extend({
            "Question ID (machine readable)": f"{prefix}{suffix}",
            "Response ID (machine readable)": response,
            "Response value": value,
            "Human-friendly question label": f"Photos detail/{suffix}/{response}",
        } for suffix, response, value in answers)
        rows.append({
            "Question ID (machine readable)": "PSL_DATA_TYPES_AUDIO",
            "Response ID (machine readable)": "PSL_AUDIO",
            "Response value": "true",
            "Human-friendly question label": "Audio files/Voice or sound recordings",
        })
        return rows

    def test_only_photo_response_values_change(self):
        rows = self.current_template_rows()
        original = [row.copy() for row in rows]

        changed = data_safety.remove_retired_photos_only(rows)

        self.assertEqual(changed, 20)
        for before, after in zip(original, rows):
            if before["Question ID (machine readable)"] == "PSL_DATA_TYPES_PHOTOS_AND_VIDEOS":
                self.assertEqual(after["Response value"], "false")
            elif before["Question ID (machine readable)"].startswith("PSL_DATA_USAGE_RESPONSES:PSL_PHOTOS:"):
                self.assertEqual(after["Response value"], "")
            else:
                self.assertEqual(after, before)
            self.assertEqual(
                {key: value for key, value in after.items() if key != "Response value"},
                {key: value for key, value in before.items() if key != "Response value"},
            )

    def test_missing_or_mismatched_template_fails_without_mutating(self):
        rows = self.current_template_rows()
        rows.pop(-2)
        original = [row.copy() for row in rows]
        with self.assertRaises(SystemExit):
            data_safety.remove_retired_photos_only(rows)
        self.assertEqual(rows, original)

    def test_missing_selected_photos_type_fails_closed(self):
        rows = self.current_template_rows()
        rows[0]["Response value"] = "false"
        with self.assertRaises(SystemExit):
            data_safety.remove_retired_photos_only(rows)


if __name__ == "__main__":
    unittest.main()
