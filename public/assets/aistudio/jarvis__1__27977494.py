import os
import requests
import json
import re
import time
import base64
import subprocess
import pytz
from datetime import datetime, timedelta
from apscheduler.schedulers.asyncio import AsyncIOScheduler
from telegram import Update
from telegram.ext import ApplicationBuilder, CommandHandler, MessageHandler, filters, ContextTypes
from telegram.error import NetworkError
import config
import database

MODELS = [
    "gemini-3.8-flash"
]

MY_CHAT_ID = None
VOICE_ENABLED = True
CURRENT_VOICE = "fr-FR-DeniseNeural"
CURRENT_RATE = "+0%"
AUDIO_OUTPUT = os.path.expanduser("~/jarvis_voice.mp3")

scheduler = None

def deduplicate_text(text: str) -> str:
    if not text:
        return text
    txt = text.strip()
    length = len(txt)
    if length % 2 == 0:
        half = length // 2
        if txt[:half] == txt[half:]:
            return txt[:half]
    return txt

def get_system_instruction():
    facts = database.get_all_facts()
    return f"""Tu es Alita, une assistante personnelle autonome, intelligente, bienveillante et très proche de l'utilisateur.

CONSIGNES DE STYLE :
- Tes réponses sont claires, chaleureuses et vivantes.
- De temps en temps, tu pimentes tes échanges avec une touche d'ironie amicale ou un humour pince-sans-rire.

MÉMOIRE ET CONNAISSANCES :
{facts}
"""

def clean_text_for_tts(text):
    text = re.sub(r'[*_#`~]', '', text)
    text = re.sub(r'http\S+', '', text)
    return text.strip()

def speak(text):
    if not VOICE_ENABLED:
        return
    clean_txt = clean_text_for_tts(text)
    if clean_txt:
        try:
            clean_txt_escaped = clean_txt.replace('"', '\\"')
            cmd = f'edge-tts --voice {CURRENT_VOICE} --rate="{CURRENT_RATE}" --text "{clean_txt_escaped}" --write-media {AUDIO_OUTPUT} && mpv {AUDIO_OUTPUT} > /dev/null 2>&1'
            subprocess.Popen(cmd, shell=True)
        except Exception as e:
            print(f"Erreur TTS : {e}")

# --- APPEL GEMINI SIMPLE (SANS RECHERCHE GOOGLE / SANS TOOLS) ---

def call_gemini_api(prompt, history=None, media_bytes=None, mime_type="image/jpeg"):
    if history is None:
        history = []

    system_instruction = get_system_instruction()

    for model_name in MODELS:
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{model_name}:generateContent"
        headers = {
            "Content-Type": "application/json",
            "x-goog-api-key": config.GEMINI_API_KEY
        }

        contents = []
        for msg in history:
            role = "user" if msg.get("role") == "user" else "model"
            contents.append({
                "role": role,
                "parts": [{"text": msg.get("text", "")}]
            })

        user_parts = []
        if media_bytes:
            b64_data = base64.b64encode(media_bytes).decode('utf-8')
            user_parts.append({
                "inline_data": {
                    "mime_type": mime_type,
                    "data": b64_data
                }
            })

        user_parts.append({"text": prompt or "Réponds au message."})

        contents.append({
            "role": "user",
            "parts": user_parts
        })

        payload = {
            "contents": contents,
            "systemInstruction": {
                "parts": [{"text": system_instruction}]
            }
        }

        try:
            response = requests.post(url, json=payload, headers=headers, timeout=20)
            if response.status_code == 200:
                data = response.json()
                candidate = data.get("candidates", [{}])[0]
                parts = candidate.get("content", {}).get("parts", [])
                text_parts = [p["text"] for p in parts if "text" in p]
                if text_parts:
                    return "\n".join(text_parts)
            elif response.status_code in (429, 503):
                time.sleep(1)
                continue
            else:
                print(f"Erreur API ({response.status_code}) : {response.text}")
        except Exception as e:
            print(f"Erreur HTTP ({model_name}) : {e}")

    return "⚠️ Tous les modèles sont temporairement indisponibles."

# --- FONCTIONS RECHERCHE APPELS, SMS ET NOTIFICATIONS ---

