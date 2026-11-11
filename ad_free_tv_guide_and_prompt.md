# 📺 Android TV / Google TV — Ad Removal Master Guide & AI Prompt

यह गाइड और प्रॉम्प्ट भविष्य में किसी भी Android TV या Google TV से सभी स्पॉन्सर्ड विज्ञापन (Sponsored Ads), प्रोमोशन बैनर और भारी-भरकम 'Shop/Discover' टैब्स को बिना किसी झंझट के 1 क्लिक में हटाने के लिए तैयार किया गया है।

---

## 📋 1. AI Assistant के लिए मास्टर प्रॉम्प्ट (Copy-Paste Prompt)

जब भी आप किसी नए AI एजेंट से यह काम करवाना चाहें, तो बस नीचे दिया गया प्रॉम्प्ट कॉपी करके दे दें:

```text
मेरी Android TV (IP: <YOUR_TV_IP>) पर Google TV / Android TV लॉन्चर में आने वाले सभी स्पॉन्सर्ड वीडियो ऐड्स, स्पॉन्सर्ड चैनल्स और Shop/Discover ऐड टैब्स को बिना लॉन्चर बदले या APK तोड़े सीधे ADB से रिमूव करो।

निर्देश:
1. स्टॉक com.google.android.tvlauncher को अनइंस्टॉल न करें (इससे थर्ड-पार्टी भद्दा लॉन्चर आ जाता है)।
2. ऐड्स का मुख्य डेटा प्रोवाइडर 'com.google.android.tvrecommendations' (AdsSignalsContentProvider) है, इसे 'pm disable-user --user 0 com.google.android.tvrecommendations' से डिसेबल करें।
3. इसका डेटा और नोटिफिकेशन्स साफ करें:
   - pm clear com.google.android.tvrecommendations
   - content delete --uri content://com.google.android.tvrecommendations.NotificationContentProvider/notifications
4. ऐड-ट्रैकिंग और फ्रीप्ले बंद करें:
   - settings put secure freeplay_enabled 0
   - settings put secure limit_ad_tracking 1
5. लॉन्चर को रीस्टार्ट करें और टीवी स्क्रीन का स्क्रीनशॉट लेकर चेक करें कि केवल स्वच्छ Home और Apps टैब बचे हैं और सभी स्पॉन्सर्ड ऐड्स गायब हो चुके हैं।
```

---

## ⚡ 2. 1-Click ऑटोमेशन स्क्रिप्ट (Direct Execution)

अगर आप AI के बिना भी खुद 5 सेकंड में यह करना चाहते हैं, तो यह स्क्रिप्ट सीधे चला सकते हैं:

### Windows Batch Script (`remove_tv_ads.bat`):
```bat
@echo off
set /p TV_IP="Enter TV IP Address (e.g. 192.168.3.30): "
echo Connecting to %TV_IP%...
adb connect %TV_IP%:5555

echo.
echo [1/4] Disabling Ad & Recommendations Provider...
adb -s %TV_IP%:5555 shell pm disable-user --user 0 com.google.android.tvrecommendations
adb -s %TV_IP%:5555 shell pm clear com.google.android.tvrecommendations

echo.
echo [2/4] Disabling Ad Tracking and Freeplay...
adb -s %TV_IP%:5555 shell settings put secure freeplay_enabled 0
adb -s %TV_IP%:5555 shell settings put secure limit_ad_tracking 1

echo.
echo [3/4] Clearing Ad Notifications...
adb -s %TV_IP%:5555 shell content delete --uri content://com.google.android.tvrecommendations.NotificationContentProvider/notifications

echo.
echo [4/4] Restarting TV Launcher...
adb -s %TV_IP%:5555 shell am force-stop com.google.android.tvlauncher
adb -s %TV_IP%:5555 shell am start -n com.google.android.tvlauncher/.MainActivity

echo.
echo ========================================================
echo Done! All Sponsored Ads and Bloat Tabs are now REMOVED!
echo ========================================================
pause
```

---

## 🔄 3. यदि कभी रीस्टोर (Undo) करना हो

यदि कभी किसी कारण से पुरानी स्थिति वापस चाहिए:
```bash
adb shell pm enable com.google.android.tvrecommendations
adb shell settings put secure freeplay_enabled 1
adb shell settings put secure limit_ad_tracking 0
adb shell am force-stop com.google.android.tvlauncher
adb shell am start -n com.google.android.tvlauncher/.MainActivity
```
