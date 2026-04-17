import time
import os
import asyncio
from dotenv import load_dotenv

load_dotenv(".env")

from volc_knowledge_client import load_volc_config_from_env, search_knowledge, chat_completion, generate_prompt

def test():
    print("Testing Volcengine knowledge API latency...")
    cfg = load_volc_config_from_env()
    pure_user_text = "你好，请问你们有哪些旅游线路？"
    
    t0 = time.time()
    search_payload = search_knowledge(pure_user_text, cfg)
    t1 = time.time()
    print(f"search_knowledge took: {t1 - t0:.2f}s")
    
    system_prompt = generate_prompt(search_payload)
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": pure_user_text}
    ]
    
    t2 = time.time()
    reply = chat_completion(messages, cfg)
    t3 = time.time()
    print(f"chat_completion took: {t3 - t2:.2f}s")
    print(f"Total time: {t3 - t0:.2f}s")
    print(f"Reply: {reply}")

if __name__ == "__main__":
    test()
