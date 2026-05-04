import tempfile
import unittest
from pathlib import Path

import database


class ContactMemoryFilterTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self._old_db_path = database.DB_PATH
        database.DB_PATH = Path(self._tmp.name) / "agent_chat_test.db"
        database.init_db()
        contact = database.resolve_contact("session-1", "测试客户")
        self.contact_id = int(contact["id"])

    def tearDown(self):
        database.DB_PATH = self._old_db_path
        self._tmp.cleanup()

    def test_filters_assistant_prompt_rewritten_as_customer_need(self):
        database.merge_contact_memory(
            self.contact_id,
            {
                "dynamic_state": {
                    "最近关注点": {
                        "value": "客户希望确认小朋友的具体年龄以适配亲子活动强度",
                        "confidence": "medium",
                    },
                    "下次跟进重点": {
                        "value": "继续确认出发地后再出完整方案",
                        "confidence": "medium",
                    },
                },
                "facts": [
                    {
                        "category": "客户需求",
                        "value": "客户希望确认小朋友的具体年龄以适配亲子活动强度",
                        "confidence": "medium",
                    },
                    {
                        "category": "背景事实",
                        "value": "同行小朋友10岁",
                        "confidence": "high",
                    },
                ],
            },
            session_id="session-1",
        )

        contact = database.get_contact(self.contact_id)
        memory = contact["memory"]
        self.assertNotIn("最近关注点", memory["dynamic_state"])
        self.assertEqual(
            "继续确认出发地后再出完整方案",
            memory["dynamic_state"]["下次跟进重点"]["value"],
        )
        fact_values = [fact["value"] for fact in memory["facts"]]
        self.assertNotIn("客户希望确认小朋友的具体年龄以适配亲子活动强度", fact_values)
        self.assertIn("同行小朋友10岁", fact_values)


if __name__ == "__main__":
    unittest.main()
