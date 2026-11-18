# MK Cam - Stage 1: Live Audio (MVP)

Device phone ka microphone live Viewer phone par sunai deta hai. Video aur baqi features agle stages mein.

## Kya kya hai
- `server/` - Node.js signaling server (PIN pairing + WebRTC signal relay)
- `android/` - 3 modules: `core` (shared), `device` (MK Cam Device app), `viewer` (MK Cam Viewer app)
- `.github/workflows/build.yml` - GitHub par APK khud ban jata hai

## Step 1: Server chalana
Server ko internet par chahiye (dono phones door door hon). Options: VPS, ya Render / Railway jaisi free-tier service.
```
cd server
npm install
npm start
```
- Port: environment variable `PORT` (default 3000). Health check: `/health`
- Production mein HTTPS zaroori hai (reverse proxy jaise Caddy / nginx, ya hosting service ka apna HTTPS).
- TURN (mobile data / mushkil networks ke liye): coturn chalayein, phir
  `TURN_URL=turn:your-server:3478 TURN_USER=... TURN_PASS=...` set karein. Bina TURN ke sirf kuch networks par chalega.
- Free hosting par `pairs.json` restart par mit sakti hai. Phir dobara pair karna parega.

## Step 2: APK banana (phone se)
1. Is poore folder ko ek GitHub repo mein upload karein (branch `main`).
2. Repo ke Actions tab mein "Build APKs" chalne dein (5-10 minute).
3. Run ke Artifacts se `MKCamDevice-debug-apk` aur `MKCamViewer-debug-apk` download karein (zip, andar APK).
4. Device phone par Device APK, apne phone par Viewer APK install karein.

Agar build fail ho to Actions ka error log mujhe bhej dein. Maine yeh code asli Android build par abhi test nahi kiya, isliye pehli baar chhoti galtiyan (library version waghera) aa sakti hain.

## Step 3: Test
1. Dono apps mein server address likhein (jaise `https://your-server.com`).
2. Viewer: "Generate pairing PIN". 6 digit PIN ayega.
3. Device: server address aur PIN likh kar "Pair with Viewer".
4. Device: "Start service" (microphone aur notification permission dein). Battery settings mein "Unrestricted" karein.
5. Viewer: "Device: ONLINE" aane par "Start listening".

## Maloom hadood (MVP)
- Reboot ke baad Device service khud start nahi hoti. Android 14+ background se microphone service start karne nahi deta, isliye app khol kar "Start service" dabana parta hai.
- Service ko system maar de to dobara start karne ke liye bhi app kholni pare gi, yahi wajah hai.
- Microphone sirf tab khulta hai jab Viewer sun raha ho. Notification Device par hamesha nazar aati hai.
- Cleartext `http://` testing ke liye allowed hai. Release se pehle band karein.
- Unpair abhi server se record nahi mitata, sirf phone se.
- Abhi: video, motion alert, recording, billing, QR pairing nahi hain. Yeh agle stages hain.
