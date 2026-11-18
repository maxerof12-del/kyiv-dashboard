import re
import time
import random
import asyncio
import sqlite3
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
try:
    import requests
    HAVE_REQUESTS = True
except Exception:
    HAVE_REQUESTS = False
from datetime import datetime, timedelta, time as dtime, date as _date
from zoneinfo import ZoneInfo

from telegram import Update, InlineKeyboardButton, InlineKeyboardMarkup
from telegram.ext import (
    Application, CommandHandler, MessageHandler,
    CallbackQueryHandler, ContextTypes, filters,
)

# ====== НАСТРОЙКИ ======
# Личные значения (токен, дата рождения, координаты) берутся из вашего старого файла
# feeding_bot_old.py или из bot_private.txt — в этом файле их нет.
import os as _os
import ast as _ast
_HERE = _os.path.dirname(_os.path.abspath(__file__))


def _private(name, default):
    for fn in ("bot_private.txt", "feeding_bot_old.py"):
        try:
            with open(_os.path.join(_HERE, fn), encoding="utf-8") as f:
                txt = f.read()
            m = re.search(r"^" + re.escape(name) + r"\s*=\s*(.+?)\s*(#.*)?$", txt, re.M)
            if m:
                return _ast.literal_eval(m.group(1))
        except Exception:
            pass
    return default


BOT_TOKEN = _private("BOT_TOKEN", "")
TZ = ZoneInfo("Europe/Kyiv")
DB_PATH = _os.path.join(_HERE, "feedings.db")
DAY_SHIFT_MIN = 20
REPORT_HOUR = 0
REPORT_MIN = 20
DAY_GOAL_ML = _private("DAY_GOAL_ML", 800)
POOP_LONG_HOURS = 8
LAT = _private("LAT", 50.45)
LON = _private("LON", 30.52)
WEATHER_FROM_HOUR = 6
BIRTH_YEAR, BIRTH_MONTH, BIRTH_DAY = _private("BIRTH_YEAR, BIRTH_MONTH, BIRTH_DAY", (2026, 1, 1))
HOT_TEMP = 28
MIN_ML, MAX_ML = 1, 1500
DASH_PORT = 8765  # порт для дашборда на планшете (тот же Wi-Fi)
DASH_RELAY = "https://ntfy.sh/kyivdash-"  # передача данных дашборду через интернет


def _dash_code():
    code = _private("DASH_CODE", "")
    if code:
        return code
    alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
    code = "".join(random.SystemRandom().choice(alphabet) for _ in range(10))
    try:
        with open(_os.path.join(_HERE, "bot_private.txt"), "a", encoding="utf-8") as f:
            f.write(f'\nDASH_CODE = "{code}"\n')
    except Exception as e:
        print("Не смог сохранить код дашборда:", e)
    return code


DASH_CODE = _dash_code()
# =======================

CONS_LABELS = {"liquid": "жидкий 💧", "mush": "кашицей 🟡", "thick": "густой 🟤", "hard": "твёрдый 🌰"}
AMT_LABELS = {"low": "мало 🤏", "norm": "норма 👍", "much": "много 💦"}
POOP_TRIGGERS = ["какашк", "покакал", "какаха", "какаш", "💩"]

PK_CATS = {
    "kasha": "каша 🥣", "kasha_add": "каша с добавками 🥣", "ovoshi": "овощи 🥦",
    "frukty": "фрукты 🍎", "myaso": "мясное 🍖", "zheltok": "желток 🥚",
}
PK_AMTS = {"t1": "1 ч.л.", "t2": "2 ч.л.", "t3": "3 ч.л.", "t5": "5 ч.л.", "g50": "50 г", "g100": "100 г"}


def init_db():
    con = sqlite3.connect(DB_PATH)
    con.execute("CREATE TABLE IF NOT EXISTS feedings (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER, amount REAL, user TEXT, ts TEXT)")
    con.execute("CREATE TABLE IF NOT EXISTS poops (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER, ts TEXT, consistency TEXT, amount TEXT, user TEXT)")
    con.execute("CREATE TABLE IF NOT EXISTS prikorm (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER, ts TEXT, item TEXT, amount TEXT, user TEXT)")
    con.execute("CREATE TABLE IF NOT EXISTS chats (chat_id INTEGER PRIMARY KEY)")
    con.execute("CREATE TABLE IF NOT EXISTS weather_log (chat_id INTEGER, day TEXT, PRIMARY KEY (chat_id, day))")
    con.execute("CREATE TABLE IF NOT EXISTS report_log (chat_id INTEGER, day TEXT, PRIMARY KEY (chat_id, day))")
    con.execute("CREATE TABLE IF NOT EXISTS congrats_log (chat_id INTEGER, day TEXT, slot TEXT, PRIMARY KEY (chat_id, day, slot))")
    con.execute("CREATE TABLE IF NOT EXISTS prikorm_tip_log (chat_id INTEGER, day TEXT, PRIMARY KEY (chat_id, day))")
    con.execute("CREATE TABLE IF NOT EXISTS weather_cache (day TEXT PRIMARY KEY, tmax REAL, rain INTEGER)")
    con.execute("CREATE TABLE IF NOT EXISTS rotate (chat_id INTEGER PRIMARY KEY, n INTEGER)")
    con.commit()
    con.close()


def db():
    return sqlite3.connect(DB_PATH, timeout=15)


def remember_chat(chat_id):
    con = db()
    con.execute("INSERT OR IGNORE INTO chats (chat_id) VALUES (?)", (chat_id,))
    con.commit()
    con.close()


def all_chats():
    con = db()
    rows = [r[0] for r in con.execute("SELECT chat_id FROM chats").fetchall()]
    con.close()
    return rows


def add_feeding(chat_id, amount, user, ts):
    con = db()
    con.execute("INSERT INTO feedings (chat_id, amount, user, ts) VALUES (?, ?, ?, ?)", (chat_id, amount, user, ts.isoformat()))
    con.commit()
    con.close()


def add_poop(chat_id, user, ts):
    con = db()
    cur = con.execute("INSERT INTO poops (chat_id, ts, consistency, amount, user) VALUES (?, ?, NULL, NULL, ?)", (chat_id, ts.isoformat(), user))
    pid = cur.lastrowid
    con.commit()
    con.close()
    return pid


def set_poop_field(pid, field, value):
    con = db()
    con.execute(f"UPDATE poops SET {field}=? WHERE id=?", (value, pid))
    con.commit()
    con.close()


def delete_poop(chat_id, pid):
    con = db()
    row = con.execute("SELECT ts FROM poops WHERE id=? AND chat_id=?", (pid, chat_id)).fetchone()
    if row:
        con.execute("DELETE FROM poops WHERE id=?", (pid,))
        con.commit()
    con.close()
    return row is not None


def add_prikorm(chat_id, user, ts, item=None, amount=None):
    con = db()
    cur = con.execute("INSERT INTO prikorm (chat_id, ts, item, amount, user) VALUES (?,?,?,?,?)", (chat_id, ts.isoformat(), item, amount, user))
    pid = cur.lastrowid
    con.commit()
    con.close()
    return pid


