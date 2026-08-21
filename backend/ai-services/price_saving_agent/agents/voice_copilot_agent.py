# backend/ai-services/price_saving_agent/agents/voice_copilot_agent.py
"""
Voice Co-pilot Ajanı — sürücünün sesli sorusunu (metne çevrilmiş halini),
PostGIS'ten gelen GERÇEK yakın istasyon/tarife verisiyle birleştirip
yapılandırılmış LLM çağrısıyla doğal, kısa (sesli okunacak) bir yanıt üretir.

Bilinçli tasarım kararı: CrewAI'nin çok-ajanlı orkestrasyonu (Fiyat
Tasarruf Ajanı'nda olduğu gibi) burada KULLANILMIYOR — bu tek-adımlı,
düşük gecikme gerektiren bir görev (sürücü yanıtı beklerken duruyor).
Doğrudan tek bir LangChain chat modeli çağrısı + yapılandırılmış JSON çıktı
+ pydantic doğrulaması, CrewAI'nin çoklu-ajan/görev overhead'inden çok daha
hızlı. Sağlayıcı VOICE_LLM_PROVIDER ile seçilir (bkz. services/llm_factory.py).
"""
import json
import re
import logging

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

from config import settings
from models.voice_schemas import VoiceQueryRequest, VoiceQueryResponse
from services.anonymizer import anonymizer
from services.conversation_memory import append_turn, load_history
from services.llm_factory import build_chat_llm
from services.db_service import DbService

logger = logging.getLogger(__name__)

# Kesik bir yanittan kurtarilan metnin sesli okunmaya deger sayilmasi icin
# gereken en az uzunluk.
MIN_SALVAGEABLE_REPLY_CHARS = 20

_SYSTEM_PROMPT = """Sen Eva'sın. Elektrikli araç kullanan birinin yol arkadaşısın — asistanı değil, arkadaşı.

NASIL KONUŞURSUN
Karşındaki direksiyonda, seni duyuyor. Yazıyla değil, sesle konuşuyorsun.
- Arkadaşın nasıl konuşuyorsa öyle konuş: kısa, rahat, sıcak. "Şu an   itibarıyla en uygun seçenek" değil, "en ucuzu Karaköy'deki, 7,80'den".
- Her seferinde AYNI kalıbı kurma. Bazen sadece cevabı ver, bazen küçük bir   yorum ekle, bazen soruyla karşılık ver. Tekdüzelik en büyük hatan olur.
- Kullanıcının adını ya da aracını biliyorsan doğal yerlerde kullan, her   cümlede değil.
- Gereksiz nezaket kalıplarını at: "Tabii ki!", "Elbette!", "Size yardımcı   olmaktan mutluluk duyarım" gibi şeyler robot işi. Doğrudan konuya gir.
- Uzunluk konuya göre değişsin. Fiyat sorulduysa tek cümle yeter;   "hangisini seçeyim" diye soruyorsa iki üç cümle konuşabilirsin.

KONUŞMANIN AKIŞI
Sana önceki konuşmalar da veriliyor. Onlara YASLAN: "peki ya diğeri?" dediğinde neyden bahsettiğini bilmelisin. Az önce söylediğin şeyi tekrar etme, üstüne konuş.

VERİ KONUSUNDA KESİN KURAL
Sana gerçek, doğrulanmış istasyon ve fiyat verisi veriliyor. Bir istasyon, mesafe ya da fiyat UYDURMA — veri yoksa "şu an fiyatı bilmiyorum" de. Bilmediğini söylemek, uydurmaktan iyidir. Sürücü senin sözüne güvenip yoldan çıkacak.

Şarj dışında bir şey sorarsa normal bir arkadaş gibi cevap ver; her şeyi şarja bağlamaya çalışma. Ama uydurma kuralı BURADA DA geçerli: hava durumu, trafik, yol durumu gibi anlık bilgilere erişimin YOK. Bunlar sorulduğunda bir sayı ya da durum uydurma — "ona bakamıyorum" de. Sohbet etmek serbest, olmayan veriyi bilgi diye sunmak değil.

ÇIKTI BİÇİMİ
Yanıtını SADECE şu JSON şemasına uygun döndür, başka metin ekleme:
{
  "spoken_reply": "sesli okunacak yanıt",
  "recommended_station_id": "önerdiğin istasyonun station_id'si ya da null",
  "recommended_station_name": "istasyon adı ya da null",
  "distance_meters": mesafe (tam sayı) ya da null,
  "estimated_price_per_kwh": kWh başı fiyat (ondalık) ya da null,
  "follow_up_suggested": true ya da false,
  "action": "none" ya da "navigate"
}
Bir istasyon önermiyorsan (örn. sohbet ediyorsanız) istasyon alanları null kalsın — bu bir hata değil.

ROTA ÇİZME
Kullanıcı bir yere GİTMEK istediğini belli ederse ("rota çiz", "oraya götür",
"navigasyon başlat", "hadi gidelim", "yol tarifi ver") action alanını
"navigate" yap ve recommended_station_id'ye hedef istasyonu koy. Bu durumda
harita rotayı kendisi çizecek — sen sadece kısaca onayla ("Tamam, Karaköy'e
rota çizdim") ve tarif SAYMA, dönüş dönüş anlatma.
Sadece bilgi soruyorsa ("en ucuz nerede?") action "none" kalsın — istenmeden
rota çizmek sürücüyü şaşırtır."""


