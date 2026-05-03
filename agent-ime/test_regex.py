import re

def _strip(reply_text: str, contact_name: str) -> str:
    text = (reply_text or "").strip()
    name = (contact_name or "").strip()
    if not text:
        return text
        
    if name and name != "客户":
        escaped_name = re.escape(name)
        text = re.sub(
            rf"^{escaped_name}\s*(?:您好|你好|早上好|上午好|中午好|下午好|晚上好)?[，,、：:！!\s~。.]*",
            "",
            text,
            count=1
        ).lstrip()
        
    stripped = re.sub(
        r"^[^，,、：:！!\s~。]{0,8}\s*(?:您好|你好|早上好|上午好|中午好|下午好|晚上好)[，,、：:！!\s~。.]*",
        "",
        text,
        count=1
    ).lstrip()
    return stripped or text

print("1:", _strip("邓先生晚上好~五一假期刚过", "客户"))
print("2:", _strip("邓先生，五一假期刚过", "邓先生"))
print("3:", _strip("李女士你好！五一假期刚过", "客户"))
print("4:", _strip("晚上好！需要调整吗？", "客户"))
print("5:", _strip("邓先生晚上好", "邓先生"))
