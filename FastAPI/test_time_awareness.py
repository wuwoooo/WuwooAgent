import datetime as py_datetime
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

BASE_DIR = Path(__file__).resolve().parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

import database

_TEMP_DB = tempfile.TemporaryDirectory()
database.DB_PATH = Path(_TEMP_DB.name) / "agent_chat_test.db"

import main


class FixedDateTime(py_datetime.datetime):
    @classmethod
    def now(cls, tz=None):
        return cls(2026, 5, 4, 18, 28, tzinfo=tz)


class TimeAwarenessTest(unittest.TestCase):
    def test_replaces_specific_plan_delivery_deadline(self):
        reply = "我这就把活动细节再优化下，下午3点前发您完整方案，您看这个时间方便接收吗？"

        repaired = main._repair_time_blind_reply(reply)

        self.assertEqual("好的，我这边继续把方案细节整理好，核算清楚后发您确认。", repaired)

    def test_replaces_today_morning_itinerary_after_morning_has_passed(self):
        reply = "那我帮您把喜洲小火车和磻溪S湾骑行都安排进5月4日的行程里，上午9点出发光线柔和适合拍照。"

        with patch.object(main.datetime, "datetime", FixedDateTime):
            repaired = main._repair_time_blind_reply(reply)

        self.assertIn("具体行程日期我还没确认", repaired)
        self.assertIn("计划哪天去大理玩", repaired)
        self.assertNotIn("上午9点", repaired)


if __name__ == "__main__":
    unittest.main()