class VoiceCopilotAgent:
    def __init__(self, db_service: DbService) -> None:
        self._db_service = db_service
        # Sağlayıcı .env'den seçilir (VOICE_LLM_PROVIDER). Bu yol CrewAI
        # kullanmadığı için doğrudan bir LangChain chat modeli alır.
        self._llm = build_chat_llm(
            provider=settings.voice_llm_provider,
            model=settings.voice_agent_model_name,
            # Yuksek sicaklik BILINCLI: 0.2 ile asistan her seferinde
            # neredeyse ayni cumleyi kuruyordu ("... en ucuz, ... uzaklikta").
            # Sohbet dogalligi icin cesitlilik sart. Veri uydurma riski
            # sicakliktan degil PROMPT'tan ve saglanan gercek veriden
            # kontrol ediliyor.
            temperature=0.8,
            # NEDEN 1200 (yanit 280 karakterle sinirliyken):
            # gpt-oss bir AKIL YURUTME modeli ve dusunme token'lari da bu
            # butceden harcaniyor. 400 ile dusunme butcenin neredeyse
            # tamamini yiyor, JSON yanit ORTASINDA kesiliyordu -- olculdu:
            # ham cikti 22 karakterde bitip ('{"spoken_reply": "S')
            # kullaniciya "Seni tam duyamadim" donuyordu. Gorunur yanit
            # zaten voice_agent_max_response_chars ile kirpiliyor, bu
            # yuzden yuksek tavan cikti uzunlugunu artirmaz.
            max_tokens=1200,
        )

    async def answer(self, request: VoiceQueryRequest) -> VoiceQueryResponse:
        # Sesle gelen serbest metin -- kullanıcı yanlışlıkla bir telefon
        # numarası, e-posta vb. söylemiş olabilir. LLM'e gitmeden önce
        # anonymizer'dan geçiriliyor (bkz. Fiyat Tasarruf Ajanı'ndaki aynı
        # ilke).
        sanitized_transcript = anonymizer.sanitize_text(request.transcript).sanitized_text

        try:
            nearby_stations = await self._db_service.get_nearby_stations_with_tariff(
                lat=request.lat,
                lon=request.lon,
                radius_meters=25_000,
                connector_types=request.vehicle_connector_types or None,
                limit=8,
            )
        except Exception as exc:
            logger.error("Voice Co-pilot: yakın istasyon sorgusu başarısız.", exc_info=exc)
            return VoiceQueryResponse(
                spoken_reply="Şu anda istasyon verisine ulaşamıyorum, birazdan tekrar dener misin?",
                follow_up_suggested=False,
            )

        if not nearby_stations:
            return VoiceQueryResponse(
                spoken_reply="Yakınında uygun bir şarj istasyonu bulamadım.",
                follow_up_suggested=False,
            )

        context_payload = {
            "driver_question": sanitized_transcript,
            "battery_soc_percent": request.battery_soc_percent,
            "nearby_stations": [
                {
                    "station_id": str(s["station_id"]),
                    "name": s["name"],
                    "distance_meters": round(s["distance_meters"]),
                    "max_power_kw": float(s["max_power_kw"]),
                    "price_per_kwh": float(s["price_per_kwh"]) if s["price_per_kwh"] else None,
                    "currency": s["currency"],
                }
                for s in nearby_stations
            ],
        }

        # Onceki turlari yukle: "peki ya digeri?" gibi devam sorularinin
        # anlasilmasi ve ayni cumlelerin tekrarlanmamasi icin sart.
        session_id = request.session_id or ""
        history = await load_history(session_id)

        messages: list = [SystemMessage(content=_SYSTEM_PROMPT)]
        for turn in history:
            if turn.get("role") == "assistant":
                messages.append(AIMessage(content=turn.get("content", "")))
            else:
                messages.append(HumanMessage(content=turn.get("content", "")))
        messages.append(
            HumanMessage(content=json.dumps(context_payload, ensure_ascii=False))
        )

        try:
            llm_response = await self._llm.ainvoke(messages)
            raw_text = llm_response.content if isinstance(llm_response.content, str) else str(
                llm_response.content
            )
        except Exception as exc:
            logger.error("Voice Co-pilot: Claude çağrısı başarısız.", exc_info=exc)
            return VoiceQueryResponse(
                spoken_reply="Şu anda yanıt veremiyorum, az sonra tekrar dener misin?",
                follow_up_suggested=False,
            )

        response = self._parse_llm_response(raw_text, nearby_stations)

        # Turu hafizaya yaz. Kullanicinin SOYLEDIGI metin saklanir, ham
        # baglam JSON'i degil -- konum/fiyat gibi anlik veriler eskiyince
        # yanlis cevap uretir, her istekte taze gelmeleri gerekir.
        await append_turn(session_id, sanitized_transcript, response.spoken_reply)

        return response

    def _parse_llm_response(self, raw_text: str, nearby_stations: list[dict]) -> VoiceQueryResponse:
        try:
            # Claude bazen JSON'ı ```json ... ``` bloğu içine alabilir —
            # önce bunu temizliyoruz.
            cleaned = raw_text.strip()
            if cleaned.startswith("```"):
                cleaned = cleaned.split("```")[1]
                cleaned = cleaned.removeprefix("json").strip()

            parsed = json.loads(cleaned)
            response = VoiceQueryResponse.model_validate(parsed)

            # Güvenlik ağı: LLM'in önerdiği station_id gerçekten sorguladığımız
            # istasyonlar arasında mı? Değilse (halüsinasyon riski), öneriyi
            # temizleyip yalnızca metni koruyoruz.
            valid_ids = {str(s["station_id"]) for s in nearby_stations}
            if response.recommended_station_id and response.recommended_station_id not in valid_ids:
                logger.warning(
                    "Voice Co-pilot: LLM var olmayan bir station_id önerdi, temizleniyor."
                )
                response.recommended_station_id = None
                response.recommended_station_name = None

            # Hedefsiz "navigate" olmaz. LLM rota niyeti bildirip istasyon
            # vermezse (ya da verdigi istasyon yukarida temizlendiyse)
            # istemci nereye rota cizecegini bilemez; niyeti dusuruyoruz.
            if response.action == "navigate" and not response.recommended_station_id:
                logger.warning("Voice Co-pilot: hedefsiz 'navigate' niyeti, 'none'a dusuruldu.")
                response.action = "none"

            # Sesli yanıt aşırı uzunsa kes (TTS deneyimi kötüleşmesin).
            if len(response.spoken_reply) > settings.voice_agent_max_response_chars:
                response.spoken_reply = response.spoken_reply[
                    : settings.voice_agent_max_response_chars
                ].rsplit(" ", 1)[0] + "."

            return response
        except Exception as exc:
            # JSON gelmediyse yaniti CÖPE ATMA -- duz metin olarak kullan.
            #
            # Neden: sohbet sorularinda ("nasilsin", "ne kadar surer")
            # model dogal olarak duz metinle cevap veriyor ve eski kod bunu
            # hata sayip "Yanıtı tam olarak oluşturamadım" diyordu. Yani
            # asistan tam da SOHBET ETTIGINDE bozuluyordu. Istasyon
            # alanlari bos kalir; bu bir hata degil, o turda istasyon
            # onerilmedigi anlamina gelir.
            fallback = self._extract_plain_reply(raw_text)
            if fallback:
                logger.info("Voice Co-pilot: JSON yok, duz metin yanit kullanildi.")
                return VoiceQueryResponse(
                    spoken_reply=fallback,
                    follow_up_suggested=False,
                )

            # Ham yaniti da kaydet: bos donus (kota/hiz siniri) ile bozuk
            # JSON ayni hata mesajini uretiyordu ve ikisi tamamen farkli
            # sorunlar. Metin kirpiliyor -- log'a tam yanit dokmek gereksiz.
            logger.error(
                "Voice Co-pilot: LLM yanıtı ne JSON ne de kullanılabilir metin. "
                "ham_uzunluk=%d ham_onek=%r",
                len(raw_text or ""),
                (raw_text or "")[:200],
                exc_info=exc,
            )
            return VoiceQueryResponse(
                spoken_reply="Seni tam duyamadım, tekrar eder misin?",
                follow_up_suggested=True,
            )

    @staticmethod
    def _extract_plain_reply(raw_text: str) -> str:
        """
        JSON olmayan bir yaniti sesli okunabilir metne cevirir.

        Kod bloklarini ve olasi yarim JSON parcalarini temizler; geriye
        anlamli bir cumle kalmazsa bos string doner.
        """
        text = raw_text.strip()
        if text.startswith("```"):
            parts = text.split("```")
            text = parts[1] if len(parts) > 1 else parts[0]
            text = text.removeprefix("json").strip()

        # Yarim kalmis JSON: ham metni sesli okumak sacma olur ("suslu
        # parantez spoken reply iki nokta...") ama icindeki cevabi ATMAK da
        # yazik. Olculdu: model devam sorularinda ("peki ya digeri?")
        # duzenli olarak kesik JSON uretiyor ve eski kod bunu tamamen
        # reddedip "Seni tam duyamadım" diyordu.
        #
        # Bu yuzden once spoken_reply alanini regex ile cikarmayi deneriz.
        if text.startswith("{") or text.startswith("["):
            match = re.search(r'"spoken_reply"\s*:\s*"(.*?)(?<!\\)"', text, re.DOTALL)

            # Yanit tam ORTADA kesildiyse kapanis tirnagi HIC gelmez ve
            # yukaridaki desen bosa duser. Bu durumda acilis tirnagindan
            # sonrasini sonuna kadar aliyoruz -- ama yalnizca anlamli bir
            # uzunluktaysa: tek harflik bir kirinti ("S") sesli okununca
            # kullaniciyi sasirtir, "tekrar eder misin" demek daha durust.
            if not match:
                partial = re.search(r'"spoken_reply"\s*:\s*"(.+)', text, re.DOTALL)
                if partial and len(partial.group(1).strip()) >= MIN_SALVAGEABLE_REPLY_CHARS:
                    match = partial

            if match:
                # \" ve \n gibi JSON kacislarini coz; ham haliyle sesli
                # okunursa kullanici ters bolu duyar.
                return (
                    match.group(1)
                    .replace('\\"', '"')
                    .replace("\\n", " ")
                    .replace("\\\\", "\\")
                    .strip()
                )
            return ""

        # Cok uzun yanitlari kirp: surucu dinliyor, paragraf istemiyoruz.
        if len(text) > 400:
            text = text[:400].rsplit(" ", 1)[0] + "."

        return text