def get_recent_calls_and_sms():
    details = []
    try:
        proc_calls = subprocess.run(["termux-call-log", "-l", "10"], capture_output=True, text=True, timeout=5)
        if proc_calls.returncode == 0 and proc_calls.stdout.strip():
            calls = json.loads(proc_calls.stdout)
            missed = [c for c in calls if c.get("type") in ["MISSED", "REJECTED"]]
            if missed:
                details.append("📞 **Appels manqués récents :**")
                for c in missed[:5]:
                    name = c.get("name") or c.get("phone_number", "Inconnu")
                    date_str = c.get("date", "")
                    details.append(f"- {name} ({date_str})")
    except Exception as e:
        print(f"Erreur lecture appels : {e}")

    try:
        proc_sms = subprocess.run(["termux-sms-list", "-l", "10"], capture_output=True, text=True, timeout=5)
        if proc_sms.returncode == 0 and proc_sms.stdout.strip():
            sms_list = json.loads(proc_sms.stdout)
            received = [s for s in sms_list if s.get("type") == "inbox"]
            if received:
                details.append("💬 **Derniers SMS reçus :**")
                for s in received[:5]:
                    sender = s.get("number") or s.get("sender", "Inconnu")
                    body = s.get("body", "")
                    details.append(f"- De {sender} : \"{body}\"")
    except Exception as e:
        print(f"Erreur lecture SMS : {e}")

    return "\n".join(details)

def get_recent_notifications_text():
    try:
        proc = subprocess.run(["termux-notification-list"], capture_output=True, text=True, timeout=5)
        if proc.returncode == 0 and proc.stdout.strip():
            notifs_data = json.loads(proc.stdout)
            if notifs_data:
                notif_summary = []
                for n in notifs_data[:10]:
                    title = n.get("title", "Sans titre")
                    content = n.get("content", "")
                    app = n.get("packageName", "").split(".")[-1]
                    if content:
                        notif_summary.append(f"- [{app}] {title} : {content}")
                return "\n".join(notif_summary)
    except Exception as e:
        print(f"Erreur lecture notifications : {e}")
    return ""

async def check_all_communications_and_notifs(bot):
    global MY_CHAT_ID
    if not MY_CHAT_ID:
        return

    comms_data = get_recent_calls_and_sms()
    notifs_data = get_recent_notifications_text()

    if not comms_data and not notifs_data:
        return

    prompt = (
        "Voici le bilan récent des communications et notifications du téléphone :\n\n"
        f"--- NOTIFICATIONS ANDROID ---\n{notifs_data or 'Aucune'}\n\n"
        f"--- APPELS ET SMS ---\n{comms_data or 'Aucun'}\n\n"
        "Fais une synthèse claire et concise des éléments importants. "
        "Si des SMS, appels manqués ou messages nécessitent une réponse, propose des réponses prêtes à l'envoi."
    )

    history = database.get_recent_history(limit=4)
    response_text = call_gemini_api(prompt, history=history)
    
    if "⚠️" not in response_text and len(response_text.strip()) > 10:
        database.add_message("model", response_text)
        try:
            await bot.send_message(chat_id=MY_CHAT_ID, text=response_text, parse_mode="Markdown")
        except Exception:
            await bot.send_message(chat_id=MY_CHAT_ID, text=response_text)
        speak(response_text)

# --- TÂCHES PLANIFIÉES ET RITUELS ---

async def morning_routine(bot):
    global MY_CHAT_ID
    if not MY_CHAT_ID:
        return

    prompt = (
        "C'est le matin à 9h00. Souhaite-moi une très belle journée. "
        "Partage un verset de la Bible inspirant et fortifiant, accompagné d'une réflexion courte et encourageante pour ma journée. "
        "Propose également 2 ou 3 petits objectifs facultatifs et simples."
    )
    history = database.get_recent_history(limit=4)
    response_text = call_gemini_api(prompt, history=history)
    
    database.add_message("model", response_text)
    try:
        await bot.send_message(chat_id=MY_CHAT_ID, text=response_text, parse_mode="Markdown")
    except Exception:
        await bot.send_message(chat_id=MY_CHAT_ID, text=response_text)
    speak(response_text)

async def evening_routine(bot):
    global MY_CHAT_ID
    if not MY_CHAT_ID:
        return

    prompt = (
        "C'est la fin de soirée à 23h00. Fais un mot chaleureux et apaisant. "
        "Invite-moi à faire un bref bilan de ma journée (ce qui s'est bien passé). "
        "Partage un verset biblique réconfortant ou une courte prière du soir pour passer une nuit sereine."
    )
    history = database.get_recent_history(limit=4)
    response_text = call_gemini_api(prompt, history=history)
    
    database.add_message("model", response_text)
    try:
        await bot.send_message(chat_id=MY_CHAT_ID, text=response_text, parse_mode="Markdown")
    except Exception:
        await bot.send_message(chat_id=MY_CHAT_ID, text=response_text)
    speak(response_text)

