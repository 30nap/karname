from fastapi import FastAPI, UploadFile, Form
from fastapi.responses import JSONResponse
from vosk import Model, KaldiRecognizer
from models import SessionLocal, User, Category, Activity
import subprocess, io, wave, json, re
from datetime import datetime

app = FastAPI()

vosk_model_path = "vosk-model-small-fa-0.42"
model = Model(vosk_model_path)

CATEGORY_KEYWORDS = {
    "مطالعه": ["مطالعه", "کتاب", "درس", "خواندن"],
    "ورزش": ["ورزش", "دویدن", "پیاده‌روی", "باشگاه", "شنا", "دوچرخه"],
    "کار": ["کار", "کدنویسی", "برنامه‌نویسی", "جلسه", "ایمیل"],
    "سرگرمی": ["فیلم", "بازی", "موزیک", "نتفلیکس"],
    "استراحت": ["خواب", "استراحت", "چرت"],
}

def parse_activity(text):
    duration_minutes = 0
    match_hours = re.search(r"(\d+)\s*ساعت", text)
    if match_hours:
        duration_minutes += int(match_hours.group(1)) * 60
    match_minutes = re.search(r"(\d+)\s*دقیقه", text)
    if match_minutes:
        duration_minutes += int(match_minutes.group(1))
    
    category = "نامشخص"
    for cat, keywords in CATEGORY_KEYWORDS.items():
        for word in keywords:
            if word in text:
                category = cat
                break
        if category != "نامشخص":
            break
    return {"text": text, "duration_minutes": duration_minutes, "category": category}

def mp3_to_wav_bytes(mp3_bytes):
    process = subprocess.Popen(
        ["ffmpeg", "-i", "pipe:0", "-ar", "16000", "-ac", "1", "-f", "wav", "pipe:1"],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL
    )
    wav_bytes, _ = process.communicate(mp3_bytes)
    return wav_bytes


@app.post("/upload-voice/")
async def upload_voice(file: UploadFile, username: str = Form(...)):
    audio_bytes = await file.read()
    wav_bytes = mp3_to_wav_bytes(audio_bytes)

    wf = wave.open(io.BytesIO(wav_bytes), "rb")
    rec = KaldiRecognizer(model, wf.getframerate())
    result_text = ""
    while True:
        data = wf.readframes(4000)
        if len(data) == 0:
            break
        if rec.AcceptWaveform(data):
            res = json.loads(rec.Result())
            result_text += res.get("text", "") + " "
    res = json.loads(rec.FinalResult())
    result_text += res.get("text", "")

    activity_data = parse_activity(result_text)

    db = SessionLocal()
    user = db.query(User).filter(User.username == username).first()
    if not user:
        user = User(username=username)
        db.add(user)
        db.commit()
        db.refresh(user)
    cat = db.query(Category).filter(Category.name == activity_data["category"]).first()
    if not cat:
        cat = Category(name=activity_data["category"])
        db.add(cat)
        db.commit()
        db.refresh(cat)
    activity = Activity(
        user_id=user.id,
        category_id=cat.id,
        title=activity_data["text"],
        duration_minutes=activity_data["duration_minutes"]
    )
    db.add(activity)
    db.commit()

    return JSONResponse({
        "text": activity_data["text"],
        "duration_minutes": activity_data["duration_minutes"],
        "category": activity_data["category"]
    })
