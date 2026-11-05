import os, json, urllib.request, urllib.error
from http.server import HTTPServer, SimpleHTTPRequestHandler

ASSETS = "app/src/main/assets"

class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ASSETS, **kwargs)

    def do_POST(self):
        if self.path != "/api/chat":
            self.send_error(404)
            return
        try:
            data = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
            question = data.get("message", "")
            language = data.get("language", "ur-PK")

            if any(x in question.lower() for x in [
                "तुझे किसने बनाया", "तुम्हें किसने बनाया",
                "आपको किसने बनाया", "کس نے بنایا",
                "تمہیں کس نے بنایا", "who created you",
                "who made you", "who built you"
            ]):
                reply = "मुझे वसीम बॉस ने बनाया।"
            else:
                if not os.environ.get("GROQ_API_KEY"):
                    raise Exception("Termux में Groq API Key उपलब्ध नहीं है")
                import urllib.request
                payload = {
                    "model": "openai/gpt-oss-120b",
                    "messages": [
                        {"role": "system", "content": "You are JARVIS. Reply naturally in the language with code " + language + ". Be helpful and conversational."},
                        {"role": "user", "content": question}
                    ],
                    "temperature": 0.7
                }
                req = urllib.request.Request(
                    "https://api.groq.com/openai/v1/chat/completions",
                    data=json.dumps(payload).encode(),
                    headers={"Authorization": "Bearer " + os.environ["GROQ_API_KEY"],
                             "Content-Type": "application/json", "User-Agent": "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"},
                    method="POST"
                )
                with urllib.request.urlopen(req, timeout=60) as response:
                    result = json.loads(response.read())
                reply = result["choices"][0]["message"]["content"]

            body = json.dumps({"reply": reply}, ensure_ascii=False).encode()
            self.send_response(200)
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")
            body = json.dumps({"reply": "Groq HTTP " + str(e.code) + ": " + detail}, ensure_ascii=False).encode()
            self.send_response(e.code)
        except Exception as e:
            body = json.dumps({"reply": "AI से जवाब लेने में समस्या: " + str(e)}, ensure_ascii=False).encode()
            self.send_response(500)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

print("JARVIS Chrome Server: http://127.0.0.1:8000")
HTTPServer(("127.0.0.1", 8000), Handler).serve_forever()
