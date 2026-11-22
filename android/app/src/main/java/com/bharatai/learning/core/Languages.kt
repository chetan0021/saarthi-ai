package com.bharatai.learning.core

/**
 * The 22 scheduled languages of the Indian Constitution + English.
 * tier: 0 = officially supported by Llama 3.2 (English, Hindi), 1 = good results expected
 * with prompting, 2 = experimental (low-resource for a 1B model; Phase-2 LoRA target).
 */
data class AppLanguage(val code: String, val english: String, val native: String, val script: String, val tier: Int)

object Languages {
    val all = listOf(
        AppLanguage("en", "English", "English", "Latin", 0),
        AppLanguage("hi", "Hindi", "हिन्दी", "Devanagari", 0),
        AppLanguage("bn", "Bengali", "বাংলা", "Bengali", 1),
        AppLanguage("mr", "Marathi", "मराठी", "Devanagari", 1),
        AppLanguage("ta", "Tamil", "தமிழ்", "Tamil", 1),
        AppLanguage("te", "Telugu", "తెలుగు", "Telugu", 1),
        AppLanguage("kn", "Kannada", "ಕನ್ನಡ", "Kannada", 1),
        AppLanguage("gu", "Gujarati", "ગુજરાતી", "Gujarati", 1),
        AppLanguage("ml", "Malayalam", "മലയാളം", "Malayalam", 1),
        AppLanguage("pa", "Punjabi", "ਪੰਜਾਬੀ", "Gurmukhi", 1),
        AppLanguage("or", "Odia", "ଓଡ଼ିଆ", "Odia", 2),
        AppLanguage("as", "Assamese", "অসমীয়া", "Bengali", 2),
        AppLanguage("ur", "Urdu", "اردو", "Perso-Arabic", 1),
        AppLanguage("sa", "Sanskrit", "संस्कृतम्", "Devanagari", 2),
        AppLanguage("ne", "Nepali", "नेपाली", "Devanagari", 1),
        AppLanguage("kok", "Konkani", "कोंकणी", "Devanagari", 2),
        AppLanguage("mai", "Maithili", "मैथिली", "Devanagari", 2),
        AppLanguage("doi", "Dogri", "डोगरी", "Devanagari", 2),
        AppLanguage("sd", "Sindhi", "سنڌي", "Perso-Arabic", 2),
        AppLanguage("ks", "Kashmiri", "کٲشُر", "Perso-Arabic", 2),
        AppLanguage("mni", "Manipuri", "মৈতৈলোন্", "Bengali", 2),
        AppLanguage("sat", "Santali", "ᱥᱟᱱᱛᱟᱲᱤ", "Ol Chiki", 2),
        AppLanguage("brx", "Bodo", "बड़ो", "Devanagari", 2),
    )

    fun of(code: String) = all.firstOrNull { it.code == code } ?: all[0]
}