def set_prikorm(pid, field, value):
    con = db()
    con.execute(f"UPDATE prikorm SET {field}=? WHERE id=?", (value, pid))
    con.commit()
    con.close()


def delete_prikorm(chat_id, pid):
    con = db()
    row = con.execute("SELECT ts FROM prikorm WHERE id=? AND chat_id=?", (pid, chat_id)).fetchone()
    if row:
        con.execute("DELETE FROM prikorm WHERE id=?", (pid,))
        con.commit()
    con.close()
    return row is not None


def shifted_day(d):
    start_today = d.replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(minutes=DAY_SHIFT_MIN)
    if d < start_today:
        return (d - timedelta(days=1)).date()
    return d.date()


def day_rows(chat_id, date):
    con = db()
    cur = con.execute("SELECT ts, amount, user FROM feedings WHERE chat_id=? ORDER BY ts", (chat_id,))
    rows = []
    for ts_str, amount, user in cur.fetchall():
        d = datetime.fromisoformat(ts_str)
        if shifted_day(d) == date:
            rows.append((d.strftime("%H:%M"), amount, user))
    con.close()
    return rows


def day_rows_full(chat_id, date):
    con = db()
    cur = con.execute("SELECT id, ts, amount, user FROM feedings WHERE chat_id=? ORDER BY ts", (chat_id,))
    rows = []
    for rid, ts_str, amount, user in cur.fetchall():
        d = datetime.fromisoformat(ts_str)
        if shifted_day(d) == date:
            rows.append((rid, d.strftime("%H:%M"), amount, user))
    con.close()
    return rows


def poop_rows(chat_id, date):
    con = db()
    cur = con.execute("SELECT id, ts, consistency, amount, user FROM poops WHERE chat_id=? ORDER BY ts", (chat_id,))
    rows = []
    for pid, ts_str, cons, amt, user in cur.fetchall():
        d = datetime.fromisoformat(ts_str)
        if shifted_day(d) == date:
            rows.append((pid, d, cons, amt, user))
    con.close()
    return rows


def prikorm_rows(chat_id, date):
    con = db()
    cur = con.execute("SELECT id, ts, item, amount, user FROM prikorm WHERE chat_id=? ORDER BY ts", (chat_id,))
    rows = []
    for pid, ts_str, item, amt, user in cur.fetchall():
        d = datetime.fromisoformat(ts_str)
        if shifted_day(d) == date:
            rows.append((pid, d, item, amt, user))
    con.close()
    return rows


def prev_feeding_ts(chat_id, ts):
    con = db()
    cur = con.execute("SELECT ts FROM feedings WHERE chat_id=? ORDER BY ts DESC", (chat_id,))
    result = None
    for (ts_str,) in cur.fetchall():
        d = datetime.fromisoformat(ts_str)
        if d < ts:
            result = d
            break
    con.close()
    return result


def delete_last(chat_id):
    con = db()
    row = con.execute("SELECT id, amount, ts FROM feedings WHERE chat_id=? ORDER BY ts DESC LIMIT 1", (chat_id,)).fetchone()
    if row:
        con.execute("DELETE FROM feedings WHERE id=?", (row[0],))
        con.commit()
    con.close()
    return row


def delete_by_id(chat_id, rid):
    con = db()
    row = con.execute("SELECT amount FROM feedings WHERE id=? AND chat_id=?", (rid, chat_id)).fetchone()
    if row:
        con.execute("DELETE FROM feedings WHERE id=? AND chat_id=?", (rid, chat_id))
        con.commit()
    con.close()
    return row[0] if row else None


def reset_today(chat_id, date):
    con = db()
    for tbl in ("feedings", "poops", "prikorm"):
        ids = [i for (i, ts_str) in con.execute(f"SELECT id, ts FROM {tbl} WHERE chat_id=?", (chat_id,)).fetchall()
               if shifted_day(datetime.fromisoformat(ts_str)) == date]
        for i in ids:
            con.execute(f"DELETE FROM {tbl} WHERE id=?", (i,))
    con.commit()
    con.close()
    return 1


def fmt(x):
    if float(x).is_integer():
        return str(int(x))
    return str(round(float(x), 1)).replace(".", ",")


SMALL_COMMENTS = ["Маловато покушали 🍼", "Чисто на разогрев 😋", "Видимо, не сильно проголодалась 🤏", "Дегустация, а не обед 🍽️", "Перекусили и баиньки 😴", "Аппетит ещё спит 💤", "Клюнула самую малость 🐤", "Наша малышка скромничает 🌸", "Капризничаем, не кушаем 🙈"]
MID_COMMENTS = ["О, аппетит проснулся! 😋", "Хороший заход! 👍", "Кушаем как по расписанию ⏰", "Достойная порция! 🙌", "Растём по графику 📈", "Уплетает за обе щёки 😄", "Наша принцесса подкрепилась 👑", "Молодец, девочка! 💕", "Покушала на славу 🌟"]
BIG_COMMENTS = ["Вот это зверский аппетит! 🦁", "Богатырша растёт! 💪", "Подмела всё подчистую 🧹", "Кто-то проголодался не на шутку 🍗", "Рекордсменка, готовь медаль 🥇", "Космический аппетит! 🚀", "Настоящая обжорка (в хорошем смысле) 🍰", "Наша красавица не на шутку проголодалась 😍", "Растём не по дням, а по часам! 🌱"]
WARM_WORDS = ["Обнимаю вас обеих 🤍", "Вы прекрасно справляетесь 💛", "Малышка растёт в любви — это главное 🌷", "Спокойствие мамы — спокойствие малышки. Берегите себя 💗", "Каждый день с ней — маленькое чудо ✨"]


def comment_for(amount):
    if amount < 60:
        return random.choice(SMALL_COMMENTS)
    elif amount <= 100:
        return random.choice(MID_COMMENTS)
    return random.choice(BIG_COMMENTS)