async def periodic_notification(bot):
    global MY_CHAT_ID
    if not MY_CHAT_ID:
        return

    prompt = "Fais un petit coucou amical ou donne une pensée concise/sympa pour prendre des nouvelles."
    history = database.get_recent_history(limit=4)
    response_text = call_gemini_api(prompt, history=history)
    
    database.add_message("model", response_text)
    try:
        await bot.send_message(chat_id=MY_CHAT_ID, text=response_text, parse_mode="Markdown")
    except Exception:
        await bot.send_message(chat_id=MY_CHAT_ID, text=response_text)
    speak(response_text)

async def trigger_alarm(bot, chat_id):
    msg = "⏰ **DEBOUT !** C'est l'heure du réveil ! ☀️🔔"
    await bot.send_message(chat_id=chat_id, text=msg, parse_mode="Markdown")
    speak("Debout ! C'est l'heure du réveil !")

async def cmd_reveil(update: Update, context: ContextTypes.DEFAULT_TYPE):
    global scheduler
    
    args = context.args
    if not args and update.message.text:
        parts = update.message.text.split()
        if len(parts) > 1:
            args = parts[1:]

    if not args:
        await update.message.reply_text("Usage : `/reveil 15s`, `/reveil 1m` ou `/reveil 07:30`", parse_mode="Markdown")
        return

    arg = args[0].lower()
    local_tz = pytz.timezone("Europe/Paris")
    now = datetime.now(local_tz)
    run_date = None

    if arg.endswith("s") and arg[:-1].isdigit():
        secondes = int(arg[:-1])
        run_date = now + timedelta(seconds=secondes)
    elif arg.endswith("m") and arg[:-1].isdigit():
        minutes = int(arg[:-1])
        run_date = now + timedelta(minutes=minutes)
    elif ":" in arg:
        try:
            h, m = map(int, arg.split(":"))
            run_date = now.replace(hour=h, minute=m, second=0, microsecond=0)
            if run_date <= now:
                run_date += timedelta(days=1)
        except ValueError:
            pass

    if not run_date:
        await update.message.reply_text("❌ Format invalide. Exemple : `/reveil 15s`, `/reveil 1m` ou `/reveil 07:30`", parse_mode="Markdown")
        return

    chat_id = update.effective_chat.id
    scheduler.add_job(
        trigger_alarm,
        'date',
        run_date=run_date,
        args=[context.bot, chat_id]
    )

    heure_str = run_date.strftime("%H:%M:%S")
    await update.message.reply_text(f"⏰ Réveil/minuteur confirmé pour **{heure_str}** !", parse_mode="Markdown")

# --- COMMANDES PHOTO, VOIX, NOTIFICATIONS, COMMUNICATIONS ET HELP ---

async def cmd_comms(update: Update, context: ContextTypes.DEFAULT_TYPE):
    msg_wait = await update.message.reply_text("📞 Vérification des SMS et journal d'appels...")
    
    try:
        data = get_recent_calls_and_sms()
        if not data or not data.strip():
            await msg_wait.edit_text("📋 **Liste vide** : Aucun appel manqué ni SMS récent trouvé.", parse_mode="Markdown")
            return

        prompt = (
            f"Voici le relevé des appels et SMS :\n{data}\n\n"
            "Fais un résumé clair et propose des réponses SMS ou actions prêtes pour chaque contact."
        )
        history = database.get_recent_history(limit=4)
        response_text = call_gemini_api(prompt, history=history)
        
        try:
            await msg_wait.edit_text(response_text, parse_mode="Markdown")
        except Exception:
            await msg_wait.edit_text(response_text)

        speak(response_text)
    except Exception as e:
        await msg_wait.edit_text(f"❌ Erreur lors du traitement : {e}")

async def cmd_notifs(update: Update, context: ContextTypes.DEFAULT_TYPE):
    msg_wait = await update.message.reply_text("📲 Récupération et analyse des notifications...")
    try:
        raw_text = get_recent_notifications_text()
        if not raw_text:
            await msg_wait.edit_text("📭 Aucune notification en attente ou permission Termux manquante.")
            return

        prompt = (
            f"Voici les notifications reçues sur le téléphone :\n{raw_text}\n\n"
            "Fais une synthèse claire des messages importants et propose des réponses."
        )

        history = database.get_recent_history(limit=4)
        response_text = call_gemini_api(prompt, history=history)
        
        database.add_message("user", "[Analyse notifications]")
        database.add_message("model", response_text)

        try:
            await msg_wait.edit_text(response_text, parse_mode="Markdown")
        except Exception:
            await msg_wait.edit_text(response_text)

        speak(response_text)

    except Exception as e:
        await msg_wait.edit_text(f"❌ Erreur lors de la lecture des notifications : {e}")

