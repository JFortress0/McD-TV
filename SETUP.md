# McD TV setup checklist

Do these steps once. Total time: about 30 minutes. Most of it is waiting for the first build.

## Part 1. Put the project on GitHub (Mac)

1. Go to **github.com** and create a free account. Skip this step if you have one.
2. Download **GitHub Desktop** from **desktop.github.com**. Install it and sign in.
3. In your browser, open the GitHub tab. The new-repository form is already filled in (name **McD-TV**, Public). Click **Create repository**.
4. In GitHub Desktop, click **File > Add Local Repository**. Choose the **McD TV** folder on your Desktop. Click **Add Repository**.
   The project is already a repository, its commits are ready, and it already points at `github.com/JFortress0/McD-TV`.
5. Click **Push origin** (or **Publish branch**) at the top.

> **Why public?** The Downloader app on the Fire Stick cannot sign in to GitHub. It can only download from a public repository. The code holds no passwords or account keys. Your signing key stays on your Mac and in a GitHub secret. Your Real-Debrid key goes into the app on the TV, never into the code.

## Part 2. Add your signing key to GitHub

This key lets each new APK install over the old one.

1. In Finder, open **Desktop > McD TV > keystore**.
2. Open **KEYSTORE_BASE64.txt** with TextEdit. Press **Cmd+A**, then **Cmd+C**.
3. In your browser, open your repository: `github.com/JFortress0/McD-TV`.
4. Click **Settings** (top bar of the repository).
5. In the left menu, click **Secrets and variables > Actions**.
6. Click **New repository secret**.
   - **Name:** `KEYSTORE_BASE64`
   - **Secret:** press **Cmd+V**
   - Click **Add secret**.

## Part 3. Build the APK

1. On your repository page, click the **Actions** tab.
2. Click **Build APK** on the left.
3. Click **Run workflow**, then the green **Run workflow** button.
4. Wait for the green check mark. The first build takes about 8 minutes.
5. Click **Code** (top left), then **Releases** on the right side. You see `McD-TV.apk`.

If the run shows a red X: click the run, click **build**, copy the red error text, and send it to Claude.

## Part 4. Install on the Fire Stick

Do steps 1 to 4 one time only.

1. On the Fire Stick home screen, search for **Downloader** (orange icon). Install it.
2. Go to **Settings > My Fire TV**.
   - If you do not see **Developer Options**: open **About**, select your device name 7 times, then press BACK.
3. Open **Developer Options > Install unknown apps**.
4. Set **Downloader** to **ON**.
5. Open **Downloader**. In the URL box, type:
   `https://github.com/JFortress0/McD-TV/releases/latest/download/McD-TV.apk`
6. Select **Go**. The download starts.
7. Select **Install**, then **Open**. The intro plays.
8. Downloader asks to delete the APK file. Select **Delete**. The app stays installed.

Tip: Downloader saves the URL. Next time, open Downloader and select the same URL.

## Part 5. Update the app later

1. Ask Claude for a change. Claude edits the files in your McD TV folder.
2. In GitHub Desktop, type a short summary and click **Commit to main**.
3. Click **Push origin**.
4. Wait for the build to finish (Actions tab, green check mark).
5. On the Fire Stick, open Downloader and download the same URL again. Select **Install**.

## Part 6. Connect your sources (on the TV)

1. Get a free TMDB API key: sign up at **themoviedb.org**, then open **Settings > API** and request a key.
2. On the TV, open **Settings > Open Phone Setup**. The TV shows a web address.
3. On your phone (same Wi-Fi), open that address.
4. Paste the TMDB key. Tap **Save to TV**.
5. Paste an addon link (a `manifest.json` URL) and tap **Save to TV**. Repeat for each addon.
6. Optional: paste your Live TV playlist URL (M3U).
7. Press BACK on the remote. Open **Settings > Connect Real-Debrid**.
8. On your phone, open **real-debrid.com/device** and enter the code the TV shows.

## Test the player

1. Open **Settings** and select **Play test stream**.
2. Press **OK** to show the control bar.
3. Select the **CC** button. Subtitles turn on.
4. Select the **gear** button. Choose a different audio track.
5. Press **BACK** twice to return to Home.
6. To play your own link: paste it in Phone Setup, then select **Play My Stream** in Settings.
