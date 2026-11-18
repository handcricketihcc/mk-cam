# MK Cam - Stage 1: Live Audio (MVP)

Device phone ka microphone live Viewer phone par sunai deta hai. Video aur baqi features agle stages mein.
Sab kuch free sources par chalta hai (Render free + GitHub Actions free + free public TURN).

## Folder
- `server/` - Node.js signaling server (PIN pairing + WebRTC signal relay). Database ki zarurat nahi.
- `android/` - `core` (shared), `device` (MK Cam Device), `viewer` (MK Cam Viewer)
- `render.yaml` - Render par server ek click mein banane ke liye
- `.github/workflows/build.yml` - APK banane ke liye. Sirf haath se chalta hai (Actions tab, "Run workflow")

## Step 1: Server (Render, free, card nahi chahiye)
1. render.com par GitHub se sign up karein.
2. New -> Blueprint -> repo `mk-cam` chunein -> Apply. `render.yaml` se sab khud set ho jata hai.
3. Deploy hone par server ka address milega (jaise `https://mk-cam-signaling-xxxx.onrender.com`). Browser mein `/health` lagayen, "ok" aana chahiye.
4. Free plan 15 minute khali rehne par so jata hai, aur jagne mein ~1 minute lagta hai. Isay jagaye rakhne ke liye uptimerobot.com (free) par ek HTTP monitor banayein jo har 5 minute `/health` ko check kare.
5. `SERVER_KEY` Render khud bana deta hai. Isay badlein ge to sab pairings khatam ho jayen gi.

## Step 2: TURN
Server ab free public TURN (Open Relay) khud apps ko de deta hai. Testing ke liye theek hai, par bharosemand nahi. Apna TURN ho to Render ke Environment mein `TURN_URL`, `TURN_USER`, `TURN_PASS` daalein. Public wala band karna ho to `FREE_TURN=off`.

## Step 3: APK
Server chal jaye aur uska `/health` "ok" de, tab Actions tab mein "Build APKs" -> "Run workflow". Artifacts se dono APK download karein.

## Step 4: Test
1. Dono apps mein server address likhein.
2. Viewer: "Generate pairing PIN". Device: server address aur PIN likh kar "Pair with Viewer".
3. Device: "Start service" (microphone aur notification permission, battery "Unrestricted").
4. Viewer: "Device: ONLINE" aane par "Start listening".

## Maloom hadood (MVP)
- Reboot ke baad Device service khud start nahi hoti (Android 14+ background se microphone service nahi chalne deta). App khol kar "Start service" dabayein.
- Microphone sirf tab khulta hai jab Viewer sun raha ho. Device par notification hamesha nazar aati hai.
- `http://` testing ke liye allowed hai. Release se pehle band karein.
- Unpair abhi server se record nahi mitata (secret bina database ke bante hain). Sab pairings ek saath khatam karne ke liye `SERVER_KEY` badlein.
- Abhi nahi hain: video, motion alert, recording, billing, QR pairing. Yeh agle stages hain.