async def cmd_help(update: Update, context: ContextTypes.DEFAULT_TYPE):
    help_text = (
        "🤖 **Fonctionnalités d'Alita**\n\n"
        "⏰ **/reveil [durée/heure]** : Programme un minuteur ou un réveil.\n"
        "   • Exemples : `/reveil 15s`, `/reveil 5m`, `/reveil 07:30`\n\n"
        "📞 **/comms** : Analyse les SMS et appels manqués et prépare des réponses.\n\n"
        "📲 **/notifs** : Résume les notifications Android et propose des réponses.\n\n"
        "📸 **/photo** : Dégaine l'appareil photo du téléphone et renvoie le cliché.\n\n"
        "🎙️ **/voix [nom]** : Modifie la voix TTS (`denise`, `eloise`, `henri`).\n\n"
        "⚡ **/vitesse [débit]** : Ajuste la vitesse vocale (ex: `/vitesse +20%`).\n\n"
        "🔄 **Planifications automatiques :**\n"
        "   • **09h00** : Verset biblique du matin & objectifs facultatifs.\n"
        "   • **23h00** : Bilan de journée & verset / prière du soir.\n"
        "   • **Toutes les 1h** : Petit mot amical.\n"
        "   • **Toutes les 2h** : Synthèse combinée (Notifications, SMS & Appels manqués).\n\n"
        "❓ **/help** : Affiche ce menu d'aide."
    )
    await update.message.reply_text(help_text, parse_mode="Markdown")

async def cmd_photo(update: Update, context: ContextTypes.DEFAULT_TYPE):
    msg_wait = await update.message.reply_text("📸 Prise de photo en cours...")
    photo_path = os.path.expanduser("~/photo.jpg")

    if os.path.exists(photo_path):
        os.remove(photo_path)

    proc = subprocess.run(["termux-camera-photo", "-c", "0", photo_path], capture_output=True, text=True)

    if os.path.exists(photo_path) and os.path.getsize(photo_path) > 0:
        with open(photo_path, "rb") as f:
            await update.message.reply_photo(photo=f, caption="📸 Photo prise par le téléphone.")
        await msg_wait.delete()
    else:
        err = proc.stderr.strip() if proc.stderr else "Impossible de capturer la photo"
        await msg_wait.edit_text(f"❌ Erreur appareil photo : {err}")

async def cmd_voix(update: Update, context: ContextTypes.DEFAULT_TYPE):
    global CURRENT_VOICE
    args = context.args
    if not args:
        nom_voix = CURRENT_VOICE.replace("fr-FR-", "").replace("Neural", "")
        await update.message.reply_text(f"🗣️ Voix actuelle : **{nom_voix}**\nExemples : `/voix eloise` ou `/voix denise`", parse_mode="Markdown")
        return

    choix = args[0].lower()
    if choix in ["eloise", "Éloïse", "eloïse"]:
        CURRENT_VOICE = "fr-FR-EloiseNeural"
        msg = "Voix changée pour **Eloise** 🎙️"
    elif choix in ["denise"]:
        CURRENT_VOICE = "fr-FR-DeniseNeural"
        msg = "Voix changée pour **Denise** 🎙️"
    elif choix in ["henri"]:
        CURRENT_VOICE = "fr-FR-HenriNeural"
        msg = "Voix changée pour **Henri** 🎙️"
    else:
        msg = f"Voix personnalisée : `{choix}`"
        CURRENT_VOICE = choix

    await update.message.reply_text(msg, parse_mode="Markdown")

async def cmd_vitesse(update: Update, context: ContextTypes.DEFAULT_TYPE):
    global CURRENT_RATE
    args = context.args
    if not args:
        await update.message.reply_text(f"⚡ Vitesse actuelle : **{CURRENT_RATE}**\nExemple : `/vitesse +20%` ou `/vitesse -10%`", parse_mode="Markdown")
        return

    val = args[0].strip()
    if not (val.startswith("+") or val.startswith("-")):
        val = f"+{val}"
    if not val.endswith("%"):
        val = f"{val}%"

    CURRENT_RATE = val
    await update.message.reply_text(f"⚡ Vitesse de parole réglée sur **{CURRENT_RATE}**", parse_mode="Markdown")

