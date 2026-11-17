package uz.barchasibittada

object T {
    const val SYSTEM_PROMPT = "Sen o'zbek tilida gaplashadigan yordamchisan. Javoblaring qisqa, aniq va sodda bo'lsin. " +
        "Ekran o'quvchi bilan o'qilishini yodda tut: jadval, murakkab belgilar va ortiqcha bezaklardan foydalanma. " +
        "Savol bergan odamning tilida javob ber."

    const val DETAIL = "Javob qisqa bo'lmasin: savol nima haqida bo'lsa, o'sha narsani har tomonlama, keng va chuqur yorit. " +
        "Faktlarni sanab qo'yish bilan cheklanma — sababini, tafsilotini va o'zaro bog'liq tomonlarini ham tushuntir. " +
        "Javob kamida 2500 belgidan iborat batafsil matn bo'lsin."

    const val SEARCH_HINT = "Internetdan topilgan eng yangi ma'lumotga tayan va manbalarni hisobga ol."

    const val NO_KEY = "Hali birorta API kaliti qo'shilmagan. Sozlamalar bo'limida Gemini, Claude, ChatGPT yoki Grok " +
        "kalitini qo'shing. Kalitsiz ham ishlaydigan narsalar: hujjatlarni o'qish, lotin va kirill almashtirish."

    const val TAQIQ_18 = "Kechirasiz, bu savolga javob berilmaydi. 18+ va pornografik mavzulardagi so'rovlarga javob berilmaydi."

    const val TAQIQ_DIN = "Diniy savollarga javob berilmaydi — bu sohada javob faqat mutaxassis tomonidan beriladi. " +
        "Iltimos, tegishli diniy muassasaga murojaat qiling."

    const val BOT_MAIN = "UzNaKo_bot"
    const val BOT_SECOND = "Girgittonei_bot"

    const val BOTS_TITLE = "Ilovaning Telegram botlari"
    const val BOTS_TEXT = "Bu ilovadagi imkoniyatlar Telegram botlarida ham mavjud. Botlarga havolalar:"

    const val HELP = "barchasibittada — o'zbek tilida ishlaydigan yordamchi. U savolga javob beradi, rasm, hujjat, audio va " +
        "videoni tushunadi, matnni ovozga aylantiradi, kitob yozadi va ko'proq.\n\n" +
        "SUHBAT\n" +
        "Suhbat bo'limida savolingizni yozing yoki mikrofon tugmasini bosib ovozli savol bering. " +
        "«Biriktirish» tugmasi bilan rasm, PDF, Word, kitob, audio yoki video fayl yuboring — ilova nima qilishni so'raydi. " +
        "Xabar tagidagi «Amallar» tugmasi orqali javobni o'qib berish, nusxa olish, ulashish, lotin yoki kirillga o'girish, tarjima va imlo tuzatish mumkin.\n\n" +
        "TABIIY BUYRUQLAR\n" +
        "Matn ichida shunday yozsangiz ham bo'ladi: «Salom dunyo, o'qib ber», «lotinga o'gir: <matn>», «ruschaga o'gir: <matn>», " +
        "«imlosini to'g'irla: <matn>», «Ona tili haqida 10 sahifali maqola yoz».\n\n" +
        "ASBOBLAR\n" +
        "Savol tuzish, konspekt, sodda tushuntirish, tarjima, test (interaktiv), maqola yozish, matn tahriri (imlo), " +
        "lotin va kirill almashtirish, rasm yaratish, kitob (sahifalarni kameradan o'qish) va matnni audiokitobga aylantirish.\n\n" +
        "OVOZ\n" +
        "Ovoz bo'limida audiolashtirish ishlari ko'rinadi: matn yoki kitob (skanerlangan PDF ham) Gemini ovozi bilan o'zbekcha audioga aylantiriladi. " +
        "Skanerlangan sahifalar AI bilan o'qiladi, imlosi kitob mazmuniga tayanib tuzatiladi va audiolashtiriladi. " +
        "Limit tugasa, ish to'xtagan joyidan o'zi davom etadi; to'xtagan joydagi matnni saqlab olish mumkin. " +
        "Ovoz, o'qish uslubi, modellar, imlo tuzatish sozlamalari va xato jurnali ham shu bo'limda. " +
        "Har javobga ovoz qo'shish va ovoz rejimini yoqish mumkin.\n\n" +
        "KALITLAR\n" +
        "Sozlamalar bo'limida o'z kalitlaringizni qo'shing — har bir xizmat uchun kamida 5 ta (eng ko'pi 10 ta) va ular saqlanadi. Biri limitga yetsa yoki ishlamasa, ilova keyingisiga o'tadi. " +
        "Kalit faqat shu qurilmada shifrlangan holda saqlanadi. " +
        "Grok kaliti qo'shilganda Grok ning matn, rasm tahlili, internet va X qidiruvi, rasm yaratish va ovozni matnga aylantirish imkoniyatlari ochiladi.\n\n" +
        "TELEGRAM BOTLARI\n" +
        "Ilovaning Telegram botlari: @UzNaKo_bot va @Girgittonei_bot."
}
