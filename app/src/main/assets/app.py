from flask import Flask, request, jsonify, render_template_string
from datetime import datetime
import json
import ast
import operator
import os
import urllib.request
import urllib.error

app = Flask(__name__)
HISTORY_FILE = "chat_history.json"

def calculate(expr):
    ops = {
        ast.Add: operator.add,
        ast.Sub: operator.sub,
        ast.Mult: operator.mul,
        ast.Div: operator.truediv,
        ast.Pow: operator.pow,
        ast.USub: operator.neg
    }

    def solve(n):
        if isinstance(n, ast.Constant) and type(n.value) in (int, float):
            return n.value
        if isinstance(n, ast.BinOp) and type(n.op) in ops:
            return ops[type(n.op)](solve(n.left), solve(n.right))
        if isinstance(n, ast.UnaryOp) and type(n.op) in ops:
            return ops[type(n.op)](solve(n.operand))
        raise ValueError()

    return solve(ast.parse(expr, mode="eval").body)

def save_history(q, a):
    try:
        with open(HISTORY_FILE, encoding="utf-8") as f:
            history = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        history = []

    history.append({
        "question": q,
        "answer": a,
        "time": datetime.now().strftime("%d-%m-%Y %I:%M %p")
    })

    with open(HISTORY_FILE, "w", encoding="utf-8") as f:
        json.dump(history[-100:], f, ensure_ascii=False, indent=2)



def ask_ai(message, language):
    api_key = os.getenv("GROQ_API_KEY")
    if not api_key:
        return "Groq API key nahi mili."

    names = {"ur-PK": "Urdu", "hi-IN": "Hindi", "en-US": "English"}
    chosen = names.get(language, "Urdu")

    messages = [{
        "role": "system",
        "content": (
            "You are JARVIS, a friendly, funny and helpful assistant. "
            "Reply naturally in " + chosen + ". "
            "Use light jokes and playful banter. Be respectful and useful. "
            "Use previous conversation for context when relevant."
        )
    }]

    try:
        with open(HISTORY_FILE, encoding="utf-8") as f:
            history = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        history = []

    for item in history[-10:]:
        if item.get("question") and item.get("answer"):
            messages.append({"role": "user", "content": item["question"]})
            messages.append({"role": "assistant", "content": item["answer"]})

    messages.append({"role": "user", "content": message})

    data = {
        "model": "openai/gpt-oss-120b",
        "messages": messages,
        "temperature": 0.8
    }

    req = urllib.request.Request(
        "https://api.groq.com/openai/v1/chat/completions",
        data=json.dumps(data).encode("utf-8"),
        headers={
            "Authorization": "Bearer " + api_key,
            "Content-Type": "application/json",
            "User-Agent": "JARVIS-AI/1.0"
        }
    )

    try:
        with urllib.request.urlopen(req, timeout=45) as response:
            result = json.loads(response.read().decode("utf-8"))
            return result["choices"][0]["message"]["content"].strip()
    except urllib.error.HTTPError as e:
        return "AI server error: " + str(e.code)
    except Exception:
        return "AI se connection nahi hua. Internet check karke dobara koshish karein."

