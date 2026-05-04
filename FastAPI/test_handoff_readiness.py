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
    messages = []

    @staticmethod
    def get_session_messages(session_id):
        return list(_FakeDatabase.messages)


class HandoffReadinessTest(unittest.TestCase):
    def test_final_quote_intent_does_not_handoff_when_required_info_missing(self):
        _FakeDatabase.messages = []
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
        _FakeDatabase.messages = []
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
        _FakeDatabase.messages = []
        with patch.object(main, "database", _FakeDatabase):
            readiness = main._handoff_readiness(latest_text=latest, session_id="test-session")
            should_handoff = main._should_request_handoff(latest, "test-session")

        self.assertEqual([], readiness["missing"])
        self.assertTrue(should_handoff)

    def test_short_answer_continues_collecting_remaining_handoff_info(self):
        _FakeDatabase.messages = [
            {"role": "user", "content": "请问现在开始出方案了吗？"},
            {
                "role": "assistant",
                "content": "方案已经在做了，您方便确认下小朋友的具体年龄吗？这样能更好调整活动强度。",
            },
        ]
        profile = {
            "people_count": "2大1小",
            "travel_time": "5月10日出发，5天4晚",
            "elder_child_status": "有一个孩子，没有老人",
            "health_mobility_status": "没有病患和腿脚不便者",
        }
        with patch.object(main, "database", _FakeDatabase):
            readiness = main._handoff_readiness(
                latest_text="10岁",
                session_id="test-session",
                session_profile=profile,
            )
            active = main._is_handoff_info_collection_active("test-session")

        self.assertTrue(active)
        self.assertEqual(["出发地"], readiness["missing"])


if __name__ == "__main__":
    unittest.main()
