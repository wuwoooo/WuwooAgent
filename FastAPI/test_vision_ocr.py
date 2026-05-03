import base64
import os
import requests
import json
import sys
import urllib.request

API_KEY = "ark-0773e8a1-0054-4243-83b9-b1a4f06b67da-d677d"
MODEL_ID = "doubao-seed-2-0-pro-260215"
BASE_URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"

def encode_image(image_path):
    with open(image_path, "rb") as image_file:
        return base64.b64encode(image_file.read()).decode('utf-8')

def download_sample_image(save_path):
    # 下载一张包含文字的测试图片
    url = "https://www.google.com/images/branding/googlelogo/1x/googlelogo_color_272x92dp.png" 
    print(f"正在下载测试图片: {url}...")
    headers = {"User-Agent": "Mozilla/5.0"}
    response = requests.get(url, headers=headers)
    with open(save_path, "wb") as f:
        f.write(response.content)
    print("下载完成。")

def test_vision(image_path):
    print(f"正在对图片进行 Base64 编码: {image_path}")
    base64_image = encode_image(image_path)
    
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {API_KEY}"
    }
    
    prompt = "请提取这张图片中的所有文字内容，如果可以，请总结这段文字的主题。"
    print(f"正在请求 Volcano Ark API...\n模型 ID: {MODEL_ID}\n提示词: {prompt}")
    
    payload = {
        "model": MODEL_ID,
        "messages": [
            {
                "role": "user",
                "content": [
                    {
                        "type": "text",
                        "text": prompt
                    },
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": f"data:image/png;base64,{base64_image}"
                        }
                    }
                ]
            }
        ]
    }
    
    try:
        response = requests.post(BASE_URL, headers=headers, json=payload, timeout=30)
        if response.status_code == 200:
            result = response.json()
            print("\n" + "="*40)
            print("🚀 大模型响应结果：")
            print("="*40)
            print(result["choices"][0]["message"]["content"])
            print("="*40)
        else:
            print(f"请求失败，状态码: {response.status_code}")
            print(response.text)
    except Exception as e:
        print(f"发生异常: {e}")

if __name__ == "__main__":
    test_image_path = "sample_test_image.png"
    
    # 允许用户传入自己的截图路径
    if len(sys.argv) > 1:
        test_image_path = sys.argv[1]
    
    if not os.path.exists(test_image_path):
        print(f"找不到文件 '{test_image_path}'。")
        if test_image_path == "sample_test_image.png":
            download_sample_image(test_image_path)
        else:
            sys.exit(1)
            
    test_vision(test_image_path)