PAGE = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>JARVIS</title>
<style>
*{box-sizing:border-box}
body{
 background:radial-gradient(circle at top,#123b25,#020b08 65%);
 color:#fff;font-family:Arial,sans-serif;text-align:center;
 padding:15px;margin:0;min-height:100vh
}
h1{color:#35ff8a;font-size:34px;text-shadow:0 0 18px #00ff77}
h2,h3{color:#42ff9b}
.panel{
 background:linear-gradient(145deg,#10291d,#030d09);
 border:1px solid #19e879;
 box-shadow:0 0 14px #00c96b;
 padding:16px;border-radius:18px;margin:14px auto;
 max-width:550px
}
input,select,button{
 box-sizing:border-box;width:100%;padding:14px;margin:7px 0;
 border:1px solid #22e878;border-radius:12px;font-size:16px
}
input,select{background:#061810;color:white}
button{
 background:linear-gradient(135deg,#087b42,#00d878);
 color:white;font-weight:bold;cursor:pointer;
 box-shadow:0 0 10px #00c96b
}
button:active{transform:scale(.98)}
#answer{white-space:pre-wrap;line-height:1.7}
.brand{color:#ffd95a;font-size:22px;font-weight:bold;letter-spacing:2px}
.tagline{color:#7dffb4;font-size:14px}
@media(max-width:400px){h1{font-size:27px}.panel{padding:12px}}
</style>
</head>
<body>
<h1>🇵🇰 JARVIS AI</h1>
<div class="brand">👑 WASIM KHAN 👑</div>
<p class="tagline">POWERED BY AI • FOR A SMARTER PAKISTAN</p>
<div class="panel">
<h3>JARVIS Online</h3>
<select id="language">
<option value="ur-PK">اردو</option>
<option value="hi-IN">हिन्दी</option>
<option value="en-US">English</option>
</select>
<select id="voiceChoice">
<option value="default">Default Voice</option>
<option value="male">Male Voice</option>
<option value="female">Female Voice</option>
</select>
</div>
<div class="panel">
<input id="message" placeholder="اپنا سوال لکھیں...">
<button onclick="sendMessage()">SEND</button>
<button onclick="startMic()">🎤 MIC</button>
<button onclick="speakAnswer()">🔊 SPEAK</button>
</div>
<div class="panel">
<h3>JARVIS کا جواب</h3>
<div id="answer">میں تیار ہوں۔</div>
</div>
<div class="panel">
<button onclick="quick('time')">TIME</button>
<button onclick="quick('date')">DATE</button>
<button onclick="quick('google')">GOOGLE</button>
<button onclick="quick('youtube')">YOUTUBE</button>
<button onclick="showHistory()">CHAT HISTORY</button>
</div>
<script>
let lastAnswer = "";

function quick(x){
 document.getElementById("message").value=x;
 sendMessage();
}

async function sendMessage(){
 let message=document.getElementById("message").value.trim();
 if(!message)return;
 let language=document.getElementById("language").value;
 try{
  let r=await fetch("/chat",{
   method:"POST",
   headers:{"Content-Type":"application/json"},
   body:JSON.stringify({message,language})
  });
  let d=await r.json();
  lastAnswer=d.reply;
  if (!d.url) { setTimeout(speakAnswer, 300); }
  document.getElementById("answer").textContent=d.reply;
  if(d.url)window.open(d.url,"_blank");
 }catch(e){
  document.getElementById("answer").textContent="سرور سے رابطہ نہیں ہوا۔";
 }
}

function speakAnswer(){
 if(!lastAnswer)return;
 speechSynthesis.cancel();
 let v=new SpeechSynthesisUtterance(lastAnswer);
 v.lang=document.getElementById("language").value;
 let choice=document.getElementById("voiceChoice").value;
 let voices=speechSynthesis.getVoices().filter(x=>x.lang.toLowerCase().startsWith(v.lang.slice(0,2).toLowerCase()));
 let pattern=choice==="female" ? /female|woman|zira|samantha|susan|heera/i : /male|man|david|mark|daniel|ravi/i;
 if(choice!=="default"){
  let found=voices.find(x=>pattern.test(x.name));
  if(found)v.voice=found;
 }
 speechSynthesis.speak(v);
}

function startMic(){
 let R=window.SpeechRecognition||window.webkitSpeechRecognition;
 if(!R){
  document.getElementById("answer").textContent="مائیک دستیاب نہیں۔";
  return;
 }
 let rec=new R();
 rec.lang=document.getElementById("language").value;
 rec.onresult=e=>{
  document.getElementById("message").value=e.results[0][0].transcript;
  sendMessage();
 };
 rec.start();
}

async function showHistory(){
 let r=await fetch("/history");
 let d=await r.json();
 document.getElementById("answer").textContent=d.length
 ?d.slice().reverse().map(x=>x.time+"\\nآپ: "+x.question+"\\nJARVIS: "+x.answer).join("\\n\\n")
 :"ابھی کوئی History نہیں ہے۔";
}

document.getElementById("message").addEventListener("keydown",e=>{
 if(e.key==="Enter")sendMessage();
});
</script>
</body>
</html>
"""

@app.route("/")
def home():
    return render_template_string(PAGE)

@app.route("/history")
def history():
    try:
        with open(HISTORY_FILE, encoding="utf-8") as f:
            return jsonify(json.load(f))
    except (FileNotFoundError, json.JSONDecodeError):
        return jsonify([])

@app.route("/chat", methods=["POST"])
def chat():
    data = request.get_json(silent=True) or {}
    message = data.get("message", "").strip()
    language = data.get("language", "ur-PK")
    lower = message.lower()
    now = datetime.now()

    if "کس نے بنایا" in message or "किसने बनाया" in message or "who made you" in lower:
        replies = {
            "ur-PK": "مجھے وسیم نے بنایا ہے۔ میرے بنانے والے کا نام وسیم ہے۔",
            "hi-IN": "मुझे Wasim ने बनाया है।",
            "en-US": "I was created by Wasim."
        }
        reply = replies.get(language, replies["ur-PK"])

    elif lower == "time" or "وقت" in message or "समय" in message:
        t = now.strftime("%I:%M %p")
        replies = {
            "ur-PK": "ابھی وقت ہے " + t,
            "hi-IN": "अभी समय है " + t,
            "en-US": "The current time is " + t
        }
        reply = replies.get(language, replies["ur-PK"])

    elif lower == "date" or "تاریخ" in message or "तारीख" in message:
        d = now.strftime("%d-%m-%Y")
        replies = {
            "ur-PK": "آج کی تاریخ ہے " + d,
            "hi-IN": "आज की तारीख है " + d,
            "en-US": "Today's date is " + d
        }
        reply = replies.get(language, replies["ur-PK"])

    elif lower == "google" or "گوگل" in message:
        reply = "Google کھول رہا ہوں"
        save_history(message, reply)
        return jsonify({"reply": reply, "url": "https://www.google.com"})

    elif lower == "youtube" or "یوٹیوب" in message:
        reply = "YouTube کھول رہا ہوں"
        save_history(message, reply)
        return jsonify({"reply": reply, "url": "https://www.youtube.com"})

    elif lower.startswith("calc "):
        try:
            result = str(calculate(message[5:]))
            reply = "جواب ہے: " + result if language == "ur-PK" else "Answer: " + result
        except Exception:
            reply = "حساب درست نہیں۔ مثال: calc 5+5"

    else:
        reply = ask_ai(message, language)

    save_history(message, reply)
    return jsonify({"reply": reply})

if __name__ == "__main__":
    app.run(host="127.0.0.1", port=5000)