def human_interval(td):
    total_min = int(td.total_seconds() // 60)
    if total_min <= 0:
        return ""
    h, m = divmod(total_min, 60)
    if h and m:
        return f"{h} ч {m} мин"
    if h:
        return f"{h} ч"
    return f"{m} мин"


def looks_like_entry(text):
    return any(ch.isdigit() for ch in text)


def is_poop(text):
    low = text.lower()
    return any(t in low for t in POOP_TRIGGERS)


def is_prikorm(text):
    low = text.strip().lower()
    return low == "прикорм" or low.startswith("прикорм ") or low == "🥣" or low.startswith("🥣 ")


def normalize(text):
    t = text.strip()
    t = re.sub(r"\s*[-–—]\s*", " - ", t)
    t = re.sub(r"\s*:\s*", ":", t)
    t = re.sub(r"\s+", " ", t)
    return t


RE_TIME_FIRST = re.compile(r"^(?:в\s+)?(\d{1,2}):(\d{2})\s*-?\s*(\d+(?:[.,]\d+)?)\s*(?:мл|ml)?\.?$", re.IGNORECASE)
RE_VOL_FIRST = re.compile(r"^(\d+(?:[.,]\d+)?)\s*(?:мл|ml)?\.?(?:\s+|\s*[-в,]\s*)(?:в\s+)?(\d{1,2}):(\d{2})$", re.IGNORECASE)
RE_VOL_ONLY = re.compile(r"^(\d+(?:[.,]\d+)?)\s*(?:мл|ml)?\.?$", re.IGNORECASE)


def parse_entry(text):
    t = normalize(text)
    m = RE_TIME_FIRST.match(t)
    if m:
        return (m.group(3), m.group(1), m.group(2))
    m = RE_VOL_FIRST.match(t)
    if m:
        return (m.group(1), m.group(2), m.group(3))
    m = RE_VOL_ONLY.match(t)
    if m:
        return (m.group(1), None, None)
    return None


def compute_ts(parsed, now):
    num, hour, minute = parsed
    amount = float(num.replace(",", "."))
    if hour is not None:
        h, mi = int(hour), int(minute)
        if h > 23 or mi > 59:
            return amount, None
        ts = now.replace(hour=h, minute=mi, second=0, microsecond=0)
        if ts > now + timedelta(hours=12):
            ts = ts - timedelta(days=1)
        return amount, ts
    return amount, now


def report_sent(chat_id, date):
    con = db()
    row = con.execute("SELECT 1 FROM report_log WHERE chat_id=? AND day=?", (chat_id, date.isoformat())).fetchone()
    con.close()
    return row is not None


def mark_report_sent(chat_id, date):
    con = db()
    con.execute("INSERT OR IGNORE INTO report_log (chat_id, day) VALUES (?, ?)", (chat_id, date.isoformat()))
    con.commit()
    con.close()


def has_morning_feed(chat_id, date):
    for when, amount, user in day_rows(chat_id, date):
        if int(when.split(":")[0]) >= WEATHER_FROM_HOUR:
            return True
    return False


# ---------- ПОГОДА ----------
def weather_shown(chat_id, date):
    con = db()
    row = con.execute("SELECT 1 FROM weather_log WHERE chat_id=? AND day=?", (chat_id, date.isoformat())).fetchone()
    con.close()
    return row is not None


def mark_weather(chat_id, date):
    con = db()
    con.execute("INSERT OR IGNORE INTO weather_log (chat_id, day) VALUES (?, ?)", (chat_id, date.isoformat()))
    con.commit()
    con.close()


def fetch_weather():
    if not HAVE_REQUESTS:
        return None
    try:
        r = requests.get(
            "https://api.open-meteo.com/v1/forecast",
            params={"latitude": LAT, "longitude": LON, "daily": "temperature_2m_max,precipitation_probability_max", "timezone": "Europe/Kyiv", "forecast_days": 1},
            timeout=10,
        )
        d = r.json()["daily"]
        return (d["temperature_2m_max"][0], d["precipitation_probability_max"][0])
    except Exception as e:
        print("weather error:", e)
        return None


def weather_advice(t):
    if t < 10:
        return "❄️ Холодно — тепло одевайтесь, гулять недолго."
    if t < 18:
        return "🧥 Прохладно — оденьтесь потеплее, прогулка пойдёт на пользу."
    if t < 26:
        return "🙂 Тепло — отличная погода, гуляйте на здоровье!"
    if t < 30:
        return "🥵 Жарковато, но терпимо — можно чуть прогуляться, лучше в тени, утром или вечером."
    return "🔥 Пекло — на улицу лучше не высовываться, очень жарко."


def rain_line(p):
    if p is None:
        return ""
    if p >= 60:
        return f"☔ Дождь вероятен ({p}%) — берите зонт."
    if p >= 30:
        return f"🌥 Возможен дождь ({p}%)."
    return f"☀️ Дождь маловероятен ({p}%)."


def cache_weather(day, tmax, rain):
    con = db()
    con.execute("INSERT OR REPLACE INTO weather_cache (day, tmax, rain) VALUES (?, ?, ?)", (day.isoformat(), tmax, rain))
    con.commit()
    con.close()


def get_tmax(day):
    con = db()
    row = con.execute("SELECT tmax FROM weather_cache WHERE day=?", (day.isoformat(),)).fetchone()
    con.close()
    return row[0] if row else None


async def maybe_send_weather(chat, chat_id):
    today = shifted_day(datetime.now(TZ))
    if not weather_shown(chat_id, today) and has_morning_feed(chat_id, today):
        mark_weather(chat_id, today)
        data = await asyncio.to_thread(fetch_weather)
        if data is None:
            return
        tmax, rain = data
        cache_weather(today, tmax, rain)
        parts = [f"🌡 Сегодня днём до {round(tmax)}°", weather_advice(tmax)]
        rl = rain_line(rain)
        if rl:
            parts.append(rl)
        await chat.send_message("\n".join(parts))


async def start_poop(chat, chat_id, user):
    now = datetime.now(TZ)
    pid = add_poop(chat_id, user, now)
    await chat.send_message(f"💩 Записал в {now.strftime('%H:%M')}. Какой стул?", reply_markup=poop_cons_kb(pid))


# ---------- ПРИКОРМ ----------
PRIKORM_TIPS = [
    "Новый продукт вводите утром и по одному — 3-5 дней наблюдайте реакцию, прежде чем добавить следующий.",
    "Начинайте с 1/2-1 чайной ложки, постепенно увеличивая за неделю до возрастной порции.",
    "Прикорм даётся ДО молока/смеси, пока малышка голодная — так охотнее пробует новое.",
    "Не солите и не сластите прикорм — почкам рано, а вкус к соли и сахару формируется на всю жизнь.",
    "Консистенция сейчас — однородное пюре без комочков; густоту увеличивайте постепенно.",
    "Отказ от нового продукта — норма. Предложите снова через несколько дней, иногда нужно много попыток.",
    "Ложка своя, мягкая; кормите, когда малышка сидит с поддержкой, а не лёжа.",
    "Следите за стулом при вводе овощей и фруктов — он может измениться, это нормально.",
    "С началом твёрдой пищи можно предлагать немного воды из ложки или поильника.",
    "Аллергичные продукты (яйцо, рыба, глютен) вводят осторожно и по согласованию с педиатром.",
    "Не заставляйте доедать. Ребёнок сам регулирует, сколько ему нужно — уважайте его сигналы сытости.",
    "Первый прикорм часто начинают с овощей (кабачок, цветная капуста, брокколи) или безмолочной каши.",
    "Каши без глютена для старта: гречневая, рисовая, кукурузная.",
    "Готовьте пюре без масла первые дни; позже можно добавить каплю растительного масла в овощи.",
    "Новый вкус может не понравиться сразу — это нормально, вкусы формируются постепенно.",
    "Молоко или смесь остаются основой питания примерно до года — прикорм их пока дополняет, а не заменяет.",
    "Ведите список введённых продуктов — при аллергии проще понять причину.",
    "Не давайте мёд до 1 года — риск ботулизма.",
    "Коровье молоко как питьё — не раньше года; в кашу на молоке — по рекомендации врача.",
    "Пюре комнатной температуры или чуть тёплое; всегда проверяйте температуру перед кормлением.",
    "Не разогревайте детское питание повторно и не докармливайте из начатой банки на следующий день.",
    "Признаки готовности к прикорму: держит голову, сидит с опорой, интересуется едой, угас рефлекс выталкивания ложки.",
    "Кусочки и еда руками (BLW) — позже, когда малышка увереннее сидит; сейчас безопаснее пюре.",
    "При запоре помогают чернослив, тыква, кабачок; при склонности к жидкому — рис, банан.",
    "Одно новое — за раз. Смешивать несколько новых продуктов в один день не стоит, чтобы отследить реакцию.",
    "Аллергия может проявиться сыпью, краснотой щёк, беспокойством, изменением стула — при сомнениях к врачу.",
    "Мясо (индейка, кролик, телятина) обычно вводят после овощей и каш, ближе к 7-8 месяцам — уточните у педиатра.",
    "Соки в раннем прикорме не нужны и не рекомендуются — лучше фрукт в виде пюре.",
    "Создайте ритуал: своё место, слюнявчик, спокойная обстановка без телевизора и телефона.",
    "Наберитесь терпения: прикорм — это про знакомство и навык, а не про объём. Беспорядок за столом — часть процесса!",
]


def prikorm_line(chat_id, date):
    rows = prikorm_rows(chat_id, date)
    if not rows:
        return ""
    parts = [f"{item}{(' ' + amt) if amt else ''}" for pid, d, item, amt, user in rows]
    return f"🥣 Прикорм: {len(rows)} раз — " + "; ".join(parts)


def prikorm_tip_shown(chat_id, date):
    con = db()
    row = con.execute("SELECT 1 FROM prikorm_tip_log WHERE chat_id=? AND day=?", (chat_id, date.isoformat())).fetchone()
    con.close()
    return row is not None


def mark_prikorm_tip(chat_id, date):
    con = db()
    con.execute("INSERT OR IGNORE INTO prikorm_tip_log (chat_id, day) VALUES (?, ?)", (chat_id, date.isoformat()))
    con.commit()
    con.close()


def prikorm_cat_kb(pid):
    return InlineKeyboardMarkup([
        [InlineKeyboardButton("🥣 Каша", callback_data=f"pk:{pid}:kasha"),
         InlineKeyboardButton("🥣 Каша с доб.", callback_data=f"pk:{pid}:kasha_add")],
        [InlineKeyboardButton("🥦 Овощи", callback_data=f"pk:{pid}:ovoshi"),
         InlineKeyboardButton("🍎 Фрукты", callback_data=f"pk:{pid}:frukty")],
        [InlineKeyboardButton("🍖 Мясное", callback_data=f"pk:{pid}:myaso"),
         InlineKeyboardButton("🥚 Желток", callback_data=f"pk:{pid}:zheltok")],
        [InlineKeyboardButton("➕ Другое (напишу текстом)", callback_data=f"pk:{pid}:drugoe")],
    ])


def prikorm_amt_kb(pid):
    return InlineKeyboardMarkup([
        [InlineKeyboardButton("1 ч.л.", callback_data=f"pv:{pid}:t1"),
         InlineKeyboardButton("2 ч.л.", callback_data=f"pv:{pid}:t2"),
         InlineKeyboardButton("3 ч.л.", callback_data=f"pv:{pid}:t3"),
         InlineKeyboardButton("5 ч.л.", callback_data=f"pv:{pid}:t5")],
        [InlineKeyboardButton("50 г", callback_data=f"pv:{pid}:g50"),
         InlineKeyboardButton("100 г", callback_data=f"pv:{pid}:g100")],
    ])


async def start_prikorm(chat, chat_id, user, inline_text):
    now = datetime.now(TZ)
    if inline_text:
        add_prikorm(chat_id, user, now, item=inline_text, amount="")
        n = len(prikorm_rows(chat_id, shifted_day(now)))
        await chat.send_message(f"🥣 Прикорм записан: {inline_text}. Сегодня прикорм: {n} раз.")
    else:
        pid = add_prikorm(chat_id, user, now)
        await chat.send_message("🥣 Что из прикорма?", reply_markup=prikorm_cat_kb(pid))


async def maybe_send_prikorm_tip(chat, chat_id):
    today = shifted_day(datetime.now(TZ))
    if not prikorm_tip_shown(chat_id, today):
        mark_prikorm_tip(chat_id, today)
        await chat.send_message("🥣 Совет по прикорму: " + random.choice(PRIKORM_TIPS))


# ---------- ВОЗРАСТ / ЮМОР / ПОЗДРАВЛЕНИЯ ----------
TIPS = {
    "0-2": ["Много контакта кожа-к-коже и на руках — это формирует чувство безопасности.", "Выкладывайте на животик по чуть-чуть под присмотром — укрепляет шею и спину.", "Зрение ещё нечёткое: лицо на расстоянии ~20-30 см малышка видит лучше всего.", "Плач — единственный язык. Отклик на плач не портит ребёнка, а учит доверию.", "Новорождённые спят 16-18 часов в сутки короткими отрезками — это норма."],
    "3-4": ["К 4 месяцам многие детки учатся переворачиваться — не оставляйте на краю дивана ни на секунду!", "Появляется гуление — отвечайте малышке, это первые диалоги и основа речи.", "Тянет всё в рот — это норма познания мира. Уберите подальше мелкие предметы.", "Хватает игрушки — предлагайте лёгкие погремушки, это развивает моторику.", "Держит голову увереннее; выкладывание на живот по-прежнему полезно."],
    "5-6": ["Ближе к 6 месяцам вводят первый прикорм — сроки и продукты согласуйте с педиатром.", "Может сидеть с поддержкой; не сажайте насильно и надолго — спина ещё зреет.", "Активно переворачивается — пол с ковриком безопаснее дивана.", "Начинает узнавать своих и настораживаться на чужих — это нормальный этап.", "Любит рассматривать себя в зеркало и хватать ножки — поощряйте, это развитие."],
    "7-9": ["Может появиться тревога при расставании с мамой — признак здоровой привязанности.", "Учится ползать и садиться — проверьте дом: углы, розетки, лестницы.", "Развивайте пинцетный захват: крупные безопасные кусочки под присмотром.", "Понимает простые слова и интонации — почаще называйте предметы вслух.", "Игры в ку-ку учат, что мама исчезает и возвращается — снижают тревогу."],
    "10-12": ["Может вставать у опоры и идти вдоль мебели — дома обувь не обязательна.", "Появляются первые слова и жесты — повторяйте и хвалите.", "Ест кусочками с общего стола (адаптированно) — следите за консистенцией.", "К году обычно плановый осмотр — сверьтесь с календарём у педиатра.", "Больше самостоятельной игры — пирамидки, сортеры, книжки."],
    "13-36": ["Ходит и всё исследует — уберите бытовую химию и лекарства повыше.", "Понимает больше, чем говорит — читайте книжки и называйте, что видите.", "Учится есть ложкой сама — будет грязно, но это важный навык.", "Растёт словарный запас — разговаривайте, задавайте простые вопросы."],
}
CONGRATS_LINES = ["Как же быстро летит время 💕", "Расти большой и здоровой! 🌸", "Спасибо, что ты у нас есть 🥰", "Пусть каждый месяц дарит новые улыбки 😊", "Ты наше маленькое чудо ✨"]


def age_months(d):
    m = (d.year - BIRTH_YEAR) * 12 + (d.month - BIRTH_MONTH)
    if d.day < BIRTH_DAY:
        m -= 1
    return m


def tip_for_age(months):
    if months <= 2:
        key = "0-2"
    elif months <= 4:
        key = "3-4"
    elif months <= 6:
        key = "5-6"
    elif months <= 9:
        key = "7-9"
    elif months <= 12:
        key = "10-12"
    else:
        key = "13-36"
    tips = TIPS.get(key, [])
    return random.choice(tips) if tips else None


def get_rotate(chat_id):
    con = db()
    row = con.execute("SELECT n FROM rotate WHERE chat_id=?", (chat_id,)).fetchone()
    con.close()
    return row[0] if row else 0


def incr_rotate(chat_id):
    n = get_rotate(chat_id) + 1
    con = db()
    con.execute("INSERT OR REPLACE INTO rotate (chat_id, n) VALUES (?, ?)", (chat_id, n))
    con.commit()
    con.close()
    return n


def low_feed_streak(chat_id):
    con = db()
    rows = con.execute("SELECT amount FROM feedings WHERE chat_id=? ORDER BY ts DESC LIMIT 6", (chat_id,)).fetchall()
    con.close()
    streak = 0
    for (amt,) in rows:
        if amt < 60:
            streak += 1
        else:
            break
    return streak


def last_poop_dt(chat_id):
    con = db()
    row = con.execute("SELECT ts FROM poops WHERE chat_id=? ORDER BY ts DESC LIMIT 1", (chat_id,)).fetchone()
    con.close()
    return datetime.fromisoformat(row[0]) if row else None


def smart_comment(chat_id, amount, ts):
    now = datetime.now(TZ)
    today = shifted_day(ts)
    small = amount < 60
    big = amount > 100
    tmax = get_tmax(today)
    pooped_today = len(poop_rows(chat_id, today)) > 0
    lp = last_poop_dt(chat_id)
    hours_since_poop = (now - lp).total_seconds() / 3600 if lp else None
    streak = low_feed_streak(chat_id)
    months = age_months(now.date())
    if small and tmax is not None and tmax >= HOT_TEMP:
        return "🥵 В жару аппетит часто снижается — это нормально. Предлагайте чаще и понемногу, не забывайте про питьё 💧"
    if small and streak >= 3:
        return "Аппетит снизился не первый раз. Если малышка активна и подгузники мокрые как обычно — чаще это временно. Сомневаетесь — спросите педиатра 👩‍⚕️"
    if small and pooped_today:
        return "Съела меньше, но животик работает и настроение бодрое — возможно, просто такой день или маленький скачок роста 🌱"
    if big and hours_since_poop is not None and hours_since_poop > POOP_LONG_HOURS:
        return "Отлично поела! А стула давно не было — возможно, скоро сюрприз 💩"
    if big:
        return "Хороший аппетит — топливо для роста и новых умелок! 💪"
    n = incr_rotate(chat_id) % 3
    if n == 0:
        return comment_for(amount)
    if n == 1:
        return random.choice(WARM_WORDS)
    tip = tip_for_age(months)
    return f"💡 {tip}" if tip else comment_for(amount)


def congrats_sent(chat_id, date, slot):
    con = db()
    row = con.execute("SELECT 1 FROM congrats_log WHERE chat_id=? AND day=? AND slot=?", (chat_id, date.isoformat(), slot)).fetchone()
    con.close()
    return row is not None


def mark_congrats(chat_id, date, slot):
    con = db()
    con.execute("INSERT OR IGNORE INTO congrats_log (chat_id, day, slot) VALUES (?, ?, ?)", (chat_id, date.isoformat(), slot))
    con.commit()
    con.close()


async def maybe_send_congrats(chat, chat_id):
    now = datetime.now(TZ)
    if now.day != BIRTH_DAY:
        return
    if now.hour < 9:
        slot = "am"
    elif now.hour < 18:
        slot = "pm"
    else:
        return
    if congrats_sent(chat_id, now.date(), slot):
        return
    mark_congrats(chat_id, now.date(), slot)
    months = age_months(now.date())
    await chat.send_message(f"🎉 Поздравляем! Нашей малышке сегодня {months} мес! 🥳\n{random.choice(CONGRATS_LINES)}")


async def after_feeding_extras(chat, chat_id):
    await maybe_send_weather(chat, chat_id)
    await maybe_send_prikorm_tip(chat, chat_id)
    await maybe_send_congrats(chat, chat_id)


def period_summary(chat_id, days):
    today = shifted_day(datetime.now(TZ))
    lines = [f"📅 Суточные объёмы за {days} дней:"]
    total_sum = 0
    active_days = 0
    for i in range(days - 1, -1, -1):
        d = today - timedelta(days=i)
        rows = day_rows(chat_id, d)
        vol = sum(r[1] for r in rows)
        np = len(poop_rows(chat_id, d))
        nk = len(prikorm_rows(chat_id, d))
        if not rows and not np and not nk:
            continue
        active_days += 1
        total_sum += vol
        tail = ""
        if np:
            tail += f" · 💩{np}"
        if nk:
            tail += f" · 🥣{nk}"
        lines.append(f"{d.strftime('%d.%m')} — {fmt(vol)} мл ({len(rows)}){tail}")
    if active_days:
        avg = total_sum / active_days
        lines.append("")
        lines.append(f"Среднее за {active_days} дней: {fmt(round(avg))} мл")
    else:
        lines.append("За этот период записей нет.")
    return "\n".join(lines)


# ---------- ОТЧЁТ / ТЕКСТЫ ----------
def build_report(chat_id, date, now):
    feeds = day_rows(chat_id, date)
    total = sum(r[1] for r in feeds)
    poops = poop_rows(chat_id, date)
    n_poop = len(poops)
    lines = [f"🌙 Итоги дня ({date.strftime('%d.%m')})"]
    lines.append(f"🍼 Покушали: {fmt(total)} мл за {len(feeds)} кормлений")
    if total == 0:
        lines.append("Сегодня записей о кормлении нет.")
    elif total < DAY_GOAL_ML:
        lines.append("Аппетит был не очень — но ничего, ночью догоним 😉")
    else:
        lines.append("Покушали отлично, молодцы! 💪")
    if n_poop == 0:
        lines.append("💩 Сегодня не какали — последите, возможно завтра наверстаем.")
    else:
        last_dt = poops[-1][1]
        cons_txt = CONS_LABELS.get(poops[-1][2], "—") if poops[-1][2] else "—"
        lines.append(f"💩 Покакали: {n_poop} раз, последний в {last_dt.strftime('%H:%M')} ({cons_txt})")
        gap = now - last_dt
        if gap > timedelta(hours=POOP_LONG_HOURS):
            lines.append(f"С последнего раза прошло {human_interval(gap)} — ночью возможен сюрприз 💩")
    pk = prikorm_line(chat_id, date)
    if pk:
        lines.append(pk)
    lines.append("Спокойной ночи! 💤")
    return "\n".join(lines)


def action_kb():
    return InlineKeyboardMarkup([
        [InlineKeyboardButton("📊 Итог", callback_data="total"), InlineKeyboardButton("📋 Список", callback_data="list")],
        [InlineKeyboardButton("💩 Стул сегодня", callback_data="poopday"), InlineKeyboardButton("📅 Вчера", callback_data="yesterday")],
        [InlineKeyboardButton("🗑 Удалить запись", callback_data="dellist"), InlineKeyboardButton("🌙 Отчёт дня", callback_data="report")],
        [InlineKeyboardButton("📈 10", callback_data="week10"), InlineKeyboardButton("📈 30", callback_data="month30")],
        [InlineKeyboardButton("↩️ Отменить последнюю", callback_data="undo"), InlineKeyboardButton("🧹 Сбросить день", callback_data="reset")],
    ])


def poop_cons_kb(pid):
    return InlineKeyboardMarkup([
        [InlineKeyboardButton("💧 Жидкий", callback_data=f"pc:{pid}:liquid"), InlineKeyboardButton("🟡 Кашицей", callback_data=f"pc:{pid}:mush")],
        [InlineKeyboardButton("🟤 Густой", callback_data=f"pc:{pid}:thick"), InlineKeyboardButton("🌰 Твёрдый", callback_data=f"pc:{pid}:hard")],
    ])


def poop_amt_kb(pid):
    return InlineKeyboardMarkup([[InlineKeyboardButton("🤏 Мало", callback_data=f"pn:{pid}:low"), InlineKeyboardButton("👍 Норма", callback_data=f"pn:{pid}:norm"), InlineKeyboardButton("💦 Много", callback_data=f"pn:{pid}:much")]])


def recent_del_rows(chat_id):
    today = shifted_day(datetime.now(TZ))
    yesterday = today - timedelta(days=1)
    rows = []
    for d, lbl in [(yesterday, yesterday.strftime("%d.%m")), (today, today.strftime("%d.%m"))]:
        for rid, when, amount, user in day_rows_full(chat_id, d):
            rows.append(("f", rid, f"{lbl} {when}", f"{fmt(amount)} мл", user))
        for pid, dt, cons, amt, user in poop_rows(chat_id, d):
            rows.append(("p", pid, f"{lbl} {dt.strftime('%H:%M')}", f"💩 {CONS_LABELS.get(cons, '?') if cons else '?'}", user))
        for pid, dt, item, amt, user in prikorm_rows(chat_id, d):
            rows.append(("k", pid, f"{lbl} {dt.strftime('%H:%M')}", f"🥣 {item}", user))
    return rows


def del_kb(chat_id):
    rows = recent_del_rows(chat_id)
    buttons = [[InlineKeyboardButton(f"❌ {when} — {what} ({user})", callback_data=f"del:{kind}:{rid}")] for kind, rid, when, what, user in rows]
    buttons.append([InlineKeyboardButton("⬅️ Назад в меню", callback_data="menu")])
    return InlineKeyboardMarkup(buttons)


def total_text(chat_id, date=None, label="сегодня"):
    if date is None:
        date = shifted_day(datetime.now(TZ))
    rows = day_rows(chat_id, date)
    total = sum(r[1] for r in rows)
    out = f"📊 Итого за {label}: {fmt(total)} мл ({len(rows)} кормлений)\n💩 Покакали: {len(poop_rows(chat_id, date))} раз"
    pk = prikorm_line(chat_id, date)
    if pk:
        out += "\n" + pk
    return out


def list_text(chat_id, date=None, label="сегодня"):
    if date is None:
        date = shifted_day(datetime.now(TZ))
    rows = day_rows(chat_id, date)
    poops = poop_rows(chat_id, date)
    prik = prikorm_rows(chat_id, date)
    if not rows and not poops and not prik:
        return f"За {label} записей нет."
    lines = [f"📋 За {label}:"]
    for when, amount, user in rows:
        lines.append(f"• {when} — {fmt(amount)} мл ({user})")
    lines.append(f"Итого молока: {fmt(sum(r[1] for r in rows))} мл")
    if poops:
        lines.append("")
        for pid, dt, cons, amt, user in poops:
            atxt = AMT_LABELS.get(amt, "") if amt else ""
            lines.append(f"💩 {dt.strftime('%H:%M')} — {CONS_LABELS.get(cons, '?') if cons else '?'}{(', ' + atxt) if atxt else ''}")
    if prik:
        lines.append("")
        for pid, dt, item, amt, user in prik:
            lines.append(f"🥣 {dt.strftime('%H:%M')} — {item}{(' ' + amt) if amt else ''}")
    return "\n".join(lines)


def poopday_text(chat_id, date=None, label="сегодня"):
    if date is None:
        date = shifted_day(datetime.now(TZ))
    poops = poop_rows(chat_id, date)
    if not poops:
        return f"💩 За {label} ещё не какали."
    lines = [f"💩 Стул за {label}: {len(poops)} раз"]
    for pid, dt, cons, amt, user in poops:
        atxt = AMT_LABELS.get(amt, "") if amt else ""
        lines.append(f"• {dt.strftime('%H:%M')} — {CONS_LABELS.get(cons, '?') if cons else '?'}{(', ' + atxt) if atxt else ''} ({user})")
    return "\n".join(lines)


async def cmd_start(update, context):
    remember_chat(update.effective_chat.id)
    await update.message.reply_text("Привет! Я считаю кормления, стул и прикорм 🍼💩🥣\n\n• «120» — кормление сейчас\n• «10:00 - 120» — задним числом\n• несколько строк сразу — запишу все\n• «покакала» / 💩 — спрошу стул\n• «прикорм» / 🥣 — спрошу прикорм\n\nКнопки, отчёт, сводки: /menu", reply_markup=action_kb())


async def cmd_menu(update, context):
    remember_chat(update.effective_chat.id)
    await update.message.reply_text("Что показать?", reply_markup=action_kb())


async def cmd_total(update, context):
    await update.message.reply_text(total_text(update.effective_chat.id))


async def cmd_list(update, context):
    await update.message.reply_text(list_text(update.effective_chat.id))


async def cmd_yesterday(update, context):
    y = shifted_day(datetime.now(TZ)) - timedelta(days=1)
    await update.message.reply_text(list_text(update.effective_chat.id, y, "вчера"))


async def cmd_report(update, context):
    await update.message.reply_text(build_report(update.effective_chat.id, shifted_day(datetime.now(TZ)), datetime.now(TZ)))


async def cmd_week10(update, context):
    await update.message.reply_text(period_summary(update.effective_chat.id, 10))


async def cmd_month30(update, context):
    await update.message.reply_text(period_summary(update.effective_chat.id, 30))


async def cmd_tip(update, context):
    months = age_months(datetime.now(TZ).date())
    tip = tip_for_age(months)
    await update.message.reply_text(f"💡 Нам {months} мес: {tip}" if tip else "Совет не найден.")


async def cmd_dash(update, context):
    await update.message.reply_text(f"Код для дашборда: {DASH_CODE}")


async def cmd_undo(update, context):
    row = delete_last(update.effective_chat.id)
    if row:
        await update.message.reply_text(f"↩️ Удалил последнюю запись: {fmt(row[1])} мл.")
    else:
        await update.message.reply_text("Нечего удалять — записей нет.")


async def cmd_reset(update, context):
    reset_today(update.effective_chat.id, shifted_day(datetime.now(TZ)))
    await update.message.reply_text("🧹 Удалил все записи за сегодня.")


async def on_button(update, context):
    query = update.callback_query
    await query.answer()
    chat_id = update.effective_chat.id
    data = query.data
    if data == "total":
        await query.message.reply_text(total_text(chat_id))
    elif data == "list":
        await query.message.reply_text(list_text(chat_id))
    elif data == "poopday":
        await query.message.reply_text(poopday_text(chat_id))
    elif data == "report":
        await query.message.reply_text(build_report(chat_id, shifted_day(datetime.now(TZ)), datetime.now(TZ)))
    elif data == "week10":
        await query.message.reply_text(period_summary(chat_id, 10))
    elif data == "month30":
        await query.message.reply_text(period_summary(chat_id, 30))
    elif data == "yesterday":
        y = shifted_day(datetime.now(TZ)) - timedelta(days=1)
        await query.message.reply_text(list_text(chat_id, y, "вчера"))
    elif data == "undo":
        row = delete_last(chat_id)
        await query.message.reply_text(f"↩️ Удалил последнюю запись: {fmt(row[1])} мл." if row else "Нечего удалять — записей нет.")
    elif data == "reset":
        reset_today(chat_id, shifted_day(datetime.now(TZ)))
        await query.message.reply_text("🧹 Удалил все записи за сегодня.")
    elif data == "menu":
        await query.message.reply_text("Что показать?", reply_markup=action_kb())
    elif data == "dellist":
        rows = recent_del_rows(chat_id)
        if not rows:
            await query.message.reply_text("Записей за сегодня и вчера нет — удалять нечего.")
        else:
            await query.message.reply_text("Выбери запись, которую удалить:", reply_markup=del_kb(chat_id))
    elif data.startswith("del:"):
        _, kind, rid = data.split(":")
        rid = int(rid)
        if kind == "f":
            ok = delete_by_id(chat_id, rid) is not None
        elif kind == "p":
            ok = delete_poop(chat_id, rid)
        else:
            ok = delete_prikorm(chat_id, rid)
        if not ok:
            await query.answer("Запись уже удалена")
        else:
            remaining = recent_del_rows(chat_id)
            if remaining:
                await query.edit_message_text("✅ Удалил. Удалить ещё?", reply_markup=del_kb(chat_id))
            else:
                await query.edit_message_text("✅ Удалил. Записей больше нет.")
    elif data.startswith("pk:"):
        _, pid, cat = data.split(":")
        pid = int(pid)
        if cat == "drugoe":
            delete_prikorm(chat_id, pid)
            await query.edit_message_text("🥣 Напишите одним сообщением, например: прикорм каша с бананом 3 ложки")
        else:
            set_prikorm(pid, "item", PK_CATS[cat])
            await query.edit_message_text(f"🥣 {PK_CATS[cat]}. Сколько?", reply_markup=prikorm_amt_kb(pid))
    elif data.startswith("pv:"):
        _, pid, amt = data.split(":")
        set_prikorm(int(pid), "amount", PK_AMTS[amt])
        n = len(prikorm_rows(chat_id, shifted_day(datetime.now(TZ))))
        await query.edit_message_text(f"🥣 Записано, {PK_AMTS[amt]}. Сегодня прикорм: {n} раз.")
    elif data.startswith("pc:"):
        _, pid, cons = data.split(":")
        set_poop_field(int(pid), "consistency", cons)
        await query.edit_message_text(f"💩 Стул: {CONS_LABELS[cons]}. Сколько?", reply_markup=poop_amt_kb(int(pid)))
    elif data.startswith("pn:"):
        _, pid, amt = data.split(":")
        set_poop_field(int(pid), "amount", amt)
        n = len(poop_rows(chat_id, shifted_day(datetime.now(TZ))))
        await query.edit_message_text(f"💩 Записал. Сегодня покакали: {n} раз.\nПоследний: {AMT_LABELS[amt]}.")


async def on_text(update, context):
    remember_chat(update.effective_chat.id)
    raw = update.message.text
    user = update.message.from_user.first_name or "?"
    chat_id = update.effective_chat.id
    now = datetime.now(TZ)

    if is_prikorm(raw):
        low = raw.strip()
        rest = low[7:].strip() if low.lower().startswith("прикорм") else low[1:].strip()
        await start_prikorm(update.message.chat, chat_id, user, rest)
        return
    if is_poop(raw):
        await start_poop(update.message.chat, chat_id, user)
        return

    lines = [ln.strip() for ln in raw.splitlines() if ln.strip()]
    good = [(ln, parse_entry(ln)) for ln in lines]
    good = [(ln, p) for ln, p in good if p is not None]

    if len(good) >= 2:
        added = 0
        bad = 0
        last_day = None
        for ln, p in good:
            amount, ts = compute_ts(p, now)
            if ts is None or amount < MIN_ML or amount > MAX_ML:
                bad += 1
                continue
            add_feeding(chat_id, amount, user, ts)
            added += 1
            last_day = shifted_day(ts)
        msg = f"✅ Записал {added} кормлений."
        if last_day is not None:
            total = sum(r[1] for r in day_rows(chat_id, last_day))
            msg += f"\nИтого за {last_day.strftime('%d.%m')}: {fmt(total)} мл."
        if bad:
            msg += f"\n⚠️ Пропустил строк с ошибкой: {bad}."
        await update.message.chat.send_message(msg)
        await after_feeding_extras(update.message.chat, chat_id)
        return

    if not good:
        if looks_like_entry(raw):
            await update.message.chat.send_message("Не понял запись 🤔 Напиши, например:\n«120» или «10:00 - 120»")
        return

    ln, parsed = good[0]
    amount, ts = compute_ts(parsed, now)
    if ts is None:
        await update.message.chat.send_message("Не понял время — оно должно быть от 00:00 до 23:59.")
        return
    if amount < MIN_ML or amount > MAX_ML:
        await update.message.chat.send_message(f"Хм, проверь объём — должно быть от {MIN_ML} до {MAX_ML} мл 🙂")
        return

    prev = prev_feeding_ts(chat_id, ts)
    interval_line = ""
    if prev is not None:
        gap = human_interval(ts - prev)
        if gap:
            interval_line = f" · ⏱ {gap}"
    add_feeding(chat_id, amount, user, ts)
    when = ts.strftime("%H:%M")
    total = sum(r[1] for r in day_rows(chat_id, shifted_day(ts)))
    await update.message.chat.send_message(f"✅ {fmt(amount)} мл в {when}{interval_line}\nИтого за {shifted_day(ts).strftime('%d.%m')}: {fmt(total)} мл · {smart_comment(chat_id, amount, ts)}")
    await after_feeding_extras(update.message.chat, chat_id)


async def on_sticker(update, context):
    remember_chat(update.effective_chat.id)
    st = update.message.sticker
    emoji = (st.emoji or "") if st else ""
    if is_poop(emoji):
        user = update.message.from_user.first_name or "?"
        await start_poop(update.message.chat, update.effective_chat.id, user)


async def scheduler_loop(app):
    while True:
        try:
            now = datetime.now(TZ)
            report_time = now.replace(hour=REPORT_HOUR, minute=REPORT_MIN, second=0, microsecond=0)
            if now >= report_time:
                due_day = shifted_day(report_time - timedelta(minutes=1))
                ref = report_time
                for chat_id in all_chats():
                    if report_sent(chat_id, due_day):
                        continue
                    if (now.date() - due_day) > timedelta(days=1):
                        mark_report_sent(chat_id, due_day)
                        continue
                    if not day_rows(chat_id, due_day) and not poop_rows(chat_id, due_day) and not prikorm_rows(chat_id, due_day):
                        mark_report_sent(chat_id, due_day)
                        continue
                    try:
                        await app.bot.send_message(chat_id, build_report(chat_id, due_day, ref))
                        mark_report_sent(chat_id, due_day)
                    except Exception as e:
                        print("report send failed (retry later):", e)
        except Exception as e:
            print("scheduler error:", e)
        await asyncio.sleep(300)


async def on_error(update, context):
    print("Ошибка при обработке (бот продолжает работать):", context.error)


_bg_tasks = set()


async def post_init(app):
    t = asyncio.create_task(scheduler_loop(app))
    _bg_tasks.add(t)
    t.add_done_callback(_bg_tasks.discard)
    app.add_error_handler(on_error)


# ---------- ДАННЫЕ ДЛЯ ДАШБОРДА (планшет в том же Wi-Fi) ----------
def dash_chat_id():
    """Чат с самым свежим кормлением (обычно ваша группа)."""
    con = db()
    row = con.execute("SELECT chat_id FROM feedings ORDER BY ts DESC LIMIT 1").fetchone()
    con.close()
    return row[0] if row else None


def dash_data():
    chat_id = dash_chat_id()
    now = datetime.now(TZ)
    today = shifted_day(now)
    out = {"ok": True, "app": "feeding_bot", "now": now.isoformat(), "day": today.isoformat(), "goal_ml": DAY_GOAL_ML}
    if chat_id is None:
        out["last_feed"] = None
        return out
    con = db()
    last = con.execute("SELECT ts, amount, user FROM feedings WHERE chat_id=? ORDER BY ts DESC LIMIT 1", (chat_id,)).fetchone()
    con.close()
    if last:
        lts = datetime.fromisoformat(last[0])
        prev = prev_feeding_ts(chat_id, lts)
        out["last_feed"] = {
            "ts": lts.isoformat(), "ml": last[1], "user": last[2],
            "gap_min": int((lts - prev).total_seconds() // 60) if prev else None,
        }
    else:
        out["last_feed"] = None
    rows = day_rows(chat_id, today)
    out["today_ml"] = sum(r[1] for r in rows)
    out["today_count"] = len(rows)
    poops = poop_rows(chat_id, today)
    out["poops_today"] = len(poops)
    lp = last_poop_dt(chat_id)
    out["last_poop"] = lp.isoformat() if lp else None
    out["prikorm_today"] = len(prikorm_rows(chat_id, today))
    return out


class DashHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.split("?")[0] not in ("/", "/dash.json"):
            self.send_response(404)
            self.end_headers()
            return
        try:
            body = json.dumps(dash_data(), ensure_ascii=False).encode("utf-8")
            code = 200
        except Exception as e:
            body = json.dumps({"ok": False, "error": str(e)}).encode("utf-8")
            code = 500
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass  # не засорять экран Termux


def _relay_loop():
    import urllib.request
    last_body, last_sent = None, 0
    while True:
        try:
            d = dash_data()
            d.pop("now", None)
            body = json.dumps(d, ensure_ascii=False, sort_keys=True)
            if body != last_body or time.time() - last_sent > 600:
                d["now"] = datetime.now(TZ).isoformat()
                req = urllib.request.Request(
                    DASH_RELAY + DASH_CODE,
                    data=json.dumps(d, ensure_ascii=False).encode("utf-8"),
                    method="POST",
                    headers={"Title": "feeding", "Cache": "yes"},
                )
                urllib.request.urlopen(req, timeout=15).read()
                last_body, last_sent = body, time.time()
        except Exception as e:
            print("Дашборд через интернет: ошибка отправки (повторю):", e)
        time.sleep(30)


def start_dash_server():
    try:
        srv = ThreadingHTTPServer(("0.0.0.0", DASH_PORT), DashHandler)
    except OSError as e:
        print("Дашборд-сервер не запустился (порт занят?):", e)
        return
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    print(f"Данные для дашборда: порт {DASH_PORT}")


def start_dash_relay():
    threading.Thread(target=_relay_loop, daemon=True).start()
    print(f"Код для дашборда: {DASH_CODE}  (впишите его в дашборде: ⚙ → Годування)")


def main():
    if not BOT_TOKEN:
        print("Не найден токен: положите рядом feeding_bot_old.py (старую версию) или bot_private.txt со строкой BOT_TOKEN = \"...\"")
        return
    init_db()
    start_dash_server()
    start_dash_relay()
    while True:
        try:
            app = (
                Application.builder()
                .token(BOT_TOKEN)
                .post_init(post_init)
                .connect_timeout(30)
                .read_timeout(30)
                .write_timeout(30)
                .pool_timeout(30)
                .get_updates_read_timeout(40)
                .build()
            )
            app.add_handler(CommandHandler(["start", "help"], cmd_start))
            app.add_handler(CommandHandler("menu", cmd_menu))
            app.add_handler(CommandHandler("total", cmd_total))
            app.add_handler(CommandHandler("list", cmd_list))
            app.add_handler(CommandHandler("yesterday", cmd_yesterday))
            app.add_handler(CommandHandler("report", cmd_report))
            app.add_handler(CommandHandler("week10", cmd_week10))
            app.add_handler(CommandHandler("month30", cmd_month30))
            app.add_handler(CommandHandler("tip", cmd_tip))
            app.add_handler(CommandHandler("undo", cmd_undo))
            app.add_handler(CommandHandler("dash", cmd_dash))
            app.add_handler(CommandHandler("reset", cmd_reset))
            app.add_handler(CallbackQueryHandler(on_button))
            app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, on_text))
            app.add_handler(MessageHandler(filters.Sticker.ALL, on_sticker))
            print("Бот запущен. Ctrl+C для остановки.")
            app.run_polling()
            break
        except (KeyboardInterrupt, SystemExit):
            break
        except Exception as e:
            print("Проблема с сетью, переподключусь через 15 сек:", e)
            time.sleep(15)


if __name__ == "__main__":
    main()