# --- HANDLERS TEXTE, PHOTOS ET VOCAUX ---

async def handle_message(update: Update, context: ContextTypes.DEFAULT_TYPE):
    global MY_CHAT_ID
    MY_CHAT_ID = update.effective_chat.id
    raw_text = update.message.text
    if not raw_text:
        return

    if raw_text.startswith("/réveil") or raw_text.startswith("/reveil"):
        await cmd_reveil(update, context)
        return

    user_text = deduplicate_text(raw_text)

    database.add_message("user", user_text)
    history = database.get_recent_history(limit=6)
    response_text = call_gemini_api(user_text, history)
    database.add_message("model", response_text)

    for attempt in range(3):
        try:
            await update.message.reply_text(response_text, parse_mode="Markdown")
            break
        except Exception:
            try:
                await update.message.reply_text(response_text)
                break
            except NetworkError:
                time.sleep(2)

    speak(response_text)

async def handle_photo(update: Update, context: ContextTypes.DEFAULT_TYPE):
    global MY_CHAT_ID
    MY_CHAT_ID = update.effective_chat.id
    
    photo_file = await update.message.photo[-1].get_file()
    image_bytes = await photo_file.download_as_bytearray()
    caption = update.message.caption or "Que vois-tu sur cette image ?"

    database.add_message("user", f"[Photo reçue] {caption}")
    history = database.get_recent_history(limit=4)
    
    response_text = call_gemini_api(caption, history=history, media_bytes=bytes(image_bytes), mime_type="image/jpeg")
    database.add_message("model", response_text)

    for attempt in range(3):
        try:
            await update.message.reply_text(response_text)
            break
        except NetworkError:
            time.sleep(2)

    speak(response_text)

async def handle_voice(update: Update, context: ContextTypes.DEFAULT_TYPE):
    global MY_CHAT_ID
    MY_CHAT_ID = update.effective_chat.id

    voice_file = await update.message.voice.get_file()
    voice_bytes = await voice_file.download_as_bytearray()

    prompt = "Écoute cet enregistrement vocal, réponds directement à ce que dit l'utilisateur."
    history = database.get_recent_history(limit=4)

    response_text = call_gemini_api(prompt, history=history, media_bytes=bytes(voice_bytes), mime_type="audio/ogg")

    database.add_message("user", "[Message vocal]")
    database.add_message("model", response_text)

    for attempt in range(3):
        try:
            await update.message.reply_text(response_text)
            break
        except NetworkError:
            time.sleep(2)

    speak(response_text)

async def post_init(app):
    global scheduler
    local_tz = pytz.timezone("Europe/Paris")
    scheduler = AsyncIOScheduler(timezone=local_tz)
    
    # Message du matin (9h00)
    scheduler.add_job(morning_routine, 'cron', hour=9, minute=0, args=[app.bot])
    
    # Message du soir / Bilan (23h00)
    scheduler.add_job(evening_routine, 'cron', hour=23, minute=0, args=[app.bot])
    
    # Prise de nouvelles amicale horaire
    scheduler.add_job(periodic_notification, 'interval', hours=1, args=[app.bot])
    
    # Synthèse complète (Notifications Android + Appels/SMS) toutes les 2 heures (120 minutes)
    scheduler.add_job(check_all_communications_and_notifs, 'interval', minutes=120, args=[app.bot])
    
    scheduler.start()

if __name__ == '__main__':
    database.init_db()
    app = ApplicationBuilder().token(config.TELEGRAM_TOKEN).post_init(post_init).build()
    
    app.add_handler(CommandHandler(["help", "aide"], cmd_help))
    app.add_handler(CommandHandler(["reveil", "alarm", "alarme"], cmd_reveil))
    app.add_handler(CommandHandler(["comms", "appels", "sms"], cmd_comms))
    app.add_handler(CommandHandler(["notif", "notifs", "notifications"], cmd_notifs))
    app.add_handler(CommandHandler(["photo", "photos"], cmd_photo))
    app.add_handler(CommandHandler("voix", cmd_voix))
    app.add_handler(CommandHandler(["vitesse", "rate"], cmd_vitesse))
    
    app.add_handler(MessageHandler(filters.PHOTO, handle_photo))
    app.add_handler(MessageHandler(filters.VOICE, handle_voice))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_message))
    
    print("🤖 Alita est en ligne sur Telegram !")
    app.run_polling(drop_pending_updates=True)
