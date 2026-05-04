import unittest
import sys
import tempfile
from pathlib import Path
from unittest.mock import patch

BASE_DIR = Path(__file__).resolve().parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

import database

_TEMP_DB = tempfile.TemporaryDirectory()
database.DB_PATH = Path(_TEMP_DB.name) / "agent_chat_test.db"

import main


class _FakeDatabase:
    @staticmethod
    def get_session_messages(session_id):
        return []


class HandoffReadinessTest(unittest.TestCase):
    def test_final_quote_intent_does_not_handoff_when_required_info_missing(self):
        with patch.object(main, "database", _FakeDatabase):
            self.assertFalse(main._should_request_handoff("那你报个价吧", "test-session"))
            readiness = main._handoff_readiness(latest_text="那你报个价吧", session_id="test-session")

        self.assertIn("出行人数", readiness["missing"])
        self.assertIn("是否有病患或腿脚不便者", readiness["missing"])

    def test_final_quote_intent_handoffs_when_required_info_is_known(self):
        profile = {
            "people_count": "2大1小",
            "travel_time": "5月10日出发，5天4晚",
            "departure_city": "南京",
            "elder_child_status": "有一个孩子，没有老人",
            "health_mobility_status": "没有病患和腿脚不便者",
        }
        with patch.object(main, "database", _FakeDatabase):
            self.assertTrue(
                main._should_request_handoff(
                    "那就按这个方案报个价吧",
                    "test-session",
                    session_profile=profile,
                )
            )

    def test_latest_message_can_supply_required_info(self):
        latest = "我们3个人，5月10日从南京出发，有一个孩子，没有病患和腿脚不便，按这个方案报个价"
        with patch.object(main, "database", _FakeDatabase):
            readiness = main._handoff_readiness(latest_text=latest, session_id="test-session")
            should_handoff = main._should_request_handoff(latest, "test-session")

        self.assertEqual([], readiness["missing"])
        self.assertTrue(should_handoff)


if __name__ == "__main__":
    unittest.main()
